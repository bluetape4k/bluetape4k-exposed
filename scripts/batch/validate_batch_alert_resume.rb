#!/usr/bin/env ruby
# frozen_string_literal: true

# 의존성 없이 alert resume receipt가 현재 alert 계약과 보호된 증적을
# 만족하는지 fail-closed 방식으로 검증한다.

require "digest"
require "time"
require "uri"
require "yaml"

require_relative "validate_batch_alerts"

class BatchAlertResumeUsageError < StandardError; end
class BatchAlertResumeContractError < StandardError; end

HEX_40 = /\A[0-9a-f]{40}\z/
HEX_64 = /\A[0-9a-f]{64}\z/
REFERENCE_SECRET = /(?:password|passwd|secret|token|credential|api[_-]?key|bearer|private[_-]?key)/i

def resume_fail(message)
  raise BatchAlertResumeContractError, message
end

def resume_load_yaml(path)
  resume_fail("missing file #{path}") unless File.file?(path)
  value = YAML.safe_load(File.read(path, encoding: "UTF-8"), aliases: false)
  resume_fail("#{path} must contain a mapping") unless value.is_a?(Hash)
  value
rescue Psych::SyntaxError => error
  resume_fail("invalid YAML #{path}: #{error.message.lines.first.strip}")
rescue SystemCallError => error
  raise BatchAlertResumeUsageError, "cannot read #{path}: #{error.message}"
end

def resume_nonblank(value, path)
  resume_fail("#{path} must be a nonblank string") unless value.is_a?(String) && !value.strip.empty?
  value
end

def resume_timestamp(value, path)
  resume_nonblank(value, path)
  Time.iso8601(value).utc
rescue ArgumentError
  resume_fail("#{path} must be an ISO-8601 timestamp")
end

def validate_reference(value, path)
  reference = resume_nonblank(value, path)
  uri = URI.parse(reference)
  valid_scheme = uri.scheme == "restricted" && uri.host && !uri.host.empty? && uri.path && uri.path.length > 1
  resume_fail("#{path} must be a restricted opaque reference") unless valid_scheme
  resume_fail("#{path} must not contain credentials, query, or fragment") if uri.userinfo || uri.query || uri.fragment
  resume_fail("#{path} contains a secret-like path") if uri.path.match?(REFERENCE_SECRET)
  reference
rescue URI::InvalidURIError
  resume_fail("#{path} must be a valid restricted reference")
end

def validate_hash_keys(value, expected, path)
  resume_fail("#{path} must be a mapping") unless value.is_a?(Hash)
  missing = expected - value.keys
  extra = value.keys - expected
  resume_fail("#{path} is missing: #{missing.join(', ')}") unless missing.empty?
  resume_fail("#{path} has unsupported fields: #{extra.join(', ')}") unless extra.empty?
end

def parse_options
  args = ARGV.dup
  receipt = args.shift
  alerts = nil
  expected_head = nil
  until args.empty?
    option = args.shift
    case option
    when "--alerts"
      alerts = args.shift
    when "--expected-release-head"
      expected_head = args.shift
    else
      raise BatchAlertResumeUsageError, "unknown option #{option}"
    end
  end
  raise BatchAlertResumeUsageError, "usage: validate_batch_alert_resume.rb <receipt.yaml> --alerts <alerts.yaml> --expected-release-head <40hex>" if receipt.nil? || alerts.nil? || expected_head.nil?
  raise BatchAlertResumeUsageError, "expected release head must be lowercase 40-hex" unless expected_head.match?(HEX_40)
  [receipt, alerts, expected_head]
end

def validate_resume(receipt, alerts, expected_head)
  validate_catalog(alerts)
  validate_hash_keys(
    receipt,
    %w[schemaVersion application releaseHead releaseOwner alert triggeredAt clearWindow resumeAuthority approvedAt expiresAt protectedApproval requiredEvidence],
    "receipt",
  )
  resume_fail("schemaVersion must be 1") unless receipt["schemaVersion"] == 1
  resume_nonblank(receipt["application"], "receipt.application")
  resume_fail("receipt.releaseHead must match expected release head") unless receipt["releaseHead"] == expected_head && receipt["releaseHead"].match?(HEX_40)
  resume_nonblank(receipt["releaseOwner"], "receipt.releaseOwner")

  alert_ref = receipt["alert"]
  validate_hash_keys(alert_ref, %w[id owner route], "receipt.alert")
  alert = alerts.fetch("alerts").find { |candidate| candidate["id"] == alert_ref["id"] }
  resume_fail("receipt.alert.id is not present in the alert catalog") unless alert
  resume_fail("receipt.alert.owner does not match the alert catalog") unless alert_ref["owner"] == alert["owner"]
  resume_fail("receipt.alert.route does not match the alert catalog") unless alert_ref["route"] == alert["route"]

  triggered_at = resume_timestamp(receipt["triggeredAt"], "receipt.triggeredAt")
  clear_window = receipt["clearWindow"]
  validate_hash_keys(clear_window, %w[startedAt endedAt sampleCount result], "receipt.clearWindow")
  clear_started = resume_timestamp(clear_window["startedAt"], "receipt.clearWindow.startedAt")
  clear_ended = resume_timestamp(clear_window["endedAt"], "receipt.clearWindow.endedAt")
  resume_fail("clear window must follow the alert trigger") if clear_started < triggered_at
  resume_fail("clear window end must follow its start") if clear_ended <= clear_started
  required_minutes = alert.fetch("clearCondition").fetch("window").delete_suffix("m").to_i
  resume_fail("clear window is shorter than the alert contract") if clear_ended - clear_started < required_minutes * 60
  sample_count = clear_window["sampleCount"]
  resume_fail("receipt.clearWindow.sampleCount must be a positive integer") unless sample_count.is_a?(Integer) && sample_count.positive?
  resume_fail("receipt.clearWindow.result must be clear") unless clear_window["result"] == "clear"

  authority = receipt["resumeAuthority"]
  validate_hash_keys(authority, %w[authority role], "receipt.resumeAuthority")
  resume_fail("receipt.resumeAuthority does not match the alert contract") unless authority == alert.fetch("resumeAuthority").slice("authority", "role")
  approved_at = resume_timestamp(receipt["approvedAt"], "receipt.approvedAt")
  expires_at = resume_timestamp(receipt["expiresAt"], "receipt.expiresAt")
  resume_fail("approvedAt must follow the clear window") if approved_at < clear_ended
  resume_fail("expiresAt must follow approvedAt") unless expires_at > approved_at
  resume_fail("resume approval is expired") if expires_at <= Time.now.utc

  approval = receipt["protectedApproval"]
  validate_hash_keys(approval, %w[storeReference signature checksum], "receipt.protectedApproval")
  validate_reference(approval["storeReference"], "receipt.protectedApproval.storeReference")
  resume_fail("protected approval signature must be lowercase SHA-256") unless approval["signature"].is_a?(String) && approval["signature"].match?(HEX_64)
  resume_fail("protected approval checksum must be lowercase SHA-256") unless approval["checksum"].is_a?(String) && approval["checksum"].match?(HEX_64)

  evidence = receipt["requiredEvidence"]
  resume_fail("receipt.requiredEvidence must be a non-empty array") unless evidence.is_a?(Array) && !evidence.empty?
  expected_kinds = alert.fetch("resumeAuthority").fetch("requiredEvidence")
  actual_kinds = evidence.map { |item| item.is_a?(Hash) ? item["kind"] : nil }
  resume_fail("required evidence kinds do not match the alert contract") unless actual_kinds == expected_kinds
  evidence.each_with_index do |item, index|
    path = "receipt.requiredEvidence[#{index}]"
    validate_hash_keys(item, %w[kind uri sha256], path)
    validate_reference(item["uri"], "#{path}.uri")
    resume_fail("#{path}.sha256 must be lowercase SHA-256") unless item["sha256"].is_a?(String) && item["sha256"].match?(HEX_64)
  end
  receipt
end

def main
  receipt_path, alerts_path, expected_head = parse_options
  receipt = resume_load_yaml(receipt_path)
  alerts = resume_load_yaml(alerts_path)
  validate_resume(receipt, alerts, expected_head)
  puts "batch-alert-resume: PASS #{receipt_path} (sha256=#{Digest::SHA256.file(receipt_path).hexdigest})"
rescue BatchAlertResumeContractError, BatchAlertContractError => error
  warn "batch-alert-resume: FAIL #{error.message}"
  exit 1
rescue BatchAlertResumeUsageError, BatchAlertUsageError => error
  warn "batch-alert-resume: ERROR #{error.message}"
  exit 2
end

main if $PROGRAM_NAME == __FILE__
