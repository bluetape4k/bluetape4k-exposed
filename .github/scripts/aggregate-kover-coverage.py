#!/usr/bin/env python3
"""
Kover XML 리포트를 집계해서 GitHub Step Summary에 모듈별 coverage 표를 출력합니다.

Usage:
    aggregate-kover-coverage.py <coverage-root>

Coverage provenance is fail-closed: an empty artifact directory, missing report,
malformed XML, or report without an instruction counter exits non-zero.
"""
import glob
import os
import sys
from typing import Optional
import xml.etree.ElementTree as ET


class CoverageReportError(ValueError):
    """Raised when coverage evidence is missing or cannot be trusted."""


def parse_report(path: str) -> tuple[int, int]:
    if not os.path.isfile(path) or os.path.getsize(path) == 0:
        raise CoverageReportError(f"coverage report is missing or empty: {path}")

    try:
        root = ET.parse(path).getroot()
    except (ET.ParseError, OSError) as error:
        raise CoverageReportError(f"cannot parse coverage report {path}: {error}") from error

    counters = [counter for counter in root.findall("counter") if counter.get("type") == "INSTRUCTION"]
    if len(counters) != 1:
        raise CoverageReportError(
            f"coverage report must contain exactly one INSTRUCTION counter: {path}"
        )

    counter = counters[0]
    try:
        covered = int(counter.attrib["covered"])
        missed = int(counter.attrib["missed"])
    except (KeyError, TypeError, ValueError) as error:
        raise CoverageReportError(
            f"coverage report has invalid INSTRUCTION counts: {path}"
        ) from error
    if covered < 0 or missed < 0:
        raise CoverageReportError(f"coverage report has negative counts: {path}")
    return covered, missed


def module_from_path(root_dir: str, path: str) -> str:
    rel = os.path.relpath(path, root_dir)
    parts = rel.split(os.sep)
    for i in range(len(parts) - 1, -1, -1):
        if parts[i] == "build" and i >= 1:
            return parts[i - 1]
    return os.path.basename(os.path.dirname(os.path.dirname(path)))


def aggregate(root_dir: str, summary_path: Optional[str] = None) -> str:
    patterns = [f"{root_dir}/**/report.xml", f"{root_dir}/**/reportJvm.xml"]
    rows: list[tuple[str, int, int, float]] = []
    total_covered = total_missed = 0
    seen: set[tuple[str, str]] = set()

    report_paths = []
    for pattern in patterns:
        report_paths.extend(sorted(glob.glob(pattern, recursive=True)))

    if not report_paths:
        raise CoverageReportError(f"no Kover XML reports found under {root_dir}")

    for xml_path in report_paths:
        module = module_from_path(root_dir, xml_path)
        key = (module, os.path.basename(xml_path))
        if key in seen:
            continue
        seen.add(key)
        covered, missed = parse_report(xml_path)
        total = covered + missed
        if total == 0:
            raise CoverageReportError(f"coverage report has no instructions: {xml_path}")
        pct = covered * 100.0 / total
        rows.append((module, covered, missed, pct))
        total_covered += covered
        total_missed += missed

    lines: list[str] = ["## Kover Coverage Summary", ""]
    lines += [
        "| Module | Instruction Covered | Instruction Missed | Coverage |",
        "|--------|--------------------:|-------------------:|---------:|",
    ]
    for module, covered, missed, pct in rows:
        lines.append(f"| `{module}` | {covered} | {missed} | {pct:.2f}% |")
    grand_total = total_covered + total_missed
    grand_pct = total_covered * 100.0 / grand_total
    lines.append(f"| **TOTAL** | **{total_covered}** | **{total_missed}** | **{grand_pct:.2f}%** |")

    output = "\n".join(lines) + "\n"
    if summary_path:
        with open(summary_path, "a", encoding="utf-8") as fp:
            fp.write(output)
    return output


def main_for_test(root_dir: str) -> int:
    print(aggregate(root_dir))
    return 0


def main() -> int:
    root_dir = sys.argv[1] if len(sys.argv) > 1 else "coverage-artifacts"
    print(aggregate(root_dir, os.environ.get("GITHUB_STEP_SUMMARY")))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except CoverageReportError as error:
        print(f"Kover coverage validation failed: {error}", file=sys.stderr)
        sys.exit(1)
