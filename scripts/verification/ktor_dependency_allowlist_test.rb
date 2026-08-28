require "json"
require "minitest/autorun"
require "open3"
require "set"
require "tmpdir"

ROOT = File.expand_path("../..", __dir__)
POLICY = JSON.parse(
  File.read(File.join(ROOT, "scripts/verification/ktor-dependency-allowlist.json")),
).freeze
CI_WORKFLOW = File.read(File.join(ROOT, ".github/workflows/ci.yml")).freeze
BUILD_GRADLE = File.read(File.join(ROOT, "build.gradle.kts")).freeze
ALIASES = POLICY.fetch("aliases").transform_keys(&:to_s).transform_values(&:to_s).freeze
COMMON = Set.new(POLICY.fetch("common").map(&:to_s)).freeze
MODULES = POLICY.fetch("modules").transform_values { |coordinates| Set.new(coordinates.map(&:to_s)) }.freeze
SELECTIVE_MODULES = %w[core jdbc r2dbc cache].freeze
BOUNDARY_SURFACES = %w[api compileClasspath runtimeClasspath publishedPom publishedGradleMetadata].freeze

class KtorDependencyAllowlistTest < Minitest::Test
  SIBLING_BACKENDS = {
    "core" => "io.github.bluetape4k.exposed:bluetape4k-exposed-jdbc",
    "jdbc" => "io.github.bluetape4k.exposed:bluetape4k-exposed-r2dbc",
    "r2dbc" => "io.github.bluetape4k.exposed:bluetape4k-exposed-cache",
    "cache" => "io.github.bluetape4k.exposed:bluetape4k-exposed-jdbc",
  }.freeze

  SERIALIZATION_VARIANTS = %w[
    org.jetbrains.kotlinx:kotlinx-serialization-core-js
    org.jetbrains.kotlinx:kotlinx-serialization-core-native
    org.jetbrains.kotlinx:kotlinx-serialization-json-js
    org.jetbrains.kotlinx:kotlinx-serialization-json-native
  ].freeze

  def test_policy_has_exact_schema_and_selective_modules
    assert_equal 1, POLICY.fetch("schema")
    assert_equal %w[cache core jdbc r2dbc], MODULES.keys.sort
  end

  def test_policy_coordinates_are_fully_qualified
    coordinates = COMMON + MODULES.values.flat_map(&:to_a)

    coordinates.each do |coordinate|
      assert_match(/\A[^:\s]+:[^:\s]+\z/, coordinate, "invalid coordinate: #{coordinate}")
    end
  end

  def test_base_coordinates_normalize_to_allowed_jvm_variants
    ALIASES.each do |raw, canonical|
      assert_equal canonical, canonical_coordinate(raw)
      assert_includes COMMON, canonical, "alias target is not a common coordinate: #{canonical}"
    end
  end

  def test_rejects_unknown_third_party_edge_for_every_selective_module
    MODULES.each_key do |module_name|
      refute allowed?(module_name, "com.example:unexpected-backend-#{module_name}")
    end
  end

  def test_rejects_sibling_backend_edge_for_every_selective_module
    SIBLING_BACKENDS.each do |module_name, coordinate|
      refute allowed?(module_name, coordinate)
    end
  end

  def test_rejects_unlisted_exposed_ktor_namespace_for_every_selective_module
    MODULES.each_key do |module_name|
      refute allowed?(module_name, "io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-unlisted")
    end
  end

  def test_rejects_non_jvm_serialization_variants
    SERIALIZATION_VARIANTS.each do |coordinate|
      refute allowed?("core", coordinate)
    end
  end

  def test_allowlist_changes_trigger_ktor_ci_job
    %w[
      scripts/verification/ktor-dependency-allowlist.json
      scripts/verification/ktor_dependency_allowlist_test.rb
      scripts/verification/validate_ktor_consumer.rb
    ].each do |workflow_path|
      assert_includes CI_WORKFLOW, "- '#{workflow_path}'"
    end
  end

  def test_boundary_task_description_states_allowlist_contract
    assert_includes BUILD_GRADLE,
                    'description = "Checks selective Ktor artifacts against a fully-qualified dependency allowlist across third-party, namespace, alias, POM, and Gradle metadata edges."'
  end

  def test_gradle_boundary_rejects_forbidden_policy_edge_on_every_surface
    fixture_policy = JSON.parse(JSON.generate(POLICY))
    fixture_policy.fetch("common").delete("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm")

    Dir.mktmpdir("ktor-allowlist-fixture") do |root|
      policy_path = File.join(root, "policy.json")
      File.write(policy_path, JSON.pretty_generate(fixture_policy))
      stdout, stderr, status = Open3.capture3(
        { "KTOR_DEPENDENCY_ALLOWLIST_FILE" => policy_path },
        File.join(ROOT, "gradlew"),
        "checkKtorDependencyBoundary",
        "--no-configuration-cache",
        "--no-daemon",
        "--no-build-cache",
        "--rerun-tasks",
        "--console=plain",
        chdir: ROOT,
      )
      output = stdout + stderr

      refute status.success?, "the real Gradle checker accepted a forbidden policy fixture"
      SELECTIVE_MODULES.each do |module_name|
        BOUNDARY_SURFACES.each do |surface|
          assert_includes output, ":bluetape4k-exposed-ktor-#{module_name}:#{surface}"
        end
      end
    end
  end

  def test_gradle_boundary_rejects_non_array_metadata_fields
    %w[dependencies dependencyConstraints].each do |field|
      stdout, stderr, status = run_gradle_with_malformed_metadata(field)
      output = stdout + stderr

      refute status.success?, "the real Gradle checker accepted malformed #{field} metadata"
      assert_includes output, "Gradle metadata #{field} must be an array"
    end
  end

  private

  def run_gradle_with_malformed_metadata(field)
    Dir.mktmpdir("ktor-metadata-fixture") do |root|
      init_script = File.join(root, "malformed-metadata.init.gradle")
      File.write(
        init_script,
        <<~GRADLE,
          gradle.afterProject { project, ignoredState ->
            if (project.parent == null) {
              def checkTask = project.tasks.findByName("checkKtorDependencyBoundary")
              if (checkTask != null) {
                checkTask.doFirst {
                  def projectPaths = [
                    ":bluetape4k-exposed-ktor-core",
                    ":bluetape4k-exposed-ktor-jdbc",
                    ":bluetape4k-exposed-ktor-r2dbc",
                    ":bluetape4k-exposed-ktor-cache",
                  ]
                  projectPaths.each { projectPath ->
                    def publicationDir = gradle.rootProject.project(projectPath).layout.buildDirectory
                      .dir("publications/BluetapeExposed").get().asFile
                    def metadataFile = new File(publicationDir, "module.json")
                    def metadata = new groovy.json.JsonSlurper().parse(metadataFile)
                    metadata.variants.each { variant -> variant["#{field}"] = [malformed: true] }
                    metadataFile.text = groovy.json.JsonOutput.toJson(metadata)
                  }
                }
              }
            }
          }
        GRADLE
      )
      Open3.capture3(
        File.join(ROOT, "gradlew"),
        "checkKtorDependencyBoundary",
        "--init-script",
        init_script,
        "--no-configuration-cache",
        "--no-daemon",
        "--no-build-cache",
        "--rerun-tasks",
        "--console=plain",
        chdir: ROOT,
      )
    end
  end

  def canonical_coordinate(coordinate)
    ALIASES.fetch(coordinate, coordinate)
  end

  def allowed?(module_name, coordinate)
    (COMMON | MODULES.fetch(module_name)).include?(canonical_coordinate(coordinate))
  end
end
