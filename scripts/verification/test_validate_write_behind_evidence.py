#!/usr/bin/env python3

from __future__ import annotations

import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from validate_write_behind_evidence import validate


TIMESTAMP = "2026-08-28T00:00:00Z"


TASK_BY_ADAPTER = {
    "coordinator": ":bluetape4k-exposed-cache:test",
    "jdbc-caffeine": ":bluetape4k-exposed-jdbc-caffeine:test",
    "suspended-jdbc-caffeine": ":bluetape4k-exposed-jdbc-caffeine:test",
    "r2dbc-caffeine": ":bluetape4k-exposed-r2dbc-caffeine:test",
}
GRADLE_FLAGS = (
    "--no-configuration-cache",
    "--no-daemon",
    "--console=plain",
    "--no-parallel",
    "--max-workers=1",
    "--rerun-tasks",
    "--no-build-cache",
)


def _row(adapter: str, database: str, *, applicable: bool = True, status: str = "PASS") -> dict[str, object]:
    command = "./gradlew " + TASK_BY_ADAPTER[adapter] + " " + " ".join(GRADLE_FLAGS)
    return {
        "adapter": adapter,
        "database": database,
        "required": applicable,
        "applicable": applicable,
        "status": status,
        "reason": "fixture evidence",
        "startedAt": TIMESTAMP,
        "finishedAt": TIMESTAMP,
        "evidence": {
            "command": command if applicable else "not run: no fixture",
            "database": database,
            "log": "fixture.log" if applicable else "",
            "testResultDir": "results" if applicable else "",
            "testResultFiles": 1 if applicable else 0,
            "exitCode": 0 if applicable else None,
            "timedOut": False,
            "timeoutSeconds": 900,
            "testTaskExecuted": applicable,
            "cacheReuse": False,
            "testSummary": {"executed": 1, "skipped": 0, "failed": 0} if applicable else None,
        },
    }


def _write_valid(root: Path) -> tuple[Path, Path]:
    (root / "fixture.log").write_text("SUCCESS: Executed 1 test in 0.0s\n", encoding="utf-8")
    (root / "results").mkdir()
    (root / "results" / "TEST-fixture.xml").write_text(
        '<testsuite><testcase classname="fixture" name="passes" /></testsuite>\n',
        encoding="utf-8",
    )
    rows = [_row("coordinator", "NONE")]
    rows.extend(_row(adapter, database) for adapter in ("jdbc-caffeine", "suspended-jdbc-caffeine") for database in ("H2", "POSTGRESQL", "MYSQL_V8"))
    rows.append(_row("r2dbc-caffeine", "H2"))
    rows.extend(_row("r2dbc-caffeine", database, applicable=False, status="N/A") for database in ("POSTGRESQL", "MYSQL_V8"))
    matrix = root / "matrix.json"
    matrix.write_text(json.dumps({"schema": 1, "version": 1, "sourceHead": "0" * 40, "sourceDirty": False, "sourceDiffHash": "sha256:" + "0" * 64, "rows": rows}), encoding="utf-8")
    metrics = root / "metrics.json"
    metric_list: list[object] = []
    checksum = "sha256:" + hashlib.sha256(b"[]").hexdigest()
    metrics.write_text(json.dumps({"schema": 1, "version": 1, "sourceHead": "0" * 40, "sourceDirty": False, "sourceDiffHash": "sha256:" + "0" * 64, "metrics": metric_list, "baselineChecksum": checksum, "note": "No new coordinator meter family."}), encoding="utf-8")
    return matrix, metrics


class WriteBehindEvidenceTest(unittest.TestCase):
    def test_accepts_complete_matrix_and_empty_stable_metric_inventory(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            validate(*_write_valid(root), evidence_root=root)

    def test_rejects_missing_required_row(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            matrix, metrics = _write_valid(root)
            payload = json.loads(matrix.read_text(encoding="utf-8"))
            payload["rows"].pop()
            matrix.write_text(json.dumps(payload), encoding="utf-8")
            with self.assertRaises(SystemExit):
                validate(matrix, metrics, evidence_root=root)

    def test_rejects_non_pass_required_row(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            matrix, metrics = _write_valid(root)
            payload = json.loads(matrix.read_text(encoding="utf-8"))
            payload["rows"][0]["status"] = "PENDING"
            matrix.write_text(json.dumps(payload), encoding="utf-8")
            with self.assertRaises(SystemExit):
                validate(matrix, metrics, evidence_root=root)

    def test_rejects_queue_depth_metric_label(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            matrix, metrics = _write_valid(root)
            payload = json.loads(metrics.read_text(encoding="utf-8"))
            payload["metrics"] = [{"name": "cache.health", "type": "gauge", "tags": [{"key": "queueDepth", "value": "jdbc"}]}]
            payload["baselineChecksum"] = "sha256:" + hashlib.sha256(json.dumps(payload["metrics"], sort_keys=True, separators=(",", ":")).encode()).hexdigest()
            metrics.write_text(json.dumps(payload), encoding="utf-8")
            with self.assertRaises(SystemExit):
                validate(matrix, metrics, evidence_root=root)

    def test_rejects_required_row_downgrade(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            matrix, metrics = _write_valid(root)
            payload = json.loads(matrix.read_text(encoding="utf-8"))
            payload["rows"][0]["required"] = False
            matrix.write_text(json.dumps(payload), encoding="utf-8")
            with self.assertRaises(SystemExit):
                validate(matrix, metrics, evidence_root=root)

    def test_rejects_pass_row_with_failed_testcase(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            matrix, metrics = _write_valid(root)
            payload = json.loads(matrix.read_text(encoding="utf-8"))
            payload["rows"][0]["evidence"]["testSummary"]["failed"] = 1
            matrix.write_text(json.dumps(payload), encoding="utf-8")
            with self.assertRaises(SystemExit):
                validate(matrix, metrics, evidence_root=root)

    def test_rejects_boolean_as_exit_code(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            matrix, metrics = _write_valid(root)
            payload = json.loads(matrix.read_text(encoding="utf-8"))
            payload["rows"][0]["evidence"]["exitCode"] = True
            matrix.write_text(json.dumps(payload), encoding="utf-8")
            with self.assertRaises(SystemExit):
                validate(matrix, metrics, evidence_root=root)

    def test_rejects_missing_log(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            matrix, metrics = _write_valid(root)
            (root / "fixture.log").unlink()
            with self.assertRaises(SystemExit):
                validate(matrix, metrics, evidence_root=root)

    def test_rejects_missing_required_gradle_flag(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            matrix, metrics = _write_valid(root)
            payload = json.loads(matrix.read_text(encoding="utf-8"))
            payload["rows"][0]["evidence"]["command"] = payload["rows"][0]["evidence"]["command"].replace("--no-build-cache", "")
            matrix.write_text(json.dumps(payload), encoding="utf-8")
            with self.assertRaises(SystemExit):
                validate(matrix, metrics, evidence_root=root)


if __name__ == "__main__":
    unittest.main()
