#!/usr/bin/env ruby
# frozen_string_literal: true

require "json"
require "open3"
require "pathname"
require "set"
require "optparse"

module LocalizationInventory
  README_PATTERN = %r{(^|/)README(\.ko)?\.md$}i
  DOC_EXTENSIONS = %w[.md .adoc .rst].freeze
  KOTLIN_EXTENSIONS = %w[.kt .kts].freeze
  OPERATING_SURFACES = %w[AGENTS.md CLAUDE.md].freeze
  OPERATING_PREFIXES = %w[.omx/ .codex/].freeze

  Result = Struct.new(
    :repository_root,
    :tracked_docs,
    :tracked_kotlin,
    :excluded_docs,
    :single_language_docs,
    :english_kdoc_files,
    :internal_class_files,
    :data_class_files,
    keyword_init: true,
  ) do
    def to_h
      {
        "repositoryRoot" => repository_root,
        "counts" => {
          "trackedDocs" => tracked_docs,
          "trackedKotlin" => tracked_kotlin,
          "excludedDocs" => excluded_docs.length,
          "singleLanguageDocs" => single_language_docs.length,
          "englishKdocFiles" => english_kdoc_files.length,
          "internalClassFiles" => internal_class_files.length,
          "dataClassFiles" => data_class_files.length,
        },
        "excludedDocs" => excluded_docs.map(&:to_h),
        "singleLanguageDocs" => single_language_docs,
        "englishKdocFiles" => english_kdoc_files,
        "internalClassFiles" => internal_class_files,
        "dataClassFiles" => data_class_files,
      }
    end
  end

  ExcludedDoc = Struct.new(:path, :reason, keyword_init: true) do
    def to_h
      { "path" => path, "reason" => reason }
    end
  end

  class Scanner
    def initialize(repository_root)
      @repository_root = Pathname.new(repository_root).expand_path
    end

    def scan
      files = tracked_files
      file_set = files.to_set
      docs = files.select { |path| DOC_EXTENSIONS.include?(File.extname(path).downcase) }
      kotlin = files.select { |path| KOTLIN_EXTENSIONS.include?(File.extname(path).downcase) }
      excluded_docs = []
      single_language_docs = []

      docs.each do |path|
        reason = exclusion_reason(path, file_set)
        if reason
          excluded_docs << ExcludedDoc.new(path: path, reason: reason)
        else
          single_language_docs << path
        end
      end

      Result.new(
        repository_root: @repository_root.to_s,
        tracked_docs: docs.length,
        tracked_kotlin: kotlin.length,
        excluded_docs: excluded_docs,
        single_language_docs: single_language_docs,
        english_kdoc_files: kotlin.select { |path| english_kdoc?(path) },
        internal_class_files: kotlin.select { |path| internal_class?(path) },
        data_class_files: kotlin.select { |path| data_class?(path) },
      )
    end

    private

    def tracked_files
      output, status = Open3.capture2("git", "-C", @repository_root.to_s, "ls-files")
      raise "git ls-files failed under #{@repository_root}" unless status.success?

      output.lines(chomp: true).sort
    end

    def exclusion_reason(path, file_set)
      return "README excluded by epic scope" if README_PATTERN.match?(path)
      return "LLM/OMX operating surface kept in English" if operating_surface?(path)
      return "bilingual manual pair kept as parity target" if paired_manual?(path, file_set)

      nil
    end

    def operating_surface?(path)
      OPERATING_SURFACES.include?(path) || OPERATING_PREFIXES.any? { |prefix| path.start_with?(prefix) }
    end

    def paired_manual?(path, file_set)
      if path.start_with?("docs/manual/en/")
        file_set.include?(path.sub("docs/manual/en/", "docs/manual/ko/"))
      elsif path.start_with?("docs/manual/ko/")
        file_set.include?(path.sub("docs/manual/ko/", "docs/manual/en/"))
      else
        false
      end
    end

    def read(path)
      File.read(@repository_root.join(path), encoding: "UTF-8", invalid: :replace, undef: :replace)
    end

    def english_kdoc?(path)
      read(path).match?(%r{/\*\*.*?[A-Za-z]{4,}.*?\*/}m)
    end

    def internal_class?(path)
      read(path).match?(/\binternal\s+(?:data\s+)?class\s+\w+/)
    end

    def data_class?(path)
      read(path).match?(/\bdata\s+class\s+\w+/)
    end
  end

  class Cli
    def initialize(argv)
      @argv = argv
      @json = false
      @limit = 40
      @repository_root = Dir.pwd
    end

    def run
      parse!
      result = Scanner.new(@repository_root).scan
      if @json
        puts JSON.pretty_generate(result.to_h)
      else
        print_text(result)
      end
    end

    private

    def parse!
      OptionParser.new do |parser|
        parser.banner = "Usage: ruby scripts/docs/localization_inventory.rb [--root PATH] [--json] [--limit N]"
        parser.on("--root PATH", "Repository root. Defaults to current directory.") { |value| @repository_root = value }
        parser.on("--json", "Emit full JSON inventory.") { @json = true }
        parser.on("--limit N", Integer, "Number of paths to show per section in text mode.") { |value| @limit = value }
      end.parse!(@argv)
    end

    def print_text(result)
      counts = result.to_h.fetch("counts")
      puts "Localization inventory for #{result.repository_root}"
      counts.each { |key, value| puts "#{key}: #{value}" }
      puts
      print_paths("singleLanguageDocs", result.single_language_docs)
      print_paths("englishKdocFiles", result.english_kdoc_files)
      print_paths("internalClassFiles", result.internal_class_files)
      print_paths("dataClassFiles", result.data_class_files)
      print_exclusions(result.excluded_docs)
    end

    def print_paths(label, paths)
      puts "## #{label} (#{paths.length})"
      paths.first(@limit).each { |path| puts path }
      puts "... #{paths.length - @limit} more" if paths.length > @limit
      puts
    end

    def print_exclusions(exclusions)
      grouped = exclusions.group_by(&:reason)
      puts "## excludedDocs (#{exclusions.length})"
      grouped.sort.each do |reason, rows|
        puts "- #{reason}: #{rows.length}"
      end
    end
  end
end

if $PROGRAM_NAME == __FILE__
  LocalizationInventory::Cli.new(ARGV).run
end
