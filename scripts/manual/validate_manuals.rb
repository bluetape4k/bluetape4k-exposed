#!/usr/bin/env ruby

require "json"
require_relative "manual_contract"

inventory_path = ARGV.fetch(0, "build/manual/module-inventory-1.12.1.json")
manifest_path = ARGV.fetch(1, "docs/manual/manifest.yaml")
inventory = JSON.parse(File.read(inventory_path))
errors = ManualDocs::Validator.new(
  inventory: inventory,
  manifest_path: manifest_path,
  repository_root: Dir.pwd,
  expected_release: {
    "ref" => ENV.fetch("MANUAL_RELEASE_REF", "1.12.1"),
    "commit" => ENV.fetch("MANUAL_RELEASE_COMMIT", "4cc2cce07087241ec24a597d8464615434ea2b81"),
  },
).errors
abort(errors.join("\n")) unless errors.empty?
puts "Manuals are aligned."
