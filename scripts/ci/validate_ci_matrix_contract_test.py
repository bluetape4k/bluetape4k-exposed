import fnmatch
import re
import unittest
from pathlib import Path

from validate_ci_matrix_contract import (
    MODULE_OUTPUTS,
    TENANT_JDBC_REQUIRED_TOKENS,
    validate,
    validate_tenant_jdbc_job,
)


class CiMatrixContractTest(unittest.TestCase):
    def test_shared_changes_activate_consumers(self):
        expected = {
            "exposed/cache/src/main/kotlin/WriteBehindCoordinator.kt": {
                "cache",
                "jdbc-caffeine",
                "r2dbc-caffeine",
                "lettuce",
                "redisson",
                "ktor",
                "spring-boot",
                "examples",
                "benchmark",
            },
            "exposed/core/src/main/kotlin/ExposedPage.kt": {
                "core",
                "serialization",
                "tink",
                "jdbc",
                "r2dbc",
                "jdbc-caffeine",
                "r2dbc-caffeine",
                "lettuce",
                "redisson",
                "spring-boot",
                "ktor",
                "examples",
                "benchmark",
                "timefold",
            },
            "exposed/jdbc/src/main/kotlin/ExposedJdbcRepository.kt": {
                "jdbc",
                "jdbc-caffeine",
                "lettuce",
                "redisson",
                "spring-boot",
                "ktor",
                "examples",
                "benchmark",
                "timefold",
                "spring-boot-batch",
            },
            "exposed/r2dbc/src/main/kotlin/ExposedR2dbcRepository.kt": {
                "r2dbc",
                "r2dbc-caffeine",
                "lettuce",
                "redisson",
                "spring-boot",
                "ktor",
                "examples",
                "benchmark",
            },
            "spring-boot/common/src/main/kotlin/Query.kt": {
                "spring-boot",
                "spring-modulith",
                "examples",
            },
        }
        for path, consumers in expected.items():
            with self.subTest(path=path):
                self.assertLessEqual(consumers, self.activated_outputs(path))

    def test_jdbc_fixture_changes_activate_reverse_consumers(self):
        consumers = {
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
        }
        for path in (
            "exposed/jdbc/src/main/kotlin/ExposedJdbcRepository.kt",
            "exposed/jdbc-tests/src/test/kotlin/JdbcTest.kt",
        ):
            with self.subTest(path=path):
                self.assertLessEqual(consumers, self.activated_outputs(path))

    def test_r2dbc_fixture_changes_activate_reverse_consumers(self):
        consumers = {
            "cache",
            "ktor",
            "lettuce",
            "r2dbc",
            "r2dbc-caffeine",
            "redisson",
            "spring-boot",
            "utils-batch",
        }
        for path in (
            "exposed/r2dbc/src/main/kotlin/ExposedR2dbcRepository.kt",
            "exposed/r2dbc-tests/src/test/kotlin/R2dbcTest.kt",
        ):
            with self.subTest(path=path):
                self.assertLessEqual(consumers, self.activated_outputs(path))

    def test_tenant_jdbc_change_does_not_activate_fixture_only_consumers(self):
        activated = self.activated_outputs(
            "exposed/tenant-jdbc/src/main/kotlin/TenantDatabase.kt"
        )

        self.assertNotIn("serialization", activated)
        self.assertNotIn("tink", activated)
        self.assertNotIn("measured", activated)
        self.assertNotIn("postgresql-module", activated)
        self.assertNotIn("mysql8-module", activated)

    def test_isolated_module_keeps_narrow_matrix(self):
        self.assertEqual(
            {"druid"},
            self.activated_outputs("exposed/druid/src/main/kotlin/DruidDialect.kt"),
        )

    def test_docs_only_does_not_expand_downstream_matrix(self):
        for path in (
            "README.md",
            "exposed/core/README.md",
            "exposed/cache/README.ko.md",
            "docs/research.md",
        ):
            with self.subTest(path=path):
                self.assertEqual(set(), self.activated_outputs(path))

    @staticmethod
    def activated_outputs(path):
        workflow = Path(".github/workflows/ci.yml").read_text(encoding="utf-8")
        filters = {}
        name = None
        for line in workflow.split("          filters: |\n", 1)[1].splitlines():
            header = re.fullmatch(r"            ([\w-]+):", line)
            pattern = re.fullmatch(r"              - '([^']+)'", line)
            if header:
                name = header[1]
                filters[name] = []
            elif pattern and name:
                filters[name].append(pattern[1])
            elif line.strip():
                break
        matched = {
            name
            for name, patterns in filters.items()
            if any(
                fnmatch.fnmatchcase(path, p) for p in patterns if not p.startswith("!")
            )
            and not any(
                fnmatch.fnmatchcase(path, p[1:]) for p in patterns if p.startswith("!")
            )
        }
        outputs = workflow.split("    outputs:\n", 1)[1].split("    steps:\n", 1)[0]
        result = set()
        for name, expression in re.findall(
            r"^      ([\w-]+): (.*)$", outputs, re.MULTILINE
        ):
            if (
                "steps.filter.outputs['non-doc'] == 'true' && (" in expression
                and "non-doc" not in matched
            ):
                continue
            references = re.findall(
                r"steps\.filter\.outputs(?:\.([\w-]+)|\['([^']+)'\])", expression
            )
            if name in MODULE_OUTPUTS and any(
                (left or right) in matched - {"non-doc"} for left, right in references
            ):
                result.add(name)
        return result

    def test_current_workflow_satisfies_global_contract(self):
        with open(".github/workflows/ci.yml", encoding="utf-8") as workflow_file:
            errors = validate(workflow_file.read())

        self.assertEqual([], errors)

    def test_rejects_missing_fixture_reverse_consumer_link(self):
        with open(".github/workflows/ci.yml", encoding="utf-8") as workflow_file:
            workflow = workflow_file.read()

        broken_workflow = workflow.replace(
            " || steps.filter.outputs['jdbc-test-fixture'] == 'true'",
            "",
            1,
        )
        errors = validate(broken_workflow)

        self.assertTrue(
            any("does not expand for jdbc-test-fixture" in error for error in errors)
        )

    def test_rejects_missing_fixture_filter_path(self):
        with open(".github/workflows/ci.yml", encoding="utf-8") as workflow_file:
            workflow = workflow_file.read()

        broken_workflow = workflow.replace(
            "            r2dbc-test-fixture:\n"
            "              - 'exposed/r2dbc/**'\n"
            "              - 'exposed/r2dbc-tests/**'\n",
            "            r2dbc-test-fixture:\n              - 'exposed/r2dbc/**'\n",
            1,
        )
        errors = validate(broken_workflow)

        self.assertTrue(
            any(
                "r2dbc-test-fixture filter is missing exposed/r2dbc-tests/**" in error
                for error in errors
            )
        )

    def test_current_ci_and_nightly_workflows_satisfy_tenant_jdbc_contract(self):
        for workflow_path in (
            ".github/workflows/ci.yml",
            ".github/workflows/nightly-tests.yml",
        ):
            with (
                self.subTest(workflow_path=workflow_path),
                open(workflow_path, encoding="utf-8") as workflow_file,
            ):
                errors = validate_tenant_jdbc_job(workflow_file.read())

            self.assertEqual([], errors)

    def test_rejects_missing_tenant_jdbc_contract_tokens(self):
        for workflow_path in (
            ".github/workflows/ci.yml",
            ".github/workflows/nightly-tests.yml",
        ):
            with open(workflow_path, encoding="utf-8") as workflow_file:
                workflow = workflow_file.read()

            for token in TENANT_JDBC_REQUIRED_TOKENS:
                with self.subTest(workflow_path=workflow_path, token=token):
                    broken_workflow = workflow.replace(token, "")
                    errors = validate_tenant_jdbc_job(broken_workflow)

                self.assertTrue(any(token in error for error in errors))

    def test_rejects_module_condition_without_global_trigger(self):
        workflow = """
            all-modules:
              - 'settings.gradle.kts'
      outputs:
        core: ${{ steps.filter.outputs.core }}
  test-benchmark:
    run: :benchmark-exposed-benchmark:test :benchmark-exposed-benchmark:benchmarkClasses :benchmark-exposed-benchmark:detekt
    if: ${{ needs.changes.outputs.benchmark == 'true' }}
  docs-only-validation:
  ci-status:
    if: ${{ needs.changes.outputs.core == 'true' || github.event_name == 'workflow_dispatch' }}
"""
        errors = validate(workflow)

        self.assertTrue(any("all-modules filter" in error for error in errors))
        self.assertTrue(any("benchmark positive job" in error for error in errors))
        self.assertTrue(any("changes output" in error for error in errors))

    def test_rejects_uninstrumented_example_coverage_tasks(self):
        with open(".github/workflows/ci.yml", encoding="utf-8") as workflow_file:
            workflow = workflow_file.read()

        broken_workflow = workflow.replace(
            ":examples-ddd-spring-modulith-demo:koverXmlReport",
            ":examples-exposed-bigquery-dry-run:koverXmlReport",
            1,
        )
        errors = validate(broken_workflow)

        self.assertTrue(any("examples coverage" in error for error in errors))

    def test_rejects_uninstrumented_batch_aggregator_coverage_task(self):
        with open(".github/workflows/ci.yml", encoding="utf-8") as workflow_file:
            workflow = workflow_file.read()

        broken_workflow = workflow.replace(
            ":bluetape4k-exposed-batch-core:koverXmlReport",
            ":bluetape4k-exposed-batch:koverXmlReport",
            1,
        )
        errors = validate(broken_workflow)

        self.assertTrue(any("utils-batch coverage" in error for error in errors))

    def test_nightly_utils_batch_coverage_omits_compatibility_aggregator(self):
        with open(
            ".github/workflows/nightly-tests.yml", encoding="utf-8"
        ) as workflow_file:
            workflow = workflow_file.read()

        batch_start = workflow.find("  test-utils-batch:\n")
        coverage_start = workflow.find("\n  coverage-report:\n", batch_start + 1)
        self.assertGreaterEqual(batch_start, 0)
        self.assertGreater(coverage_start, batch_start)
        batch = workflow[batch_start:coverage_start]

        self.assertNotIn(":bluetape4k-exposed-batch:koverXmlReport", batch)
        self.assertNotIn("utils/batch/build/reports/kover/", batch)
        for path in (
            "utils/batch/core/build/reports/kover/",
            "utils/batch/jdbc/build/reports/kover/",
            "utils/batch/r2dbc/build/reports/kover/",
        ):
            self.assertIn(path, batch)


if __name__ == "__main__":
    unittest.main()
