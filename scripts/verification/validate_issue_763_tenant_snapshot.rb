#!/usr/bin/env ruby
# frozen_string_literal: true

require "json"
require "open-uri"
require "rexml/document"

module TenantSnapshotContract
  MANIFEST_PATH = File.expand_path("issue-763-tenant-snapshot.json", __dir__)
  EXPECTED_ARTIFACTS = %w[bluetape4k-ktor-tenant bluetape4k-tenant].freeze

  class ValidationError < StandardError; end

  module_function

  def load_manifest(path = MANIFEST_PATH)
    validate_manifest(JSON.parse(File.read(path)))
  end

  def validate_manifest(manifest)
    version = manifest.fetch("version")
    artifacts = manifest.fetch("artifacts")

    raise ValidationError, "manifest version must remain 2.0.0-SNAPSHOT" unless version == "2.0.0-SNAPSHOT"
    unless artifacts.is_a?(Array) && artifacts.map(&:to_s).sort == EXPECTED_ARTIFACTS
      raise ValidationError, "manifest artifacts must be exactly #{EXPECTED_ARTIFACTS.join(", ")}"
    end

    manifest
  rescue KeyError => e
    raise ValidationError, "manifest is incomplete: #{e.message}"
  end

  def resolve_metadata(metadata, group:, artifact:, version:)
    document = REXML::Document.new(metadata)
    observed_coordinates = %w[groupId artifactId version].map do |field|
      document.elements["metadata/#{field}"]&.text
    end
    expected_coordinates = [group, artifact, version]
    unless observed_coordinates == expected_coordinates
      raise ValidationError,
            "#{artifact} metadata coordinates=#{observed_coordinates.join(":")}, expected #{expected_coordinates.join(":")}"
    end

    timestamp = document.elements["metadata/versioning/snapshot/timestamp"]&.text
    build_number = document.elements["metadata/versioning/snapshot/buildNumber"]&.text
    unless timestamp&.match?(/\A\d{8}\.\d{6}\z/) && build_number&.match?(/\A\d+\z/)
      raise ValidationError, "#{artifact} metadata timestamp/buildNumber is invalid"
    end

    resolved_version = "#{version.delete_suffix("-SNAPSHOT")}-#{timestamp}-#{build_number}"
    resolved_artifacts = {}
    document.elements.each("metadata/versioning/snapshotVersions/snapshotVersion") do |entry|
      classifier = entry.elements["classifier"]&.text
      next unless classifier.nil? || classifier.empty?

      extension = entry.elements["extension"]&.text
      resolved_artifacts[extension] = entry.elements["value"]&.text if %w[pom jar].include?(extension)
    end
    %w[pom jar].each do |extension|
      unless resolved_artifacts[extension] == resolved_version
        raise ValidationError, "#{artifact} current unclassified #{extension} snapshot is missing or inconsistent"
      end
    end

    {
      version: resolved_version,
      timestamp: timestamp,
      build_number: build_number,
      artifacts: resolved_artifacts,
    }
  rescue REXML::ParseException => e
    raise ValidationError, "#{artifact} metadata XML is invalid: #{e.message}"
  end

  def fetch_bytes(url)
    URI.open(url, "User-Agent" => "bluetape4k-exposed-issue-763-verifier", &:read)
  rescue OpenURI::HTTPError => e
    raise ValidationError, "#{url} returned #{e.message}"
  rescue StandardError => e
    raise ValidationError, "#{url} could not be read: #{e.class}: #{e.message}"
  end

  def validate_remote(manifest, fetcher: method(:fetch_bytes))
    repository = manifest.fetch("repository").sub(%r{/$}, "")
    group = manifest.fetch("group")
    group_path = group.split(".").join("/")
    version = manifest.fetch("version")

    manifest.fetch("artifacts").to_h do |artifact|
      artifact_root = "#{repository}/#{group_path}/#{artifact}/#{version}"
      metadata = fetcher.call("#{artifact_root}/maven-metadata.xml")
      resolved = resolve_metadata(metadata, group: group, artifact: artifact, version: version)

      resolved.fetch(:artifacts).each do |extension, resolved_version|
        bytes = fetcher.call("#{artifact_root}/#{artifact}-#{resolved_version}.#{extension}")
        raise ValidationError, "#{artifact} #{extension} is empty" if bytes.empty?
      end

      [artifact, resolved]
    end
  rescue KeyError => e
    raise ValidationError, "manifest is incomplete: #{e.message}"
  end
end

if $PROGRAM_NAME == __FILE__
  begin
    manifest = TenantSnapshotContract.load_manifest(ARGV.fetch(0, TenantSnapshotContract::MANIFEST_PATH))
    resolved = TenantSnapshotContract.validate_remote(manifest)
    receipts = resolved.sort.map do |artifact, entry|
      "#{artifact}=#{entry.fetch(:version)}"
    end
    puts "issue-763 tenant snapshot: PASS #{receipts.join(" ")}"
  rescue TenantSnapshotContract::ValidationError => e
    warn "issue-763 tenant snapshot: FAIL: #{e.message}"
    exit 1
  end
end
