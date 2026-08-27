#!/usr/bin/env python3
"""Fail-closed validation for the Issue #732 write-behind evidence files."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shlex
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any


STATUS_VALUES = {"PASS", "FAIL", "PENDING", "N/A", "SKIPPED", "TIMED_OUT"}
EXPECTED_ROWS: dict[tuple[str, str], tuple[bool, bool]] = {
    ("coordinator", "NONE"): (True, True),
    ("r2dbc-caffeine", "H2"): (True, True),
    ("r2dbc-caffeine", "POSTGRESQL"): (False, False),
    ("r2dbc-caffeine", "MYSQL_V8"): (False, False),
}
EXPECTED_ROWS.update(
    {
        (adapter, database): (True, True)
        for adapter in ("jdbc-caffeine", "suspended-jdbc-caffeine")
        for database in ("H2", "POSTGRESQL", "MYSQL_V8")
    }
)
TASK_BY_ADAPTER = {
    "coordinator": ":bluetape4k-exposed-cache:test",
    "jdbc-caffeine": ":bluetape4k-exposed-jdbc-caffeine:test",
    "suspended-jdbc-caffeine": ":bluetape4k-exposed-jdbc-caffeine:test",
    "r2dbc-caffeine": ":bluetape4k-exposed-r2dbc-caffeine:test",
}
REQUIRED_GRADLE_FLAGS = (
    "--no-configuration-cache",
    "--no-daemon",
    "--console=plain",
    "--no-parallel",
    "--max-workers=1",
    "--rerun-tasks",
    "--no-build-cache",
)
TEST_SUMMARY_LINE = re.compile(
    r"SUCCESS: Executed (?P<executed>\d+) tests? in .*?(?:\((?P<skipped>\d+) skipped\))?$"
)
UTC_TIMESTAMP = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$")
SOURCE_HEAD = re.compile(r"^[0-9a-f]{40}$")
SHA256 = re.compile(r"^sha256:[0-9a-f]{64}$")


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


def _strict_int(value: Any) -> bool:
    return type(value) is int


def _validate_timestamp(value: Any, location: str) -> None:
    if not isinstance(value, str) or not UTC_TIMESTAMP.fullmatch(value):
        _fail(f"{location} must be an ISO-8601 UTC timestamp")


def _validate_source(payload: dict[str, Any], kind: str, expected_source_head: str | None) -> None:
    source_head = payload.get("sourceHead")
    if not isinstance(source_head, str) or not SOURCE_HEAD.fullmatch(source_head):
        _fail(f"{kind}.sourceHead must be a 40-character lowercase git commit")
    if payload.get("sourceDirty") is not False:
        _fail(f"{kind}.sourceDirty must be false")
    if not isinstance(payload.get("sourceDiffHash"), str) or not SHA256.fullmatch(payload["sourceDiffHash"]):
        _fail(f"{kind}.sourceDiffHash must be a sha256 fingerprint")
    if expected_source_head is not None and source_head != expected_source_head:
        _fail(f"{kind}.sourceHead does not match expected HEAD: {source_head} != {expected_source_head}")


def _resolve_evidence_path(value: str, evidence_root: Path) -> Path:
    path = Path(value)
    return path if path.is_absolute() else evidence_root / path


def _xml_summary(result_dir: Path, location: str, expected_files: int) -> dict[str, int]:
    result_files = sorted(result_dir.glob("TEST-*.xml"))
    if len(result_files) != expected_files:
        _fail(f"{location}.evidence.testResultFiles does not match result directory")
    executed = skipped = failed = 0
    for result_file in result_files:
        try:
            document = ET.parse(result_file)
        except ET.ParseError as error:
            _fail(f"{location}.evidence contains malformed test XML {result_file}: {error}")
        for testcase in document.iter("testcase"):
            executed += 1
            if testcase.find("skipped") is not None:
                skipped += 1
            if testcase.find("failure") is not None or testcase.find("error") is not None:
                failed += 1
    return {"executed": executed, "skipped": skipped, "failed": failed}


def _validate_log_summary(log_path: Path, summary: dict[str, int], location: str) -> None:
    matches = [match for match in (TEST_SUMMARY_LINE.search(line.strip()) for line in log_path.read_text(encoding="utf-8").splitlines()) if match]
    if not matches:
        _fail(f"{location}.evidence.log lacks a SUCCESS test summary")
    match = matches[-1]
    logged = {
        "executed": int(match.group("executed")),
        "skipped": int(match.group("skipped") or 0),
        "failed": 0,
    }
    if logged != summary:
        _fail(f"{location}.evidence log/XML test summaries disagree: {logged} != {summary}")


def _validate_execution_evidence(row: dict[str, Any], location: str, evidence_root: Path) -> None:
    evidence = row["evidence"]
    if not _nonblank(evidence.get("command")):
        _fail(f"{location}.evidence.command must be nonblank")
    if evidence.get("database") != row.get("database"):
        _fail(f"{location}.evidence.database must match row.database")
    if not row.get("applicable"):
        if row.get("status") != "N/A":
            _fail(f"{location} non-applicable row must have status N/A")
        expected_empty = {
            "log": "",
            "testResultDir": "",
            "testResultFiles": 0,
            "exitCode": None,
            "testTaskExecuted": False,
            "cacheReuse": False,
            "testSummary": None,
        }
        for field, expected in expected_empty.items():
            if evidence.get(field) != expected:
                _fail(f"{location}.evidence.{field} must be {expected!r} for non-applicable rows")
        return

    try:
        command = shlex.split(evidence["command"])
    except ValueError as error:
        _fail(f"{location}.evidence.command is not shell-parseable: {error}")
    task = TASK_BY_ADAPTER.get(row.get("adapter"))
    if task is None or task not in command:
        _fail(f"{location}.evidence.command must execute the expected test task")
    missing_flags = [flag for flag in REQUIRED_GRADLE_FLAGS if flag not in command]
    if missing_flags:
        _fail(f"{location}.evidence.command is missing required flags: {missing_flags}")
    if not _nonblank(evidence.get("log")):
        _fail(f"{location}.evidence.log must be nonblank for applicable rows")
    log_path = _resolve_evidence_path(evidence["log"], evidence_root)
    if not log_path.is_file():
        _fail(f"{location}.evidence.log does not exist: {log_path}")
    if not _nonblank(evidence.get("testResultDir")):
        _fail(f"{location}.evidence.testResultDir must be nonblank for applicable rows")
    result_dir = _resolve_evidence_path(evidence["testResultDir"], evidence_root)
    if not result_dir.is_dir():
        _fail(f"{location}.evidence.testResultDir does not exist: {result_dir}")
    if not _strict_int(evidence.get("testResultFiles")) or evidence["testResultFiles"] < 0:
        _fail(f"{location}.evidence.testResultFiles must be a non-negative integer")
    if not _strict_int(evidence.get("exitCode")):
        _fail(f"{location}.evidence.exitCode must be an integer for applicable rows")
    if not isinstance(evidence.get("timedOut"), bool):
        _fail(f"{location}.evidence.timedOut must be boolean")
    if not _strict_int(evidence.get("timeoutSeconds")) or evidence["timeoutSeconds"] <= 0:
        _fail(f"{location}.evidence.timeoutSeconds must be a positive integer")
    if not isinstance(evidence.get("testTaskExecuted"), bool):
        _fail(f"{location}.evidence.testTaskExecuted must be boolean")
    if not isinstance(evidence.get("cacheReuse"), bool):
        _fail(f"{location}.evidence.cacheReuse must be boolean")
    summary = evidence.get("testSummary")
    if summary is not None:
        if not isinstance(summary, dict):
            _fail(f"{location}.evidence.testSummary must be an object or null")
        for field in ("executed", "skipped", "failed"):
            if not _strict_int(summary.get(field)) or summary[field] < 0:
                _fail(f"{location}.evidence.testSummary.{field} must be a non-negative integer")
        if summary["skipped"] > summary["executed"] or summary["failed"] > summary["executed"]:
            _fail(f"{location}.evidence.testSummary counts are inconsistent")
    if evidence.get("timedOut") and row.get("status") != "TIMED_OUT":
        _fail(f"{location} timedOut evidence must have TIMED_OUT status")
    if row.get("status") == "TIMED_OUT" and not evidence.get("timedOut"):
        _fail(f"{location} TIMED_OUT status must have timedOut evidence")
    if row.get("status") == "PASS":
        if evidence.get("exitCode") != 0 or not evidence.get("testTaskExecuted") or evidence.get("cacheReuse"):
            _fail(f"{location} PASS row lacks fresh executed test evidence")
        if summary is None or summary["executed"] <= 0 or summary["failed"] != 0:
            _fail(f"{location} PASS row must include positive testSummary with failed=0")
        if evidence["testResultFiles"] <= 0:
            _fail(f"{location} PASS row must include test result XML files")
        actual_summary = _xml_summary(result_dir, location, evidence["testResultFiles"])
        if actual_summary != summary:
            _fail(f"{location}.evidence.testSummary does not match test XML: {summary} != {actual_summary}")
        _validate_log_summary(log_path, summary, location)


def _validate_matrix(path: Path, expected_source_head: str | None, evidence_root: Path) -> None:
    payload = _read_json(path)
    if not isinstance(payload, dict):
        _fail("matrix must be a JSON object")
    if payload.get("schema") != 1 or payload.get("version") != 1:
        _fail("matrix schema/version must both be 1")
    _validate_source(payload, "matrix", expected_source_head)
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
        expected_required, expected_applicable = EXPECTED_ROWS[identity]
        if (row["required"], row["applicable"]) != (expected_required, expected_applicable):
            _fail(f"{location}.required/applicable does not match the supported matrix: {identity}")
        _validate_execution_evidence(row, location, evidence_root)
        if row["required"] and row["applicable"] and status != "PASS":
            _fail(f"required applicable row is not PASS: {adapter}/{database}={status}")
        if not row["applicable"] and status == "PASS":
            _fail(f"non-applicable row cannot be PASS: {adapter}/{database}")

    missing = EXPECTED_ROWS.keys() - seen
    if missing:
        _fail(f"matrix is missing rows: {sorted(missing)}")


def _canonical_metrics(metrics: list[Any]) -> bytes:
    return json.dumps(metrics, ensure_ascii=True, sort_keys=True, separators=(",", ":")).encode("utf-8")


def _validate_metrics(path: Path, expected_source_head: str | None) -> None:
    payload = _read_json(path)
    if not isinstance(payload, dict):
        _fail("metrics must be a JSON object")
    if payload.get("schema") != 1 or payload.get("version") != 1:
        _fail("metrics schema/version must both be 1")
    _validate_source(payload, "metrics", expected_source_head)
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


def validate(
    matrix: Path,
    metrics: Path,
    expected_source_head: str | None = None,
    evidence_root: Path | None = None,
) -> None:
    _validate_matrix(matrix, expected_source_head, evidence_root or Path.cwd())
    _validate_metrics(metrics, expected_source_head)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--matrix", type=Path, default=Path("build/verification/write-behind-db-matrix.json"))
    parser.add_argument("--metrics", type=Path, default=Path("build/verification/write-behind-metrics.json"))
    parser.add_argument("--expected-head")
    parser.add_argument("--root", type=Path, default=Path.cwd())
    args = parser.parse_args()
    validate(args.matrix, args.metrics, expected_source_head=args.expected_head, evidence_root=args.root.resolve())
    print(f"write-behind-evidence: PASS matrix={args.matrix} metrics={args.metrics}")


if __name__ == "__main__":
    main()
