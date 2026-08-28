#!/usr/bin/env python3
"""Run the serial Issue #732 database matrix and emit fail-closed receipts."""

from __future__ import annotations

import argparse
import fcntl
import hashlib
import json
import os
import re
import signal
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


sys.dont_write_bytecode = True

from validate_write_behind_evidence import validate


DATABASES = ("H2", "POSTGRESQL", "MYSQL_V8")
GRADLE_FLAGS = (
    "--no-configuration-cache",
    "--no-daemon",
    "--console=plain",
    "--no-parallel",
    "--max-workers=1",
    "--rerun-tasks",
    "--no-build-cache",
)
GRADLE_TIMEOUT_SECONDS = 900
TEST_TASK_OUTCOME = re.compile(r"^> Task (?P<task>:[^ ]+:test)(?:\s+(?P<outcome>[A-Z-]+))?$")
TEST_SUMMARY = re.compile(
    r"SUCCESS: Executed (?P<executed>\d+) tests? in .*?(?:\((?P<skipped>\d+) skipped\))?$",
    re.MULTILINE,
)
CONTAINER_UNAVAILABLE = re.compile(
    r"(?:Could not find a valid Docker environment|"
    r"Cannot connect to the Docker daemon|Docker daemon is not running|"
    r"docker\.sock.*(?:No such file|operation not supported|connection refused)|"
    r"DockerClientProviderStrategy.*failed|"
    r"Ryuk.*(?:connection refused|not available))",
    re.IGNORECASE,
)


def timestamp() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def source_head(root: Path) -> str:
    return subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip()


def source_state(root: Path) -> tuple[str, bool, str]:
    head = source_head(root)
    status = subprocess.check_output(
        ["git", "-C", str(root), "status", "--porcelain", "--untracked-files=all"],
        text=True,
    )
    diff = subprocess.check_output(["git", "-C", str(root), "diff", "--binary", "HEAD"], text=False)
    fingerprint = hashlib.sha256(status.encode("utf-8") + diff).hexdigest()
    return head, bool(status.strip()), "sha256:" + fingerprint


def module_dir(root: Path, task: str) -> Path:
    module_paths = {
        ":bluetape4k-exposed-cache:test": ("exposed", "cache"),
        ":bluetape4k-exposed-jdbc-caffeine:test": ("exposed", "jdbc-caffeine"),
        ":bluetape4k-exposed-r2dbc-caffeine:test": ("exposed", "r2dbc-caffeine"),
    }
    try:
        return root.joinpath(*module_paths[task])
    except KeyError as error:
        raise ValueError(f"unsupported test task: {task}") from error


def _log_summary(log_text: str) -> dict[str, int] | None:
    matches = list(TEST_SUMMARY.finditer(log_text))
    if not matches:
        return None
    match = matches[-1]
    return {
        "executed": int(match.group("executed")),
        "skipped": int(match.group("skipped") or 0),
        "failed": 0,
    }


def test_summary(result_dir: Path, log_text: str) -> tuple[dict[str, int] | None, int, str | None]:
    executed = skipped = failed = 0
    result_files = sorted(result_dir.glob("TEST-*.xml"))
    for result_file in result_files:
        try:
            document = ET.parse(result_file)
        except ET.ParseError as error:
            return None, len(result_files), f"malformed test XML {result_file}: {error}"
        for testcase in document.iter("testcase"):
            executed += 1
            if testcase.find("skipped") is not None:
                skipped += 1
            if testcase.find("failure") is not None or testcase.find("error") is not None:
                failed += 1
    if executed > 0:
        summary = {"executed": executed, "skipped": skipped, "failed": failed}
        logged = _log_summary(log_text)
        if logged is None:
            return None, len(result_files), "test log lacks a SUCCESS summary"
        if logged != summary:
            return None, len(result_files), f"log/XML test summaries disagree: {logged} != {summary}"
        return summary, len(result_files), None
    return None, len(result_files), "no testcases found in execution-specific XML results" if result_files else None


def test_task_executed(task: str, log_text: str) -> tuple[bool, bool]:
    executed = False
    cache_reuse = False
    for line in log_text.splitlines():
        match = TEST_TASK_OUTCOME.match(line.strip())
        if not match or match.group("task") != task:
            continue
        outcome = match.group("outcome")
        if outcome in {"UP-TO-DATE", "FROM-CACHE", "SKIPPED", "NO-SOURCE"}:
            cache_reuse = True
        else:
            executed = True
    return executed, cache_reuse


def _result_slug(task: str, database: str | None) -> str:
    task_name = {
        ":bluetape4k-exposed-cache:test": "coordinator",
        ":bluetape4k-exposed-jdbc-caffeine:test": "jdbc-caffeine",
        ":bluetape4k-exposed-r2dbc-caffeine:test": "r2dbc-caffeine",
    }[task]
    return f"{task_name}-{database or 'NONE'}".lower().replace("_", "-")


def _prepare_result_dir(root: Path, task: str, result_dir: Path) -> None:
    module_result_dir = module_dir(root, task) / "build" / "test-results" / "test"
    if module_result_dir.is_symlink():
        raise SystemExit(f"write-behind-evidence: refusing symlinked test result directory: {module_result_dir}")
    if module_result_dir.exists():
        shutil.rmtree(module_result_dir)
    module_result_dir.mkdir(parents=True, exist_ok=True)
    if result_dir.exists():
        if result_dir.is_symlink():
            raise SystemExit(f"write-behind-evidence: refusing symlinked receipt result directory: {result_dir}")
        shutil.rmtree(result_dir)
    result_dir.mkdir(parents=True, exist_ok=True)


def _capture_result_files(root: Path, task: str, result_dir: Path) -> int:
    module_result_dir = module_dir(root, task) / "build" / "test-results" / "test"
    files = sorted(module_result_dir.glob("TEST-*.xml"))
    for source in files:
        shutil.copy2(source, result_dir / source.name)
    return len(files)


def run_gradle(root: Path, task: str, database: str | None, log_path: Path, result_dir: Path) -> tuple[str, dict[str, Any]]:
    started = timestamp()
    environment = os.environ.copy()
    if database is not None:
        environment["EXPOSED_TEST_DB"] = database
    command = ["./gradlew", task, *GRADLE_FLAGS]
    log_path.parent.mkdir(parents=True, exist_ok=True)
    _prepare_result_dir(root, task, result_dir)
    timed_out = False
    with log_path.open("w", encoding="utf-8") as log:
        log.write("$ " + " ".join(command) + "\n")
        if database is not None:
            log.write(f"EXPOSED_TEST_DB={database}\n")
        log.flush()
        process = subprocess.Popen(
            command,
            cwd=root,
            env=environment,
            stdout=log,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )
        try:
            return_code = process.wait(timeout=GRADLE_TIMEOUT_SECONDS)
        except subprocess.TimeoutExpired:
            timed_out = True
            log.write(f"\nTIMED_OUT after {GRADLE_TIMEOUT_SECONDS}s\n")
            try:
                os.killpg(process.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            try:
                return_code = process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                return_code = process.wait()
    finished = timestamp()
    log_text = log_path.read_text(encoding="utf-8")
    task_executed, cache_reuse = test_task_executed(task, log_text)
    result_file_count = _capture_result_files(root, task, result_dir)
    summary, result_file_count, summary_error = test_summary(result_dir, log_text)
    if summary_error is not None:
        status = "FAIL"
    elif timed_out:
        status = "TIMED_OUT"
    elif return_code != 0:
        status = "PENDING" if CONTAINER_UNAVAILABLE.search(log_text) else "FAIL"
    elif not task_executed or cache_reuse or summary is None or summary["executed"] <= 0 or summary["failed"] != 0:
        status = "FAIL"
    else:
        status = "PASS"
    evidence: dict[str, Any] = {
        "command": " ".join(command),
        "database": database or "NONE",
        "exitCode": return_code,
        "log": str(log_path.relative_to(root)).replace(os.sep, "/"),
        "startedAt": started,
        "finishedAt": finished,
        "timedOut": timed_out,
        "timeoutSeconds": GRADLE_TIMEOUT_SECONDS,
        "testTaskExecuted": task_executed,
        "cacheReuse": cache_reuse,
        "testResultDir": str(result_dir.relative_to(root)).replace(os.sep, "/"),
        "testResultFiles": result_file_count,
        "testSummary": summary,
    }
    if summary_error is not None:
        evidence["testSummaryError"] = summary_error
    return status, evidence


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


def write_receipts(root: Path, rows: list[dict[str, Any]], head: str, dirty: bool, diff_hash: str) -> tuple[Path, Path]:
    verification_dir = root / "build" / "verification"
    verification_dir.mkdir(parents=True, exist_ok=True)
    matrix = verification_dir / "write-behind-db-matrix.json"
    metrics = verification_dir / "write-behind-metrics.json"
    matrix.write_text(
        json.dumps(
            {"schema": 1, "version": 1, "sourceHead": head, "sourceDirty": dirty, "sourceDiffHash": diff_hash, "rows": rows},
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    metric_list: list[object] = []
    canonical = json.dumps(metric_list, ensure_ascii=True, sort_keys=True, separators=(",", ":")).encode("utf-8")
    metrics.write_text(
        json.dumps(
            {
                "schema": 1,
                "version": 1,
                "sourceHead": head,
                "sourceDirty": dirty,
                "sourceDiffHash": diff_hash,
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


@contextmanager
def runner_lock(root: Path):
    lock_path = root / "build" / "verification" / "write-behind-matrix.lock"
    lock_path.parent.mkdir(parents=True, exist_ok=True)
    with lock_path.open("a+", encoding="utf-8") as lock:
        try:
            fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as error:
            raise SystemExit("write-behind-evidence: another matrix runner is active") from error
        try:
            yield
        finally:
            fcntl.flock(lock.fileno(), fcntl.LOCK_UN)


def _run_matrix(root: Path) -> None:
    head, dirty, diff_hash = source_state(root)
    if dirty:
        status = subprocess.check_output(
            ["git", "-C", str(root), "status", "--porcelain", "--untracked-files=all"],
            text=True,
        ).strip()
        raise SystemExit(
            "write-behind-evidence: exact-head verification requires a clean worktree\n"
            f"detected changes:\n{status}"
        )
    rows: list[dict[str, Any]] = []

    coordinator_task = ":bluetape4k-exposed-cache:test"
    status, evidence = run_gradle(
        root,
        coordinator_task,
        None,
        root / "build/verification/logs/coordinator.log",
        root / "build/verification/results" / f"{_result_slug(coordinator_task, None)}-{os.getpid()}-{time.time_ns()}",
    )
    rows.append(make_row("coordinator", "NONE", True, True, status, "coordinator unit and conformance tests", evidence))

    for database in DATABASES:
        jdbc_task = ":bluetape4k-exposed-jdbc-caffeine:test"
        status, evidence = run_gradle(
            root,
            jdbc_task,
            database,
            root / f"build/verification/logs/jdbc-caffeine-{database}.log",
            root / "build/verification/results" / f"{_result_slug(jdbc_task, database)}-{os.getpid()}-{time.time_ns()}",
        )
        rows.append(make_row("jdbc-caffeine", database, True, True, status, "JDBC and suspended JDBC Caffeine tests", evidence))
        rows.append(make_row("suspended-jdbc-caffeine", database, True, True, status, "Suspended JDBC tests are included in the jdbc-caffeine task", evidence))

    r2dbc_task = ":bluetape4k-exposed-r2dbc-caffeine:test"
    status, evidence = run_gradle(
        root,
        r2dbc_task,
        "H2",
        root / "build/verification/logs/r2dbc-caffeine-H2.log",
        root / "build/verification/results" / f"{_result_slug(r2dbc_task, 'H2')}-{os.getpid()}-{time.time_ns()}",
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
            "exitCode": None,
            "timedOut": False,
            "timeoutSeconds": GRADLE_TIMEOUT_SECONDS,
            "testTaskExecuted": False,
            "cacheReuse": False,
            "testResultDir": "",
            "testResultFiles": 0,
            "testSummary": None,
        }
        rows.append(make_row("r2dbc-caffeine", database, False, False, "N/A", "No supported R2DBC Caffeine fixture is registered", evidence))

    matrix, metrics = write_receipts(root, rows, head, dirty, diff_hash)
    validate(matrix, metrics, expected_source_head=head, evidence_root=root)
    if any(row["required"] and row["applicable"] and row["status"] != "PASS" for row in rows):
        raise SystemExit("write-behind-evidence: required matrix row failed")
    print(f"write-behind-evidence: PASS matrix={matrix} metrics={metrics}")


def run(root: Path) -> None:
    with runner_lock(root):
        _run_matrix(root)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()
    run(args.root.resolve())


if __name__ == "__main__":
    main()
