require "json"
require "rexml/document"

module Publication
  class GradleModuleMetadataAudit
    API_VARIANT = "apiElements"
    RUNTIME_VARIANT = "runtimeElements"
    REQUIRED_VARIANTS = [API_VARIANT, RUNTIME_VARIANT].freeze
    PLATFORM_CATEGORIES = ["platform"].freeze
    INTERNAL_GROUP_PREFIXES = ["io.github.bluetape4k", "io.bluetape4k"].freeze
    PLATFORM_GOVERNANCE = {
      ["org.jetbrains.exposed", "exposed-bom"] => [
        "org.jetbrains.exposed:exposed-crypt",
        "org.jetbrains.exposed:exposed-dao",
        "org.jetbrains.exposed:exposed-json",
        "org.jetbrains.exposed:exposed-kotlin-datetime",
        "org.jetbrains.exposed:exposed-migration-r2dbc",
        "org.jetbrains.exposed:exposed-money",
      ],
      ["org.jetbrains.kotlinx", "kotlinx-coroutines-bom"] => [
        "org.jetbrains.kotlinx:kotlinx-coroutines-core",
        "org.jetbrains.kotlinx:kotlinx-coroutines-debug",
        "org.jetbrains.kotlinx:kotlinx-coroutines-reactive",
        "org.jetbrains.kotlinx:kotlinx-coroutines-reactor",
        "org.jetbrains.kotlinx:kotlinx-coroutines-test",
      ],
      ["org.jetbrains.kotlinx", "kotlinx-serialization-bom"] => [
        "org.jetbrains.kotlinx:kotlinx-serialization-core",
        "org.jetbrains.kotlinx:kotlinx-serialization-core-jvm",
        "org.jetbrains.kotlinx:kotlinx-serialization-json",
        "org.jetbrains.kotlinx:kotlinx-serialization-json-jvm",
        "org.jetbrains.kotlinx:kotlinx-serialization-properties",
        "org.jetbrains.kotlinx:kotlinx-serialization-protobuf",
      ],
      ["io.ktor", "ktor-bom"] => [
        "io.ktor:ktor-server-core",
        "io.ktor:ktor-server-status-pages",
        "io.ktor:ktor-server-content-negotiation",
        "io.ktor:ktor-serialization-kotlinx-json",
      ],
      ["com.fasterxml.jackson", "jackson-bom"] => [
        "com.fasterxml.jackson.module:jackson-module-blackbird",
        "com.fasterxml.jackson.module:jackson-module-kotlin",
      ],
      ["tools.jackson", "jackson-bom"] => [
        "tools.jackson.module:jackson-module-blackbird",
        "tools.jackson.module:jackson-module-kotlin",
      ],
      ["org.testcontainers", "testcontainers-bom"] => [
        "org.testcontainers:testcontainers",
        "org.testcontainers:testcontainers-mariadb",
        "org.testcontainers:testcontainers-mysql",
        "org.testcontainers:testcontainers-postgresql",
      ],
      ["org.springframework.boot", "spring-boot-dependencies"] => [
        "org.springframework.boot:spring-boot-starter-batch",
        "org.springframework.data:spring-data-commons",
        "org.springframework:spring-tx",
      ],
      ["org.springframework.modulith", "spring-modulith-bom"] => [
        "org.springframework.modulith:spring-modulith-events-api",
        "org.springframework.modulith:spring-modulith-events-core",
        "org.springframework.modulith:spring-modulith-events-jackson",
      ],
      ["io.micrometer", "micrometer-bom"] => [
        "io.micrometer:micrometer-core",
      ],
      ["ai.timefold.solver", "timefold-solver-bom"] => [
        "ai.timefold.solver:timefold-solver-core",
      ],
    }.freeze

    Result = Struct.new(
      :errors,
      :file_count,
      :variant_count,
      :dependency_count,
      keyword_init: true,
    )

    def initialize(paths, expected_paths: nil)
      @paths = Array(paths).map { |path| File.expand_path(path) }.sort
      @expected_paths = expected_paths&.map { |path| File.expand_path(path) }&.sort
    end

    def validate
      return Result.new(
        errors: ["no Gradle module metadata files found"],
        file_count: 0,
        variant_count: 0,
        dependency_count: 0,
      ) if @paths.empty? && @expected_paths.nil?

      errors = coverage_errors
      variant_count = 0
      dependency_count = 0

      @paths.each do |path|
        metadata = JSON.parse(File.read(path))
        variants = metadata.fetch("variants")
        variant_count += variants.length

        variants_by_name = variants.each_with_object({}) do |variant, result|
          result[variant.fetch("name")] = variant
        end
        missing_variants = REQUIRED_VARIANTS.reject { |name| variants_by_name.key?(name) }
        unless missing_variants.empty?
          errors << "#{path}: missing required publication variants: #{missing_variants.join(", ")}"
        end

        visible_variants = variants.select { |variant| publication_variant?(variant.fetch("name")) }
        visible_variants.each do |variant|
          dependencies = Array(variant["dependencies"])
          dependency_count += dependencies.length
          dependencies.each do |dependency|
            next unless external_versionless?(dependency)
            next if governed_by_constraint?(variant, dependency)
            next if governed_by_platform?(variant, dependency)

            errors << (
              "#{path}: #{variant.fetch("name")} exports versionless external dependency " \
                "without a same-variant versioned platform or dependency constraint: " \
                "#{coordinate(dependency)}"
            )
          end
        end

        if visible_variants.any? { |variant| Array(variant["dependencies"]).any? { |dependency| external_versionless?(dependency) } }
          validate_pom(path, errors)
        end
      rescue JSON::ParserError, KeyError => error
        errors << "#{path}: invalid Gradle module metadata: #{error.message}"
      end

      Result.new(
        errors: errors.sort,
        file_count: @paths.length,
        variant_count: variant_count,
        dependency_count: dependency_count,
      )
    end

    private

    def coverage_errors
      return [] unless @expected_paths

      errors = []
      missing = @expected_paths - @paths
      unexpected = @paths - @expected_paths
      unless missing.empty?
        errors << "publication metadata coverage missing: #{missing.join(", ")}"
      end
      unless unexpected.empty?
        errors << "publication metadata coverage has unexpected files: #{unexpected.join(", ")}"
      end
      errors
    end

    def publication_variant?(name)
      name.to_s.match?(/(?:api|runtime)Elements\z/i)
    end

    def external_versionless?(dependency)
      group = dependency["group"].to_s
      !group.empty? &&
        !internal_group?(group) &&
        versionless?(dependency)
    end

    def internal_group?(group)
      INTERNAL_GROUP_PREFIXES.any? { |prefix| group == prefix || group.start_with?("#{prefix}.") }
    end

    def versionless?(dependency)
      !versioned?(dependency)
    end

    def versioned?(dependency)
      version = dependency["version"] || {}
      %w[requires strictly prefers].any? do |key|
        value = version[key]
        !value.nil? && !value.to_s.strip.empty?
      end
    end

    def coordinate(dependency)
      "#{dependency["group"]}:#{dependency["module"]}"
    end

    def governed_by_constraint?(variant, dependency)
      Array(variant["dependencyConstraints"]).any? do |constraint|
        constraint["group"] == dependency["group"] &&
          constraint["module"] == dependency["module"] &&
          versioned?(constraint)
      end
    end

    def governed_by_platform?(variant, dependency)
      Array(variant["dependencies"]).any? do |platform_dependency|
        next false unless PLATFORM_CATEGORIES.include?(platform_dependency.dig("attributes", "org.gradle.category"))
        next false unless versioned?(platform_dependency)

        governed_coordinates = PLATFORM_GOVERNANCE[
          [platform_dependency["group"], platform_dependency["module"]]
        ] || []
        governed_coordinates.include?(coordinate(dependency))
      end
    end

    def validate_pom(module_path, errors)
      pom_path = File.join(File.dirname(module_path), "pom-default.xml")
      unless File.file?(pom_path)
        errors << "#{module_path}: missing matching publication POM: #{pom_path}"
        return
      end

      document = REXML::Document.new(File.read(pom_path))
      managed_dependencies = REXML::XPath.match(
        document,
        "/project/dependencyManagement/dependencies/dependency",
      )
      managed_versions = managed_dependencies.each_with_object({}) do |dependency, result|
        version = dependency.elements["version"]&.text.to_s.strip
        result[coordinate(dependency)] = version unless version.empty?
      end
      has_versioned_bom_import = managed_dependencies.any? do |dependency|
        version = dependency.elements["version"]&.text.to_s.strip
        type = dependency.elements["type"]&.text.to_s.strip
        scope = dependency.elements["scope"]&.text.to_s.strip
        !version.empty? && type == "pom" && scope == "import"
      end

      return if has_versioned_bom_import || !managed_versions.empty?

      errors << "#{pom_path}: no versioned dependency management entry or imported BOM for versionless publication metadata"
    rescue REXML::ParseException => error
      errors << "#{pom_path}: invalid XML: #{error.message.lines.first.to_s.strip}"
    end
  end
end
