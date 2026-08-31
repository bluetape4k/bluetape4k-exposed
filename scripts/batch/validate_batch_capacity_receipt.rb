#!/usr/bin/env ruby
# frozen_string_literal: true

# 승인된 DB statement budget이 lease heartbeat 및 start burst 공식을
# 감당하는지 dependency-free로 재계산하고 fail-closed 검증한다.

require "digest"
require "time"
require "yaml"

class BatchCapacityUsageError < StandardError; end
class BatchCapacityContractError < StandardError; end

HEX_40 = /\A[0-9a-f]{40}\z/

def capacity_fail(message)
  raise BatchCapacityContractError, message
end

def capacity_load(path)
  capacity_fail("missing file #{path}") unless File.file?(path)
  value = YAML.safe_load(File.read(path, encoding: "UTF-8"), aliases: false)
  capacity_fail("#{path} must contain a mapping") unless value.is_a?(Hash)
  value
rescue Psych::SyntaxError => error
  capacity_fail("invalid YAML #{path}: #{error.message.lines.first.strip}")
rescue SystemCallError => error
  raise BatchCapacityUsageError, "cannot read #{path}: #{error.message}"
end

def capacity_keys(value, required, path)
  capacity_fail("#{path} must be a mapping") unless value.is_a?(Hash)
  missing = required - value.keys
  extra = value.keys - required
  capacity_fail("#{path} is missing: #{missing.join(', ')}") unless missing.empty?
  capacity_fail("#{path} has unsupported fields: #{extra.join(', ')}") unless extra.empty?
end

def capacity_number(value, path, positive: false)
  valid = value.is_a?(Numeric) && !value.is_a?(TrueClass) && !value.is_a?(FalseClass) && value.finite?
  valid &&= value >= 0
  valid &&= value.positive? if positive
  capacity_fail("#{path} must be #{positive ? 'a positive' : 'a non-negative'} finite number") unless valid
  value.to_f
end

def capacity_integer(value, path)
  capacity_fail("#{path} must be a positive integer") unless value.is_a?(Integer) && value.positive?
  value
end

def capacity_validate(receipt, expected_head)
  capacity_keys(receipt, %w[schemaVersion application releaseHead releaseOwner generatedAt inputs calculated], "receipt")
  capacity_fail("receipt.schemaVersion must be 1") unless receipt["schemaVersion"] == 1
  capacity_fail("receipt.application must be a nonblank string") unless receipt["application"].is_a?(String) && !receipt["application"].strip.empty?
  capacity_fail("receipt.releaseHead must match expected release head") unless receipt["releaseHead"].is_a?(String) && receipt["releaseHead"].match?(HEX_40) && receipt["releaseHead"] == expected_head
  capacity_fail("receipt.releaseOwner must be a nonblank string") unless receipt["releaseOwner"].is_a?(String) && !receipt["releaseOwner"].strip.empty?
  begin
    Time.iso8601(receipt["generatedAt"])
  rescue ArgumentError, TypeError
    capacity_fail("receipt.generatedAt must be an ISO-8601 timestamp")
  end

  inputs = receipt["inputs"]
  capacity_keys(inputs, %w[maxConcurrentRunners maxJobStartsPerSecond maxJobStartBurst maxStepStartsPerSecond maxStepStartBurst approvedDbStatementsPerSecond approvedDbBurstBudget], "receipt.inputs")
  runners = capacity_integer(inputs["maxConcurrentRunners"], "receipt.inputs.maxConcurrentRunners")
  job_rate = capacity_number(inputs["maxJobStartsPerSecond"], "receipt.inputs.maxJobStartsPerSecond")
  job_burst = capacity_number(inputs["maxJobStartBurst"], "receipt.inputs.maxJobStartBurst")
  step_rate = capacity_number(inputs["maxStepStartsPerSecond"], "receipt.inputs.maxStepStartsPerSecond")
  step_burst = capacity_number(inputs["maxStepStartBurst"], "receipt.inputs.maxStepStartBurst")
  approved_rate = capacity_number(inputs["approvedDbStatementsPerSecond"], "receipt.inputs.approvedDbStatementsPerSecond", positive: true)
  approved_burst = capacity_number(inputs["approvedDbBurstBudget"], "receipt.inputs.approvedDbBurstBudget", positive: true)

  calculated = receipt["calculated"]
  capacity_keys(calculated, %w[steadyStateStatementsPerSecond oneSecondBurstStatements], "receipt.calculated")
  expected_steady = runners + (7 * job_rate) + (7 * step_rate)
  expected_burst = (10 * runners) + (7 * (job_burst + step_burst + job_rate + step_rate))
  actual_steady = capacity_number(calculated["steadyStateStatementsPerSecond"], "receipt.calculated.steadyStateStatementsPerSecond")
  actual_burst = capacity_number(calculated["oneSecondBurstStatements"], "receipt.calculated.oneSecondBurstStatements")
  capacity_fail("steadyStateStatementsPerSecond does not match the approved formula") unless (actual_steady - expected_steady).abs < 1e-9
  capacity_fail("oneSecondBurstStatements does not match the approved formula") unless (actual_burst - expected_burst).abs < 1e-9
  capacity_fail("steady-state statement budget is exceeded") if actual_steady > approved_rate
  capacity_fail("one-second burst statement budget is exceeded") if actual_burst > approved_burst
  receipt
end

def main
  receipt_path = ARGV.shift
  option = ARGV.shift
  expected_head = ARGV.shift if option == "--expected-release-head"
  raise BatchCapacityUsageError, "usage: validate_batch_capacity_receipt.rb <receipt.yaml> --expected-release-head <40hex>" unless option == "--expected-release-head" && ARGV.empty? && receipt_path && expected_head
  raise BatchCapacityUsageError, "expected release head must be lowercase 40-hex" unless expected_head.match?(HEX_40)
  receipt = capacity_load(receipt_path)
  capacity_validate(receipt, expected_head)
  puts "batch-capacity: PASS #{receipt_path} (sha256=#{Digest::SHA256.file(receipt_path).hexdigest}, validatedAt=#{Time.now.utc.iso8601})"
rescue BatchCapacityContractError => error
  warn "batch-capacity: FAIL #{error.message}"
  exit 1
rescue BatchCapacityUsageError => error
  warn "batch-capacity: ERROR #{error.message}"
  exit 2
end

main if $PROGRAM_NAME == __FILE__
