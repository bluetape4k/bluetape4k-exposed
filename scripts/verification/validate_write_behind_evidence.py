#!/usr/bin/env python3
"""Fail-closed validation for the Issue #732 write-behind evidence files."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path
from typing import Any


STATUS_VALUES = {"PASS", "FAIL", "PENDING", "N/A", "SKIPPED"}
EXPECTED_ROWS = {
    ("coordinator", "NONE"),
    *((adapter, database) for adapter in ("jdbc-caffeine", "suspended-jdbc-caffeine") for database in ("H2", "POSTGRESQL", "MYSQL_V8")),
    ("r2dbc-caffeine", "H2"),
    ("r2dbc-caffeine", "POSTGRESQL"),
    ("r2dbc-caffeine", "MYSQL_V8"),
}
UTC_TIMESTAMP = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$")


def _fail(message: str) -> None:
    raise SystemExit(f"write-behind-evidence: {message}")


def _read_json(path: Path) -> Any:
    if not path.is_file():
        _fail(f"missing evidence file: {path}")
    try:
        return json.loads(path.read_text(encoding="utf-8"), parse_constant=lambda value: _fail(f"non-finite JSON constant in {path}: {value}"))
    except json.JSONDecodeError as error:
        _fail(f"invalid JSON {path}: {error}")


def _nonblank(value: Any) -> bool:
    return isinstance(value, str) and bool(value.strip())


def _validate_timestamp(value: Any, location: str) -> None:
    if not isinstance(value, str) or not UTC_TIMESTAMP.fullmatch(value):
        _fail(f"{location} must be an ISO-8601 UTC timestamp")


def _validate_matrix(path: Path) -> None:
    payload = _read_json(path)
    if not isinstance(payload, dict):
        _fail("matrix must be a JSON object")
    if payload.get("schema") != 1 or payload.get("version") != 1:
        _fail("matrix schema/version must both be 1")
    if not _nonblank(payload.get("sourceHead")):
        _fail("matrix.sourceHead must be nonblank")
    rows = payload.get("rows")
    if not isinstance(rows, list) or not rows:
        _fail("matrix.rows must be a non-empty array")

    seen: set[tuple[str, str]] = set()
    for index, row in enumerate(rows):
        location = f"matrix.rows[{index}]"
        if not isinstance(row, dict):
            _fail(f"{location} must be an object")
        adapter = row.get("adapter")
        database = row.get("database")
        identity = (adapter, database)
        if not (_nonblank(adapter) and _nonblank(database)):
            _fail(f"{location}.adapter/database must be nonblank")
        if identity in seen:
            _fail(f"duplicate matrix row: {adapter}/{database}")
        seen.add(identity)
        if identity not in EXPECTED_ROWS:
            _fail(f"unexpected matrix row: {adapter}/{database}")
        for field in ("required", "applicable"):
            if not isinstance(row.get(field), bool):
                _fail(f"{location}.{field} must be boolean")
        status = row.get("status")
        if status not in STATUS_VALUES:
            _fail(f"{location}.status must be one of {sorted(STATUS_VALUES)}")
        if not _nonblank(row.get("reason")):
            _fail(f"{location}.reason must be nonblank")
        _validate_timestamp(row.get("startedAt"), f"{location}.startedAt")
        _validate_timestamp(row.get("finishedAt"), f"{location}.finishedAt")
        if not isinstance(row.get("evidence"), dict) or not row["evidence"]:
            _fail(f"{location}.evidence must be a non-empty object")
        if row["required"] and row["applicable"] and status != "PASS":
            _fail(f"required applicable row is not PASS: {adapter}/{database}={status}")
        if not row["applicable"] and status == "PASS":
            _fail(f"non-applicable row cannot be PASS: {adapter}/{database}")

    missing = EXPECTED_ROWS - seen
    if missing:
        _fail(f"matrix is missing rows: {sorted(missing)}")


def _canonical_metrics(metrics: list[Any]) -> bytes:
    return json.dumps(metrics, ensure_ascii=True, sort_keys=True, separators=(",", ":")).encode("utf-8")


def _validate_metrics(path: Path) -> None:
    payload = _read_json(path)
    if not isinstance(payload, dict):
        _fail("metrics must be a JSON object")
    if payload.get("schema") != 1 or payload.get("version") != 1:
        _fail("metrics schema/version must both be 1")
    if not _nonblank(payload.get("sourceHead")):
        _fail("metrics.sourceHead must be nonblank")
    metrics = payload.get("metrics")
    if not isinstance(metrics, list):
        _fail("metrics.metrics must be an array")
    for index, metric in enumerate(metrics):
        location = f"metrics.metrics[{index}]"
        if not isinstance(metric, dict):
            _fail(f"{location} must be an object")
        if not _nonblank(metric.get("name")) or not _nonblank(metric.get("type")):
            _fail(f"{location}.name/type must be nonblank")
        tags = metric.get("tags", [])
        if not isinstance(tags, list):
            _fail(f"{location}.tags must be an array")
        for tag in tags:
            if not isinstance(tag, dict) or not _nonblank(tag.get("key")) or not _nonblank(tag.get("value")):
                _fail(f"{location}.tags entries must contain nonblank key/value")
            if tag["key"].lower().replace("-", "_") in {"queue_depth", "queuedepth"}:
                _fail("queueDepth must not be a metric label")
        if any(key.lower() in {"throwable", "exception", "cause", "message"} for key in metric):
            _fail(f"{location} contains unstable exception detail")
    checksum = payload.get("baselineChecksum")
    expected = "sha256:" + hashlib.sha256(_canonical_metrics(metrics)).hexdigest()
    if checksum != expected:
        _fail("metrics.baselineChecksum does not match canonical metrics")
    if not _nonblank(payload.get("note")):
        _fail("metrics.note must be nonblank")


def validate(matrix: Path, metrics: Path) -> None:
    _validate_matrix(matrix)
    _validate_metrics(metrics)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--matrix", type=Path, default=Path("build/verification/write-behind-db-matrix.json"))
    parser.add_argument("--metrics", type=Path, default=Path("build/verification/write-behind-metrics.json"))
    args = parser.parse_args()
    validate(args.matrix, args.metrics)
    print(f"write-behind-evidence: PASS matrix={args.matrix} metrics={args.metrics}")


if __name__ == "__main__":
    main()
