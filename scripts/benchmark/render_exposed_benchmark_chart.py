#!/usr/bin/env python3
"""Render a grouped Exposed benchmark chart from preserved JSON evidence."""

from __future__ import annotations

import argparse
import html
import json
import math
from pathlib import Path
from statistics import median


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("evidence_dir", type=Path)
    parser.add_argument("output_svg", type=Path)
    return parser.parse_args()


def rows(evidence_dir: Path) -> list[dict[str, object]]:
    result: list[dict[str, object]] = []
    for report in sorted(evidence_dir.glob("*.json")):
        payload = json.loads(report.read_text(encoding="utf-8"))
        if not isinstance(payload, list):
            raise ValueError(f"benchmark report must be an array: {report}")
        for entry in payload:
            metric = entry.get("primaryMetric")
            if not isinstance(metric, dict):
                continue
            result.append(
                {
                    "benchmark": entry["benchmark"],
                    "params": entry.get("params", {}),
                    "score": float(metric["score"]),
                    "unit": metric.get("scoreUnit", "ops/s"),
                }
            )
    return result


def median_series(
    all_rows: list[dict[str, object]],
    benchmark_suffix: str,
    params: dict[str, str],
) -> float:
    values = [
        float(entry["score"])
        for entry in all_rows
        if str(entry["benchmark"]).endswith(benchmark_suffix)
        and entry["params"] == params
    ]
    if len(values) != 3:
        raise ValueError(
            f"expected three repetitions for {benchmark_suffix} {params}, got {len(values)}"
        )
    return median(values)


def escape(value: object) -> str:
    return html.escape(str(value), quote=True)


def panel(
    title: str,
    subtitle: str,
    labels_and_values: list[tuple[str, float]],
    y: int,
    color: str,
    log_scale: bool = False,
) -> list[str]:
    lines: list[str] = []
    panel_height = 390 if len(labels_and_values) <= 3 else 480
    left = 34
    label_x = 68
    bar_x = 450
    bar_max = 1190
    lines.append(f'<rect x="{left}" y="{y}" width="1732" height="{panel_height}" rx="18" class="panel"/>')
    lines.append(f'<text x="{label_x}" y="{y + 48}" class="panelTitle">{escape(title)}</text>')
    lines.append(f'<text x="{label_x}" y="{y + 78}" class="panelSubtitle">{escape(subtitle)}</text>')
    transformed = [math.log10(value) if log_scale else value for _, value in labels_and_values]
    maximum = max(transformed)
    minimum = min(transformed) if log_scale else 0.0
    span = max(maximum - minimum, 1.0)
    row_height = 45
    for index, (label, value) in enumerate(labels_and_values):
        row_y = y + 112 + index * row_height
        transformed_value = math.log10(value) if log_scale else value
        width = max(12.0, bar_max * (transformed_value - minimum) / span)
        lines.append(f'<text x="{label_x}" y="{row_y + 22}" class="label">{escape(label)}</text>')
        lines.append(f'<rect x="{bar_x}" y="{row_y}" width="{width:.1f}" height="24" rx="6" fill="{color}"/>')
        lines.append(f'<text x="{bar_x + width + 16:.1f}" y="{row_y + 21}" class="value">{value:,.0f} ops/s</text>')
    return lines


def render(all_rows: list[dict[str, object]], evidence_name: str) -> str:
    cache = [
        ("Near cache hit · size 10,000", median_series(all_rows, "CacheStrategyBenchmark.nearCacheHit", {"cacheSize": "10000"})),
        ("Local Caffeine hit · size 10,000", median_series(all_rows, "CacheStrategyBenchmark.localCaffeineHit", {"cacheSize": "10000"})),
        ("Near cache read-through miss · size 10,000", median_series(all_rows, "CacheStrategyBenchmark.nearCacheReadThroughMiss", {"cacheSize": "10000"})),
    ]
    database = [
        ("JDBC platform thread · rows 10,000", median_series(all_rows, "JdbcThreadingBenchmark.platformThreadSelectById", {"poolSize": "10", "rowCount": "10000"})),
        ("JDBC virtual thread · rows 10,000", median_series(all_rows, "JdbcThreadingBenchmark.virtualThreadSelectById", {"poolSize": "10", "rowCount": "10000"})),
        ("R2DBC suspend transaction · rows 10,000", median_series(all_rows, "R2dbcCoroutineBenchmark.suspendTransactionSelectById", {"rowCount": "10000"})),
    ]
    custom_ids = [
        ("UUID", median_series(all_rows, "CustomIdTableBenchmark.uuidTableSelectByName", {"rowCount": "10000"})),
        ("Base62 UUIDv7", median_series(all_rows, "CustomIdTableBenchmark.base62TableSelectByName", {"rowCount": "10000"})),
        ("KSUID millis", median_series(all_rows, "CustomIdTableBenchmark.ksuidMillisTableSelectByName", {"rowCount": "10000"})),
        ("KSUID", median_series(all_rows, "CustomIdTableBenchmark.ksuidTableSelectByName", {"rowCount": "10000"})),
        ("Snowflake", median_series(all_rows, "CustomIdTableBenchmark.snowflakeTableSelectByName", {"rowCount": "10000"})),
        ("Timebased UUID", median_series(all_rows, "CustomIdTableBenchmark.timebasedUuidTableSelectByName", {"rowCount": "10000"})),
        ("ULID", median_series(all_rows, "CustomIdTableBenchmark.ulidTableSelectByName", {"rowCount": "10000"})),
    ]
    lines = [
        '<svg xmlns="http://www.w3.org/2000/svg" width="1800" height="1510" viewBox="0 0 1800 1510" role="img" aria-labelledby="title desc">',
        '  <title id="title">Exposed benchmark comparison</title>',
        f'  <desc id="desc">Grouped median throughput from three JDK 25 H2 repetitions in {escape(evidence_name)}. All panels use a linear width scale within their own unit and comparison group.</desc>',
        '  <style>',
        '    .background { fill: #08111f; }',
        '    .panel { fill: #111f33; stroke: #263b57; stroke-width: 2; }',
        '    .title { fill: #f8fafc; font: 700 34px Arial, sans-serif; }',
        '    .subtitle { fill: #a9bdd8; font: 16px Arial, sans-serif; }',
        '    .panelTitle { fill: #f8fafc; font: 700 23px Arial, sans-serif; }',
        '    .panelSubtitle { fill: #a9bdd8; font: 15px Arial, sans-serif; }',
        '    .label { fill: #dbeafe; font: 15px Arial, sans-serif; }',
        '    .value { fill: #f8fafc; font: 700 14px Arial, sans-serif; }',
        '    .footnote { fill: #8da7c4; font: 14px Arial, sans-serif; }',
        '  </style>',
        '  <rect class="background" width="1800" height="1510"/>',
        '  <text x="34" y="54" class="title">Exposed benchmark comparison</text>',
        '  <text x="34" y="84" class="subtitle">Three-run median · JDK 25.0.4 · H2 · throughput (ops/s)</text>',
    ]
    lines.extend(panel("Cache strategy", "Cache size 10,000 · linear bar width; compare values within this panel", cache, 112, "#38bdf8"))
    lines.extend(panel("JDBC vs R2DBC", "Rows 10,000 · pool size 10 for JDBC · linear bar width", database, 530, "#2dd4bf"))
    lines.extend(panel("Custom ID table lookup", "Select by name · rows 10,000 · linear bar width", custom_ids, 948, "#fbbf24"))
    lines.extend([
        '  <text x="68" y="1470" class="footnote">Interpret bars within their panel only. Redis profiles are not included because no Redis endpoint was available; see the preserved JSON evidence for all series.</text>',
        '</svg>',
    ])
    return "\n".join(lines) + "\n"


def main() -> None:
    args = parse_args()
    args.output_svg.parent.mkdir(parents=True, exist_ok=True)
    args.output_svg.write_text(render(rows(args.evidence_dir), args.evidence_dir.name), encoding="utf-8")


if __name__ == "__main__":
    main()
