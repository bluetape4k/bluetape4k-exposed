#!/usr/bin/env python3
"""Fail-closed validation for a single kotlinx-benchmark report directory."""

from __future__ import annotations

import argparse
import json
import math
import subprocess
from pathlib import Path
from typing import Any


REQUIRED_FIELDS = (
    "runId",
    "sourceRef",
    "sourceHead",
    "environment",
    "warmups",
    "iterations",
    "metric",
)


def _fail(message: str) -> None:
    raise SystemExit(f"benchmark-sidecar: {message}")


def _read_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"), parse_constant=lambda value: _fail(f"non-finite JSON constant in {path}: {value}"))
    except json.JSONDecodeError as error:
        _fail(f"invalid JSON {path}: {error}")


def _is_nonblank(value: Any) -> bool:
    return isinstance(value, str) and bool(value.strip())


def _assert_finite_numbers(value: Any, path: str) -> None:
    if isinstance(value, bool):
        return
    if isinstance(value, (int, float)):
        if not math.isfinite(float(value)):
            _fail(f"{path} must be finite")
        return
    if isinstance(value, dict):
        for key, item in value.items():
            _assert_finite_numbers(item, f"{path}.{key}")
    elif isinstance(value, list):
        for index, item in enumerate(value):
            _assert_finite_numbers(item, f"{path}[{index}]")


def _has_measured_entry(value: Any) -> bool:
    if isinstance(value, dict):
        benchmark = value.get("benchmark")
        score = value.get("score")
        primary_metric = value.get("primaryMetric")
        primary_score = primary_metric.get("score") if isinstance(primary_metric, dict) else None
        has_finite_score = (
            isinstance(score, (int, float)) and not isinstance(score, bool) and math.isfinite(float(score))
        ) or (
            isinstance(primary_score, (int, float))
            and not isinstance(primary_score, bool)
            and math.isfinite(float(primary_score))
        )
        if _is_nonblank(benchmark) and has_finite_score:
            return True
        return any(_has_measured_entry(item) for item in value.values())
    if isinstance(value, list):
        return any(_has_measured_entry(item) for item in value)
    return False


def current_head(source_root: Path) -> str:
    try:
        return subprocess.check_output(
            ["git", "-C", str(source_root), "rev-parse", "HEAD"],
            text=True,
            stderr=subprocess.STDOUT,
        ).strip()
    except (OSError, subprocess.CalledProcessError) as error:
        _fail(f"cannot resolve source HEAD from {source_root}: {error}")


def validate(report_dir: Path, expected_head: str, expected_run_id: str | None = None) -> None:
    if not report_dir.is_dir():
        _fail(f"report directory does not exist: {report_dir}")
    metadata_path = report_dir / "metadata.json"
    report_path = report_dir / "benchmark.json"
    if not metadata_path.is_file():
        _fail(f"missing metadata sidecar: {metadata_path}")
    if not report_path.is_file():
        _fail(f"missing benchmark report: {report_path}")

    metadata = _read_json(metadata_path)
    if not isinstance(metadata, dict):
        _fail("metadata must be a JSON object")
    missing = [field for field in REQUIRED_FIELDS if field not in metadata]
    if missing:
        _fail(f"metadata missing required fields: {', '.join(missing)}")

    run_id = metadata["runId"]
    if not _is_nonblank(run_id):
        _fail("metadata.runId must be nonblank")
    if run_id != report_dir.name:
        _fail(f"metadata.runId={run_id!r} does not identify report directory {report_dir.name!r}")
    if expected_run_id is not None and run_id != expected_run_id:
        _fail(f"metadata.runId={run_id!r} does not match expected run id {expected_run_id!r}")
    for field in ("sourceRef", "sourceHead"):
        if not _is_nonblank(metadata[field]):
            _fail(f"metadata.{field} must be nonblank")
    if metadata["sourceHead"] != expected_head:
        _fail(f"metadata.sourceHead={metadata['sourceHead']!r} is stale; expected {expected_head!r}")

    environment = metadata["environment"]
    if not (_is_nonblank(environment) or (isinstance(environment, dict) and environment)):
        _fail("metadata.environment must be a nonblank string or object")
    for field in ("warmups", "iterations"):
        value = metadata[field]
        if not isinstance(value, int) or isinstance(value, bool) or value <= 0:
            _fail(f"metadata.{field} must be a positive integer")

    metric = metadata["metric"]
    if not isinstance(metric, dict) or not _is_nonblank(metric.get("type")):
        _fail("metadata.metric.type must be nonblank")
    if not isinstance(metric.get("value"), (int, float)) or isinstance(metric.get("value"), bool):
        _fail("metadata.metric.value must be numeric")
    if not math.isfinite(float(metric["value"])):
        _fail("metadata.metric.value must be finite")
    _assert_finite_numbers(metadata, "metadata")

    report_text = report_path.read_text(encoding="utf-8")
    if "pending" in report_text.lower():
        _fail(f"pending placeholder is not a benchmark result: {report_path}")
    report = _read_json(report_path)
    _assert_finite_numbers(report, "benchmark")
    if not isinstance(report, (dict, list)) or not report:
        _fail(f"benchmark report is empty: {report_path}")
    if not _has_measured_entry(report):
        _fail(f"benchmark report has no measured benchmark/score entry: {report_path}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("report_dir", type=Path)
    parser.add_argument("--source-root", type=Path, default=Path.cwd())
    parser.add_argument("--expected-head")
    parser.add_argument("--expected-run-id")
    args = parser.parse_args()
    validate(args.report_dir, args.expected_head or current_head(args.source_root), args.expected_run_id)
    print(f"benchmark-sidecar: PASS {args.report_dir}")


if __name__ == "__main__":
    main()
