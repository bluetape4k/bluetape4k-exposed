require "minitest/autorun"

require_relative "validate_issue_763_tenant_snapshot"

class TenantSnapshotContractTest < Minitest::Test
  METADATA = <<~XML.freeze
    <?xml version="1.0" encoding="UTF-8"?>
    <metadata>
      <groupId>io.github.bluetape4k</groupId>
      <artifactId>bluetape4k-tenant</artifactId>
      <version>2.0.0-SNAPSHOT</version>
      <versioning>
        <snapshot>
          <timestamp>20260901.010203</timestamp>
          <buildNumber>7</buildNumber>
        </snapshot>
        <snapshotVersions>
          <snapshotVersion>
            <extension>pom</extension>
            <value>2.0.0-20260901.010203-7</value>
            <updated>20260901010203</updated>
          </snapshotVersion>
          <snapshotVersion>
            <extension>jar</extension>
            <value>2.0.0-20260901.010203-7</value>
            <updated>20260901010203</updated>
          </snapshotVersion>
        </snapshotVersions>
      </versioning>
    </metadata>
  XML

  def test_manifest_tracks_mutable_snapshot_coordinates_without_content_digests
    manifest = TenantSnapshotContract.load_manifest

    assert_equal "2.0.0-SNAPSHOT", manifest.fetch("version")
    assert_equal %w[bluetape4k-ktor-tenant bluetape4k-tenant], manifest.fetch("artifacts").sort
    refute manifest.key?("timestamp")
    refute manifest.key?("buildNumber")
  end

  def test_resolves_current_timestamped_pom_and_jar_coordinates
    resolved = TenantSnapshotContract.resolve_metadata(
      METADATA,
      group: "io.github.bluetape4k",
      artifact: "bluetape4k-tenant",
      version: "2.0.0-SNAPSHOT",
    )

    assert_equal "2.0.0-20260901.010203-7", resolved.fetch(:version)
    assert_equal({ "pom" => resolved.fetch(:version), "jar" => resolved.fetch(:version) }, resolved.fetch(:artifacts))
  end

  def test_rejects_stable_version_as_snapshot_contract
    error = assert_raises(TenantSnapshotContract::ValidationError) do
      TenantSnapshotContract.validate_manifest(
        "repository" => "https://example.test/repository",
        "group" => "io.github.bluetape4k",
        "version" => "2.0.0",
        "artifacts" => %w[bluetape4k-tenant bluetape4k-ktor-tenant],
      )
    end

    assert_includes error.message, "2.0.0-SNAPSHOT"
  end

  def test_rejects_metadata_for_another_artifact
    error = assert_raises(TenantSnapshotContract::ValidationError) do
      TenantSnapshotContract.resolve_metadata(
        METADATA,
        group: "io.github.bluetape4k",
        artifact: "bluetape4k-ktor-tenant",
        version: "2.0.0-SNAPSHOT",
      )
    end

    assert_includes error.message, "coordinates"
  end

  def test_requires_both_unclassified_pom_and_jar_entries
    metadata_without_jar = METADATA.sub(%r{\s*<snapshotVersion>\s*<extension>jar</extension>.*?</snapshotVersion>}m, "")

    error = assert_raises(TenantSnapshotContract::ValidationError) do
      TenantSnapshotContract.resolve_metadata(
        metadata_without_jar,
        group: "io.github.bluetape4k",
        artifact: "bluetape4k-tenant",
        version: "2.0.0-SNAPSHOT",
      )
    end

    assert_includes error.message, "jar"
  end
end
