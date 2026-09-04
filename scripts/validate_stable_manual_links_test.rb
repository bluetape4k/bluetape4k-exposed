require "minitest/autorun"
require "tmpdir"

require_relative "validate_stable_manual_links"

class StableManualLinksTest < Minitest::Test
  def test_accepts_manifest_backed_english_and_korean_links
    with_fixture do |manifest, english, korean|
      validator = StableManualLinks::Validator.new(
        manifest,
        [english, korean],
        http: true,
        http_checker: ->(_uri) { [200, nil] },
      )

      assert_empty validator.validate
    end
  end

  def test_rejects_version_drift
    with_fixture do |manifest, english, korean|
      File.write(english, File.read(english).sub("/2.0/", "/1.12/"))

      errors = StableManualLinks::Validator.new(manifest, [english, korean]).validate

      assert errors.any? { |error| error.include?("expected 2.0 from manifest") }
    end
  end

  def test_rejects_path_missing_from_manifest
    with_fixture do |manifest, english, korean|
      File.write(english, File.read(english).sub("getting-started", "missing"))

      errors = StableManualLinks::Validator.new(manifest, [english, korean]).validate

      assert errors.any? { |error| error.include?("not present in the central manifest") }
    end
  end

  def test_rejects_http_failure
    with_fixture do |manifest, english, korean|
      validator = StableManualLinks::Validator.new(
        manifest,
        [english, korean],
        http: true,
        http_checker: ->(_uri) { [404, nil] },
      )

      errors = validator.validate

      assert errors.any? { |error| error.include?("HTTP validation") }
    end
  end

  def test_rejects_locale_link_drift
    with_fixture do |manifest, english, korean|
      File.write(korean, File.read(korean).sub("/guides/learning-path/", "/getting-started/"))

      errors = StableManualLinks::Validator.new(manifest, [english, korean]).validate

      assert errors.any? { |error| error.include?("README locale links differ") }
    end
  end

  private

  def with_fixture
    Dir.mktmpdir("stable-manual-links") do |root|
      manifest = File.join(root, "manifest.yaml")
      english = File.join(root, "README.md")
      korean = File.join(root, "README.ko.md")
      File.write(manifest, <<~YAML)
        releaseRef: "2.0.0"
        publication:
          manualVersion: "2.0"
        overview:
          documents:
            en:
              - "en/index.md"
              - "en/getting-started.md"
              - "en/guides/learning-path.md"
            ko:
              - "ko/index.md"
              - "ko/getting-started.md"
              - "ko/guides/learning-path.md"
        modules:
          - en: "en/modules/bluetape4k-exposed-ktor-tenant-jdbc.md"
            ko: "ko/modules/bluetape4k-exposed-ktor-tenant-jdbc.md"
      YAML
      links = <<~MARKDOWN
        [Overview](https://bluetape4k.github.io/manual/bluetape4k-exposed/2.0/)
        [Getting started](https://bluetape4k.github.io/manual/bluetape4k-exposed/2.0/getting-started/)
        [Learning path](https://bluetape4k.github.io/manual/bluetape4k-exposed/2.0/guides/learning-path/)
        [Tenant](https://bluetape4k.github.io/manual/bluetape4k-exposed/2.0/modules/bluetape4k-exposed-ktor-tenant-jdbc/)
      MARKDOWN
      File.write(english, links)
      File.write(korean, links.gsub("https://bluetape4k.github.io/manual/", "https://bluetape4k.github.io/ko/manual/"))
      yield manifest, english, korean
    end
  end
end
