require "fileutils"
require "json"
require "minitest/autorun"
require "tmpdir"

require_relative "gradle_module_metadata_audit"
require_relative "publication_inventory_policy"

class GradleModuleMetadataAuditTest < Minitest::Test
  def test_rejects_versionless_external_dependency_without_visible_governance
    with_publication(
      metadata: module_metadata(
        api_dependencies: [dependency("com.example", "api")],
        runtime_dependencies: [dependency("com.example", "runtime")],
      ),
    ) do |module_path|
      result = Publication::GradleModuleMetadataAudit.new([module_path]).validate

      assert_equal 2, result.errors.length
      assert result.errors.any? { |error| error.include?("apiElements") && error.include?("com.example:api") }
      assert result.errors.any? { |error| error.include?("runtimeElements") && error.include?("com.example:runtime") }
    end
  end

  def test_accepts_exact_same_variant_dependency_constraint
    with_publication(
      metadata: module_metadata(
        api_dependencies: [dependency("com.example", "api")],
        api_constraints: [constraint("com.example", "api")],
        runtime_dependencies: [dependency("com.example", "runtime")],
        runtime_constraints: [constraint("com.example", "runtime")],
      ),
    ) do |module_path|
      result = Publication::GradleModuleMetadataAudit.new([module_path]).validate

      assert_empty result.errors
    end
  end

  def test_accepts_versioned_platform
    with_publication(
      metadata: module_metadata(
        api_dependencies: [
          platform("org.jetbrains.kotlinx", "kotlinx-coroutines-bom"),
          dependency("org.jetbrains.kotlinx", "kotlinx-coroutines-core"),
        ],
        runtime_dependencies: [
          platform("org.jetbrains.kotlinx", "kotlinx-coroutines-bom"),
          dependency("org.jetbrains.kotlinx", "kotlinx-coroutines-core"),
        ],
      ),
    ) do |module_path|
      result = Publication::GradleModuleMetadataAudit.new([module_path]).validate

      assert_empty result.errors
    end
  end

  def test_accepts_ktor_platform
    with_publication(
      metadata: module_metadata(
        api_dependencies: [
          platform("io.ktor", "ktor-bom"),
          dependency("io.ktor", "ktor-server-core"),
        ],
        runtime_dependencies: [
          platform("io.ktor", "ktor-bom"),
          dependency("io.ktor", "ktor-server-core"),
        ],
      ),
    ) do |module_path|
      result = Publication::GradleModuleMetadataAudit.new([module_path]).validate

      assert_empty result.errors
    end
  end

  def test_accepts_kotlinx_serialization_platform
    with_publication(
      metadata: module_metadata(
        api_dependencies: [
          platform("org.jetbrains.kotlinx", "kotlinx-serialization-bom"),
          dependency("org.jetbrains.kotlinx", "kotlinx-serialization-core-jvm"),
        ],
        runtime_dependencies: [
          platform("org.jetbrains.kotlinx", "kotlinx-serialization-bom"),
          dependency("org.jetbrains.kotlinx", "kotlinx-serialization-json-jvm"),
        ],
      ),
    ) do |module_path|
      result = Publication::GradleModuleMetadataAudit.new([module_path]).validate

      assert_empty result.errors
    end
  end

  def test_rejects_enforced_platform_as_governance
    with_publication(
      metadata: module_metadata(
        api_dependencies: [
          platform("org.jetbrains.kotlinx", "kotlinx-coroutines-bom", category: "enforced-platform"),
          dependency("org.jetbrains.kotlinx", "kotlinx-coroutines-core"),
        ],
        runtime_dependencies: [
          platform("org.jetbrains.kotlinx", "kotlinx-coroutines-bom", category: "enforced-platform"),
          dependency("org.jetbrains.kotlinx", "kotlinx-coroutines-core"),
        ],
      ),
    ) do |module_path|
      result = Publication::GradleModuleMetadataAudit.new([module_path]).validate

      assert_equal 2, result.errors.length
      assert result.errors.all? { |error| error.include?("kotlinx-coroutines-core") }
    end
  end

  def test_rejects_unrelated_versioned_platform
    with_publication(
      metadata: module_metadata(
        api_dependencies: [
          platform("com.example", "example-bom"),
          dependency("org.jetbrains.kotlinx", "kotlinx-coroutines-core"),
        ],
        runtime_dependencies: [
          platform("com.example", "example-bom"),
          dependency("org.jetbrains.kotlinx", "kotlinx-coroutines-core"),
        ],
      ),
    ) do |module_path|
      result = Publication::GradleModuleMetadataAudit.new([module_path]).validate

      assert_equal 2, result.errors.length
      assert result.errors.all? { |error| error.include?("kotlinx-coroutines-core") }
    end
  end

  def test_excludes_internal_bluetape4k_project_dependencies
    with_publication(
      metadata: module_metadata(
        api_dependencies: [dependency("io.github.bluetape4k.exposed", "internal-api")],
        runtime_dependencies: [dependency("io.bluetape4k", "internal-runtime")],
      ),
      pom: empty_pom,
    ) do |module_path|
      result = Publication::GradleModuleMetadataAudit.new([module_path]).validate

      assert_empty result.errors
    end
  end

  def test_rejects_missing_matching_publication_pom
    Dir.mktmpdir("gradle-module-metadata-audit") do |root|
      module_path = File.join(root, "module.json")
      File.write(module_path, JSON.pretty_generate(module_metadata(
        api_dependencies: [dependency("com.example", "api")],
        runtime_dependencies: [],
      )))

      result = Publication::GradleModuleMetadataAudit.new([module_path]).validate

      assert result.errors.any? { |error| error.include?("missing matching publication POM") }
    end
  end

  def test_rejects_incomplete_publication_inventory
    with_publication(
      metadata: module_metadata(api_dependencies: [], runtime_dependencies: []),
    ) do |module_path|
      expected_path = File.join(File.dirname(module_path), "missing", "module.json")
      result = Publication::GradleModuleMetadataAudit.new(
        [module_path],
        expected_paths: [module_path, expected_path],
      ).validate

      assert result.errors.any? { |error| error.include?("publication metadata coverage missing") }
    end
  end

  def test_rejects_missing_required_publication_variant
    with_publication(
      metadata: {
        "formatVersion" => "1.1",
        "component" => { "group" => "io.example", "module" => "example", "version" => "1.0.0" },
        "variants" => [{ "name" => "apiElements", "dependencies" => [] }],
      },
    ) do |module_path|
      result = Publication::GradleModuleMetadataAudit.new([module_path]).validate

      assert result.errors.any? { |error| error.include?("missing required publication variants: runtimeElements") }
    end
  end

  def test_audits_published_test_fixture_variants
    metadata = module_metadata(api_dependencies: [], runtime_dependencies: [])
    metadata["variants"] << {
      "name" => "testFixturesRuntimeElements",
      "dependencies" => [dependency("com.example", "fixture")],
      "dependencyConstraints" => [],
    }

    with_publication(metadata: metadata) do |module_path|
      result = Publication::GradleModuleMetadataAudit.new([module_path]).validate

      assert result.errors.any? do |error|
        error.include?("testFixturesRuntimeElements") && error.include?("com.example:fixture")
      end
    end
  end

  def test_fails_closed_when_no_module_metadata_exists
    result = Publication::GradleModuleMetadataAudit.new([]).validate

    assert_equal ["no Gradle module metadata files found"], result.errors
    assert_equal 0, result.file_count
    assert_equal 0, result.variant_count
    assert_equal 0, result.dependency_count
  end

  def test_accepts_exactly_one_platform_publication
    assert_nil Publication::InventoryPolicy.platform_publication_error(1)
  end

  def test_rejects_multiple_platform_publications
    error = Publication::InventoryPolicy.platform_publication_error(2)

    assert_equal "publication inventory must contain exactly 1 platform publication (found 2)", error
  end

  private

  def module_metadata(api_dependencies:, runtime_dependencies:, api_constraints: [], runtime_constraints: [])
    {
      "formatVersion" => "1.1",
      "component" => { "group" => "io.example", "module" => "example", "version" => "1.0.0" },
      "variants" => [
        {
          "name" => "apiElements",
          "dependencies" => api_dependencies,
          "dependencyConstraints" => api_constraints,
        },
        {
          "name" => "runtimeElements",
          "dependencies" => runtime_dependencies,
          "dependencyConstraints" => runtime_constraints,
        },
      ],
    }
  end

  def dependency(group, module_name)
    { "group" => group, "module" => module_name }
  end

  def constraint(group, module_name)
    {
      "group" => group,
      "module" => module_name,
      "version" => { "requires" => "1.0.0" },
    }
  end

  def platform(group, module_name, category: "platform")
    {
      "group" => group,
      "module" => module_name,
      "version" => { "requires" => "1.0.0" },
      "attributes" => { "org.gradle.category" => category },
    }
  end

  def empty_pom
    "<project><dependencies /></project>"
  end

  def imported_bom_pom
    <<~XML
      <project>
        <dependencyManagement>
          <dependencies>
            <dependency>
              <groupId>com.example</groupId>
              <artifactId>example-bom</artifactId>
              <version>1.0.0</version>
              <type>pom</type>
              <scope>import</scope>
            </dependency>
          </dependencies>
        </dependencyManagement>
      </project>
    XML
  end

  def with_publication(metadata:, pom: imported_bom_pom)
    Dir.mktmpdir("gradle-module-metadata-audit") do |root|
      publication_dir = File.join(root, "BluetapeExposed")
      FileUtils.mkdir_p(publication_dir)
      module_path = File.join(publication_dir, "module.json")
      File.write(module_path, JSON.pretty_generate(metadata))
      File.write(File.join(publication_dir, "pom-default.xml"), pom)
      yield module_path
    end
  end
end
