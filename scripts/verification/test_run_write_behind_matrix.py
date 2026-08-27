#!/usr/bin/env python3

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from run_write_behind_matrix import test_summary


class WriteBehindMatrixRunnerTest(unittest.TestCase):
    def test_uses_execution_xml_and_matches_log_summary(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            result_dir = Path(directory)
            (result_dir / "TEST-fixture.xml").write_text(
                '<testsuite><testcase classname="fixture" name="passes" /></testsuite>\n',
                encoding="utf-8",
            )
            summary, files, error = test_summary(result_dir, "SUCCESS: Executed 1 test in 0.0s\n")
            self.assertEqual({"executed": 1, "skipped": 0, "failed": 0}, summary)
            self.assertEqual(1, files)
            self.assertIsNone(error)

    def test_rejects_malformed_execution_xml(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            result_dir = Path(directory)
            (result_dir / "TEST-fixture.xml").write_text("<testsuite>", encoding="utf-8")
            summary, files, error = test_summary(result_dir, "SUCCESS: Executed 1 test in 0.0s\n")
            self.assertIsNone(summary)
            self.assertEqual(1, files)
            self.assertIn("malformed test XML", error or "")

    def test_rejects_log_xml_count_mismatch(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            result_dir = Path(directory)
            (result_dir / "TEST-fixture.xml").write_text(
                '<testsuite><testcase classname="fixture" name="passes" /></testsuite>\n',
                encoding="utf-8",
            )
            summary, _, error = test_summary(result_dir, "SUCCESS: Executed 2 tests in 0.0s\n")
            self.assertIsNone(summary)
            self.assertIn("log/XML test summaries disagree", error or "")

    def test_does_not_fallback_to_log_without_execution_xml(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            summary, files, error = test_summary(Path(directory), "SUCCESS: Executed 1 test in 0.0s\n")
            self.assertIsNone(summary)
            self.assertEqual(0, files)
            self.assertIsNone(error)


if __name__ == "__main__":
    unittest.main()
