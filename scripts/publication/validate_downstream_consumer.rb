#!/usr/bin/env ruby

require "fileutils"
require "digest"
require "json"
require "open3"
require "rexml/document"
require "rexml/xpath"
require "tmpdir"

require_relative "publication_inventory_policy"

ROOT = File.expand_path("../..", __dir__)
INVENTORY_PATH = File.join(ROOT, "build/publication/publication-inventory.json")
GRADLE = ENV.fetch("GRADLE_COMMAND", File.join(ROOT, "gradlew"))
R2DBC_DAO_FIXTURE_PATH = File.join(__dir__, "fixtures/r2dbc-dao-kotlin")
EXPECTED_EXPOSED_DAO_COORDINATE = "org.jetbrains.exposed:exposed-dao-r2dbc:1.6.1"

def abort_with(message)
  warn(message)
  exit 1
end

def managed_catalog_value(root, alias_name)
  settings = File.read(File.join(root, "settings.gradle.kts"))
  catalog_ref = ENV["BLUETAPE4K_DEPENDENCIES_CATALOG_REF"] ||
    settings.match(/\.orElse\("([0-9a-f]{40,64})"\)/)&.[](1)
  abort_with("cannot identify the pinned bluetape4k-dependencies catalog ref") unless catalog_ref

  catalog_directory = File.join(root, ".gradle/bluetape4k-dependencies", catalog_ref)
  catalog_path = File.join(catalog_directory, "libs.versions.toml")
  checksum_path = "#{catalog_path}.sha256"
  abort_with("pinned dependency catalog is missing: #{catalog_path}") unless File.file?(catalog_path)
  abort_with("pinned dependency catalog checksum is missing: #{checksum_path}") unless File.file?(checksum_path)

  expected_checksum = File.read(checksum_path).split.first
  actual_checksum = Digest::SHA256.file(catalog_path).hexdigest
  abort_with("pinned dependency catalog checksum mismatch: #{catalog_path}") unless actual_checksum == expected_checksum

  catalog_text = File.read(catalog_path)
  version = catalog_text.match(/^#{Regexp.escape(alias_name)}\s*=\s*"([^"]+)"/)&.[](1)
  abort_with("version alias #{alias_name} is missing from #{catalog_path}") unless version

  version
end

def resolved_coordinates(output, configuration_name)
  output.scan(/^R2DBC_DAO_COORDINATE:#{Regexp.escape(configuration_name)}:([^\s]+)$/).flatten.uniq.sort
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

r2dbc_dao_publication = library_publications.find do |publication|
  publication[:module_name] == "bluetape4k-exposed-r2dbc-dao"
end
abort_with("R2DBC DAO publication is missing from the inventory") unless r2dbc_dao_publication
staged_bom_publication = bom_publications.find do |publication|
  publication[:module_name] == "bluetape4k-exposed-bom"
end
abort_with("staged Exposed BOM publication is missing from the inventory") unless staged_bom_publication

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

  bom = staged_bom_publication
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
                  content {
                      includeGroupAndSubgroups("io.github.bluetape4k")
                  }
                  metadataSources {
                      gradleMetadata()
                  }
              }
              maven {
                  name = "central-snapshots"
                  url = uri("https://central.sonatype.com/repository/maven-snapshots/")
                  content {
                      includeGroupAndSubgroups("io.github.bluetape4k")
                      excludeGroupAndSubgroups("io.github.bluetape4k.exposed")
                  }
              }
              mavenCentral {
                  content {
                      excludeGroupAndSubgroups("io.github.bluetape4k.exposed")
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

  r2dbc_dao_directory = local_repository_path(
    local_repo,
    r2dbc_dao_publication[:group],
    r2dbc_dao_publication[:module_name],
    r2dbc_dao_publication[:version],
  )
  r2dbc_dao_prefix = "#{r2dbc_dao_publication[:module_name]}-#{r2dbc_dao_publication[:version]}"
  staged_r2dbc_dao_pom = File.join(r2dbc_dao_directory, "#{r2dbc_dao_prefix}.pom")
  staged_r2dbc_dao_jar = File.join(r2dbc_dao_directory, "#{r2dbc_dao_prefix}.jar")
  pom_digest = Digest::SHA256.file(staged_r2dbc_dao_pom).hexdigest
  jar_digest = Digest::SHA256.file(staged_r2dbc_dao_jar).hexdigest

  pom_namespace = { "pom" => "http://maven.apache.org/POM/4.0.0" }
  pom_document = REXML::Document.new(File.read(staged_r2dbc_dao_pom))
  pom_dependencies = REXML::XPath.match(
    pom_document,
    "/pom:project/pom:dependencies/pom:dependency",
    pom_namespace,
  ).map do |dependency|
    group = REXML::XPath.first(dependency, "pom:groupId", pom_namespace)&.text
    module_name = REXML::XPath.first(dependency, "pom:artifactId", pom_namespace)&.text
    version = REXML::XPath.first(dependency, "pom:version", pom_namespace)&.text
    scope = REXML::XPath.first(dependency, "pom:scope", pom_namespace)&.text || "compile"
    [group, module_name, version, scope]
  end

  required_pom_dependencies = [
    ["org.jetbrains.exposed", "exposed-dao-r2dbc", "1.6.1", "compile"],
    ["io.github.bluetape4k.exposed", "bluetape4k-exposed-core", r2dbc_dao_publication[:version], "compile"],
  ]
  missing_pom_dependencies = required_pom_dependencies - pom_dependencies
  abort_with("R2DBC DAO POM is missing expected API dependencies: #{missing_pom_dependencies.inspect}") unless missing_pom_dependencies.empty?
  forbidden_pom_dependencies = pom_dependencies.select do |group, module_name, _version, _scope|
    (group == "org.jetbrains.exposed" && module_name == "exposed-dao") ||
      (group == "io.github.bluetape4k.exposed" && module_name.match?(/(?:jdbc|bluetape4k-exposed-r2dbc\z|bluetape4k-exposed-dao)/))
  end
  abort_with("R2DBC DAO POM contains a JDBC or legacy DAO dependency: #{forbidden_pom_dependencies.inspect}") unless forbidden_pom_dependencies.empty?

  jar_listing, jar_error, jar_status = Open3.capture3("jar", "tf", staged_r2dbc_dao_jar)
  abort_with("cannot inspect staged R2DBC DAO JAR: #{jar_error}") unless jar_status.success?
  jar_classes = jar_listing.lines.map(&:strip).grep(/\.class\z/)
  abort_with("dependency-only R2DBC DAO JAR contains classes: #{jar_classes.inspect}") unless jar_classes.empty?

  dependency_catalog_version = managed_catalog_value(ROOT, "kotlin")
  r2dbc_h2_version = managed_catalog_value(ROOT, "r2dbc-h2")
  r2dbc_dao_consumer = File.join(consumer, "r2dbc-dao-kotlin")
  FileUtils.cp_r(R2DBC_DAO_FIXTURE_PATH, r2dbc_dao_consumer)
  fixture_settings = File.join(r2dbc_dao_consumer, "settings.gradle.kts")
  settings_text = File.read(fixture_settings).gsub("__KOTLIN_PLUGIN_VERSION__", dependency_catalog_version)
  abort_with("Kotlin plugin version placeholder was not replaced") if settings_text.include?("__KOTLIN_PLUGIN_VERSION__")
  File.write(fixture_settings, settings_text)

  r2dbc_dao_command = [
    GRADLE,
    "-p", r2dbc_dao_consumer,
    "compileKotlin",
    "printCompileClasspathCoordinates",
    "printRuntimeClasspathCoordinates",
    "runR2dbcDaoConsumer",
    "-PstagedRepository=#{File.expand_path(local_repo)}",
    "-PstagedVersion=#{staged_bom_publication[:version]}",
    "-Pr2dbcH2Version=#{r2dbc_h2_version}",
    "--no-parallel",
    "--max-workers=1",
    "--refresh-dependencies",
    "--no-daemon",
    "--no-configuration-cache",
    "--no-build-cache",
    "--gradle-user-home", gradle_user_home,
  ]
  r2dbc_dao_stdout, r2dbc_dao_stderr, r2dbc_dao_status = Open3.capture3(*r2dbc_dao_command, chdir: ROOT)
  unless r2dbc_dao_status.success?
    diagnostics = (r2dbc_dao_stdout + r2dbc_dao_stderr).lines.grep(/(FAILURE|ERROR|FAILED|Could not|No matching variant|What went wrong)/i).last(100)
    diagnostics = (r2dbc_dao_stdout + r2dbc_dao_stderr).lines.last(160) if diagnostics.length < 10
    abort_with(
      "staged R2DBC DAO Kotlin consumer failed:\n" +
        (diagnostics.empty? ? (r2dbc_dao_stdout + r2dbc_dao_stderr).lines.last(100).join : diagnostics.join),
    )
  end

  compile_coordinates = resolved_coordinates(r2dbc_dao_stdout + r2dbc_dao_stderr, "compileClasspath")
  runtime_coordinates = resolved_coordinates(r2dbc_dao_stdout + r2dbc_dao_stderr, "runtimeClasspath")
  abort_with("R2DBC DAO consumer did not report the full compileClasspath") if compile_coordinates.empty?
  abort_with("R2DBC DAO consumer did not report the full runtimeClasspath") if runtime_coordinates.empty?

  expected_exposed_modules = %w[exposed-bom exposed-core exposed-dao-r2dbc exposed-r2dbc]
  [compile_coordinates, runtime_coordinates].each do |coordinates|
    exposed_coordinates = coordinates.select { |coordinate| coordinate.start_with?("org.jetbrains.exposed:") }
    exposed_modules = exposed_coordinates.map { |coordinate| coordinate.split(":", 3)[1] }.uniq.sort
    unexpected_modules = exposed_modules - expected_exposed_modules
    abort_with("unexpected Exposed components in the R2DBC DAO graph: #{unexpected_modules.inspect}") unless unexpected_modules.empty?
    abort_with("Exposed DAO-R2DBC coordinate is missing from the dependency graph") unless exposed_coordinates.include?(EXPECTED_EXPOSED_DAO_COORDINATE)
    wrong_versions = exposed_coordinates.reject { |coordinate| coordinate.end_with?(":1.6.1") }
    abort_with("Exposed components do not all resolve to 1.6.1: #{wrong_versions.inspect}") unless wrong_versions.empty?

    forbidden_coordinates = coordinates.select do |coordinate|
      group, module_name, = coordinate.split(":", 3)
      (group == "org.jetbrains.exposed" && module_name == "exposed-dao") ||
        (group == "io.github.bluetape4k.exposed" && module_name.match?(/(?:jdbc|bluetape4k-exposed-r2dbc\z|bluetape4k-exposed-dao)/))
    end
    abort_with("JDBC or legacy DAO artifact resolved in the R2DBC DAO consumer: #{forbidden_coordinates.inspect}") unless forbidden_coordinates.empty?
  end

  negative_consumer = File.join(consumer, "r2dbc-dao-no-opt-in")
  FileUtils.cp_r(R2DBC_DAO_FIXTURE_PATH, negative_consumer)
  negative_settings = File.join(negative_consumer, "settings.gradle.kts")
  negative_settings_text = File.read(negative_settings).gsub("__KOTLIN_PLUGIN_VERSION__", dependency_catalog_version)
  abort_with("Kotlin plugin version placeholder was not replaced in negative consumer") if negative_settings_text.include?("__KOTLIN_PLUGIN_VERSION__")
  File.write(negative_settings, negative_settings_text)
  FileUtils.rm_rf(File.join(negative_consumer, "src/main/kotlin"))
  negative_source = File.join(negative_consumer, "src/no-opt-in/kotlin/probe/NoOptInConsumer.kt")
  negative_source_dir = File.join(negative_consumer, "src/main/kotlin")
  FileUtils.mkdir_p(negative_source_dir)
  FileUtils.cp(negative_source, File.join(negative_source_dir, "NoOptInConsumer.kt"))
  negative_command = [
    GRADLE,
    "-p", negative_consumer,
    "compileKotlin",
    "-PstagedRepository=#{File.expand_path(local_repo)}",
    "-PstagedVersion=#{staged_bom_publication[:version]}",
    "-Pr2dbcH2Version=#{r2dbc_h2_version}",
    "--no-parallel",
    "--max-workers=1",
    "--refresh-dependencies",
    "--no-daemon",
    "--no-configuration-cache",
    "--no-build-cache",
    "--gradle-user-home", gradle_user_home,
  ]
  negative_stdout, negative_stderr, negative_status = Open3.capture3(*negative_command, chdir: ROOT)
  negative_output = negative_stdout + negative_stderr
  abort_with("R2DBC DAO consumer compiled without ExperimentalR2dbcDaoApi opt-in") if negative_status.success?

  opt_in_diagnostics = negative_output.lines.grep(/ExperimentalR2dbcDaoApi/)
  abort_with("R2DBC DAO negative consumer failed without the expected ExperimentalR2dbcDaoApi diagnostic:\n#{negative_output.lines.last(80).join}") if opt_in_diagnostics.empty?
  compiler_errors = negative_output.lines.grep(/^e: /)
  unexpected_compiler_errors = compiler_errors.reject { |line| line.include?("ExperimentalR2dbcDaoApi") }
  abort_with("R2DBC DAO negative consumer has unrelated compiler errors: #{unexpected_compiler_errors.join}") unless unexpected_compiler_errors.empty?

  verification_metadata = File.join(ROOT, "gradle/verification-metadata.xml")
  verification_status = if File.file?(verification_metadata)
                          "present"
                        else
                          "N/A (gradle/verification-metadata.xml is absent)"
                        end
  validation_report = {
    "publication" => r2dbc_dao_publication[:coordinate],
    "bom" => staged_bom_publication[:coordinate],
    "catalogVersions" => { "kotlin" => dependency_catalog_version, "r2dbc-h2" => r2dbc_h2_version },
    "metadataSources" => ["Gradle module metadata (required, no POM fallback)"],
    "pomSha256" => pom_digest,
    "jarSha256" => jar_digest,
    "jarClassEntries" => jar_classes,
    "pomDependencies" => pom_dependencies.map { |dependency| dependency.join(":") }.sort,
    "compileClasspath" => compile_coordinates,
    "runtimeClasspath" => runtime_coordinates,
    "positiveConsumer" => "compiled and ran with ExperimentalR2dbcDaoApi opt-in",
    "negativeConsumer" => "failed only with ExperimentalR2dbcDaoApi diagnostics",
    "dependencyVerificationMetadata" => verification_status,
  }
  validation_report_path = File.join(ROOT, "build/publication/r2dbc-dao-consumer-validation.json")
  FileUtils.mkdir_p(File.dirname(validation_report_path))
  File.write(validation_report_path, JSON.pretty_generate(validation_report) + "\n")

  puts("r2dbc-dao-consumer: positive=PASS negative=EXPECTED_FAILURE diagnostic=ExperimentalR2dbcDaoApi")
  puts("r2dbc-dao-consumer: exposed-dao-r2dbc:1.6.1 compile=#{compile_coordinates.length} runtime=#{runtime_coordinates.length}")
  puts("r2dbc-dao-consumer: POM SHA-256=#{pom_digest} JAR SHA-256=#{jar_digest} classes=#{jar_classes.length}")
  puts("r2dbc-dao-consumer: dependency-verification metadata: #{verification_status}")
  puts("r2dbc-dao-consumer: report=#{validation_report_path}")

  puts(
    "published-consumer: publications=#{publications.length} libraries=#{library_publications.length} " \
      "fixtures=#{fixture_targets.length} compileTasks=#{compile_tasks.length} runtimeTasks=#{runtime_tasks.length}",
  )
end
