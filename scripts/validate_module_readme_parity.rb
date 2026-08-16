module ModuleReadmeParity
  START_MARKER = "<!-- jdbc-fluent-query:START -->"
  END_MARKER = "<!-- jdbc-fluent-query:END -->"
  REQUIRED_CONTRACT_KEYS = %w[
    attached-probe
    closed-projection
    open-projection-rejected
    first-one-all-page-count-exists
    cursor-outer-transaction
    cursor-same-thread
    cursor-explicit-close
  ].freeze

  Snapshot = Struct.new(
    :contract_keys,
    :fence_languages,
    :inline_identifiers,
    :local_links,
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

      contract_keys = section.scan(/<!-- contract-key:([a-z0-9-]+) -->/).flatten
      validate_contract_keys(contract_keys, locale)
      validate_local_links(section, path, locale)

      Snapshot.new(
        contract_keys: contract_keys,
        fence_languages: section.scan(/^```([a-z0-9+-]*)\s*$/).flatten,
        inline_identifiers: inline_identifiers(section),
        local_links: local_links(section),
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
        errors << "#{path}: expected exactly one JDBC FluentQuery marker pair"
        return
      end

      start_index = source.index(START_MARKER) + START_MARKER.length
      end_index = source.index(END_MARKER)
      if end_index <= start_index
        errors << "#{path}: JDBC FluentQuery end marker precedes start marker"
        return
      end

      source[start_index...end_index]
    end

    def validate_contract_keys(keys, locale)
      counts = keys.each_with_object(Hash.new(0)) { |key, result| result[key] += 1 }
      missing = REQUIRED_CONTRACT_KEYS - keys
      duplicates = counts.select { |_key, count| count != 1 }.keys
      unknown = keys - REQUIRED_CONTRACT_KEYS

      errors << "#{locale}: missing contract keys: #{missing.join(', ')}" unless missing.empty?
      errors << "#{locale}: duplicate contract keys: #{duplicates.join(', ')}" unless duplicates.empty?
      errors << "#{locale}: unknown contract keys: #{unknown.join(', ')}" unless unknown.empty?
    end

    def inline_identifiers(section)
      without_fences = section.gsub(/^```.*?^```\s*$/m, "")
      without_fences.scan(/(?<!`)`([^`\n]+)`(?!`)/).flatten.uniq.sort
    end

    def local_links(section)
      section.scan(/\[[^\]]+\]\(([^)]+)\)/).flatten
        .reject { |target| target.start_with?("http://", "https://", "#") }
        .sort
    end

    def validate_local_links(section, path, locale)
      local_links(section).each do |target|
        clean_target = target.split("#", 2).first
        resolved = File.expand_path(clean_target, File.dirname(path))
        errors << "#{locale}: missing local link target #{target}" unless File.exist?(resolved)
      end
    end

    def compare(english, korean)
      {
        "contract keys" => [english.contract_keys, korean.contract_keys],
        "code fence languages" => [english.fence_languages, korean.fence_languages],
        "inline technical identifiers" => [english.inline_identifiers, korean.inline_identifiers],
        "local links" => [english.local_links, korean.local_links],
      }.each do |label, (left, right)|
        errors << "JDBC FluentQuery README #{label} differ" unless left == right
      end
    end
  end
end

if $PROGRAM_NAME == __FILE__
  unless ARGV.length == 2
    warn "Usage: ruby #{File.basename(__FILE__)} README.md README.ko.md"
    exit 2
  end

  errors = ModuleReadmeParity::Validator.new(ARGV[0], ARGV[1]).validate
  if errors.empty?
    puts "JDBC FluentQuery README parity is aligned"
  else
    warn errors.join("\n")
    exit 1
  end
end
