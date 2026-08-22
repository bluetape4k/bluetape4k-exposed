#!/usr/bin/env python3
"""Capture one immutable JMH report and append sanitized provenance metadata."""

from __future__ import annotations

import argparse
import fcntl
import hashlib
import json
import os
import platform
import re
import shutil
import subprocess
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from typing import NoReturn


EXPECTED_CARDINALITY = 12
FORBIDDEN = re.compile(r"jdbc:|(?:postgres(?:ql)?|mysql)://|password|passwd|secret|docker_host|@exposed2025", re.IGNORECASE)
PLACEHOLDERS = {"", "unknown", "central-catalog", "n/a", "none"}
EXPECTED_CATALOG_REF = "91f9ea9336b5ea991f5675323a1cf25ccfd6f5ed"
DRIVER_COORDINATES = {
    "POSTGRESQL": "org.postgresql:postgresql",
    "MYSQL_V8": "com.mysql:mysql-connector-j",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report-dir", type=Path, required=True)
    parser.add_argument("--destination", type=Path, required=True)
    parser.add_argument("--metadata", type=Path, required=True)
    parser.add_argument("--driver", choices=("POSTGRESQL", "MYSQL_V8"), required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--git-sha", required=True)
    parser.add_argument("--driver-version", default="unknown")
    parser.add_argument("--image", default="unknown")
    parser.add_argument("--image-digest", default="unknown")
    return parser.parse_args()


def fail(message: str) -> NoReturn:
    raise SystemExit(f"capture_jmh_run: {message}")


def regular_json_files(directory: Path) -> list[Path]:
    if not directory.is_dir() or directory.is_symlink():
        fail(f"report directory is not a regular directory: {directory}")
    files = [
        path
        for path in directory.glob("*.json")
        if path.is_file() and not path.is_symlink()
    ]
    if len(files) != 1:
        fail(f"expected exactly one regular top-level JSON report in {directory}, found {len(files)}")
    return files


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def java_runtime() -> str:
    try:
        result = subprocess.run(
            ["java", "-version"],
            check=False,
            capture_output=True,
            text=True,
            timeout=5,
        )
    except (OSError, subprocess.SubprocessError):
        return "unknown"
    first_line = (result.stderr or result.stdout).splitlines()
    return first_line[0][:160] if first_line else "unknown"


def checked_command(command: list[str], cwd: Path, label: str, timeout: int = 120) -> str:
    try:
        result = subprocess.run(
            command,
            cwd=cwd,
            check=False,
            capture_output=True,
            text=True,
            timeout=timeout,
        )
    except (OSError, subprocess.SubprocessError) as exc:
        fail(f"{label} could not be observed: {exc}")
    if result.returncode != 0:
        detail = (result.stderr or result.stdout).strip().splitlines()
        suffix = detail[-1] if detail else f"exit {result.returncode}"
        fail(f"{label} observation failed: {suffix}")
    return (result.stdout or result.stderr).strip()


def repository_root() -> Path:
    return Path(__file__).resolve().parents[3]


def resolved_driver_version(gradle_output: str, driver: str) -> str:
    coordinate = DRIVER_COORDINATES[driver]
    matches = re.findall(rf"{re.escape(coordinate)}:([0-9][^\s()]*)", gradle_output)
    if not matches:
        fail(f"resolved dependency output does not contain {coordinate}")
    return matches[-1].rstrip("*")


def observed_catalog_ref(root: Path) -> str:
    """Observe the immutable catalog ref used by the capture's Gradle invocation."""
    for variable in (
        "BLUETAPE4K_DEPENDENCIES_CATALOG_REF",
        "ORG_GRADLE_PROJECT_bluetape4kDependenciesCatalogRef",
    ):
        override = os.environ.get(variable)
        if override and override != EXPECTED_CATALOG_REF:
            fail(f"{variable} override is not the approved immutable catalog ref")
    gradle_opts = os.environ.get("GRADLE_OPTS", "")
    override_match = re.search(r"-Pbluetape4kDependenciesCatalogRef=([^\s]+)", gradle_opts)
    if override_match and override_match.group(1) != EXPECTED_CATALOG_REF:
        fail("GRADLE_OPTS overrides the approved immutable catalog ref")
    settings = root / "settings.gradle.kts"
    if not settings.is_file() or settings.is_symlink():
        fail(f"settings.gradle.kts is not a regular file: {settings}")
    text = settings.read_text(encoding="utf-8")
    if EXPECTED_CATALOG_REF not in re.findall(r'\.orElse\("([0-9a-f]{40,64})"\)', text):
        fail("settings.gradle.kts does not contain the approved immutable catalog ref")
    return EXPECTED_CATALOG_REF


def observed_provenance(
    driver: str,
    driver_version: str,
    image: str,
    image_digest: str,
    expected_git_sha: str,
) -> dict[str, object]:
    root = repository_root()
    observed_sha = checked_command(["git", "rev-parse", "HEAD"], root, "git HEAD").lower()
    if observed_sha != expected_git_sha:
        fail(f"git SHA argument does not match observed HEAD: {expected_git_sha} != {observed_sha}")
    git_status = checked_command(["git", "status", "--porcelain", "--untracked-files=all"], root, "git status")
    gradle_output = checked_command(
        [
            str(root / "gradlew"),
            ":benchmark-exposed-benchmark:dependencies",
            "--configuration",
            "benchmarkRuntimeClasspath",
            "--no-configuration-cache",
            "--no-parallel",
            "--max-workers=1",
            "--console=plain",
        ],
        root,
        "Gradle dependency resolution",
    )
    resolved_version = resolved_driver_version(gradle_output, driver)
    if resolved_version != driver_version:
        fail(f"resolved {driver} driver version {resolved_version} does not match {driver_version}")
    testcontainers = re.findall(
        r"org\.testcontainers:testcontainers(?:-[a-z0-9-]+)?:([0-9][^\s()]*)",
        gradle_output,
    )
    if not testcontainers:
        fail("resolved dependency output does not contain a Testcontainers version")
    image_inspect = checked_command(
        ["docker", "image", "inspect", image, "--format", "{{json .RepoDigests}}"],
        root,
        "Docker image digest",
    )
    try:
        repo_digests = json.loads(image_inspect)
    except json.JSONDecodeError as exc:
        fail(f"Docker image digest output is not JSON: {exc}")
    if not isinstance(repo_digests, list) or not any(str(value).endswith(f"@{image_digest}") for value in repo_digests):
        fail(f"Docker image {image} does not expose expected digest {image_digest}")
    gradle_version = next(
        (line.strip() for line in gradle_output.splitlines() if line.strip().startswith("Gradle ")),
        None,
    )
    if gradle_version is None:
        version_output = checked_command([str(root / "gradlew"), "--version"], root, "Gradle version")
        gradle_version = next(
            (line.strip() for line in version_output.splitlines() if line.strip().startswith("Gradle ")),
            None,
        )
    if gradle_version is None:
        fail("Gradle version output did not contain a Gradle version")
    colima_output = checked_command(["colima", "version"], root, "Colima version")
    return {
        "catalog_ref": observed_catalog_ref(root),
        "docker_context": checked_command(["docker", "context", "show"], root, "Docker context"),
        "docker_server_version": checked_command(
            ["docker", "version", "--format", "{{.Server.Version}}"], root, "Docker server version"
        ),
        "gradle_version": gradle_version,
        "git_dirty": bool(git_status),
        "host_arch": platform.machine(),
        "host_os": platform.system(),
        "colima_version": colima_output.splitlines()[0] if colima_output else "unknown",
        "observed_image_digest": image_digest,
        "resolved_driver_artifact": f"{DRIVER_COORDINATES[driver]}:{resolved_version}",
        "testcontainers_version": testcontainers[-1].rstrip("*"),
    }


def validate_metadata_record(record: dict[str, object], label: str) -> None:
    encoded = json.dumps(record, ensure_ascii=False, sort_keys=True)
    if FORBIDDEN.search(encoded):
        fail(f"{label} contains a forbidden connection or credential token")
    for field in ("driver_version", "image", "image_digest", "catalog_ref"):
        if field in record:
            validate_metadata_value(record[field], f"{label}.{field}")


def validate_metadata_value(value: object, label: str) -> None:
    if not isinstance(value, str) or value.strip().lower() in PLACEHOLDERS:
        fail(f"{label} must be a concrete non-placeholder value")


def read_metadata(metadata_path: Path) -> list[dict[str, object]]:
    if metadata_path.exists() and metadata_path.is_symlink():
        fail(f"metadata path must not be a symlink: {metadata_path}")
    if metadata_path.exists() and not metadata_path.is_file():
        fail(f"metadata path must be a regular file: {metadata_path}")
    existing: list[dict[str, object]] = []
    if metadata_path.exists():
        with metadata_path.open(encoding="utf-8") as stream:
            for line_number, line in enumerate(stream, start=1):
                if not line.strip():
                    continue
                try:
                    value = json.loads(line)
                except json.JSONDecodeError as exc:
                    fail(f"invalid metadata JSONL at line {line_number}: {exc}")
                if not isinstance(value, dict):
                    fail(f"metadata line {line_number} is not an object")
                validate_metadata_record(value, f"metadata line {line_number}")
                existing.append(value)
    return existing


def append_metadata(metadata_path: Path, record: dict[str, object]) -> None:
    validate_metadata_record(record, "metadata record")
    metadata_path.parent.mkdir(parents=True, exist_ok=True)
    lock_path = metadata_path.parent / f".{metadata_path.name}.lock"
    with lock_path.open("a+", encoding="utf-8") as lock_stream:
        fcntl.flock(lock_stream.fileno(), fcntl.LOCK_EX)
        try:
            existing = read_metadata(metadata_path)
            identity = (record["driver"], record["run_id"])
            if any((item.get("driver"), item.get("run_id")) == identity for item in existing):
                fail(f"metadata already contains driver/run-id pair: {identity[0]}/{identity[1]}")
            old_text = metadata_path.read_text(encoding="utf-8") if metadata_path.exists() else ""
            new_text = old_text
            if new_text and not new_text.endswith("\n"):
                new_text += "\n"
            new_text += json.dumps(record, ensure_ascii=False, sort_keys=True) + "\n"
            temporary: Path | None = None
            try:
                with tempfile.NamedTemporaryFile(
                    mode="w",
                    encoding="utf-8",
                    dir=metadata_path.parent,
                    prefix=f".{metadata_path.name}.",
                    delete=False,
                ) as stream:
                    temporary = Path(stream.name)
                    stream.write(new_text)
                    stream.flush()
                    os.fsync(stream.fileno())
                os.replace(temporary, metadata_path)
                temporary = None
            finally:
                if temporary is not None:
                    temporary.unlink(missing_ok=True)
        finally:
            fcntl.flock(lock_stream.fileno(), fcntl.LOCK_UN)


def main() -> None:
    args = parse_args()
    source = regular_json_files(args.report_dir)[0]
    destination = args.destination
    if destination.exists() or destination.is_symlink():
        fail(f"destination already exists: {destination}")
    if not args.run_id.strip():
        fail("run id must not be blank")
    if len(args.git_sha) != 40 or any(char not in "0123456789abcdefABCDEF" for char in args.git_sha):
        fail("git SHA must be a 40-character hexadecimal value")
    expected_git_sha = args.git_sha.lower()
    for field in ("driver_version", "image", "image_digest"):
        validate_metadata_value(getattr(args, field), f"--{field.replace('_', '-')}")
    args_metadata = {
        "driver_version": args.driver_version,
        "image": args.image,
        "image_digest": args.image_digest,
    }
    validate_metadata_record(args_metadata, "capture metadata")

    try:
        payload = json.loads(source.read_text(encoding="utf-8"), parse_constant=lambda value: fail(f"non-finite JSON constant: {value}"))
    except json.JSONDecodeError as exc:
        fail(f"source report is not valid JSON: {exc}")
    if not isinstance(payload, list):
        fail("source report root must be a JSON array")
    if len(payload) != EXPECTED_CARDINALITY:
        fail(f"source report must contain exactly {EXPECTED_CARDINALITY} primary entries")
    for index, entry in enumerate(payload):
        if not isinstance(entry, dict):
            fail(f"source report entry {index} must be an object")
        params = entry.get("params")
        if not isinstance(params, dict) or params.get("driver") != args.driver:
            fail(f"source report entry {index} does not match driver {args.driver}")

    source_sha256 = sha256(source)
    record = {
        "captured_at_utc": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "driver": args.driver,
        "driver_version": args.driver_version,
        "git_sha": expected_git_sha,
        "image": args.image,
        "image_digest": args.image_digest,
        "jdk": java_runtime(),
        "raw_file": destination.name,
        "raw_sha256": source_sha256,
        "run_id": args.run_id,
        "source_report": source.name,
        "python": platform.python_version(),
    }
    record.update(observed_provenance(args.driver, args.driver_version, args.image, args.image_digest, expected_git_sha))
    validate_metadata_record(record, "metadata record")
    existing = read_metadata(args.metadata)
    identity = (record["driver"], record["run_id"])
    if any((item.get("driver"), item.get("run_id")) == identity for item in existing):
        fail(f"metadata already contains driver/run-id pair: {identity[0]}/{identity[1]}")

    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary: Path | None = None
    published = False
    try:
        with tempfile.NamedTemporaryFile(
            mode="wb",
            dir=destination.parent,
            prefix=f".{destination.name}.",
            delete=False,
        ) as stream:
            temporary = Path(stream.name)
            with source.open("rb") as input_stream:
                shutil.copyfileobj(input_stream, stream)
        os.replace(temporary, destination)
        temporary = None
        published = True
        if sha256(destination) != source_sha256:
            fail("destination SHA-256 does not match the source report after copy")
        append_metadata(args.metadata, record)
    except BaseException:
        if published and destination.is_file() and not destination.is_symlink():
            destination.unlink()
        raise
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
    print(json.dumps(record, ensure_ascii=False, sort_keys=True))


if __name__ == "__main__":
    main()
