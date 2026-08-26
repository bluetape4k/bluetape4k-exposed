#!/usr/bin/env python3

from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from validate_benchmark_sidecars import main as validate_all
from validate_benchmark_sidecar import validate
from write_benchmark_sidecars import write_sidecars


class BenchmarkSidecarTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        subprocess.run(["git", "-C", str(self.root), "-c", "user.name=fixture", "-c", "user.email=fixture@example.com", "commit", "--allow-empty", "-qm", "fixture"], check=True)
        self.head = subprocess.check_output(["git", "-C", str(self.root), "rev-parse", "HEAD"], text=True).strip()

    def tearDown(self) -> None:
        self.temp.cleanup()

    def write_report(
        self,
        *,
        source_head: str | None = None,
        pending: bool = False,
        profile: str = "h2Jdbc",
        report: object | None = None,
        with_metadata: bool = True,
    ) -> Path:
        report_dir = self.root / profile / "run-001"
        report_dir.mkdir(parents=True)
        payload = "pending" if pending else json.dumps({"benchmark": "seedBenchmark", "score": 10.0} if report is None else report)
        (report_dir / "benchmark.json").write_text(payload, encoding="utf-8")
        if with_metadata:
            (report_dir / "metadata.json").write_text(json.dumps({
                "runId": "run-001",
                "sourceRef": "test",
                "sourceHead": source_head or self.head,
                "environment": "test",
                "warmups": 1,
                "iterations": 1,
                "metric": {"type": "ops/s", "value": 10.0},
            }), encoding="utf-8")
        return report_dir

    def test_accepts_current_finite_report(self) -> None:
        validate(self.write_report(), self.head)

    def test_accepts_primary_metric_score_with_renderable_benchmark(self) -> None:
        validate(self.write_report(report={
            "benchmark": "seedBenchmark",
            "primaryMetric": {"score": 10.0},
        }), self.head)

    def test_rejects_stale_head(self) -> None:
        with self.assertRaises(SystemExit):
            validate(self.write_report(source_head="stale"), self.head)

    def test_rejects_pending_report(self) -> None:
        with self.assertRaises(SystemExit):
            validate(self.write_report(pending=True), self.head)

    def test_rejects_report_without_finite_score(self) -> None:
        with self.assertRaises(SystemExit):
            validate(self.write_report(report={"benchmark": "missing-score"}), self.head)

    def test_rejects_score_without_renderable_benchmark(self) -> None:
        with self.assertRaises(SystemExit):
            validate(self.write_report(report={"score": 10.0}), self.head)

    def test_rejects_missing_report_root(self) -> None:
        original_argv = sys.argv
        try:
            sys.argv = ["validate_benchmark_sidecars.py", str(self.root / "missing"), "--expected-head", self.head]
            with self.assertRaises(SystemExit):
                validate_all()
        finally:
            sys.argv = original_argv

    def test_rejects_unknown_profile(self) -> None:
        original_argv = sys.argv
        self.write_report(profile="unexpected")
        try:
            sys.argv = ["validate_benchmark_sidecars.py", str(self.root), "--expected-head", self.head]
            with self.assertRaises(SystemExit):
                validate_all()
        finally:
            sys.argv = original_argv

    def test_validates_known_profile(self) -> None:
        original_argv = sys.argv
        self.write_report()
        try:
            sys.argv = ["validate_benchmark_sidecars.py", str(self.root), "--expected-head", self.head]
            validate_all()
        finally:
            sys.argv = original_argv

    def test_writes_sidecar_for_latest_report(self) -> None:
        report_dir = self.write_report(with_metadata=False, report={
            "results": [{
                "benchmark": "seedBenchmark",
                "primaryMetric": {"score": 12.0},
            }],
        })

        written = write_sidecars(
            self.root,
            source_root=self.root,
            source_ref="test",
            warmups=2,
            iterations=5,
            metric_type="ops/s",
        )

        self.assertEqual(1, written)
        metadata = json.loads((report_dir / "metadata.json").read_text(encoding="utf-8"))
        self.assertEqual("test", metadata["sourceRef"])
        self.assertEqual(12.0, metadata["metric"]["value"])

    def test_sidecar_writer_does_not_overwrite_existing_sidecar(self) -> None:
        report_dir = self.write_report(source_head=self.head)

        written = write_sidecars(
            self.root,
            source_root=self.root,
            source_ref="new-ref",
            warmups=2,
            iterations=5,
            metric_type="ops/s",
        )

        self.assertEqual(1, written)
        metadata = json.loads((report_dir / "metadata.json").read_text(encoding="utf-8"))
        self.assertEqual("test", metadata["sourceRef"])
        self.assertEqual(10.0, metadata["metric"]["value"])

    def test_sidecar_writer_rejects_stale_existing_sidecar(self) -> None:
        self.write_report(source_head="stale")

        with self.assertRaises(SystemExit):
            write_sidecars(
                self.root,
                source_root=self.root,
                source_ref="test",
                warmups=2,
                iterations=5,
                metric_type="ops/s",
            )

    def test_profile_filter_does_not_relabel_other_reports(self) -> None:
        jdbc_report = self.write_report(with_metadata=False, profile="h2Jdbc")
        r2dbc_report = self.write_report(with_metadata=False, profile="h2R2dbc")

        written = write_sidecars(
            self.root,
            source_root=self.root,
            source_ref="test",
            warmups=2,
            iterations=5,
            metric_type="ops/s",
            profiles=("h2Jdbc",),
        )

        self.assertEqual(1, written)
        self.assertTrue((jdbc_report / "metadata.json").is_file())
        self.assertFalse((r2dbc_report / "metadata.json").exists())

    def test_sidecar_writer_rejects_report_root_without_reports(self) -> None:
        with self.assertRaises(SystemExit):
            write_sidecars(
                self.root,
                source_root=self.root,
                source_ref="test",
                warmups=2,
                iterations=5,
                metric_type="ops/s",
            )


if __name__ == "__main__":
    unittest.main()
