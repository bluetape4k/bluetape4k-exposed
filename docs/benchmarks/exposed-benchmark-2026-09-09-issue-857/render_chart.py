#!/usr/bin/env python3
"""Render the Issue #857 ClickHouse memory and first-item latency chart."""

from __future__ import annotations

import argparse
import html
import json
import math
import os
import tempfile
from pathlib import Path


WIDTH = 1600
HEIGHT = 1180
PLOT_LEFT = 310
PLOT_RIGHT = 1480
PLOT_WIDTH = PLOT_RIGHT - PLOT_LEFT
APIS = ("queryFlow", "queryList")
ROWS = (100_000, 1_000_000)
API_COLORS = {
    "queryFlow": ("#58D6C0", "#9BF2E3"),
    "queryList": ("#B68CFF", "#DEC9FF"),
}
EXPECTED = {
    ("queryFlow", 100_000): (2_660_696, 38.995875),
    ("queryFlow", 1_000_000): (2_705_872, 37.7525),
    ("queryList", 100_000): (3_944_736, 80.504167),
    ("queryList", 1_000_000): (29_148_136, 457.817209),
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--summary", type=Path, required=True)
    parser.add_argument("--locale", choices=("en", "ko"), required=True)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def esc(value: object) -> str:
    return html.escape(str(value), quote=True)


def load_metrics(path: Path) -> dict[tuple[str, int], dict[str, float]]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    if payload.get("issue") != 857:
        raise ValueError("summary.json issue must be 857")
    metrics = payload.get("profile", {}).get("metrics")
    if not isinstance(metrics, list) or len(metrics) != len(EXPECTED):
        raise ValueError("summary.json must contain four profile metrics")

    loaded: dict[tuple[str, int], dict[str, float]] = {}
    for row in metrics:
        if not isinstance(row, dict):
            raise ValueError("profile metrics must contain objects")
        api = row.get("api")
        rows = row.get("rows")
        key = (api, rows)
        if key in loaded or key not in EXPECTED:
            raise ValueError(f"unsupported or duplicate profile metric: {key}")
        for field in ("liveHeapDeltaBytes", "firstItemNs", "elapsedNs", "rawHeapPeakBytes", "rssPeakKiB"):
            value = row.get(field)
            if not isinstance(value, (int, float)) or isinstance(value, bool) or not math.isfinite(value) or value < 0:
                raise ValueError(f"{key}.{field} must be finite and non-negative")
        expected_heap, expected_first_ms = EXPECTED[key]
        if row["liveHeapDeltaBytes"] != expected_heap:
            raise ValueError(f"{key} has an unexpected live heap value")
        if not math.isclose(row["firstItemNs"] / 1_000_000, expected_first_ms, rel_tol=0, abs_tol=0.000001):
            raise ValueError(f"{key} has an unexpected first-item value")
        loaded[key] = {field: float(row[field]) for field in (
            "liveHeapDeltaBytes", "firstItemNs", "elapsedNs", "rawHeapPeakBytes", "rssPeakKiB"
        )}
    if set(loaded) != set(EXPECTED):
        raise ValueError("summary.json profile metric set is incomplete")
    return loaded


def labels(locale: str) -> dict[str, str | dict[str, str]]:
    if locale == "ko":
        return {
            "title": "ClickHouse queryList | queryFlow 비교",
            "subtitle": "Issue #857 | 3회 fresh-JVM profile 중앙값 | 100,000 / 1,000,000행",
            "memory": "Forced-GC 후 live heap 증가 (MiB | 낮을수록 작음)",
            "latency": "첫 detached 항목 지연 (ms | 낮을수록 빠름)",
            "flow": "queryFlow | rendezvous streaming",
            "list": "queryList | 전체 materialization",
            "rows": {"100000": "100,000행", "1000000": "1,000,000행"},
            "interpretation": "해석",
            "line1": "10배 행 수에서 queryFlow live heap은 1.02배, queryList는 7.39배로 증가했습니다.",
            "line2": "queryFlow는 전체 매핑 전에 첫 detached 항목을 전달하며, JMH 처리량은 별도 표의 고분산 지표입니다.",
        }
    return {
        "title": "ClickHouse queryList | queryFlow comparison",
        "subtitle": "Issue #857 | median of 3 fresh-JVM profiles | 100,000 / 1,000,000 rows",
        "memory": "Live heap delta after forced GC (MiB | lower is smaller)",
        "latency": "First detached item latency (ms | lower is faster)",
        "flow": "queryFlow | rendezvous streaming",
        "list": "queryList | full materialization",
        "rows": {"100000": "100,000 rows", "1000000": "1,000,000 rows"},
        "interpretation": "Reading the result",
        "line1": "At 10x the rows, queryFlow live heap grew 1.02x while queryList grew 7.39x.",
        "line2": "queryFlow delivered the first detached item before full mapping; JMH throughput remains a separate high-variance table.",
    }


def render(summary: Path, locale: str) -> str:
    metrics = load_metrics(summary)
    text = labels(locale)
    title = esc(text["title"])
    subtitle = esc(text["subtitle"])
    memory_title = esc(text["memory"])
    latency_title = esc(text["latency"])
    interpretation = esc(text["interpretation"])
    line1 = esc(text["line1"])
    line2 = esc(text["line2"])
    row_labels = text["rows"]
    assert isinstance(row_labels, dict)
    font_title = "goorm Sans" if locale == "ko" else "Architects Daughter"
    font_body = "goorm Sans Code" if locale == "ko" else "Comic Mono"
    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{WIDTH}" height="{HEIGHT}" viewBox="0 0 {WIDTH} {HEIGHT}" role="img" aria-labelledby="title desc">',
        f'<title id="title">{title}</title>',
        f'<desc id="desc">{subtitle}; live heap retention and first detached item latency for queryFlow and queryList.</desc>',
        f'''<defs>
  <filter id="panel-shadow" x="-8%" y="-8%" width="116%" height="116%"><feDropShadow dx="0" dy="8" stdDeviation="10" flood-color="#020617" flood-opacity="0.35"/></filter>
  <style>
    .canvas{{fill:#0D1728}}
    .panel{{fill:#15243A;stroke:#2E4565;stroke-width:1.8;filter:url(#panel-shadow)}}
    .band{{fill:#102A35;stroke:#58D6C0;stroke-width:1.8}}
    .title{{font-family:"{font_title}";font-size:42px;fill:#F5F8FF;font-weight:700}}
    .subtitle,.panelTitle,.axis,.tick,.value,.row,.legend,.bandTitle,.bandText{{font-family:"{font_body}";fill:#E9F0FF}}
    .subtitle{{font-size:17px;fill:#A9B9D4}}
    .panelTitle{{font-size:21px;font-weight:700}}
    .axis{{font-size:14px;fill:#B8C8E2}}
    .tick{{font-size:12px;fill:#9CB0D0}}
    .value{{font-size:14px;font-weight:700}}
    .row,.legend{{font-size:14px;fill:#C7D6F0}}
    .grid{{stroke:#2B405E;stroke-width:1;stroke-dasharray:5 7}}
    .baseline{{stroke:#6D83A4;stroke-width:1.5}}
    .bandTitle{{font-size:19px;fill:#9BF2E3;font-weight:700}}
    .bandText{{font-size:15px;fill:#D9FFF8}}
  </style>
</defs>''',
        f'<rect class="canvas" width="{WIDTH}" height="{HEIGHT}"/>',
        f'<text class="title" x="70" y="72">{title}</text>',
        f'<text class="subtitle" x="74" y="106">{subtitle}</text>',
    ]

    legend_x = 875
    for api, key in (("queryFlow", "flow"), ("queryList", "list")):
        fill, stroke = API_COLORS[api]
        parts.append(f'<rect x="{legend_x}" y="91" width="20" height="14" rx="4" fill="{fill}" stroke="{stroke}" stroke-width="1.2"/>')
        parts.append(f'<text class="legend" x="{legend_x + 29}" y="103">{esc(text[key])}</text>')
        legend_x += 310

    panel_specs = (
        ("memory", 145, 32.0, (0, 8, 16, 24, 32), memory_title, "MiB"),
        ("latency", 555, 500.0, (0, 100, 200, 300, 400, 500), latency_title, "ms"),
    )
    panel_height = 350
    bar_height = 28
    row_centers = (250, 350)
    for metric, panel_y, max_value, ticks, panel_title, unit in panel_specs:
        parts.append(f'<rect class="panel" x="60" y="{panel_y}" width="1480" height="{panel_height}" rx="16"/>')
        parts.append(f'<text class="panelTitle" x="95" y="{panel_y + 42}">{panel_title}</text>')
        plot_top = panel_y + 100
        plot_bottom = panel_y + 285
        for tick in ticks:
            x = PLOT_LEFT + PLOT_WIDTH * tick / max_value
            parts.append(f'<line class="grid" x1="{x:.1f}" y1="{plot_top}" x2="{x:.1f}" y2="{plot_bottom}"/>')
            parts.append(f'<text class="tick" x="{x:.1f}" y="{plot_bottom + 24}" text-anchor="middle">{tick:g}</text>')
        parts.append(f'<text class="axis" x="{PLOT_RIGHT}" y="{panel_y + 42}" text-anchor="end">{unit}</text>')
        parts.append(f'<line class="baseline" x1="{PLOT_LEFT}" y1="{plot_bottom}" x2="{PLOT_RIGHT}" y2="{plot_bottom}"/>')
        for row, row_center in zip(ROWS, row_centers):
            row_y = panel_y + row_center - 145
            parts.append(f'<text class="row" x="95" y="{row_y + 28}">{esc(row_labels[str(row)])}</text>')
            for offset, api in zip((0, 42), APIS):
                value = metrics[(api, row)]["liveHeapDeltaBytes"] / 1_048_576 if metric == "memory" else metrics[(api, row)]["firstItemNs"] / 1_000_000
                bar_y = row_y + offset
                width = max(1.0, PLOT_WIDTH * value / max_value)
                fill, stroke = API_COLORS[api]
                parts.append(f'<rect x="{PLOT_LEFT}" y="{bar_y}" width="{width:.1f}" height="{bar_height}" rx="7" fill="{fill}" stroke="{stroke}" stroke-width="1.4"/>')
                rendered_value = f"{value:.2f} MiB" if metric == "memory" else f"{value:.1f} ms"
                label_x = PLOT_LEFT + width + 12
                anchor = "start"
                label_fill = "#E9F0FF"
                if label_x > PLOT_RIGHT - 110:
                    label_x = PLOT_LEFT + width - 12
                    anchor = "end"
                    label_fill = "#0D1728"
                parts.append(f'<text class="value" x="{label_x:.1f}" y="{bar_y + 20}" text-anchor="{anchor}" fill="{label_fill}">{rendered_value}</text>')

    parts.extend([
        '<rect class="band" x="60" y="965" width="1480" height="150" rx="16"/>',
        f'<text class="bandTitle" x="90" y="1003">{interpretation}</text>',
        f'<text class="bandText" x="90" y="1040">{line1}</text>',
        f'<text class="bandText" x="90" y="1075">{line2}</text>',
        '</svg>',
    ])
    return "\n".join(parts) + "\n"


def main() -> None:
    args = parse_args()
    output = args.output
    if output.exists() or output.is_symlink():
        raise SystemExit(f"render_chart: output already exists or is a symlink: {output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=output.parent, prefix=f".{output.name}.", delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(render(args.summary, args.locale))
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, output)
        temporary = None
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
