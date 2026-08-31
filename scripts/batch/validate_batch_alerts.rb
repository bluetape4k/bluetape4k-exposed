#!/usr/bin/env ruby
# frozen_string_literal: true

# 의존성 없이 batch alert catalog의 필수 계약을 검증한다.

require "digest"
require "yaml"

class BatchAlertUsageError < StandardError; end
class BatchAlertContractError < StandardError; end

REQUIRED_ALERTS = {
  "batch-lease-loss-critical" => {
    "severity" => "critical",
    "window" => "1m",
    "minimumSamples" => 1,
    "action" => "stop_scheduling",
    "condition" => {"metric" => "lease_loss_total", "operator" => ">=", "threshold" => 1},
    "clearCondition" => {"metric" => "lease_loss_total", "operator" => "==", "threshold" => 0, "window" => "10m"},
    "requiredEvidence" => ["operator"],
  },
  "batch-renewal-failure-high" => {
    "severity" => "high",
    "window" => "5m",
    "minimumSamples" => 20,
    "action" => "stop_scheduling",
    "condition" => {"metric" => "renewal_failure_ratio", "operator" => ">", "threshold" => 0.01},
    "clearCondition" => {"metric" => "renewal_failure_ratio", "operator" => "<=", "threshold" => 0.01, "window" => "15m"},
    "requiredEvidence" => ["db-health"],
  },
  "batch-renewal-latency-high" => {
    "severity" => "high",
    "window" => "5m",
    "minimumSamples" => 20,
    "action" => "stop_scheduling",
    "condition" => {"metric" => "renewal_latency_p95_millis", "operator" => ">", "threshold" => "min(executionLeaseMillis / 12, repositoryTimeoutMillis * 4 / 5)"},
    "clearCondition" => {"metric" => "renewal_latency_p95_millis", "operator" => "<=", "threshold" => "min(executionLeaseMillis / 12, repositoryTimeoutMillis * 4 / 5)", "window" => "15m"},
    "requiredEvidence" => ["capacity"],
  },
  "batch-cancellation-completion-failed-critical" => {
    "severity" => "critical",
    "window" => "1m",
    "minimumSamples" => 1,
    "action" => "stop_logical_key_and_reconcile",
    "condition" => {"metric" => "cancellation_completion_failed_total", "operator" => ">=", "threshold" => 1},
    "clearCondition" => {"metric" => "cancellation_completion_failed_total", "operator" => "==", "threshold" => 0, "window" => "10m"},
    "requiredEvidence" => ["reconciliation"],
  },
}.freeze

def fail_contract(message)
  raise BatchAlertContractError, message
end

def load_yaml(path)
  fail_contract("missing file #{path}") unless File.file?(path)

  value = YAML.safe_load(File.read(path, encoding: "UTF-8"), aliases: false)
  fail_contract("#{path} must contain a mapping") unless value.is_a?(Hash)
  value
rescue Psych::SyntaxError => error
  fail_contract("invalid YAML #{path}: #{error.message.lines.first.strip}")
rescue SystemCallError => error
  raise BatchAlertUsageError, "cannot read #{path}: #{error.message}"
end

def nonblank_string(value, path)
  fail_contract("#{path} must be a nonblank string") unless value.is_a?(String) && !value.strip.empty?
  value
end

def positive_integer(value, path)
  fail_contract("#{path} must be a positive integer") unless value.is_a?(Integer) && !value.is_a?(TrueClass) && value.positive?
  value
end

def duration_minutes(value, path)
  fail_contract("#{path} must be a positive minute duration") unless value.is_a?(String) && value.match?(/\A[1-9][0-9]*m\z/)
  value.delete_suffix("m").to_i
end

def validate_condition(condition, path)
  fail_contract("#{path} must be a mapping") unless condition.is_a?(Hash)
  allowed = %w[metric operator threshold denominator includesTimeoutSamples window]
  fail_contract("#{path} has unsupported fields: #{(condition.keys - allowed).join(', ')}") unless (condition.keys - allowed).empty?
  nonblank_string(condition["metric"], "#{path}.metric")
  fail_contract("#{path}.operator is invalid") unless [">", ">=", "<", "<=", "=="].include?(condition["operator"])
  threshold = condition["threshold"]
  valid_threshold = (threshold.is_a?(Numeric) && !threshold.is_a?(Integer) || threshold.is_a?(Integer)) ||
                    (threshold.is_a?(String) && !threshold.strip.empty?)
  fail_contract("#{path}.threshold must be numeric or a nonblank expression") unless valid_threshold
  if condition.key?("denominator")
    nonblank_string(condition["denominator"], "#{path}.denominator")
  end
  if condition.key?("includesTimeoutSamples") && ![true, false].include?(condition["includesTimeoutSamples"])
    fail_contract("#{path}.includesTimeoutSamples must be boolean")
  end
  duration_minutes(condition["window"], "#{path}.window") if condition.key?("window")
end

def validate_alert(alert, index)
  path = "alerts[#{index}]"
  fail_contract("#{path} must be a mapping") unless alert.is_a?(Hash)
  allowed = %w[id severity owner route window minimumSamples condition action clearCondition resumeAuthority]
  fail_contract("#{path} has unsupported fields: #{(alert.keys - allowed).join(', ')}") unless (alert.keys - allowed).empty?
  id = nonblank_string(alert["id"], "#{path}.id")
  fail_contract("#{path}.id contains unsafe characters") unless id.match?(/\A[a-z0-9]+(?:-[a-z0-9]+)*\z/)
  fail_contract("#{path}.severity must be critical or high") unless %w[critical high].include?(alert["severity"])
  fail_contract("#{path}.owner must be batch-platform") unless alert["owner"] == "batch-platform"
  fail_contract("#{path}.route must be batch-oncall") unless alert["route"] == "batch-oncall"
  duration_minutes(alert["window"], "#{path}.window")
  positive_integer(alert["minimumSamples"], "#{path}.minimumSamples")
  validate_condition(alert["condition"], "#{path}.condition")
  nonblank_string(alert["action"], "#{path}.action")
  validate_condition(alert["clearCondition"], "#{path}.clearCondition")

  authority = alert["resumeAuthority"]
  fail_contract("#{path}.resumeAuthority must be a mapping") unless authority.is_a?(Hash)
  fail_contract("#{path}.resumeAuthority.authority must be release-owner") unless authority["authority"] == "release-owner"
  fail_contract("#{path}.resumeAuthority.role must be release-owner") unless authority["role"] == "release-owner"
  evidence = authority["requiredEvidence"]
  fail_contract("#{path}.resumeAuthority.requiredEvidence must be a non-empty array") unless evidence.is_a?(Array) && !evidence.empty?
  fail_contract("#{path}.resumeAuthority.requiredEvidence contains duplicate kinds") unless evidence.uniq.length == evidence.length
  evidence.each_with_index do |kind, evidence_index|
    fail_contract("#{path}.resumeAuthority.requiredEvidence[#{evidence_index}] is invalid") unless %w[operator db-health capacity reconciliation].include?(kind)
  end

  id
end

def validate_catalog(data)
  fail_contract("schemaVersion must be 1") unless data["schemaVersion"] == 1
  alerts = data["alerts"]
  fail_contract("alerts must be a non-empty array") unless alerts.is_a?(Array) && !alerts.empty?
  fail_contract("alerts must contain exactly the required alert set") unless alerts.length == REQUIRED_ALERTS.length

  ids = alerts.each_with_index.map { |alert, index| validate_alert(alert, index) }
  fail_contract("alerts contains duplicate ids") unless ids.uniq.length == ids.length
  missing = REQUIRED_ALERTS.keys - ids
  fail_contract("alerts is missing required ids: #{missing.join(', ')}") unless missing.empty?

  REQUIRED_ALERTS.each do |id, expected|
    actual = alerts.find { |alert| alert["id"] == id }
    %w[severity window minimumSamples action].each do |field|
      fail_contract("#{id}.#{field} does not match the required contract") unless actual[field] == expected[field]
    end
    %w[condition clearCondition].each do |field|
      expected[field].each do |key, value|
        fail_contract("#{id}.#{field}.#{key} does not match the required contract") unless actual[field][key] == value
      end
    end
    actual_evidence = actual.fetch("resumeAuthority").fetch("requiredEvidence")
    fail_contract("#{id}.resumeAuthority.requiredEvidence does not match the required contract") unless actual_evidence == expected["requiredEvidence"]
  end
  data
end

def main
  unless ARGV.length == 1
    warn "usage: validate_batch_alerts.rb <alerts.yaml>"
    exit 2
  end

  path = ARGV.fetch(0)
  validate_catalog(load_yaml(path))
  puts "batch-alerts: PASS #{path} (sha256=#{Digest::SHA256.file(path).hexdigest})"
rescue BatchAlertContractError => error
  warn "batch-alerts: FAIL #{error.message}"
  exit 1
rescue BatchAlertUsageError => error
  warn "batch-alerts: ERROR #{error.message}"
  exit 2
end

main if $PROGRAM_NAME == __FILE__
