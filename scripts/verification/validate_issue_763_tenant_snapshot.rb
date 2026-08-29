#!/usr/bin/env ruby
# frozen_string_literal: true

require "digest"
require "json"
require "open-uri"
require "rexml/document"

MANIFEST_PATH = File.expand_path("issue-763-tenant-snapshot.json", __dir__)

def fail_with(message)
  warn "issue-763 tenant snapshot: FAIL: #{message}"
  exit 1
end

manifest_path = ARGV.fetch(0, MANIFEST_PATH)
manifest = JSON.parse(File.read(manifest_path))
repository = manifest.fetch("repository").sub(%r{/$}, "")
group = manifest.fetch("group").split(".").join("/")
version = manifest.fetch("version")
timestamp = manifest.fetch("timestamp")
build_number = manifest.fetch("buildNumber").to_s
expected_artifacts = %w[bluetape4k-ktor-tenant bluetape4k-tenant].sort
actual_artifacts = manifest.fetch("artifacts").keys.map(&:to_s).sort

unless version == "2.0.0-SNAPSHOT"
  fail_with("manifest version must remain 2.0.0-SNAPSHOT")
end
unless timestamp.match?(/\A\d{8}\.\d{6}\z/) && build_number.match?(/\A\d+\z/)
  fail_with("manifest timestamp/buildNumber is not immutable snapshot metadata")
end
fail_with("manifest artifacts must be exactly #{expected_artifacts.join(", ")}") unless actual_artifacts == expected_artifacts

def fetch_bytes(url)
  URI.open(url, "User-Agent" => "bluetape4k-exposed-issue-763-verifier", &:read)
rescue OpenURI::HTTPError => e
  fail_with("#{url} returned #{e.message}")
rescue StandardError => e
  fail_with("#{url} could not be read: #{e.class}: #{e.message}")
end

manifest.fetch("artifacts").each do |artifact, expected|
  required_digests = %w[metadataSha256 pomSha256 jarSha256]
  fail_with("#{artifact} digest manifest is incomplete") unless expected.is_a?(Hash) && expected.keys.sort == required_digests.sort
  required_digests.each do |field|
    value = expected.fetch(field).to_s
    fail_with("#{artifact} #{field} is not a SHA-256 digest") unless value.match?(/\A[0-9a-f]{64}\z/)
  end
  artifact_root = "#{repository}/#{group}/#{artifact}/#{version}"
  metadata_url = "#{artifact_root}/maven-metadata.xml"
  metadata = fetch_bytes(metadata_url)
  metadata_sha = Digest::SHA256.hexdigest(metadata)
  fail_with("#{artifact} metadata sha256=#{metadata_sha}, expected #{expected.fetch("metadataSha256")}") unless metadata_sha == expected.fetch("metadataSha256")

  xml = REXML::Document.new(metadata)
  observed_version = xml.elements["metadata/versioning/snapshotVersions/snapshotVersion[extension='pom']/value"]&.text
  observed_timestamp = xml.elements["metadata/versioning/snapshot/timestamp"]&.text
  observed_build = xml.elements["metadata/versioning/snapshot/buildNumber"]&.text
  expected_version = "#{version.delete_suffix("-SNAPSHOT")}-#{timestamp}-#{build_number}"
  fail_with("#{artifact} metadata timestamp/build/version mismatch") unless observed_timestamp == timestamp && observed_build == build_number && observed_version == expected_version

  %w[pom jar].each do |extension|
    expected_sha = expected.fetch("#{extension}Sha256")
    url = "#{artifact_root}/#{artifact}-#{expected_version}.#{extension}"
    digest = Digest::SHA256.hexdigest(fetch_bytes(url))
    fail_with("#{artifact} #{extension} sha256=#{digest}, expected #{expected_sha}") unless digest == expected_sha
  end
end

puts "issue-763 tenant snapshot: PASS timestamp=#{timestamp} build=#{build_number} artifacts=#{manifest.fetch("artifacts").length}"
