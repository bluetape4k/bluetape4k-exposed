"""Validate the path-filtered CI matrix's global-change contract."""

import re
import sys
from pathlib import Path

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
    "scripts/ci/**",
)

FIXTURE_FILTERS = {
    "jdbc-test-fixture": (
        "exposed/jdbc/**",
        "exposed/jdbc-tests/**",
    ),
    "r2dbc-test-fixture": (
        "exposed/r2dbc/**",
        "exposed/r2dbc-tests/**",
    ),
}

FIXTURE_CONSUMER_OUTPUTS = {
    "jdbc-test-fixture": (
        "benchmark",
        "cache",
        "core",
        "jdbc",
        "jdbc-caffeine",
        "ktor",
        "lettuce",
        "measured",
        "mysql8-module",
        "postgresql-module",
        "redisson",
        "serialization",
        "spring-boot",
        "spring-boot-batch",
        "spring-modulith",
        "tink",
        "timefold",
        "utils-batch",
    ),
    "r2dbc-test-fixture": (
        "cache",
        "ktor",
        "lettuce",
        "r2dbc",
        "r2dbc-caffeine",
        "redisson",
        "spring-boot",
        "utils-batch",
    ),
}

TENANT_JDBC_REQUIRED_TOKENS = (
    ":bluetape4k-exposed-tenant-jdbc:test",
    ":bluetape4k-exposed-tenant-jdbc:koverXmlReport",
    "exposed/tenant-jdbc/build/reports/kover/report.xml",
)


def validate_tenant_jdbc_job(workflow: str) -> list[str]:
    """워크플로의 tenant JDBC 테스트 및 커버리지 계약을 검증한다."""
    job_start = workflow.find("  test-jdbc-h2:\n")
    if job_start < 0:
        return ["tenant-jdbc coverage contract cannot locate test-jdbc-h2 job"]

    job_body_start = job_start + len("  test-jdbc-h2:\n")
    next_job = re.search(
        r"^  (?!#)[A-Za-z0-9][A-Za-z0-9_-]*:\n",
        workflow[job_body_start:],
        re.MULTILINE,
    )
    job = (
        workflow[job_start : job_body_start + next_job.start()]
        if next_job
        else workflow[job_start:]
    )

    return [
        f"tenant-jdbc JDBC job is missing {token}"
        for token in TENANT_JDBC_REQUIRED_TOKENS
        if token not in job
    ]


def validate_fixture_contract(workflow: str) -> list[str]:
    """fixture filter가 실제 역방향 소비자 output을 활성화하는지 검증한다."""
    errors: list[str] = []
    filter_section_start = workflow.find("          filters: |\n")
    filter_section_end = workflow.find("\n      - uses:", filter_section_start + 1)
    filter_section = workflow[
        filter_section_start : filter_section_end if filter_section_end >= 0 else None
    ]
    outputs_start = workflow.find("    outputs:\n", workflow.find("  changes:\n"))
    outputs_end = workflow.find("    steps:\n", outputs_start)
    outputs = workflow[outputs_start:outputs_end]

    for fixture, paths in FIXTURE_FILTERS.items():
        match = re.search(
            rf"^            {re.escape(fixture)}:\n"
            rf"((?:^              - '[^']+'\n?)+)",
            filter_section,
            flags=re.MULTILINE,
        )
        if not match:
            errors.append(f"{fixture} filter is missing")
            continue
        declared_paths = set(
            re.findall(r"^              - '([^']+)'$", match[1], re.MULTILINE)
        )
        for path in paths:
            if path not in declared_paths:
                errors.append(f"{fixture} filter is missing {path}")

    for fixture, consumers in FIXTURE_CONSUMER_OUTPUTS.items():
        token = f"steps.filter.outputs['{fixture}'] == 'true'"
        for consumer in consumers:
            expression = re.search(
                rf"^      {re.escape(consumer)}: .*{re.escape(token)}",
                outputs,
                flags=re.MULTILINE,
            )
            if expression is None:
                errors.append(
                    f"changes output {consumer} does not expand for {fixture}"
                )
    return errors


def validate(workflow: str) -> list[str]:
    errors: list[str] = []
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
            errors.append(
                "examples coverage job must generate the instrumented demo report"
            )
        for task in (
            ":examples-exposed-bigquery-dry-run:koverXmlReport",
            ":examples-exposed-clickhouse-oltp-olap:koverXmlReport",
            ":examples-ktor-exposed-demo:koverXmlReport",
            ":exposed-spring-boot-jdbc-demo:koverXmlReport",
            ":exposed-spring-boot-r2dbc-demo:koverXmlReport",
        ):
            if task in examples:
                errors.append(
                    f"examples coverage job invokes an uninstrumented task: {task}"
                )
        if (
            "path: examples/ddd-spring-modulith-demo/build/reports/kover/"
            not in examples
        ):
            errors.append(
                "examples coverage artifact must be scoped to the instrumented demo"
            )

    batch_start = workflow.find("  test-utils-batch:\n")
    coverage_start = workflow.find("  # Coverage aggregation\n", batch_start + 1)
    if batch_start < 0 or coverage_start < 0:
        errors.append(
            "utils-batch coverage contract cannot locate test-utils-batch job"
        )
    else:
        batch = workflow[batch_start:coverage_start]
        for task in (
            ":bluetape4k-exposed-batch-core:koverXmlReport",
            ":bluetape4k-exposed-batch-jdbc:koverXmlReport",
            ":bluetape4k-exposed-batch-r2dbc:koverXmlReport",
        ):
            if task not in batch:
                errors.append(f"utils-batch coverage job is missing {task}")
        aggregator_task = ":bluetape4k-exposed-batch:koverXmlReport"
        if aggregator_task in batch:
            errors.append(
                f"utils-batch coverage job invokes an uninstrumented task: {aggregator_task}"
            )
        for path in (
            "utils/batch/core/build/reports/kover/",
            "utils/batch/jdbc/build/reports/kover/",
            "utils/batch/r2dbc/build/reports/kover/",
        ):
            if path not in batch:
                errors.append(f"utils-batch coverage artifact is missing {path}")
        if "utils/batch/build/reports/kover/" in batch:
            errors.append(
                "utils-batch coverage artifact includes the uninstrumented aggregator"
            )

    ci_status_start = workflow.find("  ci-status:\n")
    ci_status = workflow[ci_status_start:]
    if "      - test-benchmark\n" not in ci_status:
        errors.append("ci-status must require test-benchmark")
    if (
        "WRITE_BEHIND_REQUIRED:" not in ci_status
        or "needs.changes.outputs['all-modules']" not in ci_status
    ):
        errors.append("write-behind required gate must include all-modules")

    errors.extend(validate_tenant_jdbc_job(workflow))
    errors.extend(validate_fixture_contract(workflow))

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
