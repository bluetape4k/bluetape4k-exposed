#!/usr/bin/env ruby
# frozen_string_literal: true

require "minitest/autorun"
require "open3"

class BatchAlertContractTest < Minitest::Test
  ROOT = File.expand_path("../..", __dir__)
  ALERTS = File.join(ROOT, "utils/batch/operations/batch-alerts.yaml")
  ALERT_VALIDATOR = File.join(ROOT, "scripts/batch/validate_batch_alerts.rb")
  RESUME_VALIDATOR = File.join(ROOT, "scripts/batch/validate_batch_alert_resume.rb")
  RECEIPT = File.join(ROOT, "utils/batch/operations/batch-alert-resume-receipt.example.yaml")
  EXPECTED_HEAD = "a" * 40

  def test_alert_catalog_example_passes
    output, status = Open3.capture2e("ruby", ALERT_VALIDATOR, ALERTS)

    assert status.success?, output
    assert_includes output, "PASS"
  end

  def test_resume_receipt_example_passes_for_expected_release_head
    output, status = Open3.capture2e(
      "ruby", RESUME_VALIDATOR, RECEIPT,
      "--alerts", ALERTS,
      "--expected-release-head", EXPECTED_HEAD,
    )

    assert status.success?, output
    assert_includes output, "PASS"
  end

  def test_resume_receipt_rejects_stale_release_head
    output, status = Open3.capture2e(
      "ruby", RESUME_VALIDATOR, RECEIPT,
      "--alerts", ALERTS,
      "--expected-release-head", "b" * 40,
    )

    refute status.success?, output
    assert_equal 1, status.exitstatus
    assert_includes output, "releaseHead"
  end
end
