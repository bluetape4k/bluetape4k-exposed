#!/usr/bin/env python3

from __future__ import annotations

import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from validate_write_behind_evidence import validate


TIMESTAMP = "2026-08-28T00:00:00Z"


def _row(adapter: str, database: str, *, applicable: bool = True, status: str = "PASS") -> dict[str, object]:
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
            "command": "fixture",
            "database": database,
            "log": "build/verification/fixture.log" if applicable else "",
            "exitCode": 0 if applicable else None,
            "testTaskExecuted": applicable,
            "cacheReuse": False,
            "testSummary": {"executed": 1, "skipped": 0, "failed": 0} if applicable else None,
        },
    }


def _write_valid(root: Path) -> tuple[Path, Path]:
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
            validate(*_write_valid(Path(directory)))

    def test_rejects_missing_required_row(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            matrix, metrics = _write_valid(root)
            payload = json.loads(matrix.read_text(encoding="utf-8"))
            payload["rows"].pop()
            matrix.write_text(json.dumps(payload), encoding="utf-8")
            with self.assertRaises(SystemExit):
                validate(matrix, metrics)

    def test_rejects_non_pass_required_row(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            matrix, metrics = _write_valid(root)
            payload = json.loads(matrix.read_text(encoding="utf-8"))
            payload["rows"][0]["status"] = "PENDING"
            matrix.write_text(json.dumps(payload), encoding="utf-8")
            with self.assertRaises(SystemExit):
                validate(matrix, metrics)

    def test_rejects_queue_depth_metric_label(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            matrix, metrics = _write_valid(root)
            payload = json.loads(metrics.read_text(encoding="utf-8"))
            payload["metrics"] = [{"name": "cache.health", "type": "gauge", "tags": [{"key": "queueDepth", "value": "jdbc"}]}]
            payload["baselineChecksum"] = "sha256:" + hashlib.sha256(json.dumps(payload["metrics"], sort_keys=True, separators=(",", ":")).encode()).hexdigest()
            metrics.write_text(json.dumps(payload), encoding="utf-8")
            with self.assertRaises(SystemExit):
                validate(matrix, metrics)


if __name__ == "__main__":
    unittest.main()
