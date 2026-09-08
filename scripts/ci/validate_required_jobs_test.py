import re
import unittest
from pathlib import Path

from validate_required_jobs import required_job_errors


class RequiredJobsTest(unittest.TestCase):
    def test_status_gate_wires_every_required_consumer(self):
        workflow = Path(".github/workflows/ci.yml").read_text(encoding="utf-8")
        status = workflow.split("  ci-status:\n", 1)[1]
        self.assertIn("python3 scripts/ci/validate_required_jobs.py", status)
        self.assertIn("NEEDS_JSON: ${{ toJSON(needs) }}", status)
        jobs = re.findall(r"^      - (test-[\w-]+)$", status, re.MULTILINE)
        needs = {job: {"result": "success"} for job in jobs}
        needs["changes"] = {"result": "success", "outputs": {"all-modules": "true"}}
        self.assertEqual([], required_job_errors(needs, "pull_request"))

    def test_required_consumer_must_succeed(self):
        for result in ("skipped", "cancelled", "failure", None):
            with self.subTest(result=result):
                needs = {
                    "changes": {
                        "result": "success",
                        "outputs": {"jdbc-caffeine": "true"},
                    }
                }
                if result is not None:
                    needs["test-jdbc-caffeine"] = {"result": result}
                self.assertTrue(required_job_errors(needs, "pull_request"))

    def test_success_and_unrelated_skips(self):
        needs = {
            "changes": {"result": "success", "outputs": {"jdbc-caffeine": "true"}},
            "test-jdbc-caffeine": {"result": "success"},
            "test-druid": {"result": "skipped"},
        }
        self.assertEqual([], required_job_errors(needs, "pull_request"))

    def test_docs_only_does_not_require_build_consumers(self):
        needs = {
            "changes": {
                "result": "success",
                "outputs": {"docs-only": "true", "core": "true"},
            }
        }
        self.assertEqual([], required_job_errors(needs, "pull_request"))

    def test_global_and_dispatch_require_all_consumers(self):
        for event, outputs in (
            ("workflow_dispatch", {}),
            ("pull_request", {"all-modules": "true"}),
        ):
            with self.subTest(event=event):
                errors = required_job_errors(
                    {"changes": {"result": "success", "outputs": outputs}}, event
                )
                self.assertIn("Required consumer test-jdbc-caffeine: missing", errors)
                self.assertIn(
                    "Required consumer test-ktor-driver-timeout: missing", errors
                )

    def test_failed_changes_cannot_silently_skip_consumers(self):
        self.assertTrue(required_job_errors({}, "pull_request"))


if __name__ == "__main__":
    unittest.main()
