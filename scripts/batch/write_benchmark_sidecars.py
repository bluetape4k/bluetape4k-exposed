#!/usr/bin/env python3
"""Create sidecars for the latest report of each known benchmark profile."""

from __future__ import annotations

import argparse
import json
import math
import statistics
from pathlib import Path
from typing import Any, Iterator

from write_benchmark_sidecar import write_sidecar


KNOWN_PROFILES = (
    "h2Jdbc",
    "h2R2dbc",
    "postgresJdbc",
    "postgresR2dbc",
    "mysqlJdbc",
    "mysqlR2dbc",
)


def _reject_non_finite(value: str) -> None:
    raise ValueError(f"non-finite JSON constant: {value}")


def _read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"), parse_constant=_reject_non_finite)


def _measured_scores(value: Any) -> Iterator[float]:
    if isinstance(value, dict):
        benchmark = value.get("benchmark")
        primary_metric = value.get("primaryMetric")
        score = value.get("score")
        if isinstance(primary_metric, dict):
            score = primary_metric.get("score", score)
        if (
            isinstance(benchmark, str)
            and benchmark.strip()
            and isinstance(score, (int, float))
            and not isinstance(score, bool)
            and math.isfinite(float(score))
        ):
            yield float(score)
        for child in value.values():
            yield from _measured_scores(child)
    elif isinstance(value, list):
        for child in value:
            yield from _measured_scores(child)


def _latest_report(report_root: Path, profile: str) -> Path | None:
    profile_root = report_root / profile
    reports = sorted(profile_root.rglob("benchmark.json")) if profile_root.is_dir() else []
    return max(reports, key=lambda path: (path.stat().st_mtime_ns, str(path))) if reports else None


def write_sidecars(
    report_root: Path,
    *,
    source_root: Path,
    source_ref: str,
    warmups: int,
    iterations: int,
    metric_type: str,
    environment: str | None = None,
    profiles: tuple[str, ...] = KNOWN_PROFILES,
) -> int:
    if not report_root.is_dir():
        raise SystemExit(f"benchmark-sidecar: report root does not exist: {report_root}")

    written = 0
    for profile in profiles:
        report_path = _latest_report(report_root, profile)
        if report_path is None:
            continue
        document = _read_json(report_path)
        scores = list(_measured_scores(document))
        if not scores:
            raise SystemExit(f"benchmark-sidecar: no finite measured score in {report_path}")
        metric_value = statistics.fmean(scores)
        write_sidecar(
            report_path.parent,
            source_root=source_root,
            source_ref=source_ref,
            warmups=warmups,
            iterations=iterations,
            metric_type=metric_type,
            metric_value=metric_value,
            environment=environment,
        )
        written += 1

    if written == 0:
        raise SystemExit(f"benchmark-sidecar: no benchmark reports under {report_root}")
    return written


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("report_root", type=Path)
    parser.add_argument("--source-root", type=Path, default=Path.cwd())
    parser.add_argument("--source-ref", default="local")
    parser.add_argument("--warmups", type=int, default=2)
    parser.add_argument("--iterations", type=int, default=5)
    parser.add_argument("--metric-type", default="ops/s")
    parser.add_argument("--environment", default=None)
    parser.add_argument("--profile", choices=KNOWN_PROFILES)
    args = parser.parse_args()
    written = write_sidecars(
        args.report_root,
        source_root=args.source_root,
        source_ref=args.source_ref,
        warmups=args.warmups,
        iterations=args.iterations,
        metric_type=args.metric_type,
        environment=args.environment,
        profiles=(args.profile,) if args.profile else KNOWN_PROFILES,
    )
    print(f"benchmark-sidecars: wrote {written} sidecar(s)")


if __name__ == "__main__":
    main()
