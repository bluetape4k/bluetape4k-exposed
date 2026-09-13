#!/usr/bin/env python3
"""Render the Issue #867 ClickHouse JDBC V2 RowBinary benchmark chart."""

from __future__ import annotations

import argparse
import html
import json
import math
import os
import tempfile
from pathlib import Path
from typing import Any


WIDTH = 1800
HEIGHT = 1370
MEASURED_ROWS = 2048
PATHS = ("rowbinary", "jdbc-fallback")
SHAPES = ("narrow", "wide")
ROW_COUNTS = (10_000, 100_000, 1_000_000)
FLUSH_SIZES = (256, 1_024, 4_096)
PATH_COLORS = {
    "rowbinary": ("#58D6C0", "#9BF2E3"),
    "jdbc-fallback": ("#B68CFF", "#DEC9FF"),
}
EXPECTED_FILES = tuple(
    sorted(f"{path}-run-{run}.json" for path in PATHS for run in range(1, 4))
)
REQUIRED_PROVENANCE = (
    "implementationSha",
    "gitDirty",
    "driverArtifact",
    "jdk",
    "os",
    "architecture",
    "container",
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input-dir", type=Path, required=True)
    parser.add_argument("--locale", choices=("en", "ko"), required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--semantic-ledger", type=Path, required=True)
    return parser.parse_args()


def esc(value: object) -> str:
    return html.escape(str(value), quote=True)


def is_finite_number(value: object) -> bool:
    return (
        isinstance(value, (int, float))
        and not isinstance(value, bool)
        and math.isfinite(float(value))
    )


def median(values: list[float]) -> float:
    ordered = sorted(values)
    middle = len(ordered) // 2
    if len(ordered) % 2:
        return ordered[middle]
    return (ordered[middle - 1] + ordered[middle]) / 2


def load_measurements(input_dir: Path) -> tuple[dict[tuple[str, int, int, str], dict[str, Any]], dict[str, Any]]:
    files = sorted(input_dir.glob("*-run-*.json"))
    if tuple(path.name for path in files) != EXPECTED_FILES:
        raise ValueError(
            f"expected exactly {EXPECTED_FILES}, got {tuple(path.name for path in files)}"
        )

    grouped: dict[tuple[str, int, int, str], list[dict[str, Any]]] = {}
    provenance_reference: dict[str, Any] | None = None
    for path in files:
        payload = json.loads(path.read_text(encoding="utf-8"))
        if payload.get("issue") != 867:
            raise ValueError(f"{path.name} issue must be 867")
        file_path = payload.get("path")
        run = payload.get("run")
        expected_path, expected_run = path.stem.rsplit("-run-", 1)
        if file_path not in PATHS or expected_path != file_path or run != int(expected_run):
            raise ValueError(f"{path.name} has an invalid path/run identity")
        if payload.get("warmupIterations") != 2 or payload.get("measurementIterations") != 5:
            raise ValueError(f"{path.name} must record warmup=2 and measurement=5")
        if payload.get("measurementScope") != (
            "bounded in-memory provider fixture; logical row counts are scenario labels"
        ):
            raise ValueError(f"{path.name} has an unexpected measurement scope")

        provenance = payload.get("provenance")
        if not isinstance(provenance, dict) or any(key not in provenance for key in REQUIRED_PROVENANCE):
            raise ValueError(f"{path.name} is missing provenance fields")
        if provenance_reference is None:
            provenance_reference = {key: provenance[key] for key in REQUIRED_PROVENANCE}
        elif any(provenance[key] != provenance_reference[key] for key in REQUIRED_PROVENANCE):
            raise ValueError(f"{path.name} provenance does not match the other runs")

        records = payload.get("records")
        if not isinstance(records, list) or len(records) != 18:
            raise ValueError(f"{path.name} must contain 18 records")
        for record in records:
            if not isinstance(record, dict):
                raise ValueError(f"{path.name} records must contain objects")
            row_count = record.get("rowCount")
            flush_size = record.get("maxRowsPerFlush")
            shape = record.get("rowShape")
            identity = (file_path, row_count, flush_size, shape)
            if file_path not in PATHS or row_count not in ROW_COUNTS:
                raise ValueError(f"{path.name} has unsupported row-count identity: {identity}")
            if flush_size not in FLUSH_SIZES or shape not in SHAPES:
                raise ValueError(f"{path.name} has unsupported scenario identity: {identity}")
            if record.get("path") != file_path or record.get("logicalRows") != row_count:
                raise ValueError(f"{path.name} has inconsistent path/logicalRows: {identity}")
            if record.get("measuredRows") != min(row_count, MEASURED_ROWS):
                raise ValueError(f"{path.name} does not disclose the {MEASURED_ROWS}-row cap: {identity}")
            raw_samples = record.get("rawElapsedNs")
            if not isinstance(raw_samples, list) or len(raw_samples) != 5:
                raise ValueError(f"{path.name} rawElapsedNs must contain five samples: {identity}")
            if any(not is_finite_number(sample) or sample < 0 for sample in raw_samples):
                raise ValueError(f"{path.name} has invalid raw elapsed samples: {identity}")
            for metric in ("medianElapsedNs", "medianRowsPerSecond", "firstByteNs", "peakHeapBytes"):
                value = record.get(metric)
                if not is_finite_number(value) or value < 0:
                    raise ValueError(f"{path.name}.{identity}.{metric} must be finite and non-negative")
            grouped.setdefault(identity, []).append(record)

    expected_identities = {
        (path, row_count, flush_size, shape)
        for path in PATHS
        for row_count in ROW_COUNTS
        for flush_size in FLUSH_SIZES
        for shape in SHAPES
    }
    if set(grouped) != expected_identities:
        missing = sorted(expected_identities - set(grouped))
        extra = sorted(set(grouped) - expected_identities)
        raise ValueError(f"scenario set mismatch; missing={missing}, extra={extra}")
    if any(len(records) != 3 for records in grouped.values()):
        raise ValueError("every scenario must have exactly three process runs")

    summary: dict[tuple[str, int, int, str], dict[str, Any]] = {}
    for identity, records in grouped.items():
        summary[identity] = {
            "medianRowsPerSecond": median(
                [float(record["medianRowsPerSecond"]) for record in records]
            ),
            "medianPeakHeapBytes": median(
                [float(record["peakHeapBytes"]) for record in records]
            ),
            "medianFirstByteNs": median(
                [float(record["firstByteNs"]) for record in records]
            ),
            "measuredRows": records[0]["measuredRows"],
        }
    assert provenance_reference is not None
    return summary, provenance_reference


def labels(locale: str) -> dict[str, str]:
    if locale == "ko":
        return {
            "title": "ClickHouse JDBC V2 RowBinary · 배치 경로 비교",
            "subtitle": "Issue #867 | 3회 process 중앙값 | rows/s · 논리 행 수는 시나리오 라벨",
            "axis": "중앙값 rows/s",
            "narrow": "narrow row shape",
            "wide": "wide row shape",
            "rowbinary": "RowBinary profile",
            "fallback": "JDBC fallback profile",
            "flush": "flush",
            "rows": "논리 행 수",
            "note_title": "측정 경계",
            "note1": "실제 측정은 bounded in-memory provider fixture에서 최대 2,048행이며, 10,000/100,000/1,000,000행은 시나리오 라벨입니다.",
            "note2": "이 차트는 production ClickHouse wire 처리량이나 driver private buffer 상한을 보장하지 않습니다. RowBinary는 명시적 opt-in입니다.",
        }
    return {
        "title": "ClickHouse JDBC V2 RowBinary · batch path comparison",
        "subtitle": "Issue #867 | median of 3 processes | rows/s · logical row counts are scenario labels",
        "axis": "median rows/s",
        "narrow": "narrow row shape",
        "wide": "wide row shape",
        "rowbinary": "RowBinary profile",
        "fallback": "JDBC fallback profile",
        "flush": "flush",
        "rows": "logical rows",
        "note_title": "Measurement boundary",
        "note1": "The bounded in-memory provider fixture measures at most 2,048 rows; 10,000/100,000/1,000,000 rows are scenario labels.",
        "note2": "This chart is not production ClickHouse wire throughput and does not bound private driver buffers. RowBinary remains explicit opt-in.",
    }


def format_rows(value: int, locale: str) -> str:
    if locale == "ko":
        return f"{value:,}행"
    return f"{value:,}"


def format_value(value: float) -> str:
    if value >= 1_000_000:
        return f"{value / 1_000_000:.1f}M"
    if value >= 1_000:
        return f"{value / 1_000:.1f}k"
    return f"{value:.0f}"


def render(summary: dict[tuple[str, int, int, str], dict[str, Any]], locale: str) -> str:
    text = labels(locale)
    max_value = max(item["medianRowsPerSecond"] for item in summary.values())
    chart_max = max(1.0, math.ceil(max_value / 1_000_000) * 1_000_000)
    title = esc(text["title"])
    subtitle = esc(text["subtitle"])
    font_title = "goorm Sans" if locale == "ko" else "Architects Daughter"
    font_body = "goorm Sans Code" if locale == "ko" else "Comic Mono"
    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{WIDTH}" height="{HEIGHT}" viewBox="0 0 {WIDTH} {HEIGHT}" role="img" aria-labelledby="title desc">',
        f'<title id="title">{title}</title>',
        f'<desc id="desc">{subtitle}; RowBinary and JDBC fallback median throughput across flush sizes and row shapes.</desc>',
        f'''<defs>
  <filter id="panel-shadow" x="-8%" y="-8%" width="116%" height="116%"><feDropShadow dx="0" dy="8" stdDeviation="10" flood-color="#020617" flood-opacity="0.35"/></filter>
  <style>
    .canvas{{fill:#0D1728}}
    .panel{{fill:#15243A;stroke:#2E4565;stroke-width:1.8;filter:url(#panel-shadow)}}
    .note{{fill:#102A35;stroke:#58D6C0;stroke-width:1.8}}
    .title{{font-family:"{font_title}";font-size:44px;fill:#F5F8FF;font-weight:700}}
    .subtitle,.panelTitle,.axis,.tick,.value,.scenario,.legend,.noteTitle,.noteText{{font-family:"{font_body}";fill:#E9F0FF}}
    .subtitle{{font-size:18px;fill:#A9B9D4}}
    .panelTitle{{font-size:25px;font-weight:700}}
    .axis{{font-size:15px;fill:#B8C8E2}}
    .tick{{font-size:13px;fill:#9CB0D0}}
    .value{{font-size:12px;font-weight:700}}
    .scenario,.legend{{font-size:13px;fill:#C7D6F0}}
    .grid{{stroke:#2B405E;stroke-width:1;stroke-dasharray:5 7}}
    .baseline{{stroke:#6D83A4;stroke-width:1.5}}
    .noteTitle{{font-size:20px;fill:#9BF2E3;font-weight:700}}
    .noteText{{font-size:15px;fill:#D9FFF8}}
  </style>
</defs>''',
        f'<rect class="canvas" width="{WIDTH}" height="{HEIGHT}"/>',
        f'<text class="title" x="72" y="76">{title}</text>',
        f'<text class="subtitle" x="76" y="112">{subtitle}</text>',
    ]

    legend_x = 1190
    for path in PATHS:
        fill, stroke = PATH_COLORS[path]
        label = text["rowbinary"] if path == "rowbinary" else text["fallback"]
        parts.append(
            f'<rect x="{legend_x}" y="94" width="21" height="15" rx="4" fill="{fill}" stroke="{stroke}" stroke-width="1.2"/>'
        )
        parts.append(f'<text class="legend" x="{legend_x + 30}" y="107">{esc(label)}</text>')
        legend_x += 300

    panel_y_by_shape = {"narrow": 170, "wide": 690}
    panel_height = 450
    plot_left = 225
    plot_right = 1690
    plot_top_offset = 108
    plot_bottom_offset = 370
    plot_height = plot_bottom_offset - plot_top_offset
    slot_width = (plot_right - plot_left) / 9
    bar_width = 30
    for shape in SHAPES:
        panel_y = panel_y_by_shape[shape]
        parts.append(
            f'<rect class="panel" x="60" y="{panel_y}" width="1680" height="{panel_height}" rx="16"/>'
        )
        parts.append(
            f'<text class="panelTitle" x="98" y="{panel_y + 45}">{esc(text[shape])}</text>'
        )
        parts.append(
            f'<text class="axis" x="{plot_right}" y="{panel_y + 45}" text-anchor="end">{esc(text["axis"])}</text>'
        )
        for tick in range(0, 5):
            value = chart_max * tick / 4
            y = panel_y + plot_bottom_offset - plot_height * tick / 4
            parts.append(
                f'<line class="grid" x1="{plot_left}" y1="{y:.1f}" x2="{plot_right}" y2="{y:.1f}"/>'
            )
            parts.append(
                f'<text class="tick" x="{plot_left - 15}" y="{y + 5:.1f}" text-anchor="end">{esc(format_value(value))}</text>'
            )
        baseline = panel_y + plot_bottom_offset
        parts.append(
            f'<line class="baseline" x1="{plot_left}" y1="{baseline}" x2="{plot_right}" y2="{baseline}"/>'
        )

        slot_index = 0
        for row_count in ROW_COUNTS:
            for flush_size in FLUSH_SIZES:
                center = plot_left + slot_width * (slot_index + 0.5)
                for offset, path in ((-bar_width * 0.65, "rowbinary"), (bar_width * 0.65, "jdbc-fallback")):
                    item = summary[(path, row_count, flush_size, shape)]
                    value = item["medianRowsPerSecond"]
                    bar_height = max(1.0, value / chart_max * plot_height)
                    bar_x = center + offset - bar_width / 2
                    bar_y = baseline - bar_height
                    fill, stroke = PATH_COLORS[path]
                    parts.append(
                        f'<rect x="{bar_x:.1f}" y="{bar_y:.1f}" width="{bar_width}" height="{bar_height:.1f}" rx="6" fill="{fill}" stroke="{stroke}" stroke-width="1.2"/>'
                    )
                    parts.append(
                        f'<text class="value" x="{bar_x + bar_width / 2:.1f}" y="{max(panel_y + plot_top_offset + 14, bar_y - 8):.1f}" text-anchor="middle">{esc(format_value(value))}</text>'
                    )
                label_y = baseline + 25
                parts.append(
                    f'<text class="scenario" x="{center:.1f}" y="{label_y}" text-anchor="middle">{esc(format_rows(row_count, locale))}</text>'
                )
                parts.append(
                    f'<text class="scenario" x="{center:.1f}" y="{label_y + 18}" text-anchor="middle">{esc(text["flush"])}={flush_size:,}</text>'
                )
                slot_index += 1

    note_y = 1190
    parts.extend(
        [
            f'<rect class="note" x="60" y="{note_y}" width="1680" height="135" rx="16"/>',
            f'<text class="noteTitle" x="94" y="{note_y + 38}">{esc(text["note_title"])}</text>',
            f'<text class="noteText" x="94" y="{note_y + 72}">{esc(text["note1"])}</text>',
            f'<text class="noteText" x="94" y="{note_y + 106}">{esc(text["note2"])}</text>',
            "</svg>",
        ]
    )
    return "\n".join(parts) + "\n"


def semantic_ledger(
    summary: dict[tuple[str, int, int, str], dict[str, Any]],
    provenance: dict[str, Any],
    input_dir: Path,
) -> dict[str, Any]:
    nodes: list[dict[str, str]] = []
    for shape in SHAPES:
        for row_count in ROW_COUNTS:
            for flush_size in FLUSH_SIZES:
                rowbinary_value = summary[("rowbinary", row_count, flush_size, shape)][
                    "medianRowsPerSecond"
                ]
                fallback_value = summary[("jdbc-fallback", row_count, flush_size, shape)][
                    "medianRowsPerSecond"
                ]
                source = (
                    f"{input_dir}/rowbinary-run-1.json..rowbinary-run-3.json:records; "
                    f"{input_dir}/jdbc-fallback-run-1.json..jdbc-fallback-run-3.json:records"
                )
                nodes.append(
                    {
                        "id": f"{shape}-{row_count}-{flush_size}",
                        "label": (
                            f"{shape} / {row_count:,} logical rows / flush {flush_size} / "
                            f"RowBinary {format_value(rowbinary_value)} vs "
                            f"JDBC fallback {format_value(fallback_value)} rows/s"
                        ),
                        "source": source,
                    }
                )
    return {
        "kind": "chart",
        "source": {
            "question": "ClickHouse JDBC V2 RowBinary와 JDBC fallback의 배치 경로·flush·행 모양별 처리량은 어떻게 다른가?",
            "revision": provenance["implementationSha"],
            "paths": [
                f"{input_dir}/{name}" for name in EXPECTED_FILES
            ]
            + [f"{input_dir}/render_rowbinary_chart.py"],
        },
        "nodes": nodes,
        "edges": [],
        "behavior": {"branches": 0, "loops": 0},
        "repairs": [
            {
                "target": "path-comparison",
                "reason": "RowBinary opt-in과 JDBC fallback을 같은 논리 시나리오에서 두 profile로 나눠 비교함",
                "touches": 1,
            },
            {
                "target": "bounded-fixture-disclosure",
                "reason": "실제 측정 행 상한과 private driver buffer 미검증 경계를 차트 하단에 고정함",
                "touches": 1,
            },
        ],
    }


def atomic_write(path: Path, content: str, *, reject_existing: bool) -> None:
    if path.is_symlink() or (reject_existing and path.exists()):
        raise SystemExit(f"render_rowbinary_chart: output already exists or is a symlink: {path}")
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w",
            encoding="utf-8",
            dir=path.parent,
            prefix=f".{path.name}.",
            delete=False,
        ) as stream:
            temporary = Path(stream.name)
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        temporary = None
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def write_semantic_ledger(path: Path, payload: dict[str, Any]) -> None:
    rendered = json.dumps(payload, ensure_ascii=False, indent=2) + "\n"
    if path.is_symlink():
        raise SystemExit(f"render_rowbinary_chart: semantic ledger is a symlink: {path}")
    if path.exists():
        try:
            existing = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as error:
            raise SystemExit(f"render_rowbinary_chart: invalid existing semantic ledger: {error}") from error
        if existing != payload:
            raise SystemExit("render_rowbinary_chart: existing semantic ledger does not match raw inputs")
        return
    atomic_write(path, rendered, reject_existing=False)


def main() -> None:
    args = parse_args()
    summary, provenance = load_measurements(args.input_dir)
    write_semantic_ledger(
        args.semantic_ledger,
        semantic_ledger(summary, provenance, args.input_dir),
    )
    atomic_write(args.output, render(summary, args.locale), reject_existing=True)


if __name__ == "__main__":
    main()
