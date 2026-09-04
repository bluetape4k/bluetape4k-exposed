import importlib.util
import io
import os
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path


SCRIPT = Path(__file__).with_name("aggregate-kover-coverage.py")
SPEC = importlib.util.spec_from_file_location("aggregate_kover_coverage", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class AggregateKoverCoverageTest(unittest.TestCase):
    def test_empty_directory_fails_closed(self):
        with tempfile.TemporaryDirectory() as root:
            with self.assertRaises(MODULE.CoverageReportError):
                MODULE.main_for_test(root)

    def test_malformed_report_fails_closed(self):
        with tempfile.TemporaryDirectory() as root:
            report = Path(root) / "module" / "build" / "reports" / "kover" / "report.xml"
            report.parent.mkdir(parents=True)
            report.write_text("<report>", encoding="utf-8")

            with self.assertRaises(MODULE.CoverageReportError):
                MODULE.parse_report(str(report))

    def test_report_without_instruction_counter_fails_closed(self):
        with tempfile.TemporaryDirectory() as root:
            report = Path(root) / "report.xml"
            report.write_text(
                '<report><counter type="LINE" covered="1" missed="0" /></report>',
                encoding="utf-8",
            )

            with self.assertRaises(MODULE.CoverageReportError):
                MODULE.parse_report(str(report))

    def test_valid_report_is_summarized(self):
        with tempfile.TemporaryDirectory() as root:
            report = Path(root) / "module" / "build" / "reports" / "kover" / "report.xml"
            report.parent.mkdir(parents=True)
            report.write_text(
                '<report><counter type="INSTRUCTION" covered="8" missed="2" /></report>',
                encoding="utf-8",
            )
            output = io.StringIO()
            with redirect_stdout(output):
                MODULE.main_for_test(root)

            self.assertIn("`module`", output.getvalue())
            self.assertIn("80.00%", output.getvalue())


if __name__ == "__main__":
    unittest.main()
