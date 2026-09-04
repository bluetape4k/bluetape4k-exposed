require "minitest/autorun"
require "tmpdir"

require_relative "validate_spring_modulith_readme"

class SpringModulithReadmeContractTest < Minitest::Test
  def test_current_bilingual_readmes_match_latest_stable
    root = File.expand_path("..", __dir__)
    validator = SpringModulithReadmeContract::Validator.new(
      File.join(root, "README.md"),
      [
        File.join(root, "spring-boot/spring-modulith/README.md"),
        File.join(root, "spring-boot/spring-modulith/README.ko.md"),
      ],
    )

    assert_empty validator.validate
  end

  def test_rejects_obsolete_version_and_locale_drift
    Dir.mktmpdir("spring-modulith-readme") do |root|
      root_readme = File.join(root, "ROOT.md")
      spring_modulith = File.join(root, "spring-modulith")
      Dir.mkdir(spring_modulith)
      english = File.join(spring_modulith, "README.md")
      korean = File.join(spring_modulith, "README.ko.md")
      File.write(root_readme, "- **Latest stable:** `2.0.0`\n")
      content = <<~MARKDOWN
        implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-spring-boot-jdbc:1.12.0")
        implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-spring-modulith:1.12.0")
      MARKDOWN
      File.write(english, content)
      File.write(korean, content.sub("1.12.0", "2.0.0"))

      errors = SpringModulithReadmeContract::Validator.new(root_readme, [english, korean]).validate

      assert errors.any? { |error| error.include?("expected stable 2.0.0") }
      assert errors.any? { |error| error.include?("dependency examples differ") }
    end
  end
end
