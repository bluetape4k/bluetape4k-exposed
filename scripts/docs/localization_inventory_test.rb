# frozen_string_literal: true

require "json"
require "minitest/autorun"
require "tmpdir"
require "fileutils"

require_relative "localization_inventory"

class LocalizationInventoryTest < Minitest::Test
  def test_classifies_docs_and_kotlin_hotspots
    with_repo do |root|
      write(root, "README.md", "# English README\n")
      write(root, "AGENTS.md", "# Agent guidance remains English\n")
      write(root, "docs/manual/en/index.md", "# Manual\n")
      write(root, "docs/manual/ko/index.md", "# 매뉴얼\n")
      write(root, "docs/lessons/example.md", "# Lesson\n\nEnglish prose.\n")
      write(root, "src/main/kotlin/Sample.kt", <<~KOTLIN)
        /** English KDoc for public API. */
        internal data class Sample(
            val id: String,
        )
      KOTLIN
      git(root, "add", ".")
      git(root, "commit", "-m", "seed")

      result = LocalizationInventory::Scanner.new(root).scan

      assert_equal 5, result.tracked_docs
      assert_equal 1, result.tracked_kotlin
      assert_equal ["docs/lessons/example.md"], result.single_language_docs
      assert_equal ["src/main/kotlin/Sample.kt"], result.english_kdoc_files
      assert_equal ["src/main/kotlin/Sample.kt"], result.internal_class_files
      assert_equal ["src/main/kotlin/Sample.kt"], result.data_class_files

      excluded = result.excluded_docs.to_h { |row| [row.path, row.reason] }
      assert_equal "README excluded by epic scope", excluded.fetch("README.md")
      assert_equal "LLM/OMX operating surface kept in English", excluded.fetch("AGENTS.md")
      assert_equal "bilingual manual pair kept as parity target", excluded.fetch("docs/manual/en/index.md")
      assert_equal "bilingual manual pair kept as parity target", excluded.fetch("docs/manual/ko/index.md")
    end
  end

  def test_json_shape_contains_counts_and_paths
    with_repo do |root|
      write(root, "docs/review/review.md", "# Review\n")
      write(root, "src/main/kotlin/Model.kt", "data class Model(val name: String)\n")
      git(root, "add", ".")
      git(root, "commit", "-m", "seed")

      result = LocalizationInventory::Scanner.new(root).scan.to_h

      assert_equal root, result.fetch("repositoryRoot")
      assert_equal 1, result.fetch("counts").fetch("singleLanguageDocs")
      assert_equal ["docs/review/review.md"], result.fetch("singleLanguageDocs")
      assert_equal ["src/main/kotlin/Model.kt"], result.fetch("dataClassFiles")
    end
  end

  private

  def with_repo
    Dir.mktmpdir("localization-inventory") do |root|
      git(root, "init", "-q")
      git(root, "config", "user.email", "test@example.test")
      git(root, "config", "user.name", "Test User")
      yield root
    end
  end

  def write(root, path, content)
    absolute = File.join(root, path)
    FileUtils.mkdir_p(File.dirname(absolute))
    File.write(absolute, content)
  end

  def git(root, *args)
    system("git", "-C", root, *args, exception: true, out: File::NULL)
  end
end
