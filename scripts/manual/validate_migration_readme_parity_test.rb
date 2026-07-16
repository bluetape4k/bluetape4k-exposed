require "minitest/autorun"
require "tmpdir"

require_relative "validate_migration_readme_parity"

class MigrationReadmeParityTest < Minitest::Test
  def test_accepts_localized_headings_with_equivalent_semantics
    with_readmes do |english, korean|
      assert_empty validator(english, korean).validate
    end
  end

  def test_rejects_code_fence_and_command_drift
    with_readmes(korean_transform: ->(text) { text.sub("./gradlew verify", "./gradlew changed") }) do |english, korean|
      errors = validator(english, korean).validate

      assert_includes errors, "README migration shell/Kotlin fences differ"
      assert_includes errors, "README migration commands differ"
    end
  end

  def test_rejects_table_row_key_drift
    with_readmes(korean_transform: ->(text) { text.sub("`Gradle plugin`", "`Plugin`") }) do |english, korean|
      assert_includes validator(english, korean).validate, "README migration table row keys differ"
    end
  end

  def test_rejects_url_drift
    transform = ->(text) { text.sub("https://example.test/migrations", "https://example.test/changed") }
    with_readmes(korean_transform: transform) do |english, korean|
      assert_includes validator(english, korean).validate, "README migration URLs differ"
    end
  end

  def test_rejects_missing_required_marker_even_when_both_locales_match
    transform = lambda do |text|
      text.sub("<!-- migration-guide:warning:credentials -->\n", "")
    end
    with_readmes(english_transform: transform, korean_transform: transform) do |english, korean|
      errors = validator(english, korean).validate

      assert_includes errors, "English: missing warning markers: credentials"
      assert_includes errors, "Korean: missing warning markers: credentials"
    end
  end

  def test_rejects_duplicate_section_markers
    transform = ->(text) { text + "\n<!-- migration-guide:start -->\n" }
    with_readmes(korean_transform: transform) do |english, korean|
      assert_includes validator(english, korean).validate,
                      "#{korean}: expected exactly one migration guide marker pair"
    end
  end

  private

  def with_readmes(english_transform: ->(text) { text }, korean_transform: ->(text) { text })
    Dir.mktmpdir("migration-readme-parity") do |root|
      english = File.join(root, "README.md")
      korean = File.join(root, "README.ko.md")
      File.write(english, english_transform.call(fixture("English")))
      File.write(korean, korean_transform.call(fixture("한국어")))
      yield english, korean
    end
  end

  def fixture(locale_title)
    headings = MigrationReadmeParity::REQUIRED_MARKERS.fetch("heading")
    warnings = MigrationReadmeParity::REQUIRED_MARKERS.fetch("warning")
    tables = MigrationReadmeParity::REQUIRED_MARKERS.fetch("table")

    body = headings.map.with_index do |key, index|
      level = index.zero? ? "###" : "####"
      "<!-- migration-guide:heading:#{key} -->\n#{level} #{locale_title} #{key}"
    end.join("\n\n")
    warning_markers = warnings.map { |key| "<!-- migration-guide:warning:#{key} -->" }.join("\n")
    table_markers = tables.map { |key| "<!-- migration-guide:table:#{key} -->" }.join("\n")

    <<~MARKDOWN
      # Before
      <!-- migration-guide:start -->
      #{body}

      #{warning_markers}
      #{table_markers}

      | Surface | Meaning |
      |---|---|
      | `Gradle plugin` | Value |

      ```kotlin
      plugins { alias(bt4k.plugins.exposed.plugin) }
      ```

      ```bash
      ./gradlew verify migrationDriftTest generateMigrations --stacktrace --info
      ```

      [Migration](https://example.test/migrations)
      <!-- migration-guide:end -->
      # After
    MARKDOWN
  end

  def validator(english, korean)
    MigrationReadmeParity::Validator.new(english, korean)
  end
end
