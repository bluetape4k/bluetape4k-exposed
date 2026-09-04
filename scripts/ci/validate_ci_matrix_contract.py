"""Validate the path-filtered CI matrix's global-change contract."""

import re
import sys
from pathlib import Path
from typing import List


MODULE_OUTPUTS = (
    "benchmark",
    "core",
    "serialization",
    "tink",
    "jdbc",
    "r2dbc",
    "spring-boot",
    "spring-modulith",
    "ktor",
    "examples",
    "lettuce",
    "redisson",
    "measured",
    "postgresql-module",
    "mysql8-module",
    "duckdb",
    "druid",
    "cache",
    "jdbc-caffeine",
    "r2dbc-caffeine",
    "clickhouse",
    "trino",
    "starrocks",
    "cockroachdb",
    "bigquery",
    "timefold",
    "spring-boot-batch",
    "utils-batch",
)

GLOBAL_PATHS = (
    "settings.gradle.kts",
    "gradle.properties",
    "gradle/**",
    "build.gradle.kts",
    "buildSrc/**",
    ".github/workflows/**",
    ".github/scripts/**",
)


def validate(workflow: str) -> List[str]:
    errors: List[str] = []
    all_modules = workflow.find("            all-modules:\n")
    if all_modules < 0:
        errors.append("changes filter must define all-modules")
    else:
        filter_end = workflow.find("\n            benchmark:\n", all_modules + 1)
        section = workflow[all_modules : filter_end if filter_end >= 0 else None]
        for path in GLOBAL_PATHS:
            if f"- '{path}'" not in section:
                errors.append(f"all-modules filter is missing {path}")

    outputs_start = workflow.find("    outputs:\n", workflow.find("  changes:\n"))
    outputs_end = workflow.find("    steps:\n", outputs_start)
    outputs = workflow[outputs_start:outputs_end]
    for output in MODULE_OUTPUTS:
        expression = rf"^      {re.escape(output)}: .*all-modules"
        if not re.search(expression, outputs, flags=re.MULTILINE):
            errors.append(f"changes output {output} does not expand for all-modules")

    benchmark_start = workflow.find("  test-benchmark:\n")
    docs_start = workflow.find("  docs-only-validation:\n", benchmark_start)
    benchmark = workflow[benchmark_start:docs_start]
    for task in (
        ":benchmark-exposed-benchmark:test",
        ":benchmark-exposed-benchmark:benchmarkClasses",
        ":benchmark-exposed-benchmark:detekt",
    ):
        if task not in benchmark:
            errors.append(f"benchmark positive job is missing {task}")
    if "needs.changes.outputs['all-modules'] == 'true'" not in benchmark:
        errors.append("benchmark positive job is not enabled by all-modules")

    examples_start = workflow.find("  test-examples:\n")
    timefold_start = workflow.find("  test-timefold:\n", examples_start + 1)
    if examples_start < 0 or timefold_start < 0:
        errors.append("examples coverage contract cannot locate test-examples job")
    else:
        examples = workflow[examples_start:timefold_start]
        expected_task = ":examples-ddd-spring-modulith-demo:koverXmlReport"
        if expected_task not in examples:
            errors.append("examples coverage job must generate the instrumented demo report")
        for task in (
            ":examples-exposed-bigquery-dry-run:koverXmlReport",
            ":examples-exposed-clickhouse-oltp-olap:koverXmlReport",
            ":examples-ktor-exposed-demo:koverXmlReport",
            ":exposed-spring-boot-jdbc-demo:koverXmlReport",
            ":exposed-spring-boot-r2dbc-demo:koverXmlReport",
        ):
            if task in examples:
                errors.append(f"examples coverage job invokes an uninstrumented task: {task}")
        if "path: examples/ddd-spring-modulith-demo/build/reports/kover/" not in examples:
            errors.append("examples coverage artifact must be scoped to the instrumented demo")

    ci_status_start = workflow.find("  ci-status:\n")
    ci_status = workflow[ci_status_start:]
    if "      - test-benchmark\n" not in ci_status:
        errors.append("ci-status must require test-benchmark")
    if "WRITE_BEHIND_REQUIRED:" not in ci_status or "needs.changes.outputs['all-modules']" not in ci_status:
        errors.append("write-behind required gate must include all-modules")

    for line in workflow.splitlines():
        if "if: ${{ needs.changes.outputs" not in line:
            continue
        if "docs-only" in line or "all-modules" in line:
            continue
        if "github.event_name" in line and "needs.changes.outputs" in line:
            errors.append(f"module job condition is not global-aware: {line.strip()}")
    return errors


def main() -> int:
    path = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".github/workflows/ci.yml")
    errors = validate(path.read_text(encoding="utf-8"))
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print("CI global-change matrix contract is aligned")
    return 0


if __name__ == "__main__":
    sys.exit(main())
