require "minitest/autorun"
require "tmpdir"

require_relative "validate_module_readme_parity"

class ModuleReadmeParityTest < Minitest::Test
  def test_accepts_localized_prose_with_equivalent_contract
    with_readmes do |english, korean|
      assert_empty validator(english, korean).validate
    end
  end

  def test_rejects_missing_contract_key_even_when_both_locales_match
    transform = ->(text) { text.sub("<!-- contract-key:attached-probe -->\n", "") }
    with_readmes(english_transform: transform, korean_transform: transform) do |english, korean|
      errors = validator(english, korean).validate

      assert_includes errors, "English: missing contract keys: attached-probe"
      assert_includes errors, "Korean: missing contract keys: attached-probe"
    end
  end

  def test_rejects_duplicate_section_markers
    transform = ->(text) { text + "\n<!-- jdbc-fluent-query:START -->\n" }
    with_readmes(korean_transform: transform) do |english, korean|
      assert_includes validator(english, korean).validate,
                      "#{korean}: expected exactly one JDBC FluentQuery marker pair"
    end
  end

  def test_rejects_code_fence_drift
    transform = ->(text) { text.sub("```kotlin", "```java") }
    with_readmes(korean_transform: transform) do |english, korean|
      assert_includes validator(english, korean).validate,
                      "JDBC FluentQuery README code fence languages differ"
    end
  end

  def test_rejects_inline_identifier_drift
    transform = ->(text) { text.sub("`stream`", "`sequence`") }
    with_readmes(korean_transform: transform) do |english, korean|
      assert_includes validator(english, korean).validate,
                      "JDBC FluentQuery README inline technical identifiers differ"
    end
  end

  def test_rejects_missing_local_link_target
    transform = ->(text) { text.sub("contract.kt", "missing.kt") }
    with_readmes(korean_transform: transform) do |english, korean|
      errors = validator(english, korean).validate

      assert_includes errors, "Korean: missing local link target ./contract/missing.kt"
      assert_includes errors, "JDBC FluentQuery README local links differ"
    end
  end

  private

  def with_readmes(english_transform: ->(text) { text }, korean_transform: ->(text) { text })
    Dir.mktmpdir("module-readme-parity") do |root|
      english = File.join(root, "README.md")
      korean = File.join(root, "README.ko.md")
      contract_dir = File.join(root, "contract")
      Dir.mkdir(contract_dir)
      File.write(File.join(contract_dir, "contract.kt"), "interface Contract")
      File.write(english, english_transform.call(fixture("English")))
      File.write(korean, korean_transform.call(fixture("한국어")))
      yield english, korean
    end
  end

  def fixture(locale_title)
    markers = ModuleReadmeParity::REQUIRED_CONTRACT_KEYS.map do |key|
      "<!-- contract-key:#{key} -->"
    end.join("\n")

    <<~MARKDOWN
      # Before
      <!-- jdbc-fluent-query:START -->
      ### #{locale_title}

      #{markers}

      Use `Example`, `stream`, and `use`.

      ```kotlin
      repository.findBy(example) { it.stream() }
      ```

      [Contract](./contract/contract.kt)
      <!-- jdbc-fluent-query:END -->
      # After
    MARKDOWN
  end

  def validator(english, korean)
    ModuleReadmeParity::Validator.new(english, korean)
  end
end
