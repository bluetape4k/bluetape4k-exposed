#!/usr/bin/env ruby
# frozen_string_literal: true

require "minitest/autorun"
require "open3"
require "tempfile"
require "yaml"

class BatchCapacityReceiptContractTest < Minitest::Test
  ROOT = File.expand_path("../..", __dir__)
  VALIDATOR = File.join(ROOT, "scripts/batch/validate_batch_capacity_receipt.rb")
  RECEIPT = File.join(ROOT, "utils/batch/operations/batch-capacity-receipt.example.yaml")
  EXPECTED_HEAD = "a" * 40

  def test_capacity_example_passes
    output, status = Open3.capture2e("ruby", VALIDATOR, RECEIPT, "--expected-release-head", EXPECTED_HEAD)

    assert status.success?, output
    assert_includes output, "PASS"
    assert_includes output, "sha256="
  end

  def test_stale_head_is_rejected
    output, status = Open3.capture2e("ruby", VALIDATOR, RECEIPT, "--expected-release-head", "b" * 40)

    refute status.success?, output
    assert_equal 1, status.exitstatus
    assert_includes output, "releaseHead"
  end

  def test_formula_tampering_is_rejected
    receipt = YAML.safe_load(File.read(RECEIPT), aliases: false)
    receipt["calculated"]["steadyStateStatementsPerSecond"] += 1
    Tempfile.create(["batch-capacity", ".yaml"]) do |file|
      file.write(receipt.to_yaml)
      file.flush
      output, status = Open3.capture2e("ruby", VALIDATOR, file.path, "--expected-release-head", EXPECTED_HEAD)

      refute status.success?, output
      assert_equal 1, status.exitstatus
      assert_includes output, "steadyStateStatementsPerSecond"
    end
  end
end
