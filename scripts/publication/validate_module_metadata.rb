#!/usr/bin/env ruby

require "json"

require_relative "gradle_module_metadata_audit"

INVENTORY_PATH = "build/publication/publication-inventory.json"

if ARGV.empty?
  paths = Dir.glob("**/build/publications/*/module.json")
    .reject { |path| path.start_with?(".worktrees/") }
  pom_paths = Dir.glob("**/build/publications/*/pom-default.xml")
    .reject { |path| path.start_with?(".worktrees/") }
  inventory_errors = []
  expected_paths = []
  expected_pom_paths = []
  begin
    inventory = JSON.parse(File.read(INVENTORY_PATH))
    entries = inventory.fetch("publications")
    expected_paths = entries.map { |entry| entry.fetch("metadataPath") }.sort
    expected_pom_paths = entries.map { |entry| entry.fetch("pomPath") }.sort
  rescue Errno::ENOENT, JSON::ParserError, KeyError => error
    inventory_errors << "invalid or missing publication inventory #{INVENTORY_PATH}: #{error.message}"
  end
else
  paths = ARGV
  expected_paths = nil
  inventory_errors = []
end

result = Publication::GradleModuleMetadataAudit.new(paths, expected_paths: expected_paths).validate
if ARGV.empty?
  actual_paths = paths.sort
  actual_pom_paths = pom_paths.sort
  inventory_errors << "publication inventory metadata mismatch: expected #{expected_paths.join(", ")}" unless actual_paths == expected_paths
  inventory_errors << "publication inventory POM mismatch: expected #{expected_pom_paths.join(", ")}" unless actual_pom_paths == expected_pom_paths
end

errors = inventory_errors + result.errors
unless errors.empty?
  warn(errors.join("\n"))
  abort(
    "gradle-module-metadata: failures=#{errors.length} " \
      "files=#{result.file_count} variants=#{result.variant_count} dependencies=#{result.dependency_count}",
  )
end

puts(
  "gradle-module-metadata: failures=0 files=#{result.file_count} " \
    "variants=#{result.variant_count} dependencies=#{result.dependency_count}",
)
