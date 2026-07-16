module MigrationReadmeParity
  START_MARKER = "<!-- migration-guide:start -->"
  END_MARKER = "<!-- migration-guide:end -->"

  REQUIRED_MARKERS = {
    "heading" => %w[
      migration-generation
      availability
      application-users
      surface-boundaries
      repository-contributors
      failure-diagnostics
      support-matrix
      promotion-review
    ],
    "warning" => %w[
      credentials
      r2dbc-jdbc-boundary
      no-runtime-management
      immutable-migrations
    ],
    "table" => %w[
      surface-boundaries
      failure-diagnostics
      support-matrix
      promotion-review
    ],
  }.freeze

  Snapshot = Struct.new(
    :markers,
    :headings,
    :fences,
    :table_row_keys,
    :commands,
    :urls,
    keyword_init: true,
  )

  class Validator
    attr_reader :errors

    def initialize(english_path, korean_path)
      @english_path = english_path
      @korean_path = korean_path
      @errors = []
    end

    def validate
      english = snapshot(@english_path, "English")
      korean = snapshot(@korean_path, "Korean")
      compare(english, korean) if english && korean
      errors
    end

    private

    def snapshot(path, locale)
      source = File.read(path, encoding: "UTF-8")
      section = marked_section(source, path)
      return unless section

      markers = section.scan(/<!-- migration-guide:(heading|warning|table):([a-z0-9-]+) -->/)
      validate_required_markers(markers, locale)

      headings = section.scan(
        /<!-- migration-guide:heading:([a-z0-9-]+) -->\s*\n(\#{3,6})\s+[^\n]+/,
      ).map { |key, hashes| [key, hashes.length] }
      heading_count = section.scan(/^\#{3,6}\s+[^\n]+/).length
      if heading_count != headings.length
        errors << "#{locale}: every migration heading must have a semantic marker"
      end

      fences = section.scan(/^```(bash|kotlin)\s*\n(.*?)^```\s*$/m).map do |language, body|
        [language, normalize_block(body)]
      end

      Snapshot.new(
        markers: markers,
        headings: headings,
        fences: fences,
        table_row_keys: section.scan(/^\|\s*`([^`]+)`\s*\|/).flatten,
        commands: extract_commands(section),
        urls: section.scan(/\]\((https:\/\/[^)]+)\)/).flatten.sort,
      )
    rescue Errno::ENOENT
      errors << "#{locale}: missing README #{path}"
      nil
    rescue ArgumentError => error
      errors << "#{locale}: cannot read #{path}: #{error.message}"
      nil
    end

    def marked_section(source, path)
      starts = source.scan(START_MARKER).length
      ends = source.scan(END_MARKER).length
      if starts != 1 || ends != 1
        errors << "#{path}: expected exactly one migration guide marker pair"
        return
      end

      start_index = source.index(START_MARKER) + START_MARKER.length
      end_index = source.index(END_MARKER)
      if end_index <= start_index
        errors << "#{path}: migration guide end marker precedes start marker"
        return
      end

      source[start_index...end_index]
    end

    def validate_required_markers(markers, locale)
      REQUIRED_MARKERS.each do |kind, required_keys|
        actual_keys = markers.select { |marker_kind, _key| marker_kind == kind }.map(&:last)
        missing = required_keys - actual_keys
        counts = actual_keys.each_with_object(Hash.new(0)) { |key, result| result[key] += 1 }
        duplicates = counts.select { |_key, count| count != 1 }.keys
        unknown = actual_keys - required_keys
        errors << "#{locale}: missing #{kind} markers: #{missing.join(', ')}" unless missing.empty?
        errors << "#{locale}: duplicate #{kind} markers: #{duplicates.join(', ')}" unless duplicates.empty?
        errors << "#{locale}: unknown #{kind} markers: #{unknown.join(', ')}" unless unknown.empty?
      end
    end

    def extract_commands(section)
      fenced_commands = section.scan(/^```bash\s*\n(.*?)^```\s*$/m).flatten.flat_map do |body|
        normalize_block(body).lines.map do |line|
          stripped = line.strip
          stripped.empty? ? nil : stripped
        end.compact
      end
      inline_commands = section.scan(/`([^`\n]+)`/).flatten.select do |value|
        value.include?("generateMigrations") || value.include?("migrationDriftTest") ||
          value.include?("--stacktrace --info")
      end
      fenced_commands + inline_commands
    end

    def normalize_block(body)
      body.lines.map(&:rstrip).join("\n").strip
    end

    def compare(english, korean)
      {
        "semantic markers" => [english.markers, korean.markers],
        "headings" => [english.headings, korean.headings],
        "shell/Kotlin fences" => [english.fences, korean.fences],
        "table row keys" => [english.table_row_keys, korean.table_row_keys],
        "commands" => [english.commands, korean.commands],
        "URLs" => [english.urls, korean.urls],
      }.each do |label, (left, right)|
        errors << "README migration #{label} differ" unless left == right
      end
    end
  end
end

if $PROGRAM_NAME == __FILE__
  unless ARGV.length == 2
    warn "Usage: ruby #{File.basename(__FILE__)} README.md README.ko.md"
    exit 2
  end

  errors = MigrationReadmeParity::Validator.new(ARGV[0], ARGV[1]).validate
  if errors.empty?
    puts "Migration README parity is aligned"
  else
    warn errors.join("\n")
    exit 1
  end
end
