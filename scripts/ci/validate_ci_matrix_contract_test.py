import ast
import fnmatch
import re
import unittest
from pathlib import Path

from validate_ci_matrix_contract import (
    COVERAGE_TRIGGER_FILTERS,
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
    def matched_filter_outputs(paths):
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
                any(
                    fnmatch.fnmatchcase(path, pattern)
                    for pattern in patterns
                    if not pattern.startswith("!")
                )
                and not any(
                    fnmatch.fnmatchcase(path, pattern[1:])
                    for pattern in patterns
                    if pattern.startswith("!")
                )
                for path in paths
            )
        }
        return matched

    @staticmethod
    def activated_outputs(path):
        matched = CiMatrixContractTest.matched_filter_outputs((path,))
        workflow = Path(".github/workflows/ci.yml").read_text(encoding="utf-8")
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

    @classmethod
    def coverage_enabled(cls, paths, event_name):
        workflow = Path(".github/workflows/ci.yml").read_text(encoding="utf-8")
        outputs = workflow.split("    outputs:\n", 1)[1].split("    steps:\n", 1)[0]
        coverage_line = next(
            line
            for line in outputs.splitlines()
            if line.startswith("      coverage-enabled:")
        )
        expression = (
            coverage_line.split(": ", 1)[1]
            .removeprefix("${{")
            .removesuffix("}}")
            .strip()
        )
        matched = cls.matched_filter_outputs(paths)
        references = re.compile(
            r"github\.event_name == 'workflow_dispatch'|"
            r"steps\.filter\.outputs(?:\.([\w-]+)|\['([^']+)'\]) == 'true'"
        )

        def replace_reference(match):
            if match.group(0).startswith("github.event_name"):
                value = event_name == "workflow_dispatch"
            else:
                filter_name = match.group(1) or match.group(2)
                value = filter_name in matched
            return "True" if value else "False"

        python_expression = references.sub(replace_reference, expression)
        python_expression = python_expression.replace("||", " or ").replace(
            "&&", " and "
        )
        root = ast.parse(python_expression, mode="eval").body

        def evaluate(node):
            if isinstance(node, ast.Constant) and isinstance(node.value, bool):
                return node.value
            if isinstance(node, ast.BoolOp) and isinstance(node.op, ast.And):
                return all(evaluate(value) for value in node.values)
            if isinstance(node, ast.BoolOp) and isinstance(node.op, ast.Or):
                return any(evaluate(value) for value in node.values)
            raise AssertionError(f"unsupported coverage expression node: {node!r}")

        return evaluate(root)

    def test_coverage_report_runs_only_when_a_coverage_producer_is_selected(self):
        workflow = Path(".github/workflows/ci.yml").read_text(encoding="utf-8")
        coverage_start = workflow.find("  coverage-report:\n")
        status_start = workflow.find("  ci-status:\n", coverage_start + 1)
        self.assertGreaterEqual(coverage_start, 0)
        self.assertGreater(status_start, coverage_start)
        coverage_job = workflow[coverage_start:status_start]

        self.assertIn(
            "needs.changes.outputs['coverage-enabled'] == 'true'",
            coverage_job,
        )

    def test_ci_status_reports_coverage_as_not_applicable_without_a_producer(self):
        workflow = Path(".github/workflows/ci.yml").read_text(encoding="utf-8")
        ci_status = workflow.split("  ci-status:\n", 1)[1]

        self.assertIn(
            "COVERAGE_REQUIRED: ${{ needs.changes.outputs['coverage-enabled'] }}",
            ci_status,
        )
        self.assertIn("CHANGES_RESULT: ${{ needs.changes.result }}", ci_status)
        self.assertIn("Kover coverage: N/A", ci_status)

    def test_coverage_enabled_tracks_all_kover_producer_filters(self):
        workflow = Path(".github/workflows/ci.yml").read_text(encoding="utf-8")
        outputs = workflow.split("    outputs:\n", 1)[1].split("    steps:\n", 1)[0]
        coverage_output = next(
            (
                line
                for line in outputs.splitlines()
                if line.startswith("      coverage-enabled:")
            ),
            None,
        )
        self.assertIsNotNone(coverage_output, "changes must expose coverage-enabled")
        if coverage_output is None:
            return

        expected_filters = (set(MODULE_OUTPUTS) - {"benchmark"}) | {
            "all-modules",
            "jdbc-test-fixture",
            "r2dbc-test-fixture",
        }
        for output in expected_filters:
            with self.subTest(output=output):
                self.assertIn(
                    f"steps.filter.outputs['{output}'] == 'true'",
                    coverage_output,
                )
        self.assertIn("github.event_name == 'workflow_dispatch'", coverage_output)
        self.assertIn("steps.filter.outputs['non-doc'] == 'true'", coverage_output)

    def test_coverage_dispatch_requires_each_producer_job_to_run(self):
        workflow = Path(".github/workflows/ci.yml").read_text(encoding="utf-8")
        broken = workflow.replace(
            "needs.changes.outputs.core == 'true' || needs.changes.outputs['all-modules'] == 'true' || github.event_name == 'workflow_dispatch'",
            "needs.changes.outputs.core == 'true' || needs.changes.outputs['all-modules'] == 'true'",
            1,
        )

        errors = validate(broken)

        self.assertIn(
            "coverage producer job test-core must run on workflow_dispatch",
            errors,
        )

    def test_coverage_enabled_boolean_truth_table(self):
        scenarios = (
            ((".github/dependabot.yml",), "pull_request", False),
            (
                ("benchmark/exposed/src/main/kotlin/Benchmark.kt",),
                "pull_request",
                False,
            ),
            (("docs/ci.md",), "pull_request", False),
            (
                (
                    "docs/ci.md",
                    "exposed/druid/src/main/kotlin/DruidDialect.kt",
                ),
                "pull_request",
                True,
            ),
            (("exposed/druid/src/main/kotlin/DruidDialect.kt",), "pull_request", True),
            ((), "workflow_dispatch", True),
        )
        for paths, event_name, expected in scenarios:
            with self.subTest(paths=paths, event_name=event_name):
                self.assertEqual(
                    expected,
                    self.coverage_enabled(paths, event_name),
                )

    def test_coverage_validator_rejects_missing_not_applicable_summary(self):
        workflow = Path(".github/workflows/ci.yml").read_text(encoding="utf-8")
        broken = workflow.replace("Kover coverage: N/A", "Kover coverage: skipped", 1)
        errors = validate(broken)

        self.assertIn(
            "ci-status must report coverage N/A when no producer is selected",
            errors,
        )

    def test_coverage_validator_rejects_omitted_producer_filter(self):
        workflow = Path(".github/workflows/ci.yml").read_text(encoding="utf-8")
        outputs = workflow.split("    outputs:\n", 1)[1].split("    steps:\n", 1)[0]
        coverage_line = next(
            line
            for line in outputs.splitlines()
            if line.startswith("      coverage-enabled:")
        )

        for filter_name in COVERAGE_TRIGGER_FILTERS:
            with self.subTest(filter_name=filter_name):
                token = f"steps.filter.outputs['{filter_name}'] == 'true'"
                broken_line = coverage_line.replace(token, "false", 1)
                self.assertNotEqual(coverage_line, broken_line)
                broken = workflow.replace(coverage_line, broken_line, 1)
                errors = validate(broken)
                self.assertIn(
                    f"coverage-enabled output is missing filter {filter_name}",
                    errors,
                )

    def test_config_only_change_selects_no_coverage_producer(self):
        coverage_producers = set(MODULE_OUTPUTS) - {"benchmark"}
        self.assertFalse(
            self.activated_outputs(".github/dependabot.yml") & coverage_producers
        )

    def test_module_change_selects_a_coverage_producer(self):
        coverage_producers = set(MODULE_OUTPUTS) - {"benchmark"}
        self.assertTrue(
            self.activated_outputs("exposed/druid/src/main/kotlin/DruidDialect.kt")
            & coverage_producers
        )

    def test_current_workflow_satisfies_global_contract(self):
        with open(".github/workflows/ci.yml", encoding="utf-8") as workflow_file:
            errors = validate(workflow_file.read())

        self.assertEqual([], errors)

    def test_rejects_missing_fixture_reverse_consumer_link(self):
        with open(".github/workflows/ci.yml", encoding="utf-8") as workflow_file:
            workflow = workflow_file.read()

        lines = workflow.splitlines()
        for index, line in enumerate(lines):
            if line.startswith("      core: "):
                lines[index] = line.replace(
                    " || steps.filter.outputs['jdbc-test-fixture'] == 'true'",
                    "",
                    1,
                )
                break
        broken_workflow = "\n".join(lines) + "\n"
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
