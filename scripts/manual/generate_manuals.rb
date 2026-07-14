#!/usr/bin/env ruby

require "fileutils"
require "pathname"
require "yaml"

module ManualDocs
  class ManualGenerator
    REQUIRED_SECTIONS = %w[
      problem when-to-use coordinates concepts quick-start api-by-task patterns
      integrations configuration failures operations testing workshops limitations sources
    ].freeze
    TEMPLATES = { "library" => "module.md", "example" => "example.md", "benchmark" => "benchmark.md" }.freeze

    def initialize(repository_root:, manifest_path:)
      @repository_root = File.expand_path(repository_root)
      @manifest_path = File.expand_path(manifest_path)
      @manual_root = File.dirname(@manifest_path)
    end

    def generate(missing_only: true)
      manifest = YAML.safe_load(File.read(@manifest_path))
      generated = []
      manifest.fetch("modules").sort_by { |entry| entry.fetch("id") }.each do |entry|
        template = read_template(entry.fetch("kind"))
        %w[en ko].each do |locale|
          relative = entry.fetch(locale)
          output = safe_output(relative)
          next if missing_only && File.exist?(output)
          FileUtils.mkdir_p(File.dirname(output))
          File.write(output, render(template, entry))
          generated << output
        end
      end
      generated
    end

    private

    def read_template(kind)
      name = TEMPLATES.fetch(kind) { raise ArgumentError, "unsupported manual kind: #{kind}" }
      File.read(File.join(@manual_root, "templates", name))
    end

    def safe_output(relative)
      unless relative.is_a?(String) && !relative.empty? && !Pathname.new(relative).absolute? && Pathname.new(relative).each_filename.none? { |part| part == ".." }
        raise ArgumentError, "unsafe manual output path: #{relative}"
      end
      output = File.expand_path(relative, @manual_root)
      raise ArgumentError, "unsafe manual output path: #{relative}" unless output.start_with?(@manual_root + File::SEPARATOR)
      raise ArgumentError, "unsafe manual output path: #{relative}" unless safe_output_chain?(output)
      output
    end

    def safe_output_chain?(output)
      repository_real = File.realpath(@repository_root)
      manual_metadata = File.lstat(@manual_root)
      return false if manual_metadata.symlink? || !manual_metadata.directory?
      manual_real = File.realpath(@manual_root)
      return false unless within?(manual_real, repository_real)

      relative = Pathname.new(output).relative_path_from(Pathname.new(@manual_root))
      current = @manual_root
      relative.each_filename do |part|
        current = File.join(current, part)
        metadata = lstat_or_nil(current)
        next unless metadata
        return false if metadata.symlink?
        return false unless within?(File.realpath(current), manual_real)
      end
      true
    rescue SystemCallError, ArgumentError
      false
    end

    def lstat_or_nil(path)
      File.lstat(path)
    rescue Errno::ENOENT, Errno::ENOTDIR
      nil
    end

    def within?(path, boundary)
      expanded = File.expand_path(path)
      root = File.expand_path(boundary)
      expanded == root || expanded.start_with?(root + File::SEPARATOR)
    end

    def render(template, entry)
      sections = REQUIRED_SECTIONS.map { |id| "## #{id} {##{id}}\n\nTODO.\n" }.join("\n")
      template.gsub("{{id}}", entry.fetch("id")).gsub("{{required_sections}}", sections)
    end
  end
end

if $PROGRAM_NAME == __FILE__
  generated = ManualDocs::ManualGenerator.new(
    repository_root: Dir.pwd, manifest_path: ARGV.fetch(0, "docs/manual/manifest.yaml"),
  ).generate
  puts "Generated #{generated.length} manual scaffolds."
end
