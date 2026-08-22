#!/usr/bin/env python3
"""Render the Issue #694 10,000-row throughput chart from summary.json."""

from __future__ import annotations

import argparse
import html
import json
import math
import os
import tempfile
from pathlib import Path


WIDTH = 1600
HEIGHT = 1160
MAX_OPS = 300.0
METHODS = ("parallelKeyEnumeration", "sequentialKeysetPaging")
DRIVERS = ("POSTGRESQL", "MYSQL_V8")
POOL_SIZES = (1, 2, 4)
EXPECTED_IMPLEMENTATION_SHA = "f325a70fbd2047cdef28be928eeea4675b4b05b6"
EXPECTED_CATALOG_REF = "91f9ea9336b5ea991f5675323a1cf25ccfd6f5ed"
EXPECTED_ARTIFACTS = {
    "com.mysql:mysql-connector-j:9.7.0",
    "org.postgresql:postgresql:42.7.13",
}
EXPECTED_IMAGE_DIGESTS = {
    "sha256:9a8afca54e7861fd90fab5fdf4c42477a6b1cb7d293595148e674e0a3181de15",
    "sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb",
}
POOL_COLORS = {
    1: ("#58D6C0", "#9BF2E3"),
    2: ("#6EA6FF", "#B7D2FF"),
    4: ("#FFB86B", "#FFD8A8"),
}
DRIVER_COLORS = {
    "POSTGRESQL": "#8FE3FF",
    "MYSQL_V8": "#D6B7FF",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--summary", type=Path, required=True)
    parser.add_argument("--locale", choices=("en", "ko"), required=True)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def esc(value: object) -> str:
    return html.escape(str(value), quote=True)


def load_rows(path: Path) -> dict[tuple[str, str], dict[int, float]]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    if payload.get("cardinalityPerRawFile") != 12:
        raise ValueError("summary.json cardinalityPerRawFile must be 12")
    provenance = payload.get("provenance")
    if not isinstance(provenance, dict):
        raise ValueError("summary.json must contain provenance")
    if provenance.get("gitSha") != EXPECTED_IMPLEMENTATION_SHA:
        raise ValueError("summary.json provenance has an unapproved implementation SHA")
    if provenance.get("catalogRef") != EXPECTED_CATALOG_REF:
        raise ValueError("summary.json provenance has an unapproved catalog ref")
    if set(provenance.get("driverArtifacts", [])) != EXPECTED_ARTIFACTS:
        raise ValueError("summary.json provenance has an unexpected driver artifact set")
    if set(provenance.get("imageDigestsObserved", [])) != EXPECTED_IMAGE_DIGESTS:
        raise ValueError("summary.json provenance has an unexpected image digest set")
    if provenance.get("testcontainersVersions") != ["2.0.5"] or provenance.get("gitDirty") != [True]:
        raise ValueError("summary.json provenance has unexpected runtime values")
    rows = payload.get("rows")
    if not isinstance(rows, list) or len(rows) != 24:
        raise ValueError("summary.json must contain a rows list")

    grouped: dict[tuple[str, str], dict[int, float]] = {}
    identities: set[tuple[str, str, int, int]] = set()
    for row in rows:
        if not isinstance(row, dict):
            raise ValueError("summary.json rows must contain objects")
        identity = (row.get("driver"), row.get("method"), row.get("rowCount"), row.get("poolSize"))
        if identity in identities:
            raise ValueError(f"summary.json contains duplicate row: {identity}")
        identities.add(identity)
        driver, method, row_count, pool_size = identity
        if driver not in DRIVERS or method not in METHODS or row_count not in (1_000, 10_000) or pool_size not in POOL_SIZES:
            raise ValueError(f"summary.json contains an unsupported row: {identity}")
        for metric in (
            "medianOpsPerSecond",
            "medianRowsPerSecond",
            "medianStatementExecutionsPerOperation",
            "medianConnectionRequestsPerOperation",
            "medianPeakActiveLeases",
            "medianActiveAtEnd",
        ):
            value = row.get(metric)
            if not isinstance(value, (int, float)) or isinstance(value, bool) or not math.isfinite(value) or value < 0:
                raise ValueError(f"summary.json {identity}.{metric} must be finite and non-negative")
        if row["medianActiveAtEnd"] != 0 or row["medianPeakActiveLeases"] > min(pool_size, 2):
            raise ValueError(f"summary.json lifecycle guard failed for {identity}")
        if row.get("rowCount") != 10_000:
            continue
        key = (row["driver"], row["method"])
        grouped.setdefault(key, {})[int(row["poolSize"])] = float(row["medianOpsPerSecond"])

    expected = {(driver, method) for driver in DRIVERS for method in METHODS}
    if set(grouped) != expected:
        raise ValueError(f"expected 10,000-row rows for {sorted(expected)}, got {sorted(grouped)}")
    for key, values in grouped.items():
        if set(values) != set(POOL_SIZES):
            raise ValueError(f"expected pool sizes {POOL_SIZES} for {key}, got {sorted(values)}")
        if any(value < 0 or value > MAX_OPS for value in values.values()):
            raise ValueError(f"ops/s outside chart domain for {key}: {values}")
    return grouped


def labels(locale: str) -> dict[str, str]:
    if locale == "ko":
        return {
            "title": "JDBC driver benchmark · pool 크기별 처리량",
            "subtitle": "10,000행 fixture · 3회 median · 단위 ops/s",
            "axis": "median ops/s",
            "pool": "poolSize",
            "guard_title": "검증 경계",
            "guard": "모든 10,000행 trial은 activeAtEnd=0이며 peak active leases는 min(poolSize, maxConcurrency=2) 이내였습니다.",
            "scope": "차트는 10,000행을 확대해 보여주며, 전체 24개 결과와 raw run은 README 표에서 확인할 수 있습니다.",
            "driver": {"POSTGRESQL": "PostgreSQL", "MYSQL_V8": "MySQL 8"},
        }
    return {
        "title": "JDBC driver benchmark · throughput by pool size",
        "subtitle": "10,000-row fixture · median of 3 runs · unit: ops/s",
        "axis": "median ops/s",
        "pool": "poolSize",
        "guard_title": "Verification boundary",
        "guard": "Every 10,000-row trial ended with activeAtEnd=0; peak active leases stayed within min(poolSize, maxConcurrency=2).",
        "scope": "The chart focuses on 10,000 rows; the README table links all 24 results and every raw run.",
        "driver": {"POSTGRESQL": "PostgreSQL", "MYSQL_V8": "MySQL 8"},
    }


def render(summary: Path, locale: str) -> str:
    values = load_rows(summary)
    text = labels(locale)
    title = esc(text["title"])
    subtitle = esc(text["subtitle"])
    axis = esc(text["axis"])
    pool_label = esc(text["pool"])
    guard_title = esc(text["guard_title"])
    guard = esc(text["guard"])
    scope = esc(text["scope"])
    font_title = "goorm Sans" if locale == "ko" else "Architects Daughter"
    font_body = "goorm Sans Code" if locale == "ko" else "Comic Mono"
    font_body_fallback = "goorm Sans" if locale == "ko" else "Comic Mono"

    parts = [
        f'''<svg xmlns="http://www.w3.org/2000/svg" width="{WIDTH}" height="{HEIGHT}" viewBox="0 0 {WIDTH} {HEIGHT}" role="img" aria-labelledby="title desc">''',
        f'<title id="title">{title}</title>',
        f'<desc id="desc">{subtitle}; PostgreSQL and MySQL 8 median throughput for pool sizes 1, 2, and 4.</desc>',
        """<defs>
  <filter id="panel-shadow" x="-8%" y="-8%" width="116%" height="116%"><feDropShadow dx="0" dy="8" stdDeviation="10" flood-color="#020617" flood-opacity="0.35"/></filter>
  <style>
    .canvas{fill:#0D1728}
    .panel{fill:#15243A;stroke:#2E4565;stroke-width:1.8;filter:url(#panel-shadow)}
    .guard{fill:#102A35;stroke:#58D6C0;stroke-width:1.8}
    .title{font-family:TITLE_FONT;font-size:42px;fill:#F5F8FF;font-weight:700}
    .subtitle,.panelTitle,.method,.axis,.tick,.value,.pool,.legend,.guardTitle,.guardText,.scope{font-family:BODY_FONT;fill:#E9F0FF}
    .subtitle{font-size:17px;fill:#A9B9D4}
    .panelTitle{font-size:22px;font-weight:700}
    .method{font-size:17px;fill:#C7D6F0}
    .axis,.tick{font-size:12px;fill:#9CB0D0}
    .value{font-size:13px;font-weight:700}
    .pool,.legend{font-size:13px;fill:#B8C8E2}
    .grid{stroke:#2B405E;stroke-width:1;stroke-dasharray:5 7}
    .baseline{stroke:#6D83A4;stroke-width:1.5}
    .guardTitle{font-size:18px;fill:#9BF2E3;font-weight:700}
    .guardText{font-size:14px;fill:#D9FFF8}
    .scope{font-size:13px;fill:#9CB0D0}
  </style>
</defs>""".replace("TITLE_FONT", font_title).replace("BODY_FONT", font_body),
        f'<rect class="canvas" width="{WIDTH}" height="{HEIGHT}"/>',
        f'<text class="title" x="70" y="74">{title}</text>',
        f'<text class="subtitle" x="74" y="108">{subtitle}</text>',
    ]

    legend_x = 1035
    for pool_size in POOL_SIZES:
        fill, stroke = POOL_COLORS[pool_size]
        parts.append(
            f'<rect x="{legend_x}" y="91" width="20" height="14" rx="4" fill="{fill}" stroke="{stroke}" stroke-width="1.2"/>'
        )
        parts.append(
            f'<text class="legend" x="{legend_x + 29}" y="103">{pool_label}={pool_size}</text>'
        )
        legend_x += 150

    panel_positions = {
        ("POSTGRESQL", "parallelKeyEnumeration"): (60, 155),
        ("POSTGRESQL", "sequentialKeysetPaging"): (820, 155),
        ("MYSQL_V8", "parallelKeyEnumeration"): (60, 535),
        ("MYSQL_V8", "sequentialKeysetPaging"): (820, 535),
    }
    panel_width = 720
    panel_height = 330
    plot_left_offset = 82
    plot_right_offset = 672
    plot_top_offset = 103
    plot_bottom_offset = 277
    plot_height = plot_bottom_offset - plot_top_offset
    bar_width = 82
    bar_centers = (220, 380, 540)

    for (driver, method), (x, y) in panel_positions.items():
        driver_color = DRIVER_COLORS[driver]
        panel_title = esc(text["driver"][driver])
        parts.extend(
            [
                f'<rect class="panel" x="{x}" y="{y}" width="{panel_width}" height="{panel_height}" rx="16"/>',
                f'<circle cx="{x + 31}" cy="{y + 35}" r="8" fill="{driver_color}"/>',
                f'<text class="panelTitle" x="{x + 52}" y="{y + 42}">{panel_title}</text>',
                f'<text class="method" x="{x + 52}" y="{y + 70}">{esc(method)}</text>',
                f'<text class="axis" x="{x + 655}" y="{y + 48}" text-anchor="end">{axis}</text>',
            ]
        )

        plot_left = x + plot_left_offset
        plot_right = x + plot_right_offset
        plot_top = y + plot_top_offset
        plot_bottom = y + plot_bottom_offset
        for tick in (0, 100, 200, 300):
            tick_y = plot_bottom - (tick / MAX_OPS) * plot_height
            parts.append(f'<line class="grid" x1="{plot_left}" y1="{tick_y:.1f}" x2="{plot_right}" y2="{tick_y:.1f}"/>')
            parts.append(f'<text class="tick" x="{plot_left - 15}" y="{tick_y + 4:.1f}" text-anchor="end">{tick}</text>')
        parts.append(f'<line class="baseline" x1="{plot_left}" y1="{plot_bottom}" x2="{plot_right}" y2="{plot_bottom}"/>')

        for pool_size, center_offset in zip(POOL_SIZES, bar_centers):
            value = values[(driver, method)][pool_size]
            bar_height = value / MAX_OPS * plot_height
            bar_x = x + center_offset - bar_width / 2
            bar_y = plot_bottom - bar_height
            fill, stroke = POOL_COLORS[pool_size]
            parts.append(
                f'<rect x="{bar_x:.1f}" y="{bar_y:.1f}" width="{bar_width}" height="{bar_height:.1f}" rx="8" fill="{fill}" stroke="{stroke}" stroke-width="1.5"/>'
            )
            parts.append(
                f'<text class="value" x="{x + center_offset}" y="{max(plot_top + 15, bar_y - 10):.1f}" text-anchor="middle">{value:.2f}</text>'
            )
            parts.append(
                f'<text class="pool" x="{x + center_offset}" y="{plot_bottom + 28}" text-anchor="middle">{pool_label}={pool_size}</text>'
            )

    guard_y = 915
    parts.extend(
        [
            f'<rect class="guard" x="60" y="{guard_y}" width="1480" height="145" rx="16"/>',
            f'<text class="guardTitle" x="90" y="{guard_y + 37}">{guard_title}</text>',
            f'<text class="guardText" x="90" y="{guard_y + 71}">{guard}</text>',
            f'<text class="scope" x="90" y="{guard_y + 108}">{scope}</text>',
        ]
    )
    parts.append("</svg>")
    return "\n".join(parts) + "\n"


def main() -> None:
    args = parse_args()
    output = args.output
    if output.exists() or output.is_symlink():
        raise SystemExit(f"render_jmh_chart: output already exists or is a symlink: {output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w",
            encoding="utf-8",
            dir=output.parent,
            prefix=f".{output.name}.",
            delete=False,
        ) as stream:
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
