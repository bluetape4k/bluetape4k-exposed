import unittest

from validate_ci_matrix_contract import MODULE_OUTPUTS, validate


class CiMatrixContractTest(unittest.TestCase):
    def test_current_workflow_satisfies_global_contract(self):
        with open(".github/workflows/ci.yml", encoding="utf-8") as workflow_file:
            errors = validate(workflow_file.read())

        self.assertEqual([], errors)

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


if __name__ == "__main__":
    unittest.main()
