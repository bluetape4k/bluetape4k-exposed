require "net/http"
require "psych"
require "set"
require "uri"

module StableManualLinks
  MANUAL_HOST = "bluetape4k.github.io"
  MANUAL_ROOT = "manual/bluetape4k-exposed"
  URL_PATTERN = %r{https://#{Regexp.escape(MANUAL_HOST)}/(?:ko/)?#{Regexp.escape(MANUAL_ROOT)}/[^)\s]+}
  MAX_REDIRECTS = 3

  class Validator
    attr_reader :errors

    def initialize(manifest_path, readme_paths, http: false, http_checker: nil)
      @manifest_path = manifest_path
      @readme_paths = readme_paths
      @http = http
      @http_checker = http_checker || method(:fetch_status)
      @errors = []
    end

    def validate
      manifest = load_manifest
      return errors unless manifest

      manual_version = manifest.dig("publication", "manualVersion").to_s
      release_ref = manifest["releaseRef"].to_s
      if manual_version.empty? || release_ref.empty?
        errors << "manifest must define publication.manualVersion and releaseRef"
        return errors
      end
      release_version = release_ref.split(".").first(2).join(".")
      if release_version != manual_version
        errors << "manifest releaseRef #{release_ref} does not match manualVersion #{manual_version}"
        return errors
      end

      allowed_paths = manifest_paths(manifest)
      snapshots = @readme_paths.each_with_object([]) do |path, result|
        current = snapshot(path, manual_version, allowed_paths)
        result << current if current
      end
      validate_locale_parity(snapshots)
      errors
    end

    private

    def load_manifest
      manifest = Psych.safe_load(File.read(@manifest_path, encoding: "UTF-8"), aliases: false)
      unless manifest.is_a?(Hash)
        errors << "manifest must contain a mapping"
        return
      end

      manifest
    rescue Errno::ENOENT => error
      errors << "cannot read manifest #{@manifest_path}: #{error.message}"
      nil
    rescue Psych::Exception => error
      errors << "cannot parse manifest #{@manifest_path}: #{error.message}"
      nil
    end

    def manifest_paths(manifest)
      manifest.values
        .flat_map { |value| nested_strings(value) }
        .grep(%r{\A(?:en|ko)/.+\.md\z})
        .to_set
    end

    def nested_strings(value)
      case value
      when Hash
        value.values.flat_map { |nested| nested_strings(nested) }
      when Array
        value.flat_map { |nested| nested_strings(nested) }
      when String
        [value]
      else
        []
      end
    end

    def snapshot(path, manual_version, allowed_paths)
      source = File.read(path, encoding: "UTF-8")
      links = source.scan(URL_PATTERN).uniq
      normalized = []
      links.each do |link|
        relative = validate_link(link, path, manual_version, allowed_paths)
        normalized << relative if relative
      end
      Snapshot.new(path: path, links: normalized.sort)
    rescue Errno::ENOENT => error
      errors << "cannot read README #{path}: #{error.message}"
      nil
    rescue ArgumentError => error
      errors << "cannot read README #{path}: #{error.message}"
      nil
    end

    Snapshot = Struct.new(:path, :links, keyword_init: true)

    def validate_link(link, source_path, manual_version, allowed_paths)
      uri = URI.parse(link)
      path_parts = uri.path.delete_prefix("/").split("/")
      korean = path_parts.first == "ko"
      path_parts.shift if korean
      expected_prefix = MANUAL_ROOT.split("/")
      unless path_parts[0, expected_prefix.length] == expected_prefix
        errors << "#{source_path}: unsupported manual path #{uri.path}"
        return
      end

      version_index = expected_prefix.length
      actual_version = path_parts[version_index]
      if actual_version != manual_version
        errors << "#{source_path}: #{link} uses manual version #{actual_version.inspect}; expected #{manual_version} from manifest"
        return
      end

      suffix = path_parts[(version_index + 1)..]&.join("/").to_s.sub(%r{\A/+|/+$}, "")
      suffix = "index" if suffix.empty?
      suffix = suffix.delete_suffix(".html")
      suffix = suffix.delete_suffix(".md")
      relative = "#{korean ? "ko" : "en"}/#{suffix}.md"
      unless allowed_paths.include?(relative)
        errors << "#{source_path}: #{link} is not present in the central manifest (#{relative})"
        return
      end

      if @http
        status, message = @http_checker.call(uri)
        unless status && status.between?(200, 299)
          detail = message || "HTTP #{status || "unavailable"}"
          errors << "#{source_path}: #{link} failed bounded HTTP validation (#{detail})"
          return
        end
      end

      suffix
    rescue URI::InvalidURIError => error
      errors << "#{source_path}: invalid manual URL #{link}: #{error.message}"
      nil
    end

    def validate_locale_parity(snapshots)
      pairs = snapshots.group_by { |snapshot| snapshot.path.sub(/\.ko(?=\.md\z)/, "") }
      pairs.each_value do |group|
        next unless group.length == 2

        left, right = group.sort_by(&:path)
        unless left.links == right.links
          errors << "README locale links differ: #{left.path} vs #{right.path}"
        end
      end
    end

    def fetch_status(uri, redirects = 0)
      return [nil, "too many redirects"] if redirects > MAX_REDIRECTS

      response = Net::HTTP.start(
        uri.host,
        uri.port,
        use_ssl: uri.scheme == "https",
        open_timeout: 10,
        read_timeout: 10,
      ) do |http|
        http.get(uri.request_uri, { "User-Agent" => "bluetape4k-stable-manual-link-check" })
      end
      if response.is_a?(Net::HTTPRedirection) && response["location"]
        return fetch_status(URI.join(uri.to_s, response["location"]), redirects + 1)
      end

      [response.code.to_i, nil]
    rescue StandardError => error
      [nil, error.message]
    end
  end
end

if $PROGRAM_NAME == __FILE__
  http = ARGV.delete("--http")
  if ARGV.length < 2
    warn "Usage: ruby #{File.basename(__FILE__)} [--http] MANIFEST.yaml README.md [README.ko.md ...]"
    exit 2
  end

  errors = StableManualLinks::Validator.new(ARGV.shift, ARGV, http: !http.nil?).validate
  if errors.empty?
    puts "Stable manual links are aligned"
  else
    warn errors.join("\n")
    exit 1
  end
end
