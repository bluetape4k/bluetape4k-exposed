require "json"
require "minitest/autorun"
require "tmpdir"
require "yaml"

require_relative "export_manifest"

class ExportManifestTest < Minitest::Test
  def test_emits_recursively_sorted_deterministic_json_with_final_lf
    Dir.mktmpdir("manifest-export") do |root|
      source = File.join(root, "manifest.yaml")
      output = File.join(root, "manifest.json")
      File.write(source, <<~YAML)
        modules: []
        releaseCommit: 0b494a5fd1e083006046764757342b68a397e4c5
        repository: bluetape4k/bluetape4k-exposed
        releaseRef: 1.11.0
        schemaVersion: 2
        title: 한국어 매뉴얼
      YAML
      exporter = ManualDocs::ManifestExporter.new(source_path: source, output_path: output)
      exporter.write
      parsed = JSON.parse(File.read(output))
      assert_equal %w[modules releaseCommit releaseRef repository schemaVersion title], parsed.keys
      assert_equal "bluetape4k/bluetape4k-exposed", parsed.fetch("repository")
      assert_equal "한국어 매뉴얼", parsed.fetch("title")
      assert File.binread(output).end_with?("\n")
      assert exporter.current?
    end
  end

  def test_repository_snapshot_matches_source_and_declared_document_paths
    repository_root = File.expand_path("../..", __dir__)
    source = YAML.safe_load(File.read(File.join(repository_root, "docs/manual/manifest.yaml")))
    generated_path = File.join(repository_root, "docs/manual/generated/manifest.json")
    generated = JSON.parse(File.read(generated_path))

    expected = source.dup
    expected["modules"] = expected.fetch("modules").sort_by { |entry| entry.fetch("id") }
    assert_equal expected, generated

    expected.fetch("modules").each do |entry|
      %w[en ko].each do |locale|
        document = File.join(repository_root, "docs/manual", entry.fetch(locale))
        assert File.file?(document), "missing #{locale} document for #{entry.fetch('id')}: #{document}"
      end
    end
  end
end
