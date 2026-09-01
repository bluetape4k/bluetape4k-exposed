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
SELECTIVE_MODULES = %w[core jdbc r2dbc cache tenant-jdbc tenant-r2dbc].freeze
BOUNDARY_SURFACES = %w[api compileClasspath runtimeClasspath publishedPom publishedGradleMetadata].freeze
SOURCE_CONFIGURATIONS = %w[api implementation compileOnly runtimeOnly].freeze
CLASSPATH_CONFIGURATIONS = %w[compileClasspath runtimeClasspath].freeze

class KtorDependencyAllowlistTest < Minitest::Test
  SIBLING_BACKENDS = {
    "core" => "io.github.bluetape4k.exposed:bluetape4k-exposed-jdbc",
    "jdbc" => "io.github.bluetape4k.exposed:bluetape4k-exposed-r2dbc",
    "r2dbc" => "io.github.bluetape4k.exposed:bluetape4k-exposed-cache",
    "cache" => "io.github.bluetape4k.exposed:bluetape4k-exposed-jdbc",
    "tenant-jdbc" => "io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-tenant-r2dbc",
    "tenant-r2dbc" => "io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-tenant-jdbc",
  }.freeze

  SERIALIZATION_VARIANTS = %w[
    org.jetbrains.kotlinx:kotlinx-serialization-core-js
    org.jetbrains.kotlinx:kotlinx-serialization-core-native
    org.jetbrains.kotlinx:kotlinx-serialization-json-js
    org.jetbrains.kotlinx:kotlinx-serialization-json-native
  ].freeze

  def test_policy_has_exact_schema_and_selective_modules
    assert_equal 1, POLICY.fetch("schema")
    assert_equal %w[cache core jdbc r2dbc tenant-jdbc tenant-r2dbc], MODULES.keys.sort
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
      scripts/verification/issue-763-tenant-snapshot.json
      scripts/verification/validate_issue_763_tenant_snapshot.rb
      scripts/verification/validate_issue_763_tenant_snapshot_test.rb
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

  def test_gradle_boundary_rejects_missing_source_configurations
    SOURCE_CONFIGURATIONS.each do |configuration_name|
      stdout, stderr, status = run_gradle_with_structure_fixture("missing-source:#{configuration_name}")
      output = stdout + stderr

      refute status.success?, "the real Gradle checker accepted missing #{configuration_name} source configuration"
      assert_includes output, "source configuration #{configuration_name} is missing"
    end
  end

  def test_gradle_boundary_rejects_missing_or_non_resolvable_classpaths
    CLASSPATH_CONFIGURATIONS.each do |configuration_name|
      %w[missing non-resolvable].each do |state|
        stdout, stderr, status = run_gradle_with_structure_fixture("#{state}-classpath:#{configuration_name}")
        output = stdout + stderr

        refute status.success?, "the real Gradle checker accepted #{state} #{configuration_name}"
        expected = if state == "missing"
                     "#{configuration_name} configuration is missing"
                   else
                     "#{configuration_name} configuration must be resolvable"
                   end
        assert_includes output, expected
      end
    end
  end

  def test_gradle_boundary_rejects_empty_metadata_variants
    stdout, stderr, status = run_gradle_with_structure_fixture("empty-metadata-variants")
    output = stdout + stderr

    refute status.success?, "the real Gradle checker accepted empty Gradle metadata variants"
    assert_includes output, "Gradle metadata variants must not be empty"
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
                    ":bluetape4k-exposed-ktor-tenant-jdbc",
                    ":bluetape4k-exposed-ktor-tenant-r2dbc",
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

  def run_gradle_with_structure_fixture(fixture)
    Dir.mktmpdir("ktor-structure-fixture") do |root|
      init_script = File.join(root, "structure-fixture.init.gradle")
      File.write(
        init_script,
        <<~GRADLE,
          def fixture = #{fixture.to_json}
          gradle.afterProject { project, ignoredState ->
            if (project.name == "bluetape4k-exposed-ktor-core" && fixture.startsWith("non-resolvable-classpath:")) {
              def configurationName = fixture.substring("non-resolvable-classpath:".length())
              project.tasks.matching { task ->
                task.name == "generateMetadataFileForBluetapeExposedPublication"
              }.configureEach { task ->
                task.doLast {
                  def existing = project.configurations.findByName(configurationName)
                  if (existing != null) {
                    project.configurations.remove(existing)
                  }
                  project.configurations.create(configurationName) {
                    canBeResolved = false
                  }
                }
              }
            }
          }
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
                    ":bluetape4k-exposed-ktor-tenant-jdbc",
                    ":bluetape4k-exposed-ktor-tenant-r2dbc",
                  ]
                  projectPaths.each { projectPath ->
                    def target = gradle.rootProject.project(projectPath)
                    if (fixture.startsWith("missing-source:")) {
                      def configurationName = fixture.substring("missing-source:".length())
                      target.configurations.remove(target.configurations.findByName(configurationName))
                    } else if (fixture.startsWith("missing-classpath:")) {
                      def configurationName = fixture.substring("missing-classpath:".length())
                      target.configurations.remove(target.configurations.findByName(configurationName))
                    } else if (fixture == "empty-metadata-variants") {
                      def publicationDir = target.layout.buildDirectory
                        .dir("publications/BluetapeExposed").get().asFile
                      def metadataFile = new File(publicationDir, "module.json")
                      def metadata = new groovy.json.JsonSlurper().parse(metadataFile)
                      metadata.variants = []
                      metadataFile.text = groovy.json.JsonOutput.toJson(metadata)
                    }
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
