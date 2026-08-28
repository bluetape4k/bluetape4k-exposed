#!/usr/bin/env ruby

require "fileutils"
require "json"
require "open3"
require "set"
require "tmpdir"

ROOT = File.expand_path("../..", __dir__)
GRADLE = ENV.fetch("GRADLE_COMMAND", File.join(ROOT, "gradlew"))
GROUP = "io.github.bluetape4k.exposed"
KOTLIN_VERSION = "2.4.10"
SNAPSHOT_REPOSITORY = "https://central.sonatype.com/repository/maven-snapshots/"
ALLOWLIST_PATH = File.join(ROOT, "scripts/verification/ktor-dependency-allowlist.json")
ALLOWLIST = JSON.parse(File.read(ALLOWLIST_PATH)).freeze
ALIASES = ALLOWLIST.fetch("aliases", {}).transform_keys(&:to_s).transform_values(&:to_s).freeze
COMMON_COORDINATES = Set.new(ALLOWLIST.fetch("common").map(&:to_s)).freeze
MODULE_COORDINATES = ALLOWLIST.fetch("modules").transform_values { |coordinates| Set.new(coordinates.map(&:to_s)) }.freeze

CONSUMERS = {
  "core" => {
    artifact: "bluetape4k-exposed-ktor-core",
    source: <<~KOTLIN,
      package probe

      import io.bluetape4k.exposed.ktor.core.ExposedKtorReadinessBackend
      import io.bluetape4k.exposed.ktor.core.ExposedKtorReadinessOutcome

      fun coreProbe(): String = "${ExposedKtorReadinessBackend.CACHE}:${ExposedKtorReadinessOutcome.UP}"
    KOTLIN
    forbidden: %w[
      bluetape4k-exposed-jdbc
      bluetape4k-exposed-r2dbc
      bluetape4k-exposed-cache
      bluetape4k-exposed-ktor
      bluetape4k-exposed-ktor-jdbc
      bluetape4k-exposed-ktor-r2dbc
      bluetape4k-exposed-ktor-cache
    ],
  },
  "jdbc" => {
    artifact: "bluetape4k-exposed-ktor-jdbc",
    source: <<~KOTLIN,
      package probe

      import io.bluetape4k.exposed.ktor.jdbc.exposedKtorJdbcReadinessProbe
      import kotlinx.coroutines.CoroutineDispatcher
      import org.jetbrains.exposed.v1.jdbc.Database

      fun jdbcProbe(database: Database, dispatcher: CoroutineDispatcher) =
          exposedKtorJdbcReadinessProbe(database, dispatcher)
    KOTLIN
    forbidden: %w[
      bluetape4k-exposed-r2dbc
      bluetape4k-exposed-cache
      bluetape4k-exposed-ktor
      bluetape4k-exposed-ktor-r2dbc
      bluetape4k-exposed-ktor-cache
    ],
  },
  "r2dbc" => {
    artifact: "bluetape4k-exposed-ktor-r2dbc",
    source: <<~KOTLIN,
      package probe

      import io.bluetape4k.exposed.ktor.r2dbc.exposedKtorR2dbcReadinessProbe
      import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase

      fun r2dbcProbe(database: R2dbcDatabase) = exposedKtorR2dbcReadinessProbe(database)
    KOTLIN
    forbidden: %w[
      bluetape4k-exposed-jdbc
      bluetape4k-exposed-cache
      bluetape4k-exposed-ktor
      bluetape4k-exposed-ktor-jdbc
      bluetape4k-exposed-ktor-cache
    ],
  },
  "cache" => {
    artifact: "bluetape4k-exposed-ktor-cache",
    source: <<~KOTLIN,
      package probe

      import io.bluetape4k.exposed.ktor.cache.ExposedKtorCacheContributor
      import io.bluetape4k.exposed.ktor.cache.ExposedKtorCacheStatus

      fun cacheProbe(): ExposedKtorCacheContributor =
          ExposedKtorCacheContributor.custom("orders") { ExposedKtorCacheStatus.UP }
    KOTLIN
    forbidden: %w[
      bluetape4k-exposed-jdbc
      bluetape4k-exposed-r2dbc
      bluetape4k-exposed-ktor
      bluetape4k-exposed-ktor-jdbc
      bluetape4k-exposed-ktor-r2dbc
    ],
  },
}.freeze

def abort_with(message)
  warn("ktor-consumer: #{message}")
  exit 1
end

def canonical_coordinate(coordinate)
  ALIASES.fetch(coordinate, coordinate)
end

def run_command(command, chdir:, env: {})
  stdout, stderr, status = Open3.capture3(env, *command, chdir: chdir)
  return stdout + stderr if status.success?

  diagnostics = (stdout + stderr).lines.grep(/(FAILURE|ERROR|FAILED|Could not|What went wrong)/i).last(80)
  diagnostics = (stdout + stderr).lines.last(120) if diagnostics.length < 5
  abort_with("command failed: #{command.join(" ")}\n#{diagnostics.join}")
end

version = ARGV.fetch(0) do
  properties = File.read(File.join(ROOT, "gradle.properties"))
  base = properties[/^baseVersion=(.+)$/, 1]&.strip
  suffix = properties[/^snapshotVersion=(.*)$/, 1]&.strip
  abort_with("cannot derive publication version") if base.nil? || suffix.nil?
  "#{base}#{suffix}"
end

temporary_directory = ENV["KEEP_KTOR_CONSUMER_TMP"] ? Dir.mktmpdir("bluetape4k-ktor-consumer") : nil
temporary_scope = proc do |&block|
  if temporary_directory
    block.call(temporary_directory)
  else
    Dir.mktmpdir("bluetape4k-ktor-consumer", &block)
  end
end

temporary_scope.call do |temporary_root|
  local_repo = File.join(temporary_root, "maven-repository")
  consumer = File.join(temporary_root, "consumer")
  gradle_user_home = File.join(temporary_root, "gradle-user-home")
  FileUtils.mkdir_p(consumer)
  repository_uri = File.expand_path(local_repo).sub(%r{/\z}, "")

  publish_command = [
    GRADLE,
    "publishPublicationValidation",
    "--no-daemon",
    "--no-configuration-cache",
    "--no-build-cache",
    "--max-workers=1",
    "-Dmaven.repo.local=#{local_repo}",
  ]
  run_command(publish_command, chdir: ROOT)

  project_names = []
  compile_tasks = []
  verify_tasks = []
  CONSUMERS.each do |name, definition|
    project_name = "consumer_#{name}"
    project_names << ":#{project_name}"
    project_dir = File.join(consumer, project_name)
    source_dir = File.join(project_dir, "src/main/kotlin/probe")
    FileUtils.mkdir_p(source_dir)
    File.write(File.join(source_dir, "Probe.kt"), definition.fetch(:source))
    File.write(
      File.join(project_dir, "build.gradle.kts"),
      <<~KOTLIN,
        plugins {
            kotlin("jvm") version "#{KOTLIN_VERSION}"
        }

        repositories {
            maven { url = uri("#{repository_uri}") }
            mavenCentral()
            maven("#{SNAPSHOT_REPOSITORY}")
        }

        dependencies {
            implementation("#{GROUP}:#{definition.fetch(:artifact)}:#{version}")
        }

        fun componentCoordinates(configurationName: String): List<String> =
            configurations.getByName(configurationName).incoming.resolutionResult.allComponents
                .mapNotNull { component ->
                    component.moduleVersion?.let { module -> "${module.group}:${module.name}" }
                }
                .distinct()
                .sorted()

        tasks.register("writeComponentReceipts") {
            doLast {
                file("build/compile-components.txt")
                    .writeText(componentCoordinates("compileClasspath").joinToString("\\n") + "\\n")
                file("build/runtime-components.txt")
                    .writeText(componentCoordinates("runtimeClasspath").joinToString("\\n") + "\\n")
            }
        }
      KOTLIN
    )
    compile_tasks << ":#{project_name}:compileKotlin"
    verify_tasks << ":#{project_name}:writeComponentReceipts"
  end

  File.write(
    File.join(consumer, "settings.gradle.kts"),
    <<~KOTLIN,
      pluginManagement {
          repositories {
              gradlePluginPortal()
              mavenCentral()
          }
      }

      dependencyResolutionManagement {
          repositories {
              maven { url = uri("#{repository_uri}") }
              mavenCentral()
              maven("#{SNAPSHOT_REPOSITORY}")
          }
      }

      rootProject.name = "bluetape4k-ktor-selective-consumer"
      #{project_names.map { |name| "include(\"#{name}\")" }.join("\n")}
    KOTLIN
  )

  consumer_command = [
    GRADLE,
    "-p", consumer,
    *(compile_tasks + verify_tasks),
    "--no-parallel",
    "--max-workers=1",
    "--refresh-dependencies",
    "--no-daemon",
    "--no-configuration-cache",
    "--no-build-cache",
    "--gradle-user-home", gradle_user_home,
  ]
  run_command(consumer_command, chdir: ROOT)

  closures = {}
  CONSUMERS.each do |name, definition|
    compile_components_path = File.join(consumer, "consumer_#{name}", "build/compile-components.txt")
    components_path = File.join(consumer, "consumer_#{name}", "build/runtime-components.txt")
    abort_with("consumer compile component receipt is missing: #{compile_components_path}") unless File.file?(compile_components_path)
    abort_with("consumer runtime component receipt is missing: #{components_path}") unless File.file?(components_path)
    compile_components = File.readlines(compile_components_path, chomp: true).reject(&:empty?)
    components = File.readlines(components_path, chomp: true).reject(&:empty?)
    allowed_coordinates = COMMON_COORDINATES | MODULE_COORDINATES.fetch(name)
    resolved_coordinates = (compile_components + components).uniq.reject do |coordinate|
      coordinate.start_with?("bluetape4k-ktor-selective-consumer:")
    end
    unallowlisted = resolved_coordinates.reject do |coordinate|
      allowed_coordinates.include?(canonical_coordinate(coordinate))
    end
    abort_with("#{name} consumer resolved unallowlisted coordinates: #{unallowlisted.join(", ")}") unless unallowlisted.empty?
    forbidden = (compile_components + components).uniq.select do |component|
      definition.fetch(:forbidden).include?(component.split(":", 2).last)
    end
    abort_with("#{name} consumer resolved forbidden coordinates: #{forbidden.join(", ")}") unless forbidden.empty?
    closures[name] = {
      "artifact" => definition.fetch(:artifact),
      "compileComponents" => compile_components,
      "runtimeComponents" => components,
      "status" => "PASS",
    }
  end

  source_head, source_head_error, source_head_status = Open3.capture3("git", "-C", ROOT, "rev-parse", "HEAD")
  abort_with("cannot resolve source HEAD: #{source_head_error}") unless source_head_status.success?

  receipt = {
    "schema" => 1,
    "version" => version,
    "sourceHead" => source_head.strip,
    "mode" => "external-published-consumer",
    "consumers" => closures,
  }
  receipt_path = File.join(ROOT, "build/verification/ktor-consumer-boundary.json")
  FileUtils.mkdir_p(File.dirname(receipt_path))
  File.write(receipt_path, JSON.pretty_generate(receipt) + "\n")
  puts("ktor-consumer: PASS consumers=#{closures.length} receipt=#{receipt_path}")
end
