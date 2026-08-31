#!/usr/bin/env ruby
# frozen_string_literal: true

require "fileutils"
require "minitest/autorun"
require "open3"
require "tempfile"
require "yaml"

class BatchWriterSafetyContractTest < Minitest::Test
  ROOT = File.expand_path("../..", __dir__)
  VALIDATOR = File.join(ROOT, "scripts/batch/validate_batch_writer_safety.rb")
  RECEIPT = File.join(ROOT, "utils/batch/operations/batch-writer-safety.example.yaml")
  INVENTORY = File.join(ROOT, "utils/batch/operations/batch-writer-inventory.example.yaml")
  EXPECTED_HEAD = "a" * 40

  def test_example_receipt_and_inventory_pass
    output, status = Open3.capture2e(
      "ruby", VALIDATOR, RECEIPT, INVENTORY,
      "--expected-release-head", EXPECTED_HEAD,
    )

    assert status.success?, output
    assert_includes output, "PASS"
  end

  def test_empty_writer_set_is_rejected
    with_mutated_receipt do |path|
      output, status = Open3.capture2e(
        "ruby", VALIDATOR, path, INVENTORY,
        "--expected-release-head", EXPECTED_HEAD,
      )

      refute status.success?, output
      assert_equal 1, status.exitstatus
      assert_includes output, "writers"
    end
  end

  private

  def with_mutated_receipt
    receipt = YAML.safe_load(File.read(RECEIPT), aliases: false)
    receipt["writers"] = []
    Tempfile.create(["batch-writer-safety", ".yaml"]) do |file|
      file.write(receipt.to_yaml)
      file.flush
      yield file.path
    end
  end
end
