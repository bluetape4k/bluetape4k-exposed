#!/usr/bin/env ruby
# frozen_string_literal: true

# Batch lease rollout observation을 관련 receipt와 함께 fail-closed 방식으로
# 검증한다. 이 스크립트는 JSON Schema 구현에 의존하지 않으며, 실제 파일의
# SHA-256과 관찰 창을 다시 계산한다.

require "digest"
require "open3"
require "time"
require "uri"
require "yaml"

class BatchRolloutUsageError < StandardError; end
class BatchRolloutContractError < StandardError; end

ROLLOUT_HEX_40 = /\A[0-9a-f]{40}\z/
ROLLOUT_HEX_64 = /\A[0-9a-f]{64}\z/
ROLLOUT_SECRET = /(?:password|passwd|secret|token|credential|api[_-]?key|bearer|private[_-]?key)/i

def rollout_fail(message)
  raise BatchRolloutContractError, message
end

def rollout_load_yaml(path)
  rollout_fail("missing file #{path}") unless File.file?(path)

  value = YAML.safe_load(File.read(path, encoding: "UTF-8"), aliases: false)
  rollout_fail("#{path} must contain a mapping") unless value.is_a?(Hash)
  value
rescue Psych::SyntaxError => error
  rollout_fail("invalid YAML #{path}: #{error.message.lines.first.strip}")
rescue SystemCallError => error
  raise BatchRolloutUsageError, "cannot read #{path}: #{error.message}"
end

def rollout_nonblank(value, path)
  rollout_fail("#{path} must be a nonblank string") unless value.is_a?(String) && !value.strip.empty?
  value
end

def rollout_timestamp(value, path)
  rollout_nonblank(value, path)
  Time.iso8601(value).utc
rescue ArgumentError, TypeError
  rollout_fail("#{path} must be an ISO-8601 timestamp")
end

def rollout_number(value, path, minimum: 0, maximum: nil)
  valid = value.is_a?(Numeric) && !value.is_a?(TrueClass) && !value.is_a?(FalseClass)
  valid &&= value.is_a?(Integer) || value.finite?
  valid &&= value >= minimum
  valid &&= value <= maximum unless maximum.nil?
  rollout_fail("#{path} must be a finite number in the allowed range") unless valid
  value.to_f
end

def rollout_positive_integer(value, path)
  rollout_fail("#{path} must be a positive integer") unless value.is_a?(Integer) && value.positive?
  value
end

def rollout_validate_keys(value, required, allowed, path)
  rollout_fail("#{path} must be a mapping") unless value.is_a?(Hash)
  missing = required - value.keys
  extra = value.keys - allowed
  rollout_fail("#{path} is missing: #{missing.join(', ')}") unless missing.empty?
  rollout_fail("#{path} has unsupported fields: #{extra.join(', ')}") unless extra.empty?
end

def rollout_validate_reference(value, path, origin)
  rollout_validate_keys(value, %w[path sha256], %w[path sha256], path)
  reference = rollout_nonblank(value["path"], "#{path}.path")
  rollout_fail("#{path}.path must be relative") if reference.start_with?("/") || reference.match?(%r{\A[A-Za-z]:[\\/]})
  rollout_fail("#{path}.path contains a parent traversal") if reference.split(/[\\\/]/).include?("..")
  checksum = value["sha256"]
  rollout_fail("#{path}.sha256 must be lowercase SHA-256") unless checksum.is_a?(String) && checksum.match?(ROLLOUT_HEX_64)

  candidates = [File.expand_path(reference, Dir.pwd), File.expand_path(reference, origin)]
  file = candidates.find { |candidate| File.file?(candidate) }
  rollout_fail("#{path}.path does not identify a readable artifact") unless file
  actual = Digest::SHA256.file(file).hexdigest
  rollout_fail("#{path}.sha256 does not match #{reference}") unless actual == checksum
  [file, actual]
rescue SystemCallError => error
  raise BatchRolloutUsageError, "cannot checksum #{path}: #{error.message}"
end

def rollout_validate_opaque_reference(value, path)
  reference = rollout_nonblank(value, path)
  uri = URI.parse(reference)
  valid = uri.scheme == "restricted" && uri.host && !uri.host.empty? && uri.path && uri.path.length > 1
  rollout_fail("#{path} must be a restricted opaque reference") unless valid
  rollout_fail("#{path} must not contain credentials, query, or fragment") if uri.userinfo || uri.query || uri.fragment
  rollout_fail("#{path} contains a secret-like path") if uri.to_s.match?(ROLLOUT_SECRET)
rescue URI::InvalidURIError
  rollout_fail("#{path} must be a valid restricted reference")
end

def rollout_validate_header(data, path, expected_head, application, owner, allowed_keys: [])
  rollout_validate_keys(
    data,
    %w[schemaVersion application releaseHead releaseOwner generatedAt],
    %w[schemaVersion application releaseHead releaseOwner generatedAt] + allowed_keys,
    path,
  )
  rollout_fail("#{path}.schemaVersion must be 1") unless data["schemaVersion"] == 1
  rollout_fail("#{path}.application must match observation") unless data["application"] == application
  rollout_fail("#{path}.releaseHead must match expected release head") unless data["releaseHead"].is_a?(String) && data["releaseHead"].match?(ROLLOUT_HEX_40) && data["releaseHead"] == expected_head
  rollout_fail("#{path}.releaseOwner must match observation") unless data["releaseOwner"] == owner
  rollout_timestamp(data["generatedAt"], "#{path}.generatedAt")
end

def rollout_validate_writer_receipt(data, path, expected_head, application, owner)
  rollout_validate_header(data, path, expected_head, application, owner, allowed_keys: ["writers"])
  writers = data["writers"]
  rollout_fail("#{path}.writers must contain at least one writer") unless writers.is_a?(Array) && !writers.empty?
  ids = []
  writers.each_with_index do |writer, index|
    writer_path = "#{path}.writers[#{index}]"
    rollout_validate_keys(
      writer,
      %w[id sideEffect recoveryReceipt reviewedAt],
      %w[id sideEffect idempotencyKey remoteFencing transactionalOutbox recoveryReceipt reviewedAt],
      writer_path,
    )
    id = rollout_nonblank(writer["id"], "#{writer_path}.id")
    rollout_fail("#{writer_path}.id contains unsafe characters") unless id.match?(/\A[a-zA-Z0-9][a-zA-Z0-9._:-]*\z/)
    rollout_nonblank(writer["sideEffect"], "#{writer_path}.sideEffect")
    rollout_timestamp(writer["reviewedAt"], "#{writer_path}.reviewedAt")
    evidence_fields = %w[idempotencyKey remoteFencing transactionalOutbox]
    rollout_fail("#{writer_path} needs idempotency or fencing evidence") unless evidence_fields.any? { |field| writer.key?(field) }
    evidence_fields.each do |field|
      next unless writer.key?(field)

      reference = writer[field]
      rollout_validate_keys(reference, %w[uri sha256], %w[uri sha256], "#{writer_path}.#{field}")
      rollout_validate_opaque_reference(reference["uri"], "#{writer_path}.#{field}.uri")
      rollout_fail("#{writer_path}.#{field}.sha256 must be lowercase SHA-256") unless reference["sha256"].is_a?(String) && reference["sha256"].match?(ROLLOUT_HEX_64)
    end
    recovery = writer["recoveryReceipt"]
    rollout_validate_keys(recovery, %w[uri sha256], %w[uri sha256], "#{writer_path}.recoveryReceipt")
    rollout_validate_opaque_reference(recovery["uri"], "#{writer_path}.recoveryReceipt.uri")
    rollout_fail("#{writer_path}.recoveryReceipt.sha256 must be lowercase SHA-256") unless recovery["sha256"].is_a?(String) && recovery["sha256"].match?(ROLLOUT_HEX_64)
    ids << id
  end
  rollout_fail("#{path}.writers contains duplicate ids") unless ids.uniq.length == ids.length
end

def rollout_validate_capacity_receipt(data, path, expected_head, application, owner)
  rollout_validate_header(data, path, expected_head, application, owner, allowed_keys: ["inputs", "calculated"])
  inputs = data["inputs"]
  rollout_validate_keys(
    inputs,
    %w[maxConcurrentRunners maxJobStartsPerSecond maxJobStartBurst maxStepStartsPerSecond maxStepStartBurst approvedDbStatementsPerSecond approvedDbBurstBudget],
    %w[maxConcurrentRunners maxJobStartsPerSecond maxJobStartBurst maxStepStartsPerSecond maxStepStartBurst approvedDbStatementsPerSecond approvedDbBurstBudget],
    "#{path}.inputs",
  )
  runners = inputs["maxConcurrentRunners"]
  rollout_fail("#{path}.inputs.maxConcurrentRunners must be a positive integer") unless runners.is_a?(Integer) && runners.positive?
  job_rate = rollout_number(inputs["maxJobStartsPerSecond"], "#{path}.inputs.maxJobStartsPerSecond")
  job_burst = rollout_number(inputs["maxJobStartBurst"], "#{path}.inputs.maxJobStartBurst")
  step_rate = rollout_number(inputs["maxStepStartsPerSecond"], "#{path}.inputs.maxStepStartsPerSecond")
  step_burst = rollout_number(inputs["maxStepStartBurst"], "#{path}.inputs.maxStepStartBurst")
  approved_rate = rollout_number(inputs["approvedDbStatementsPerSecond"], "#{path}.inputs.approvedDbStatementsPerSecond", minimum: Float::MIN)
  approved_burst = rollout_number(inputs["approvedDbBurstBudget"], "#{path}.inputs.approvedDbBurstBudget", minimum: Float::MIN)
  calculated = data["calculated"]
  rollout_validate_keys(calculated, %w[steadyStateStatementsPerSecond oneSecondBurstStatements], %w[steadyStateStatementsPerSecond oneSecondBurstStatements], "#{path}.calculated")
  expected_steady = runners + (7 * job_rate) + (7 * step_rate)
  expected_burst = (10 * runners) + (7 * (job_burst + step_burst + job_rate + step_rate))
  actual_steady = rollout_number(calculated["steadyStateStatementsPerSecond"], "#{path}.calculated.steadyStateStatementsPerSecond")
  actual_burst = rollout_number(calculated["oneSecondBurstStatements"], "#{path}.calculated.oneSecondBurstStatements")
  rollout_fail("#{path}.calculated.steadyStateStatementsPerSecond does not match the approved formula") unless (actual_steady - expected_steady).abs < 1e-9
  rollout_fail("#{path}.calculated.oneSecondBurstStatements does not match the approved formula") unless (actual_burst - expected_burst).abs < 1e-9
  rollout_fail("#{path} steady-state statement budget is exceeded") if actual_steady > approved_rate
  rollout_fail("#{path} one-second burst statement budget is exceeded") if actual_burst > approved_burst
end

def rollout_cli_matches_artifact?(cli_path, artifact_path, origin)
  candidates = [File.expand_path(cli_path, Dir.pwd), File.expand_path(cli_path, origin)]
  candidates.include?(artifact_path)
end

def rollout_validate_observation(data, expected_head, origin, cli_writer_path, cli_capacity_path, cli_alerts_path, resume_dir)
  rollout_validate_keys(
    data,
    %w[schemaVersion application releaseHead releaseOwner stage startedAt endedAt eligibleSampleCount heartbeatWindowCount metrics alertResult artifacts result],
    %w[schemaVersion application releaseHead releaseOwner stage startedAt endedAt eligibleSampleCount heartbeatWindowCount metrics alertResult artifacts result],
    "observation",
  )
  rollout_fail("observation.schemaVersion must be 1") unless data["schemaVersion"] == 1
  application = rollout_nonblank(data["application"], "observation.application")
  rollout_fail("observation.releaseHead must match expected release head") unless data["releaseHead"].is_a?(String) && data["releaseHead"].match?(ROLLOUT_HEX_40) && data["releaseHead"] == expected_head
  owner = rollout_nonblank(data["releaseOwner"], "observation.releaseOwner")
  rollout_fail("observation.stage is invalid") unless %w[shadow 1 10 50 100].include?(data["stage"])
  started_at = rollout_timestamp(data["startedAt"], "observation.startedAt")
  ended_at = rollout_timestamp(data["endedAt"], "observation.endedAt")
  rollout_fail("observation endedAt must follow startedAt") unless ended_at > started_at
  rollout_fail("observation window must be at least 5 minutes") if ended_at - started_at < 5 * 60
  samples = data["eligibleSampleCount"]
  rollout_fail("observation eligibleSampleCount must be at least 20") unless samples.is_a?(Integer) && samples >= 20
  windows = data["heartbeatWindowCount"]
  rollout_fail("observation heartbeatWindowCount must be at least 2") unless windows.is_a?(Integer) && windows >= 2

  metrics = data["metrics"]
  rollout_validate_keys(metrics, %w[renewalFailureRatio renewalLatencyP95Millis dbStatementsPerSecond], %w[renewalFailureRatio renewalLatencyP95Millis dbStatementsPerSecond], "observation.metrics")
  rollout_number(metrics["renewalFailureRatio"], "observation.metrics.renewalFailureRatio", maximum: 1)
  rollout_number(metrics["renewalLatencyP95Millis"], "observation.metrics.renewalLatencyP95Millis")
  rollout_number(metrics["dbStatementsPerSecond"], "observation.metrics.dbStatementsPerSecond")

  alert_result = data["alertResult"]
  rollout_validate_keys(alert_result, %w[criticalAlertCount activeAlertIds], %w[criticalAlertCount activeAlertIds], "observation.alertResult")
  critical_count = alert_result["criticalAlertCount"]
  rollout_fail("observation.alertResult.criticalAlertCount must be a non-negative integer") unless critical_count.is_a?(Integer) && critical_count >= 0
  active_ids = alert_result["activeAlertIds"]
  rollout_fail("observation.alertResult.activeAlertIds must be an array") unless active_ids.is_a?(Array)
  rollout_fail("observation.alertResult.activeAlertIds contains duplicates") unless active_ids.uniq.length == active_ids.length

  artifacts = data["artifacts"]
  rollout_validate_keys(artifacts, %w[writerReceipt capacityReceipt alertCatalog alertResumes], %w[writerReceipt capacityReceipt alertCatalog alertResumes], "observation.artifacts")
  writer_artifact, writer_sha = rollout_validate_reference(artifacts["writerReceipt"], "observation.artifacts.writerReceipt", origin)
  capacity_artifact, capacity_sha = rollout_validate_reference(artifacts["capacityReceipt"], "observation.artifacts.capacityReceipt", origin)
  alert_artifact, alert_sha = rollout_validate_reference(artifacts["alertCatalog"], "observation.artifacts.alertCatalog", origin)
  rollout_fail("writer receipt argument does not match observation artifact") unless rollout_cli_matches_artifact?(cli_writer_path, writer_artifact, origin)
  rollout_fail("capacity receipt argument does not match observation artifact") unless rollout_cli_matches_artifact?(cli_capacity_path, capacity_artifact, origin)
  rollout_fail("alert catalog argument does not match observation artifact") unless rollout_cli_matches_artifact?(cli_alerts_path, alert_artifact, origin)
  writer_data = rollout_load_yaml(writer_artifact)
  capacity_data = rollout_load_yaml(capacity_artifact)
  alerts_data = rollout_load_yaml(alert_artifact)
  rollout_validate_writer_receipt(writer_data, "writer receipt", expected_head, application, owner)
  rollout_validate_capacity_receipt(capacity_data, "capacity receipt", expected_head, application, owner)
  approved_db_rate = capacity_data.fetch("inputs").fetch("approvedDbStatementsPerSecond")
  rollout_fail("observed DB statements per second exceeds the approved capacity") if metrics["dbStatementsPerSecond"] > approved_db_rate
  alert_validator = File.join(__dir__, "validate_batch_alerts.rb")
  alert_output, alert_status = Open3.capture2e("ruby", alert_validator, alert_artifact)
  unless alert_status.success?
    raise BatchRolloutUsageError, "alert catalog validator could not read its input: #{alert_output.strip}" if alert_status.exitstatus == 2

    rollout_fail("alert catalog is invalid: #{alert_output.strip}")
  end
  known_alerts = alerts_data.fetch("alerts")
  known_ids = known_alerts.map { |alert| alert["id"] }
  rollout_fail("observation.alertResult.activeAlertIds contains an unknown alert") unless active_ids.all? { |id| known_ids.include?(id) }
  expected_critical_count = active_ids.count { |id| known_alerts.find { |alert| alert["id"] == id }.fetch("severity") == "critical" }
  rollout_fail("observation.alertResult.criticalAlertCount does not match active critical alerts") unless critical_count == expected_critical_count

  resumes = artifacts["alertResumes"]
  rollout_fail("observation.artifacts.alertResumes must be an array") unless resumes.is_a?(Array)
  resume_by_id = {}
  resume_shas = []
  resumes.each_with_index do |resume_artifact, index|
    resume_path, resume_sha = rollout_validate_reference(resume_artifact, "observation.artifacts.alertResumes[#{index}]", origin)
    resume_data = rollout_load_yaml(resume_path)
    resume_root = File.expand_path(resume_dir)
    resolved_resume = File.expand_path(resume_path)
    rollout_fail("alert resume is outside the supplied resume directory") unless resolved_resume == resume_root || resolved_resume.start_with?("#{resume_root}/")
    resume_validator = File.join(__dir__, "validate_batch_alert_resume.rb")
    resume_output, resume_status = Open3.capture2e(
      "ruby", resume_validator, resume_path,
      "--alerts", alert_artifact,
      "--expected-release-head", expected_head,
    )
    unless resume_status.success?
      raise BatchRolloutUsageError, "alert resume validator could not read its input: #{resume_output.strip}" if resume_status.exitstatus == 2

      rollout_fail("alert resume is invalid: #{resume_output.strip}")
    end
    id = resume_data.fetch("alert").fetch("id")
    rollout_fail("duplicate alert resume for #{id}") if resume_by_id.key?(id)
    resume_by_id[id] = [resume_path, resume_sha]
    resume_shas << resume_sha
  end
  rollout_fail("alert resume is present for an inactive alert") unless resume_by_id.keys.all? { |id| active_ids.include?(id) }
  active_ids.each do |id|
    rollout_fail("active alert #{id} has no valid resume receipt") unless resume_by_id.key?(id)
  end

  rollout_fail("observation.result must be eligible or blocked") unless %w[eligible blocked].include?(data["result"])
  if data["result"] == "eligible"
    rollout_fail("eligible rollout must have no critical alert") unless critical_count.zero?
    rollout_fail("eligible rollout must have no active alert") unless active_ids.empty?
    rollout_fail("eligible rollout exceeds the renewal failure alert threshold") if metrics["renewalFailureRatio"] > 0.01
  else
    rollout_fail("blocked rollout must record an active or critical alert") if critical_count.zero? && active_ids.empty?
  end
  [writer_sha, capacity_sha, alert_sha, resume_shas]
end

def rollout_parse_options
  args = ARGV.dup
  observation = args.shift
  options = {}
  until args.empty?
    option = args.shift
    key = {
      "--writer-receipt" => :writer,
      "--capacity-receipt" => :capacity,
      "--alerts" => :alerts,
      "--alert-resume-dir" => :resume_dir,
      "--expected-release-head" => :expected_head,
    }[option]
    raise BatchRolloutUsageError, "unknown option #{option}" unless key
    raise BatchRolloutUsageError, "missing value for #{option}" if args.empty?
    raise BatchRolloutUsageError, "duplicate option #{option}" if options.key?(key)

    options[key] = args.shift
  end
  required = %i[writer capacity alerts resume_dir expected_head]
  raise BatchRolloutUsageError, "usage: validate_batch_rollout.rb <observation.yaml> --writer-receipt <path> --capacity-receipt <path> --alerts <path> --alert-resume-dir <path> --expected-release-head <40hex>" if observation.nil? || required.any? { |key| options[key].nil? }
  raise BatchRolloutUsageError, "expected release head must be lowercase 40-hex" unless options[:expected_head].match?(ROLLOUT_HEX_40)
  raise BatchRolloutUsageError, "alert resume directory must exist" unless File.directory?(options[:resume_dir])
  [observation, options]
end

def main
  observation_path, options = rollout_parse_options
  observation = rollout_load_yaml(observation_path)
  writer_sha, capacity_sha, alert_sha, resume_shas = rollout_validate_observation(
    observation,
    options[:expected_head],
    File.dirname(File.expand_path(observation_path)),
    options[:writer],
    options[:capacity],
    options[:alerts],
    options[:resume_dir],
  )
  puts "batch-rollout: PASS #{observation_path} (writer_sha256=#{writer_sha}, capacity_sha256=#{capacity_sha}, alerts_sha256=#{alert_sha}, alert_resume_count=#{resume_shas.length}, alert_resume_sha256=#{resume_shas.join(',')}, validatedAt=#{Time.now.utc.iso8601})"
rescue BatchRolloutContractError => error
  warn "batch-rollout: FAIL #{error.message}"
  exit 1
rescue BatchRolloutUsageError => error
  warn "batch-rollout: ERROR #{error.message}"
  exit 2
end

main if $PROGRAM_NAME == __FILE__
