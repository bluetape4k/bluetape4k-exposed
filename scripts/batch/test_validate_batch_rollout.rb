#!/usr/bin/env ruby
# frozen_string_literal: true

require "minitest/autorun"
require "digest"
require "open3"
require "tempfile"
require "yaml"

class BatchRolloutContractTest < Minitest::Test
  ROOT = File.expand_path("../..", __dir__)
  VALIDATOR = File.join(ROOT, "scripts/batch/validate_batch_rollout.rb")
  OBSERVATION = File.join(ROOT, "utils/batch/operations/batch-rollout-observation.example.yaml")
  WRITER = File.join(ROOT, "utils/batch/operations/batch-writer-safety.example.yaml")
  CAPACITY = File.join(ROOT, "utils/batch/operations/batch-capacity-receipt.example.yaml")
  ALERTS = File.join(ROOT, "utils/batch/operations/batch-alerts.yaml")
  ALERT_RESUME = File.join(ROOT, "utils/batch/operations/batch-alert-resume-receipt.example.yaml")
  EXPECTED_HEAD = "a" * 40

  def test_observation_example_passes
    output, status = command

    assert status.success?, output
    assert_includes output, "PASS"
    assert_includes output, "writer_sha256="
  end

  def test_insufficient_observation_window_is_rejected
    observation = YAML.safe_load(File.read(OBSERVATION), aliases: false)
    observation["endedAt"] = "2099-01-01T00:04:59Z"
    with_observation(observation) do |path|
      output, status = command(path)

      refute status.success?, output
      assert_equal 1, status.exitstatus
      assert_includes output, "observation window"
    end
  end

  def test_blocked_alert_requires_and_accepts_matching_resume_receipt
    observation = YAML.safe_load(File.read(OBSERVATION), aliases: false)
    observation["alertResult"] = {
      "criticalAlertCount" => 1,
      "activeAlertIds" => ["batch-lease-loss-critical"],
    }
    observation["artifacts"]["alertResumes"] = [{
      "path" => "utils/batch/operations/batch-alert-resume-receipt.example.yaml",
      "sha256" => Digest::SHA256.file(ALERT_RESUME).hexdigest,
    }]
    observation["result"] = "blocked"
    with_observation(observation) do |path|
      output, status = command(path)

      assert status.success?, output
      assert_includes output, "alert_resume_count=1"
    end
  end

  private

  def command(observation = OBSERVATION)
    Open3.capture2e(
      "ruby", VALIDATOR, observation,
      "--writer-receipt", WRITER,
      "--capacity-receipt", CAPACITY,
      "--alerts", ALERTS,
      "--alert-resume-dir", File.dirname(OBSERVATION),
      "--expected-release-head", EXPECTED_HEAD,
    )
  end

  def with_observation(observation)
    Tempfile.create(["batch-rollout", ".yaml"]) do |file|
      file.write(observation.to_yaml)
      file.flush
      yield file.path
    end
  end
end
