#!/usr/bin/env python3
"""Capture one immutable JMH report and append sanitized provenance metadata."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import shutil
import subprocess
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from typing import NoReturn


EXPECTED_CARDINALITY = 12


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


def append_metadata(metadata_path: Path, record: dict[str, object]) -> None:
    if metadata_path.exists() and metadata_path.is_symlink():
        fail(f"metadata path must not be a symlink: {metadata_path}")
    metadata_path.parent.mkdir(parents=True, exist_ok=True)
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
                existing.append(value)
    identity = (record["driver"], record["run_id"])
    if any((item.get("driver"), item.get("run_id")) == identity for item in existing):
        fail(f"metadata already contains driver/run-id pair: {identity[0]}/{identity[1]}")
    with metadata_path.open("a", encoding="utf-8") as stream:
        stream.write(json.dumps(record, ensure_ascii=False, sort_keys=True) + "\n")


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

    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary: Path | None = None
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
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)

    record = {
        "captured_at_utc": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "driver": args.driver,
        "driver_version": args.driver_version,
        "git_sha": args.git_sha.lower(),
        "image": args.image,
        "image_digest": args.image_digest,
        "jdk": java_runtime(),
        "raw_file": destination.name,
        "raw_sha256": sha256(destination),
        "run_id": args.run_id,
        "source_report": source.name,
        "python": platform.python_version(),
    }
    append_metadata(args.metadata, record)
    print(json.dumps(record, ensure_ascii=False, sort_keys=True))


if __name__ == "__main__":
    main()
