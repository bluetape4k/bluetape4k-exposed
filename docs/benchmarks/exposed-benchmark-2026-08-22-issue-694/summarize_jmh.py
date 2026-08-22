#!/usr/bin/env python3
"""Validate six Issue #694 JMH payloads and emit median benchmark evidence."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import sys
from datetime import datetime
from pathlib import Path
from statistics import median


DRIVERS = ("POSTGRESQL", "MYSQL_V8")
METHODS = ("sequentialKeysetPaging", "parallelKeyEnumeration")
ROW_COUNTS = ("1000", "10000")
POOL_SIZES = ("1", "2", "4")
EXPECTED_CARDINALITY = len(METHODS) * len(ROW_COUNTS) * len(POOL_SIZES)
EXPECTED_RAW_SAMPLES = 3
FORBIDDEN = re.compile(r"jdbc:|(?:postgres(?:ql)?|mysql)://|password|passwd|secret|docker_host|@exposed2025", re.IGNORECASE)
BENCHMARK_PREFIX = "io.bluetape4k.exposed.benchmark.jdbc.JdbcDriverKeyEnumerationBenchmark."
EXPECTED_JMH_ENVELOPE = {
    "jmhVersion": "1.37",
    "mode": "thrpt",
    "threads": 1,
    "forks": 1,
    "warmupIterations": 1,
    "warmupTime": "1 s",
    "measurementIterations": 3,
    "measurementTime": "1 s",
}
RAW_NAMES = tuple(
    f"{driver.lower().replace('_v8', '')}-run-{run}.json"
    for driver in DRIVERS
    for run in range(1, 4)
)
EXPECTED_IMPLEMENTATION_SHA = "f325a70fbd2047cdef28be928eeea4675b4b05b6"
EXPECTED_CATALOG_REF = "91f9ea9336b5ea991f5675323a1cf25ccfd6f5ed"
EXPECTED_IMAGE_DIGESTS = {
    "POSTGRESQL": "sha256:9a8afca54e7861fd90fab5fdf4c42477a6b1cb7d293595148e674e0a3181de15",
    "MYSQL_V8": "sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb",
}
EXPECTED_RUNTIME = {
    "docker_context": "default",
    "docker_server_version": "29.2.1",
    "gradle_version": "Gradle 9.7.0",
    "colima_version": "colima version 0.10.3",
    "testcontainers_version": "2.0.5",
    "host_os": "Darwin",
    "host_arch": "arm64",
    "python": "3.9.6",
    "jdk": 'java version "25.0.4" 2026-07-21 LTS',
}
EXPECTED_PROVENANCE = {
    "POSTGRESQL": {
        "driver_version": "42.7.13",
        "image": "postgres:18.4-alpine",
        "resolved_driver_artifact": "org.postgresql:postgresql:42.7.13",
    },
    "MYSQL_V8": {
        "driver_version": "9.7.0",
        "image": "mysql:8.4.11",
        "resolved_driver_artifact": "com.mysql:mysql-connector-j:9.7.0",
    },
}


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


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def concrete_string(value: object, label: str) -> str:
    if not isinstance(value, str) or not value.strip() or value.strip().lower() in {"unknown", "n/a", "none"}:
        fail(f"{label} must be a concrete non-placeholder string")
    return value


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


def validate_jmh_envelope(entry: dict[str, object], label: str) -> None:
    for field, expected in EXPECTED_JMH_ENVELOPE.items():
        if entry.get(field) != expected:
            fail(f"{label}.{field} must be {expected!r}, found {entry.get(field)!r}")


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
        validate_jmh_envelope(entry, label)
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
        primary_raw = raw_values(primary, f"{label}.primaryMetric")
        if len(primary_raw) != EXPECTED_RAW_SAMPLES:
            fail(f"{label}.primaryMetric.rawData must contain exactly {EXPECTED_RAW_SAMPLES} samples")
        score = finite_number(primary.get("score"), f"{label}.primaryMetric.score")
        if score < 0:
            fail(f"{label}.primaryMetric.score must be non-negative")
        if primary.get("scoreUnit") != "ops/s":
            fail(f"{label}.primaryMetric.scoreUnit must be ops/s")

        secondary = entry.get("secondaryMetrics")
        if not isinstance(secondary, dict):
            fail(f"{label}.secondaryMetrics must be an object")
        required = ("activeAtEnd", "connectionRequests", "peakActiveLeases", "statementExecutions")
        secondary_raw: dict[str, list[float]] = {}
        for metric_name in required:
            metric_score(secondary, metric_name, label)
            values = raw_values(secondary.get(metric_name), f"{label}.secondaryMetrics.{metric_name}")
            if len(values) != EXPECTED_RAW_SAMPLES:
                fail(
                    f"{label}.secondaryMetrics.{metric_name}.rawData must contain "
                    f"exactly {EXPECTED_RAW_SAMPLES} samples"
                )
            if len(values) != len(primary_raw):
                fail(
                    f"{label}.secondaryMetrics.{metric_name}.rawData must have "
                    f"{len(primary_raw)} values, found {len(values)}"
                )
            secondary_raw[metric_name] = values
        peak_values = secondary_raw["peakActiveLeases"]
        active_values = secondary_raw["activeAtEnd"]
        peak_bound = min(int(pool_size), 2)
        if max(peak_values) > peak_bound:
            fail(f"{label} exceeded active lease bound {peak_bound}: {max(peak_values)}")
        if max(active_values) != 0:
            fail(f"{label} ended with active JDBC leases: {max(active_values)}")
    expected = {(method, row, pool) for method in METHODS for row in ROW_COUNTS for pool in POOL_SIZES}
    if seen != expected:
        fail(f"{path.name} does not cover the complete matrix: missing={expected - seen}")
    return payload


def validate_metadata(evidence_dir: Path) -> list[dict[str, object]]:
    metadata_path = evidence_dir / "raw-metadata.jsonl"
    if not metadata_path.is_file() or metadata_path.is_symlink():
        fail("raw-metadata.jsonl must be a regular file")
    records: list[dict[str, object]] = []
    with metadata_path.open(encoding="utf-8") as stream:
        for line_number, line in enumerate(stream, start=1):
            if not line.strip():
                continue
            try:
                record = json.loads(line)
            except json.JSONDecodeError as exc:
                fail(f"invalid metadata JSONL at line {line_number}: {exc}")
            if not isinstance(record, dict):
                fail(f"metadata line {line_number} must be an object")
            if FORBIDDEN.search(json.dumps(record, ensure_ascii=False)):
                fail(f"metadata line {line_number} contains a known forbidden token")
            records.append(record)
    if len(records) != len(RAW_NAMES):
        fail(f"raw-metadata.jsonl must contain exactly {len(RAW_NAMES)} records")
    by_file: dict[str, dict[str, object]] = {}
    for record in records:
        raw_file = concrete_string(record.get("raw_file"), "metadata.raw_file")
        if raw_file in by_file:
            fail(f"duplicate metadata raw_file: {raw_file}")
        by_file[raw_file] = record
    if set(by_file) != set(RAW_NAMES):
        fail(f"metadata raw files do not match required captures: {sorted(set(RAW_NAMES) - set(by_file))}")
    git_shas: set[str] = set()
    for raw_file in RAW_NAMES:
        record = by_file[raw_file]
        driver = expected_driver(Path(raw_file))
        expected = EXPECTED_PROVENANCE[driver]
        for field in (
            "driver",
            "driver_version",
            "image",
            "resolved_driver_artifact",
            "image_digest",
            "observed_image_digest",
            "catalog_ref",
            "run_id",
            "source_report",
        ):
            concrete_string(record.get(field), f"metadata.{raw_file}.{field}")
        if record["driver"] != driver:
            fail(f"metadata.{raw_file}.driver does not match its filename")
        expected_run = Path(raw_file).stem.rsplit("-", 1)[-1]
        if record["run_id"] != expected_run:
            fail(f"metadata.{raw_file}.run_id must match its filename run number")
        if record["source_report"] != "benchmark.json":
            fail(f"metadata.{raw_file}.source_report must be benchmark.json")
        captured_at = concrete_string(record.get("captured_at_utc"), f"metadata.{raw_file}.captured_at_utc")
        if not re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z", captured_at):
            fail(f"metadata.{raw_file}.captured_at_utc must be RFC3339 UTC")
        try:
            datetime.fromisoformat(captured_at.replace("Z", "+00:00"))
        except ValueError:
            fail(f"metadata.{raw_file}.captured_at_utc is not a valid timestamp")
        for field, expected_value in expected.items():
            if record[field] != expected_value:
                fail(f"metadata.{raw_file}.{field} must be {expected_value!r}, found {record[field]!r}")
        if record["catalog_ref"] != EXPECTED_CATALOG_REF:
            fail(f"metadata.{raw_file}.catalog_ref must be the approved immutable ref")
        if str(record.get("git_sha")).lower() != EXPECTED_IMPLEMENTATION_SHA:
            fail(f"metadata.{raw_file}.git_sha must be the approved implementation SHA")
        digest = str(record["image_digest"])
        if digest != EXPECTED_IMAGE_DIGESTS[driver] or not re.fullmatch(r"sha256:[0-9a-f]{64}", digest) or record["observed_image_digest"] != digest:
            fail(f"metadata.{raw_file}.image_digest was not observed from Docker")
        raw_path = evidence_dir / raw_file
        actual_sha = sha256(raw_path)
        if record.get("raw_sha256") != actual_sha:
            fail(f"metadata.{raw_file}.raw_sha256 does not match the raw file")
        if not re.fullmatch(r"[0-9a-f]{64}", str(record.get("raw_sha256"))):
            fail(f"metadata.{raw_file}.raw_sha256 must be lowercase SHA-256")
        git_sha = concrete_string(record.get("git_sha"), f"metadata.{raw_file}.git_sha").lower()
        if not re.fullmatch(r"[0-9a-f]{40}", git_sha):
            fail(f"metadata.{raw_file}.git_sha must be a 40-character SHA")
        git_shas.add(git_sha)
        if not isinstance(record.get("git_dirty"), bool):
            fail(f"metadata.{raw_file}.git_dirty must be boolean")
        if record["git_dirty"] is not True:
            fail(f"metadata.{raw_file}.git_dirty must record the captured dirty worktree")
        for field in (
            "captured_at_utc",
            "jdk",
            "python",
            "host_os",
            "host_arch",
            "gradle_version",
            "docker_context",
            "docker_server_version",
            "colima_version",
            "testcontainers_version",
        ):
            concrete_string(record.get(field), f"metadata.{raw_file}.{field}")
        for field, expected_value in EXPECTED_RUNTIME.items():
            if record[field] != expected_value:
                fail(f"metadata.{raw_file}.{field} must be observed value {expected_value!r}")
    if len(git_shas) != 1:
        fail(f"metadata captures disagree on implementation SHA: {sorted(git_shas)}")
    if git_shas != {EXPECTED_IMPLEMENTATION_SHA}:
        fail(f"metadata captures do not use the approved implementation SHA: {sorted(git_shas)}")
    return [by_file[name] for name in RAW_NAMES]


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
    metadata = validate_metadata(evidence_dir)
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
            primary_raw = raw_values(primary, f"{key}.primaryMetric")
            row_number = int(row_count)
            ops_values.append(ops)
            rows_values.append(ops * row_number)
            secondary = entry["secondaryMetrics"]
            assert isinstance(secondary, dict)
            statement_raw = raw_values(secondary["statementExecutions"], str(key))
            connection_raw = raw_values(secondary["connectionRequests"], str(key))
            if len(statement_raw) != len(primary_raw) or len(connection_raw) != len(primary_raw):
                fail(f"{key} secondary rawData lengths do not match primary rawData")
            operations = sum(primary_raw)
            if operations <= 0:
                fail(f"{key} primary rawData must contain positive operations")
            statement_per_op.append(sum(statement_raw) / operations)
            connection_per_op.append(sum(connection_raw) / operations)
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
    return {
        "cardinalityPerRawFile": EXPECTED_CARDINALITY,
        "provenance": {
            "gitSha": metadata[0]["git_sha"],
            "catalogRef": metadata[0]["catalog_ref"],
            "gitDirty": sorted({record["git_dirty"] for record in metadata}),
            "driverArtifacts": sorted({record["resolved_driver_artifact"] for record in metadata}),
            "imageDigestsObserved": sorted({record["observed_image_digest"] for record in metadata}),
            "testcontainersVersions": sorted({record["testcontainers_version"] for record in metadata}),
        },
        "rows": rows,
    }


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
