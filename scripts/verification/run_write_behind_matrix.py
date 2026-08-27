#!/usr/bin/env python3
"""Run the serial Issue #732 database matrix and emit fail-closed receipts."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from validate_write_behind_evidence import validate


DATABASES = ("H2", "POSTGRESQL", "MYSQL_V8")
GRADLE_FLAGS = (
    "--no-configuration-cache",
    "--no-daemon",
    "--console=plain",
    "--no-parallel",
    "--max-workers=1",
)


def timestamp() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def source_head(root: Path) -> str:
    return subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip()


def run_gradle(root: Path, task: str, database: str | None, log_path: Path) -> tuple[str, dict[str, Any]]:
    started = timestamp()
    environment = os.environ.copy()
    if database is not None:
        environment["EXPOSED_TEST_DB"] = database
    command = ["./gradlew", task, *GRADLE_FLAGS]
    log_path.parent.mkdir(parents=True, exist_ok=True)
    with log_path.open("w", encoding="utf-8") as log:
        log.write("$ " + " ".join(command) + "\n")
        if database is not None:
            log.write(f"EXPOSED_TEST_DB={database}\n")
        process = subprocess.run(command, cwd=root, env=environment, stdout=log, stderr=subprocess.STDOUT, check=False)
    finished = timestamp()
    status = "PASS" if process.returncode == 0 else "FAIL"
    return status, {
        "command": " ".join(command),
        "database": database or "NONE",
        "exitCode": process.returncode,
        "log": str(log_path.relative_to(root)).replace(os.sep, "/"),
        "startedAt": started,
        "finishedAt": finished,
    }


def make_row(
    adapter: str,
    database: str,
    required: bool,
    applicable: bool,
    status: str,
    reason: str,
    evidence: dict[str, Any],
) -> dict[str, Any]:
    return {
        "adapter": adapter,
        "database": database,
        "required": required,
        "applicable": applicable,
        "status": status,
        "reason": reason,
        "startedAt": evidence["startedAt"],
        "finishedAt": evidence["finishedAt"],
        "evidence": evidence,
    }


def write_receipts(root: Path, rows: list[dict[str, Any]], head: str) -> tuple[Path, Path]:
    verification_dir = root / "build" / "verification"
    verification_dir.mkdir(parents=True, exist_ok=True)
    matrix = verification_dir / "write-behind-db-matrix.json"
    metrics = verification_dir / "write-behind-metrics.json"
    matrix.write_text(json.dumps({"schema": 1, "version": 1, "sourceHead": head, "rows": rows}, indent=2) + "\n", encoding="utf-8")
    metric_list: list[object] = []
    import hashlib

    canonical = json.dumps(metric_list, ensure_ascii=True, sort_keys=True, separators=(",", ":")).encode("utf-8")
    metrics.write_text(
        json.dumps(
            {
                "schema": 1,
                "version": 1,
                "sourceHead": head,
                "metrics": metric_list,
                "baselineChecksum": "sha256:" + hashlib.sha256(canonical).hexdigest(),
                "note": "No new coordinator meter family is introduced; adapter observability remains the stable existing inventory.",
            },
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    return matrix, metrics


def run(root: Path) -> None:
    head = source_head(root)
    rows: list[dict[str, Any]] = []

    status, evidence = run_gradle(root, ":bluetape4k-exposed-cache:test", None, root / "build/verification/logs/coordinator.log")
    rows.append(make_row("coordinator", "NONE", True, True, status, "coordinator unit and conformance tests", evidence))

    for database in DATABASES:
        status, evidence = run_gradle(
            root,
            ":bluetape4k-exposed-jdbc-caffeine:test",
            database,
            root / f"build/verification/logs/jdbc-caffeine-{database}.log",
        )
        rows.append(make_row("jdbc-caffeine", database, True, True, status, "JDBC and suspended JDBC Caffeine tests", evidence))
        rows.append(make_row("suspended-jdbc-caffeine", database, True, True, status, "Suspended JDBC tests are included in the jdbc-caffeine task", evidence))

    status, evidence = run_gradle(
        root,
        ":bluetape4k-exposed-r2dbc-caffeine:test",
        "H2",
        root / "build/verification/logs/r2dbc-caffeine-H2.log",
    )
    rows.append(make_row("r2dbc-caffeine", "H2", True, True, status, "R2DBC H2 tests", evidence))
    for database in ("POSTGRESQL", "MYSQL_V8"):
        now = timestamp()
        evidence = {
            "command": "not run: no R2DBC Caffeine fixture for this database",
            "database": database,
            "log": "",
            "startedAt": now,
            "finishedAt": now,
        }
        rows.append(make_row("r2dbc-caffeine", database, False, False, "N/A", "No supported R2DBC Caffeine fixture is registered", evidence))

    matrix, metrics = write_receipts(root, rows, head)
    validate(matrix, metrics)
    if any(row["required"] and row["applicable"] and row["status"] != "PASS" for row in rows):
        raise SystemExit("write-behind-evidence: required matrix row failed")
    print(f"write-behind-evidence: PASS matrix={matrix} metrics={metrics}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()
    run(args.root.resolve())


if __name__ == "__main__":
    main()
