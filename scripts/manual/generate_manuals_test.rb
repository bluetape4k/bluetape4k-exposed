require "fileutils"
require "minitest/autorun"
require "tmpdir"
require "yaml"

require_relative "generate_manuals"

class GenerateManualsTest < Minitest::Test
  def test_scaffolds_from_kind_template_without_overwriting_authored_files
    Dir.mktmpdir("manual-generator") do |root|
      FileUtils.mkdir_p(File.join(root, "docs/manual/templates"))
      File.write(File.join(root, "docs/manual/templates/module.md"), "# {{id}}\n{{required_sections}}")
      File.write(File.join(root, "docs/manual/templates/example.md"), "example {{id}}\n{{required_sections}}")
      File.write(File.join(root, "docs/manual/templates/benchmark.md"), "benchmark {{id}}\n{{required_sections}}")
      manifest = { "modules" => [{
        "id" => "core", "kind" => "library", "en" => "en/modules/core.md", "ko" => "ko/modules/core.md",
      }] }
      FileUtils.mkdir_p(File.join(root, "docs/manual"))
      File.write(File.join(root, "docs/manual/manifest.yaml"), YAML.dump(manifest))
      generator = ManualDocs::ManualGenerator.new(repository_root: root, manifest_path: File.join(root, "docs/manual/manifest.yaml"))
      generated = generator.generate
      assert_equal 2, generated.length
      assert_includes File.read(File.join(root, "docs/manual/en/modules/core.md")), "# core"
      File.write(File.join(root, "docs/manual/en/modules/core.md"), "authored\n")
      generator.generate
      assert_equal "authored\n", File.read(File.join(root, "docs/manual/en/modules/core.md"))
    end
  end

  def test_rejects_an_unsafe_output_path
    Dir.mktmpdir("manual-generator") do |root|
      FileUtils.mkdir_p(File.join(root, "docs/manual/templates"))
      File.write(File.join(root, "docs/manual/templates/module.md"), "{{id}}")
      File.write(File.join(root, "docs/manual/manifest.yaml"), YAML.dump({ "modules" => [{
        "id" => "escape", "kind" => "library", "en" => "../../../outside.md", "ko" => "ko/escape.md",
      }] }))
      generator = ManualDocs::ManualGenerator.new(repository_root: root, manifest_path: File.join(root, "docs/manual/manifest.yaml"))
      error = assert_raises(ArgumentError) { generator.generate }
      assert_equal "unsafe manual output path: ../../../outside.md", error.message
    end
  end

  def test_rejects_a_symlinked_output_directory_that_escapes_the_manual_root
    Dir.mktmpdir("manual-generator") do |root|
      Dir.mktmpdir("outside-generator") do |outside|
        FileUtils.mkdir_p(File.join(root, "docs/manual/templates"))
        File.write(File.join(root, "docs/manual/templates/module.md"), "{{id}}")
        File.symlink(outside, File.join(root, "docs/manual/en"))
        File.write(File.join(root, "docs/manual/manifest.yaml"), YAML.dump({ "modules" => [{
          "id" => "escape", "kind" => "library", "en" => "en/escape.md", "ko" => "ko/escape.md",
        }] }))
        generator = ManualDocs::ManualGenerator.new(repository_root: root, manifest_path: File.join(root, "docs/manual/manifest.yaml"))
        error = assert_raises(ArgumentError) { generator.generate }
        assert_equal "unsafe manual output path: en/escape.md", error.message
      end
    end
  end

  def test_rejects_a_dangling_output_symlink_without_creating_the_external_file
    Dir.mktmpdir("manual-generator") do |root|
      Dir.mktmpdir("outside-generator") do |outside|
        generator = prepare_generator(root, en: "en/core.md")
        external = File.join(outside, "not-created.md")
        File.symlink(external, File.join(root, "docs/manual/en/core.md"))

        error = assert_raises(ArgumentError) { generator.generate }

        assert_equal "unsafe manual output path: en/core.md", error.message
        refute File.exist?(external)
      end
    end
  end

  def test_rejects_an_existing_output_symlink_to_an_external_file
    Dir.mktmpdir("manual-generator") do |root|
      Dir.mktmpdir("outside-generator") do |outside|
        generator = prepare_generator(root, en: "en/core.md")
        external = File.join(outside, "authored.md")
        File.write(external, "external\n")
        File.symlink(external, File.join(root, "docs/manual/en/core.md"))

        error = assert_raises(ArgumentError) { generator.generate }

        assert_equal "unsafe manual output path: en/core.md", error.message
        assert_equal "external\n", File.read(external)
      end
    end
  end

  private

  def prepare_generator(root, en:)
    FileUtils.mkdir_p(File.join(root, "docs/manual/templates"))
    FileUtils.mkdir_p(File.join(root, "docs/manual/en"))
    File.write(File.join(root, "docs/manual/templates/module.md"), "{{id}}\n{{required_sections}}")
    File.write(File.join(root, "docs/manual/manifest.yaml"), YAML.dump({ "modules" => [{
      "id" => "core", "kind" => "library", "en" => en, "ko" => "ko/core.md",
    }] }))
    ManualDocs::ManualGenerator.new(
      repository_root: root, manifest_path: File.join(root, "docs/manual/manifest.yaml"),
    )
  end
end
