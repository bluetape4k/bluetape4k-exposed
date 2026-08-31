#!/usr/bin/env ruby
# frozen_string_literal: true

# 이전 batch release를 기동하기 전에 보호된 승인, active lease drain,
# 외부 writer reconciliation의 durable receipt를 다시 검증한다.

require "digest"
require "time"
require "uri"
require "yaml"

class BatchRollbackUsageError < StandardError; end
class BatchRollbackContractError < StandardError; end

ROLLBACK_HEX_40 = /\A[0-9a-f]{40}\z/
ROLLBACK_HEX_64 = /\A[0-9a-f]{64}\z/
ROLLBACK_SECRET = /(?:password|passwd|secret|token|credential|api[_-]?key|bearer|private[_-]?key)/i

def rollback_fail(message)
  raise BatchRollbackContractError, message
end

def rollback_load_yaml(path)
  rollback_fail("missing file #{path}") unless File.file?(path)

  value = YAML.safe_load(File.read(path, encoding: "UTF-8"), aliases: false)
  rollback_fail("#{path} must contain a mapping") unless value.is_a?(Hash)
  value
rescue Psych::SyntaxError => error
  rollback_fail("invalid YAML #{path}: #{error.message.lines.first.strip}")
rescue SystemCallError => error
  raise BatchRollbackUsageError, "cannot read #{path}: #{error.message}"
end

def rollback_nonblank(value, path)
  rollback_fail("#{path} must be a nonblank string") unless value.is_a?(String) && !value.strip.empty?
  value
end

def rollback_timestamp(value, path)
  rollback_nonblank(value, path)
  Time.iso8601(value).utc
rescue ArgumentError, TypeError
  rollback_fail("#{path} must be an ISO-8601 timestamp")
end

def rollback_validate_keys(value, required, allowed, path)
  rollback_fail("#{path} must be a mapping") unless value.is_a?(Hash)
  missing = required - value.keys
  extra = value.keys - allowed
  rollback_fail("#{path} is missing: #{missing.join(', ')}") unless missing.empty?
  rollback_fail("#{path} has unsupported fields: #{extra.join(', ')}") unless extra.empty?
end

def rollback_restricted_reference(value, path)
  reference = rollback_nonblank(value, path)
  uri = URI.parse(reference)
  valid = uri.scheme == "restricted" && uri.host && !uri.host.empty? && uri.path && uri.path.length > 1
  rollback_fail("#{path} must be a restricted opaque reference") unless valid
  rollback_fail("#{path} must not contain credentials, query, or fragment") if uri.userinfo || uri.query || uri.fragment
  rollback_fail("#{path} contains a secret-like token") if uri.to_s.match?(ROLLBACK_SECRET)
  reference
rescue URI::InvalidURIError
  rollback_fail("#{path} must be a valid restricted reference")
end

def rollback_artifact(value, path, origin, extra_allowed: [])
  rollback_validate_keys(value, %w[path sha256], %w[path sha256] + extra_allowed, path)
  artifact_path = rollback_nonblank(value["path"], "#{path}.path")
  rollback_fail("#{path}.path must be relative") if artifact_path.start_with?("/") || artifact_path.match?(%r{\A[A-Za-z]:[\\/]})
  rollback_fail("#{path}.path contains a parent traversal") if artifact_path.split(/[\\\/]/).include?("..")
  checksum = value["sha256"]
  rollback_fail("#{path}.sha256 must be lowercase SHA-256") unless checksum.is_a?(String) && checksum.match?(ROLLBACK_HEX_64)
  candidates = [File.expand_path(artifact_path, Dir.pwd), File.expand_path(artifact_path, origin)]
  resolved = candidates.find { |candidate| File.file?(candidate) }
  rollback_fail("#{path}.path does not identify a readable artifact") unless resolved
  actual = Digest::SHA256.file(resolved).hexdigest
  rollback_fail("#{path}.sha256 does not match #{artifact_path}") unless checksum == actual
  [resolved, actual]
rescue SystemCallError => error
  raise BatchRollbackUsageError, "cannot checksum #{path}: #{error.message}"
end

def rollback_validate_approval(approval, expected_application, expected_environment, expected_head)
  rollback_validate_keys(
    approval,
    %w[schemaVersion application environment releaseHead rollbackTargetHead approver protectedAuthority approvedAt expiresAt protectedApproval],
    %w[schemaVersion application environment releaseHead rollbackTargetHead approver protectedAuthority approvedAt expiresAt protectedApproval],
    "approval",
  )
  rollback_fail("approval.schemaVersion must be 1") unless approval["schemaVersion"] == 1
  rollback_fail("approval.application does not match expected application") unless approval["application"] == expected_application
  rollback_fail("approval.environment does not match expected environment") unless approval["environment"] == expected_environment
  rollback_fail("approval.releaseHead must match expected release head") unless approval["releaseHead"].is_a?(String) && approval["releaseHead"].match?(ROLLBACK_HEX_40) && approval["releaseHead"] == expected_head
  target = approval["rollbackTargetHead"]
  rollback_fail("approval.rollbackTargetHead must be lowercase 40-hex and differ from releaseHead") unless target.is_a?(String) && target.match?(ROLLBACK_HEX_40) && target != expected_head
  approver = rollback_nonblank(approval["approver"], "approval.approver")
  rollback_fail("approval.approver contains a secret-like token") if approver.match?(ROLLBACK_SECRET)

  authority = approval["protectedAuthority"]
  rollback_validate_keys(authority, %w[authority role], %w[authority role], "approval.protectedAuthority")
  rollback_fail("approval.protectedAuthority.authority must be release-owner") unless authority["authority"] == "release-owner"
  rollback_fail("approval.protectedAuthority.role must be release-owner") unless authority["role"] == "release-owner"

  approved_at = rollback_timestamp(approval["approvedAt"], "approval.approvedAt")
  expires_at = rollback_timestamp(approval["expiresAt"], "approval.expiresAt")
  rollback_fail("approval.approvedAt must not be in the future") if approved_at > Time.now.utc
  rollback_fail("approval.expiresAt must follow approvedAt") unless expires_at > approved_at
  rollback_fail("rollback approval is expired") unless expires_at > Time.now.utc

  protected_approval = approval["protectedApproval"]
  rollback_validate_keys(protected_approval, %w[storeReference signature checksum], %w[storeReference signature checksum], "approval.protectedApproval")
  rollback_restricted_reference(protected_approval["storeReference"], "approval.protectedApproval.storeReference")
  rollback_fail("approval.protectedApproval.signature must be lowercase SHA-256") unless protected_approval["signature"].is_a?(String) && protected_approval["signature"].match?(ROLLBACK_HEX_64)
  rollback_fail("approval.protectedApproval.checksum must be lowercase SHA-256") unless protected_approval["checksum"].is_a?(String) && protected_approval["checksum"].match?(ROLLBACK_HEX_64)
  [target, approved_at, expires_at, approver]
end

def rollback_validate_receipt(receipt, expected_application, expected_environment, expected_head, approval, approval_path, origin)
  rollback_validate_keys(
    receipt,
    %w[schemaVersion application environment releaseHead releaseOwner rollbackTargetHead approval schedulingStoppedAt activeLeaseInventory writerReconciliation drainStartedAt drainedAt resultAt simultaneousWriterCount result],
    %w[schemaVersion application environment releaseHead releaseOwner rollbackTargetHead approval schedulingStoppedAt activeLeaseInventory writerReconciliation drainStartedAt drainedAt resultAt simultaneousWriterCount result],
    "receipt",
  )
  rollback_fail("receipt.schemaVersion must be 1") unless receipt["schemaVersion"] == 1
  rollback_fail("receipt.application does not match expected application") unless receipt["application"] == expected_application
  rollback_fail("receipt.environment does not match expected environment") unless receipt["environment"] == expected_environment
  rollback_fail("receipt.releaseHead must match expected release head") unless receipt["releaseHead"].is_a?(String) && receipt["releaseHead"].match?(ROLLBACK_HEX_40) && receipt["releaseHead"] == expected_head
  release_owner = rollback_nonblank(receipt["releaseOwner"], "receipt.releaseOwner")
  target = receipt["rollbackTargetHead"]
  rollback_fail("receipt.rollbackTargetHead must match approval") unless target == approval["rollbackTargetHead"] && target.is_a?(String) && target.match?(ROLLBACK_HEX_40) && target != expected_head

  approval_artifact, approval_sha = rollback_artifact(receipt["approval"], "receipt.approval", origin)
  supplied_approval = File.expand_path(approval_path)
  rollback_fail("receipt approval artifact must match the supplied approval path") unless File.expand_path(approval_artifact) == supplied_approval
  rollback_fail("receipt.approval.sha256 does not match supplied approval") unless approval_sha == Digest::SHA256.file(supplied_approval).hexdigest

  scheduling_stopped_at = rollback_timestamp(receipt["schedulingStoppedAt"], "receipt.schedulingStoppedAt")
  inventory = receipt["activeLeaseInventory"]
  rollback_validate_keys(inventory, %w[path sha256 lastActiveLeaseAt], %w[path sha256 lastActiveLeaseAt], "receipt.activeLeaseInventory")
  inventory_path, inventory_sha = rollback_artifact(inventory, "receipt.activeLeaseInventory", origin, extra_allowed: ["lastActiveLeaseAt"])
  last_active_at = rollback_timestamp(inventory["lastActiveLeaseAt"], "receipt.activeLeaseInventory.lastActiveLeaseAt")
  reconciliation_path, reconciliation_sha = rollback_artifact(receipt["writerReconciliation"], "receipt.writerReconciliation", origin)
  drain_started_at = rollback_timestamp(receipt["drainStartedAt"], "receipt.drainStartedAt")
  drained_at = rollback_timestamp(receipt["drainedAt"], "receipt.drainedAt")
  result_at = rollback_timestamp(receipt["resultAt"], "receipt.resultAt")
  rollback_fail("scheduling stop must precede the last active lease") if scheduling_stopped_at > last_active_at
  rollback_fail("drain must start after the last active lease") if drain_started_at < last_active_at
  rollback_fail("drainedAt must follow drainStartedAt") unless drained_at >= drain_started_at
  rollback_fail("resultAt must follow drainedAt") unless result_at >= drained_at
  rollback_fail("current time is before the last active lease") if Time.now.utc < last_active_at
  count = receipt["simultaneousWriterCount"]
  rollback_fail("receipt.simultaneousWriterCount must be zero") unless count.is_a?(Integer) && count.zero?
  rollback_fail("receipt.result must be ready") unless receipt["result"] == "ready"
  [release_owner, approval_sha, inventory_sha, reconciliation_sha, inventory_path, reconciliation_path]
end

def rollback_parse_options
  args = ARGV.dup
  receipt = args.shift
  approval = nil
  expected_application = nil
  expected_environment = nil
  expected_head = nil
  until args.empty?
    option = args.shift
    value = args.shift
    raise BatchRollbackUsageError, "missing value for #{option}" if value.nil?
    case option
    when "--approval"
      raise BatchRollbackUsageError, "duplicate option #{option}" unless approval.nil?
      approval = value
    when "--expected-application"
      raise BatchRollbackUsageError, "duplicate option #{option}" unless expected_application.nil?
      expected_application = value
    when "--expected-environment"
      raise BatchRollbackUsageError, "duplicate option #{option}" unless expected_environment.nil?
      expected_environment = value
    when "--expected-release-head"
      raise BatchRollbackUsageError, "duplicate option #{option}" unless expected_head.nil?
      expected_head = value
    else
      raise BatchRollbackUsageError, "unknown option #{option}"
    end
  end
  if receipt.nil? || approval.nil? || expected_application.nil? || expected_environment.nil? || expected_head.nil?
    raise BatchRollbackUsageError, "usage: validate_batch_lease_rollback.rb <receipt.yaml> --approval <approval.yaml> --expected-application <id> --expected-environment <name> --expected-release-head <40hex>"
  end
  raise BatchRollbackUsageError, "expected release head must be lowercase 40-hex" unless expected_head.match?(ROLLBACK_HEX_40)
  [receipt, approval, expected_application, expected_environment, expected_head]
end

def main
  receipt_path, approval_path, expected_application, expected_environment, expected_head = rollback_parse_options
  receipt = rollback_load_yaml(receipt_path)
  approval = rollback_load_yaml(approval_path)
  target, = rollback_validate_approval(approval, expected_application, expected_environment, expected_head)
  release_owner, approval_sha, inventory_sha, reconciliation_sha, = rollback_validate_receipt(
    receipt,
    expected_application,
    expected_environment,
    expected_head,
    approval,
    approval_path,
    File.dirname(File.expand_path(receipt_path)),
  )
  puts "batch-rollback: PASS #{receipt_path} (releaseOwner=#{release_owner}, rollbackTargetHead=#{target}, approval_sha256=#{approval_sha}, inventory_sha256=#{inventory_sha}, reconciliation_sha256=#{reconciliation_sha}, validatedAt=#{Time.now.utc.iso8601})"
rescue BatchRollbackContractError => error
  warn "batch-rollback: FAIL #{error.message}"
  exit 1
rescue BatchRollbackUsageError => error
  warn "batch-rollback: ERROR #{error.message}"
  exit 2
end

main if $PROGRAM_NAME == __FILE__
