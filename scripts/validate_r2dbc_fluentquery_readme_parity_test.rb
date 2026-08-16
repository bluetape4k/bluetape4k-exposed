require "minitest/autorun"
require "tmpdir"

require_relative "validate_r2dbc_fluentquery_readme_parity"

class R2dbcCoroutineFluentQueryReadmeParityTest < Minitest::Test
  def test_accepts_localized_prose_with_equivalent_contract
    with_readmes do |english, korean|
      assert_empty validator(english, korean).validate
    end
  end

  def test_rejects_missing_contract_key
    transform = ->(text) { text.sub("<!-- contract-key:cold-flow -->\n", "") }
    with_readmes(korean_transform: transform) do |english, korean|
      assert_includes validator(english, korean).validate,
                      "Korean: missing contract keys: cold-flow"
    end
  end

  def test_rejects_marker_drift
    transform = ->(text) { text.sub("r2dbc-coroutine-fluent-query:END", "r2dbc-coroutine-fluent-query:END-DRIFT") }
    with_readmes(korean_transform: transform) do |english, korean|
      assert_includes validator(english, korean).validate,
                      "#{korean}: expected exactly one R2DBC coroutine FluentQuery marker pair"
    end
  end

  def test_rejects_code_fence_drift
    transform = ->(text) { text.sub("```kotlin", "```java") }
    with_readmes(korean_transform: transform) do |english, korean|
      assert_includes validator(english, korean).validate,
                      "R2DBC coroutine FluentQuery README code fence languages differ"
    end
  end

  def test_rejects_inline_identifier_drift
    transform = ->(text) { text.sub("`Flow`", "`Flux`") }
    with_readmes(korean_transform: transform) do |english, korean|
      assert_includes validator(english, korean).validate,
                      "R2DBC coroutine FluentQuery README inline technical identifiers differ"
    end
  end

  private

  def with_readmes(english_transform: ->(text) { text }, korean_transform: ->(text) { text })
    Dir.mktmpdir("r2dbc-readme-parity") do |root|
      english = File.join(root, "README.md")
      korean = File.join(root, "README.ko.md")
      File.write(english, english_transform.call(fixture("English")))
      File.write(korean, korean_transform.call(fixture("한국어")))
      yield english, korean
    end
  end

  def fixture(locale_title)
    markers = R2dbcCoroutineFluentQueryReadmeParity::REQUIRED_CONTRACT_KEYS.map do |key|
      "<!-- contract-key:#{key} -->"
    end.join("\n")

    <<~MARKDOWN
      # Before
      <!-- r2dbc-coroutine-fluent-query:START -->
      ### #{locale_title}

      #{markers}

      Use `Example`, `Flow`, and `suspend`.

      ```kotlin
      repository.findBy(example) { it.all() }
      ```
      <!-- r2dbc-coroutine-fluent-query:END -->
      # After
    MARKDOWN
  end

  def validator(english, korean)
    R2dbcCoroutineFluentQueryReadmeParity::Validator.new(english, korean)
  end
end
