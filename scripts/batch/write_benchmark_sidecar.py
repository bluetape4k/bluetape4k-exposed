#!/usr/bin/env python3
"""Write and immediately validate provenance metadata for one benchmark run."""

from __future__ import annotations

import argparse
import json
import platform
import subprocess
from pathlib import Path

from validate_benchmark_sidecar import validate


def write_sidecar(
    report_dir: Path,
    *,
    source_root: Path,
    source_ref: str,
    warmups: int,
    iterations: int,
    metric_type: str,
    metric_value: float,
    environment: str | None = None,
) -> Path:
    source_head = subprocess.check_output(
        ["git", "-C", str(source_root), "rev-parse", "HEAD"],
        text=True,
    ).strip()
    report_dir.mkdir(parents=True, exist_ok=True)
    metadata_path = report_dir / "metadata.json"
    if metadata_path.exists():
        # A finalizer must never relabel an existing report. Re-validating the
        # sidecar keeps stale provenance fail-closed.
        validate(report_dir, source_head)
        return metadata_path
    metadata = {
        "runId": report_dir.name,
        "sourceRef": source_ref,
        "sourceHead": source_head,
        "environment": environment or f"{platform.system()}-{platform.machine()}-JVM",
        "warmups": warmups,
        "iterations": iterations,
        "metric": {"type": metric_type, "value": metric_value},
    }
    metadata_path.write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    validate(report_dir, source_head)
    return metadata_path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("report_dir", type=Path)
    parser.add_argument("--source-root", type=Path, default=Path.cwd())
    parser.add_argument("--source-ref", default="local")
    parser.add_argument("--warmups", type=int, required=True)
    parser.add_argument("--iterations", type=int, required=True)
    parser.add_argument("--metric-type", required=True)
    parser.add_argument("--metric-value", type=float, required=True)
    parser.add_argument("--environment", default=None)
    args = parser.parse_args()
    metadata_path = write_sidecar(
        args.report_dir,
        source_root=args.source_root,
        source_ref=args.source_ref,
        warmups=args.warmups,
        iterations=args.iterations,
        metric_type=args.metric_type,
        metric_value=args.metric_value,
        environment=args.environment,
    )
    print(f"benchmark-sidecar: wrote {metadata_path}")


if __name__ == "__main__":
    main()
