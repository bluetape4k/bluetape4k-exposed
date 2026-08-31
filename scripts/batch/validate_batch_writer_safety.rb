#!/usr/bin/env ruby
# frozen_string_literal: true

# 의존성 없이 application writer safety receipt와 writer inventory를 비교한다.

require "digest"
require "time"
require "uri"
require "yaml"

class BatchWriterUsageError < StandardError; end
class BatchWriterContractError < StandardError; end

HEX_40 = /\A[0-9a-f]{40}\z/
HEX_64 = /\A[0-9a-f]{64}\z/
SECRET_PATTERN = /(?:password|passwd|secret|token|credential|api[_-]?key|bearer|private[_-]?key)/i
EVIDENCE_FIELDS = %w[idempotencyKey remoteFencing transactionalOutbox].freeze

def writer_fail(message)
  raise BatchWriterContractError, message
end

def writer_load_yaml(path)
  writer_fail("missing file #{path}") unless File.file?(path)
  value = YAML.safe_load(File.read(path, encoding: "UTF-8"), aliases: false)
  writer_fail("#{path} must contain a mapping") unless value.is_a?(Hash)
  value
rescue Psych::SyntaxError => error
  writer_fail("invalid YAML #{path}: #{error.message.lines.first.strip}")
rescue SystemCallError => error
  raise BatchWriterUsageError, "cannot read #{path}: #{error.message}"
end

def writer_nonblank(value, path)
  writer_fail("#{path} must be a nonblank string") unless value.is_a?(String) && !value.strip.empty?
  value
end

def writer_timestamp(value, path)
  writer_nonblank(value, path)
  Time.iso8601(value)
rescue ArgumentError
  writer_fail("#{path} must be an ISO-8601 timestamp")
end

def writer_reference(value, path)
  writer_fail("#{path} must be a mapping") unless value.is_a?(Hash)
  keys = value.keys
  writer_fail("#{path} is missing uri or sha256") unless keys.include?("uri") && keys.include?("sha256")
  writer_fail("#{path} has unsupported fields") unless (keys - %w[uri sha256]).empty?
  uri_text = writer_nonblank(value["uri"], "#{path}.uri")
  uri = URI.parse(uri_text)
  valid = uri.scheme == "restricted" && uri.host && !uri.host.empty? && uri.path && uri.path.length > 1
  writer_fail("#{path}.uri must be a restricted opaque reference") unless valid
  writer_fail("#{path}.uri must not contain credentials, query, or fragment") if uri.userinfo || uri.query || uri.fragment
  writer_fail("#{path}.uri contains a secret-like path") if uri.path.match?(SECRET_PATTERN)
  writer_fail("#{path}.sha256 must be lowercase SHA-256") unless value["sha256"].is_a?(String) && value["sha256"].match?(HEX_64)
rescue URI::InvalidURIError
  writer_fail("#{path}.uri must be a valid restricted reference")
end

def writer_validate_keys(value, required, allowed, path)
  writer_fail("#{path} must be a mapping") unless value.is_a?(Hash)
  missing = required - value.keys
  extra = value.keys - allowed
  writer_fail("#{path} is missing: #{missing.join(', ')}") unless missing.empty?
  writer_fail("#{path} has unsupported fields: #{extra.join(', ')}") unless extra.empty?
end

def writer_validate_header(data, path, expected_head, inventory: false)
  required = inventory ? %w[schemaVersion application releaseHead releaseOwner generatedAt configChecksum writers] : %w[schemaVersion application releaseHead releaseOwner generatedAt writers]
  allowed = required
  writer_validate_keys(data, required, allowed, path)
  writer_fail("#{path}.schemaVersion must be 1") unless data["schemaVersion"] == 1
  writer_nonblank(data["application"], "#{path}.application")
  writer_fail("#{path}.releaseHead must match expected release head") unless data["releaseHead"].is_a?(String) && data["releaseHead"].match?(HEX_40) && data["releaseHead"] == expected_head
  writer_nonblank(data["releaseOwner"], "#{path}.releaseOwner")
  writer_timestamp(data["generatedAt"], "#{path}.generatedAt")
  if inventory
    writer_fail("#{path}.configChecksum must be lowercase SHA-256") unless data["configChecksum"].is_a?(String) && data["configChecksum"].match?(HEX_64)
  end
end

def writer_validate_receipt(data, expected_head)
  writer_validate_header(data, "receipt", expected_head)
  writers = data["writers"]
  writer_fail("receipt.writers must contain at least one writer") unless writers.is_a?(Array) && !writers.empty?
  ids = writers.map.with_index do |writer, index|
    path = "receipt.writers[#{index}]"
    writer_validate_keys(writer, %w[id sideEffect recoveryReceipt reviewedAt], %w[id sideEffect idempotencyKey remoteFencing transactionalOutbox recoveryReceipt reviewedAt], path)
    id = writer_nonblank(writer["id"], "#{path}.id")
    writer_fail("#{path}.id contains unsafe characters") unless id.match?(/\A[a-zA-Z0-9][a-zA-Z0-9._:-]*\z/)
    writer_nonblank(writer["sideEffect"], "#{path}.sideEffect")
    writer_timestamp(writer["reviewedAt"], "#{path}.reviewedAt")
    EVIDENCE_FIELDS.each do |field|
      writer_reference(writer[field], "#{path}.#{field}") if writer.key?(field)
    end
    writer_fail("#{path} needs idempotencyKey, remoteFencing, or transactionalOutbox evidence") unless EVIDENCE_FIELDS.any? { |field| writer.key?(field) }
    writer_reference(writer["recoveryReceipt"], "#{path}.recoveryReceipt")
    id
  end
  writer_fail("receipt.writers contains duplicate ids") unless ids.uniq.length == ids.length
  ids
end

def writer_validate_inventory(data, expected_head)
  writer_validate_header(data, "inventory", expected_head, inventory: true)
  writers = data["writers"]
  writer_fail("inventory.writers must contain at least one writer") unless writers.is_a?(Array) && !writers.empty?
  ids = writers.map.with_index do |writer, index|
    path = "inventory.writers[#{index}]"
    writer_validate_keys(writer, %w[id], %w[id], path)
    id = writer_nonblank(writer["id"], "#{path}.id")
    writer_fail("#{path}.id contains unsafe characters") unless id.match?(/\A[a-zA-Z0-9][a-zA-Z0-9._:-]*\z/)
    id
  end
  writer_fail("inventory.writers contains duplicate ids") unless ids.uniq.length == ids.length
  ids
end

def parse_writer_options
  receipt_path = ARGV.shift
  inventory_path = ARGV.shift
  expected_head = nil
  until ARGV.empty?
    option = ARGV.shift
    if option == "--expected-release-head"
      expected_head = ARGV.shift
    else
      raise BatchWriterUsageError, "unknown option #{option}"
    end
  end
  raise BatchWriterUsageError, "usage: validate_batch_writer_safety.rb <receipt.yaml> <inventory.yaml> --expected-release-head <40hex>" if receipt_path.nil? || inventory_path.nil? || expected_head.nil?
  raise BatchWriterUsageError, "expected release head must be lowercase 40-hex" unless expected_head.match?(HEX_40)
  [receipt_path, inventory_path, expected_head]
end

def main
  receipt_path, inventory_path, expected_head = parse_writer_options
  receipt = writer_load_yaml(receipt_path)
  inventory = writer_load_yaml(inventory_path)
  receipt_ids = writer_validate_receipt(receipt, expected_head)
  inventory_ids = writer_validate_inventory(inventory, expected_head)
  writer_fail("receipt and inventory application differ") unless receipt["application"] == inventory["application"]
  writer_fail("receipt and inventory releaseOwner differ") unless receipt["releaseOwner"] == inventory["releaseOwner"]
  writer_fail("receipt and inventory writer id sets differ") unless receipt_ids.sort == inventory_ids.sort
  puts "batch-writer-safety: PASS #{receipt_path} #{inventory_path} (receipt_sha256=#{Digest::SHA256.file(receipt_path).hexdigest}, inventory_sha256=#{Digest::SHA256.file(inventory_path).hexdigest})"
rescue BatchWriterContractError => error
  warn "batch-writer-safety: FAIL #{error.message}"
  exit 1
rescue BatchWriterUsageError => error
  warn "batch-writer-safety: ERROR #{error.message}"
  exit 2
end

main if $PROGRAM_NAME == __FILE__
