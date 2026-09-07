import unittest

from validate_ci_matrix_contract import (
    MODULE_OUTPUTS,
    TENANT_JDBC_REQUIRED_TOKENS,
    validate,
    validate_tenant_jdbc_job,
)


class CiMatrixContractTest(unittest.TestCase):
    def test_current_workflow_satisfies_global_contract(self):
        with open(".github/workflows/ci.yml", encoding="utf-8") as workflow_file:
            errors = validate(workflow_file.read())

        self.assertEqual([], errors)

    def test_current_ci_and_nightly_workflows_satisfy_tenant_jdbc_contract(self):
        for workflow_path in (".github/workflows/ci.yml", ".github/workflows/nightly-tests.yml"):
            with (
                self.subTest(workflow_path=workflow_path),
                open(workflow_path, encoding="utf-8") as workflow_file,
            ):
                errors = validate_tenant_jdbc_job(workflow_file.read())

            self.assertEqual([], errors)

    def test_rejects_missing_tenant_jdbc_contract_tokens(self):
        for workflow_path in (".github/workflows/ci.yml", ".github/workflows/nightly-tests.yml"):
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
        with open(".github/workflows/nightly-tests.yml", encoding="utf-8") as workflow_file:
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
