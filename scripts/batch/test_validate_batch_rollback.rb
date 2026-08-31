#!/usr/bin/env ruby
# frozen_string_literal: true

require "minitest/autorun"
require "open3"
require "tempfile"
require "yaml"

class BatchRollbackContractTest < Minitest::Test
  ROOT = File.expand_path("../..", __dir__)
  VALIDATOR = File.join(ROOT, "scripts/batch/validate_batch_lease_rollback.rb")
  RECEIPT = File.join(ROOT, "utils/batch/operations/batch-rollback-receipt.example.yaml")
  APPROVAL = File.join(ROOT, "utils/batch/operations/batch-rollback-approval.example.yaml")
  EXPECTED_HEAD = "a" * 40

  def test_rollback_examples_pass
    output, status = command

    assert status.success?, output
    assert_includes output, "PASS"
    assert_includes output, "approval_sha256="
    assert_includes output, "reconciliation_sha256="
  end

  def test_stale_release_head_is_rejected
    receipt = YAML.safe_load(File.read(RECEIPT), aliases: false)
    receipt["releaseHead"] = "b" * 40
    with_receipt(receipt) do |path|
      output, status = command(path)

      refute status.success?, output
      assert_equal 1, status.exitstatus
      assert_includes output, "releaseHead"
    end
  end

  def test_artifact_checksum_mismatch_is_rejected
    receipt = YAML.safe_load(File.read(RECEIPT), aliases: false)
    receipt["activeLeaseInventory"]["sha256"] = "0" * 64
    with_receipt(receipt) do |path|
      output, status = command(path)

      refute status.success?, output
      assert_equal 1, status.exitstatus
      assert_includes output, "sha256"
    end
  end

  def test_last_active_lease_in_the_future_is_rejected
    receipt = YAML.safe_load(File.read(RECEIPT), aliases: false)
    receipt["activeLeaseInventory"]["lastActiveLeaseAt"] = "2099-01-01T00:02:00Z"
    with_receipt(receipt) do |path|
      output, status = command(path)

      refute status.success?, output
      assert_equal 1, status.exitstatus
      assert_includes output, "last active lease"
    end
  end

  private

  def command(receipt = RECEIPT)
    Open3.capture2e(
      "ruby", VALIDATOR, receipt,
      "--approval", APPROVAL,
      "--expected-application", "bluetape4k-exposed-batch",
      "--expected-environment", "production",
      "--expected-release-head", EXPECTED_HEAD,
    )
  end

  def with_receipt(receipt)
    Tempfile.create(["batch-rollback", ".yaml"]) do |file|
      file.write(receipt.to_yaml)
      file.flush
      yield file.path
    end
  end
end
