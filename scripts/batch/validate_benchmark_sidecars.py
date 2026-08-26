#!/usr/bin/env python3
"""Validate every benchmark.json under a report root, fail-closed on stale data."""

from __future__ import annotations

import argparse
from pathlib import Path

from validate_benchmark_sidecar import current_head, validate

KNOWN_PROFILES = frozenset({
    "h2Jdbc",
    "h2R2dbc",
    "postgresJdbc",
    "postgresR2dbc",
    "mysqlJdbc",
    "mysqlR2dbc",
})


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("report_root", type=Path)
    parser.add_argument("--source-root", type=Path, default=Path.cwd())
    parser.add_argument("--expected-head")
    args = parser.parse_args()
    if not args.report_root.is_dir():
        raise SystemExit(f"benchmark-sidecars: report root does not exist: {args.report_root}")
    report_files = sorted(args.report_root.glob("*/**/benchmark.json"))
    if not report_files:
        raise SystemExit(f"benchmark-sidecars: no reports under {args.report_root}")
    expected_head = args.expected_head or current_head(args.source_root)
    for report_file in report_files:
        profile = report_file.relative_to(args.report_root).parts[0]
        if profile not in KNOWN_PROFILES:
            raise SystemExit(f"benchmark-sidecars: unexpected benchmark profile: {profile}")
        validate(report_file.parent, expected_head)
    print(f"benchmark-sidecars: PASS reports={len(report_files)}")


if __name__ == "__main__":
    main()
