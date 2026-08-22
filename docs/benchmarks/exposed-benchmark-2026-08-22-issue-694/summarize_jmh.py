#!/usr/bin/env python3
"""Validate six Issue #694 JMH payloads and emit median benchmark evidence."""

from __future__ import annotations

import argparse
import json
import math
import re
import sys
from pathlib import Path
from statistics import median


DRIVERS = ("POSTGRESQL", "MYSQL_V8")
METHODS = ("sequentialKeysetPaging", "parallelKeyEnumeration")
ROW_COUNTS = ("1000", "10000")
POOL_SIZES = ("1", "2", "4")
EXPECTED_CARDINALITY = len(METHODS) * len(ROW_COUNTS) * len(POOL_SIZES)
FORBIDDEN = re.compile(r"jdbc:|password|docker_host|@exposed2025", re.IGNORECASE)
BENCHMARK_PREFIX = "io.bluetape4k.exposed.benchmark.jdbc.JdbcDriverKeyEnumerationBenchmark."
RAW_NAMES = tuple(
    f"{driver.lower().replace('_v8', '')}-run-{run}.json"
    for driver in DRIVERS
    for run in range(1, 4)
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("evidence_dir", type=Path)
    parser.add_argument("--output", type=Path)
    return parser.parse_args()


def fail(message: str) -> "NoReturn":
    raise SystemExit(f"summarize_jmh: {message}")


def finite_number(value: object, label: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        fail(f"{label} must be numeric")
    numeric = float(value)
    if not math.isfinite(numeric) or numeric < 0:
        fail(f"{label} must be finite and non-negative: {value}")
    return numeric


def metric_score(entry: dict[str, object], metric_name: str, label: str) -> float:
    metric = entry.get(metric_name)
    if not isinstance(metric, dict):
        fail(f"{label} is missing {metric_name}")
    return finite_number(metric.get("score"), f"{label}.{metric_name}.score")


def raw_values(metric: object, label: str) -> list[float]:
    if not isinstance(metric, dict):
        fail(f"{label} must be an object")
    values = metric.get("rawData")
    if not isinstance(values, list) or not values:
        fail(f"{label}.rawData must be a non-empty array")
    flattened: list[float] = []
    for index, batch in enumerate(values):
        if not isinstance(batch, list):
            fail(f"{label}.rawData[{index}] must be an array")
        flattened.extend(finite_number(value, f"{label}.rawData[{index}]") for value in batch)
    if not flattened:
        fail(f"{label}.rawData must contain values")
    return flattened


def expected_driver(path: Path) -> str:
    return "POSTGRESQL" if path.name.startswith("postgresql-") else "MYSQL_V8"


def validate_file(path: Path) -> list[dict[str, object]]:
    if not path.is_file() or path.is_symlink():
        fail(f"raw file is not a regular file: {path}")
    raw_text = path.read_text(encoding="utf-8")
    if FORBIDDEN.search(raw_text):
        fail(f"raw file contains a forbidden connection or credential token: {path.name}")
    try:
        payload = json.loads(raw_text, parse_constant=lambda value: fail(f"{path.name} contains non-finite JSON constant: {value}"))
    except json.JSONDecodeError as exc:
        fail(f"invalid JSON in {path.name}: {exc}")
    if not isinstance(payload, list) or len(payload) != EXPECTED_CARDINALITY:
        fail(f"{path.name} must contain exactly {EXPECTED_CARDINALITY} primary entries")

    driver = expected_driver(path)
    seen: set[tuple[str, str, str]] = set()
    for index, entry in enumerate(payload):
        label = f"{path.name}[{index}]"
        if not isinstance(entry, dict):
            fail(f"{label} must be an object")
        benchmark = entry.get("benchmark")
        if not isinstance(benchmark, str) or not benchmark.startswith(BENCHMARK_PREFIX):
            fail(f"{label}.benchmark is not the approved driver benchmark")
        method = benchmark.removeprefix(BENCHMARK_PREFIX)
        if method not in METHODS:
            fail(f"{label}.benchmark has an unsupported method: {method}")
        params = entry.get("params")
        if not isinstance(params, dict):
            fail(f"{label}.params must be an object")
        actual_driver = str(params.get("driver"))
        row_count = str(params.get("rowCount"))
        pool_size = str(params.get("poolSize"))
        if actual_driver != driver or actual_driver not in DRIVERS:
            fail(f"{label}.params.driver is invalid: {actual_driver}")
        if row_count not in ROW_COUNTS or pool_size not in POOL_SIZES:
            fail(f"{label} has an unsupported rowCount/poolSize: {row_count}/{pool_size}")
        identity = (method, row_count, pool_size)
        if identity in seen:
            fail(f"{path.name} contains duplicate case: {identity}")
        seen.add(identity)

        primary = entry.get("primaryMetric")
        if not isinstance(primary, dict):
            fail(f"{label}.primaryMetric must be an object")
        score = finite_number(primary.get("score"), f"{label}.primaryMetric.score")
        if score < 0:
            fail(f"{label}.primaryMetric.score must be non-negative")
        if primary.get("scoreUnit") != "ops/s":
            fail(f"{label}.primaryMetric.scoreUnit must be ops/s")

        secondary = entry.get("secondaryMetrics")
        if not isinstance(secondary, dict):
            fail(f"{label}.secondaryMetrics must be an object")
        required = ("activeAtEnd", "connectionRequests", "peakActiveLeases", "statementExecutions")
        for metric_name in required:
            metric_score(secondary, metric_name, label)
            raw_values(secondary.get(metric_name), f"{label}.secondaryMetrics.{metric_name}")
        peak_values = raw_values(secondary["peakActiveLeases"], f"{label}.secondaryMetrics.peakActiveLeases")
        active_values = raw_values(secondary["activeAtEnd"], f"{label}.secondaryMetrics.activeAtEnd")
        peak_bound = min(int(pool_size), 2)
        if max(peak_values) > peak_bound:
            fail(f"{label} exceeded active lease bound {peak_bound}: {max(peak_values)}")
        if max(active_values) != 0:
            fail(f"{label} ended with active JDBC leases: {max(active_values)}")
    expected = {(method, row, pool) for method in METHODS for row in ROW_COUNTS for pool in POOL_SIZES}
    if seen != expected:
        fail(f"{path.name} does not cover the complete matrix: missing={expected - seen}")
    return payload


def summarize(evidence_dir: Path) -> dict[str, object]:
    if not evidence_dir.is_dir():
        fail(f"evidence directory does not exist: {evidence_dir}")
    reports: dict[tuple[str, str, str, str], list[dict[str, object]]] = {}
    files: list[Path] = []
    for name in RAW_NAMES:
        path = evidence_dir / name
        if not path.exists():
            fail(f"missing required raw file: {name}")
        files.append(path)
    for path in files:
        driver = expected_driver(path)
        run = path.stem.rsplit("-", 1)[-1]
        payload = validate_file(path)
        for entry in payload:
            benchmark = str(entry["benchmark"])
            method = benchmark.removeprefix(BENCHMARK_PREFIX)
            params = entry["params"]
            assert isinstance(params, dict)
            key = (driver, method, str(params["rowCount"]), str(params["poolSize"]))
            reports.setdefault(key, []).append({"run": run, "entry": entry})

    rows: list[dict[str, object]] = []
    for key in sorted(reports):
        driver, method, row_count, pool_size = key
        values = reports[key]
        if len(values) != 3:
            fail(f"expected three runs for {key}, found {len(values)}")
        ops_values: list[float] = []
        rows_values: list[float] = []
        statement_per_op: list[float] = []
        connection_per_op: list[float] = []
        peak_values: list[float] = []
        active_values: list[float] = []
        for value in values:
            entry = value["entry"]
            assert isinstance(entry, dict)
            primary = entry["primaryMetric"]
            assert isinstance(primary, dict)
            ops = finite_number(primary["score"], f"{key}.ops/s")
            row_number = int(row_count)
            ops_values.append(ops)
            rows_values.append(ops * row_number)
            secondary = entry["secondaryMetrics"]
            assert isinstance(secondary, dict)
            statement = metric_score(secondary, "statementExecutions", str(key))
            connections = metric_score(secondary, "connectionRequests", str(key))
            statement_per_op.append(statement / ops if ops else 0.0)
            connection_per_op.append(connections / ops if ops else 0.0)
            peak_values.append(max(raw_values(secondary["peakActiveLeases"], str(key))))
            active_values.append(max(raw_values(secondary["activeAtEnd"], str(key))))
        rows.append(
            {
                "driver": driver,
                "method": method,
                "rowCount": int(row_count),
                "poolSize": int(pool_size),
                "runs": [value["run"] for value in values],
                "medianOpsPerSecond": median(ops_values),
                "medianRowsPerSecond": median(rows_values),
                "medianStatementExecutionsPerOperation": median(statement_per_op),
                "medianConnectionRequestsPerOperation": median(connection_per_op),
                "medianPeakActiveLeases": median(peak_values),
                "medianActiveAtEnd": median(active_values),
            }
        )
    return {"cardinalityPerRawFile": EXPECTED_CARDINALITY, "rows": rows}


def main() -> None:
    args = parse_args()
    result = summarize(args.evidence_dir)
    encoded = json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    if args.output:
        if args.output.exists() or args.output.is_symlink():
            fail(f"summary output already exists: {args.output}")
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(encoded, encoding="utf-8")
    sys.stdout.write(encoded)


if __name__ == "__main__":
    main()
