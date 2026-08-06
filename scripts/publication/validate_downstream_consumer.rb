#!/usr/bin/env ruby

require "fileutils"
require "json"
require "open3"
require "tmpdir"

require_relative "publication_inventory_policy"

ROOT = File.expand_path("../..", __dir__)
INVENTORY_PATH = File.join(ROOT, "build/publication/publication-inventory.json")
GRADLE = ENV.fetch("GRADLE_COMMAND", File.join(ROOT, "gradlew"))

def abort_with(message)
  warn(message)
  exit 1
end

abort_with("publication inventory is missing: #{INVENTORY_PATH}") unless File.file?(INVENTORY_PATH)

begin
  inventory = JSON.parse(File.read(INVENTORY_PATH))
  entries = inventory.fetch("publications")
rescue JSON::ParserError, KeyError => error
  abort_with("invalid publication inventory: #{error.message}")
end

abort_with("publication inventory is empty") if entries.empty?

def relative_publication_paths(entries)
  metadata_paths = entries.map { |entry| entry.fetch("metadataPath") }.sort
  pom_paths = entries.map { |entry| entry.fetch("pomPath") }.sort
  actual_metadata_paths = Dir.glob("**/build/publications/*/module.json")
    .reject { |path| path.start_with?(".worktrees/") }
    .sort
  actual_pom_paths = Dir.glob("**/build/publications/*/pom-default.xml")
    .reject { |path| path.start_with?(".worktrees/") }
    .sort
  abort_with("publication metadata inventory mismatch") unless actual_metadata_paths == metadata_paths
  abort_with("publication POM inventory mismatch") unless actual_pom_paths == pom_paths
end

relative_publication_paths(entries)

def publication_data(entry, root)
  metadata_path = File.join(root, entry.fetch("metadataPath"))
  abort_with("publication metadata is missing: #{metadata_path}") unless File.file?(metadata_path)

  metadata = JSON.parse(File.read(metadata_path))
  component = metadata.fetch("component")
  {
    entry: entry,
    metadata: metadata,
    group: component.fetch("group"),
    module_name: component.fetch("module"),
    version: component.fetch("version"),
    coordinate: "#{component.fetch("group")}:#{component.fetch("module")}:#{component.fetch("version")}",
  }
rescue JSON::ParserError, KeyError => error
  abort_with("invalid publication metadata #{metadata_path}: #{error.message}")
end

publications = entries.map { |entry| publication_data(entry, ROOT) }
publication_versions = publications.map { |publication| publication[:version] }.uniq
abort_with("publication inventory contains multiple versions: #{publication_versions.join(", ")}") unless publication_versions.length == 1
base_version = File.read(File.join(ROOT, "gradle.properties"))
  .lines
  .find { |line| line.start_with?("baseVersion=") }
  &.split("=", 2)
  &.last
  &.strip
publication_suffix = if base_version && publication_versions.first.start_with?(base_version)
                       publication_versions.first.delete_prefix(base_version)
                     else
                       ""
                     end
library_publications = publications.select do |publication|
  publication[:metadata].fetch("variants").any? do |variant|
    variant.fetch("name").match?(/(?:api|runtime)Elements\z/i) &&
      variant.dig("attributes", "org.gradle.category") == "library"
  end
end
bom_publications = publications.reject { |publication| library_publications.include?(publication) }
abort_with("publication inventory has no library publications") if library_publications.empty?
if (platform_error = Publication::InventoryPolicy.platform_publication_error(bom_publications.length))
  abort_with(platform_error)
end

fixture_targets = publications.flat_map do |publication|
  publication[:metadata].fetch("variants").map do |variant|
    next unless variant.fetch("name").match?(/testFixtures(?:Api|Runtime)Elements\z/i)

    capability = Array(variant["capabilities"]).find do |candidate|
      candidate.fetch("name").end_with?("-test-fixtures")
    end
    next unless capability

    {
      owner_coordinate: publication[:coordinate],
      capability: "#{capability.fetch("group")}:#{capability.fetch("name")}",
      variant_name: variant.fetch("name"),
      files: Array(variant["files"]).map { |file| file.fetch("name") },
    }
  end.compact
end.uniq { |fixture| [fixture[:owner_coordinate], fixture[:capability]] }

Dir.mktmpdir("bluetape4k-exposed-downstream-consumer") do |root|
  local_repo = File.join(root, "maven-repository")
  consumer = File.join(root, "consumer")
  gradle_user_home = File.join(root, "gradle-user-home")
  FileUtils.mkdir_p(consumer)

  publish_command = [
    GRADLE,
    "exportPublicationInventory",
    "publishPublicationValidation",
    "--no-daemon",
    "--no-configuration-cache",
    "--no-build-cache",
    "-Dmaven.repo.local=#{local_repo}",
  ]
  publish_command.insert(-1, "-PsnapshotVersion=#{publication_suffix}") unless publication_suffix.empty?
  stdout, stderr, status = Open3.capture3(*publish_command, chdir: ROOT)
  unless status.success?
    diagnostics = (stdout + stderr).lines.grep(/(FAILURE|ERROR|FAILED|Could not|What went wrong)/i).last(80)
    diagnostics = (stdout + stderr).lines.last(120) if diagnostics.length < 10
    abort_with(
      "isolated publication failed:\n" + (diagnostics.empty? ? (stdout + stderr).lines.last(80).join : diagnostics.join),
    )
  end

  def local_repository_path(local_repo, group, module_name, version)
    File.join(local_repo, group.tr(".", "/"), module_name, version)
  end

  publications.each do |publication|
    group = publication[:group]
    module_name = publication[:module_name]
    version = publication[:version]
    directory = local_repository_path(local_repo, group, module_name, version)
    prefix = "#{module_name}-#{version}"
    required = ["#{prefix}.module", "#{prefix}.pom"]
    is_library = library_publications.include?(publication)
    required << "#{prefix}.jar" if is_library
    required.each do |filename|
      path = File.join(directory, filename)
      abort_with("staged publication artifact is missing: #{path}") unless File.file?(path)
    end
  end

  fixture_targets.each do |fixture|
    group, module_name, version = fixture[:owner_coordinate].split(":", 3)
    directory = local_repository_path(local_repo, group, module_name, version)
    fixture[:files].each do |filename|
      path = File.join(directory, filename)
      abort_with("staged test-fixtures artifact is missing: #{path}") unless File.file?(path)
    end
  end

  def notation(publication)
    publication[:coordinate]
  end

  repository_uri = "#{File.expand_path(local_repo).sub(%r{/\z}, "")}/"
  project_names = []
  project_builds = []
  compile_tasks = []
  runtime_tasks = []

  library_publications.each_with_index do |publication, index|
    name = format("publication_%03d", index + 1)
    project_names << ":#{name}"
    project_dir = File.join(consumer, name)
    FileUtils.mkdir_p(File.join(project_dir, "src/main/java/probe"))
    File.write(
      File.join(project_dir, "src/main/java/probe/Probe.java"),
      "package probe; public final class Probe { public static void main(String[] args) {} }\n",
    )
    File.write(
      File.join(project_dir, "build.gradle.kts"),
      <<~KOTLIN,
        plugins {
            `java-library`
        }

        dependencies {
            api("#{notation(publication)}")
        }

        tasks.register<JavaExec>("resolvePublishedRuntime") {
            dependsOn(tasks.named("classes"))
            classpath = sourceSets.main.get().runtimeClasspath
            mainClass.set("probe.Probe")
        }
      KOTLIN
    )
    compile_tasks << ":#{name}:compileJava"
    runtime_tasks << ":#{name}:resolvePublishedRuntime"
  end

  fixture_targets.each_with_index do |fixture, index|
    name = format("test_fixtures_%03d", index + 1)
    project_names << ":#{name}"
    project_dir = File.join(consumer, name)
    FileUtils.mkdir_p(File.join(project_dir, "src/main/java/probe"))
    File.write(
      File.join(project_dir, "src/main/java/probe/FixtureProbe.java"),
      "package probe; public final class FixtureProbe { public static void main(String[] args) {} }\n",
    )
    File.write(
      File.join(project_dir, "build.gradle.kts"),
      <<~KOTLIN,
        plugins {
            `java-library`
        }

        dependencies {
            compileOnly("#{fixture[:owner_coordinate]}") {
                capabilities {
                    requireCapability("#{fixture[:capability]}")
                }
            }
            runtimeOnly("#{fixture[:owner_coordinate]}") {
                capabilities {
                    requireCapability("#{fixture[:capability]}")
                }
            }
        }

        tasks.register<JavaExec>("resolvePublishedRuntime") {
            dependsOn(tasks.named("classes"))
            classpath = sourceSets.main.get().runtimeClasspath
            mainClass.set("probe.FixtureProbe")
        }
      KOTLIN
    )
    compile_tasks << ":#{name}:compileJava"
    runtime_tasks << ":#{name}:resolvePublishedRuntime"
  end

  bom = bom_publications.first
  library_publications.each_with_index do |publication, index|
    name = format("bom_target_%03d", index + 1)
    project_names << ":#{name}"
    project_dir = File.join(consumer, name)
    FileUtils.mkdir_p(File.join(project_dir, "src/main/java/probe"))
    File.write(
      File.join(project_dir, "src/main/java/probe/BomProbe.java"),
      "package probe; public final class BomProbe { public static void main(String[] args) {} }\n",
    )
    File.write(
      File.join(project_dir, "build.gradle.kts"),
      <<~KOTLIN,
        plugins {
            `java-library`
        }

        dependencies {
            api(platform("#{notation(bom)}"))
            api("#{publication[:group]}:#{publication[:module_name]}")
        }

        tasks.register<JavaExec>("resolvePublishedRuntime") {
            dependsOn(tasks.named("classes"))
            classpath = sourceSets.main.get().runtimeClasspath
            mainClass.set("probe.BomProbe")
        }
      KOTLIN
    )
    compile_tasks << ":#{name}:compileJava"
    runtime_tasks << ":#{name}:resolvePublishedRuntime"
  end

  File.write(
    File.join(consumer, "settings.gradle.kts"),
    <<~KOTLIN,
      pluginManagement {
          repositories {
              gradlePluginPortal()
          }
      }

      dependencyResolutionManagement {
          repositories {
              maven {
                  url = uri("#{repository_uri}")
                  metadataSources {
                      gradleMetadata()
                  }
              }
              mavenCentral {
                  content {
                      excludeGroup("io.github.bluetape4k.exposed")
                  }
              }
          }
      }

      rootProject.name = "bluetape4k-exposed-publication-consumer"
      #{project_names.map { |name| "include(\"#{name}\")" }.join("\n  ")}
    KOTLIN
  )

  consumer_command = [
    GRADLE,
    "-p", consumer,
    *(compile_tasks + runtime_tasks),
    "--no-parallel",
    "--max-workers=1",
    "--refresh-dependencies",
    "--no-daemon",
    "--no-configuration-cache",
    "--no-build-cache",
    "--gradle-user-home", gradle_user_home,
  ]
  stdout, stderr, status = Open3.capture3(*consumer_command, chdir: ROOT)
  unless status.success?
    diagnostics = (stdout + stderr).lines.grep(/(FAILURE|ERROR|FAILED|Could not|No matching variant|What went wrong)/i).last(100)
    diagnostics = (stdout + stderr).lines.last(160) if diagnostics.length < 10
    abort_with(
      "synthetic downstream consumer failed:\n" + (diagnostics.empty? ? (stdout + stderr).lines.last(100).join : diagnostics.join),
    )
  end

  puts(
    "published-consumer: publications=#{publications.length} libraries=#{library_publications.length} " \
      "fixtures=#{fixture_targets.length} compileTasks=#{compile_tasks.length} runtimeTasks=#{runtime_tasks.length}",
  )
end
