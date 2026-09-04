module SpringModulithReadmeContract
  ARTIFACT_PATTERN = /implementation\("io\.github\.bluetape4k\.exposed:([^:"]+):([^"]+)"\)/

  class Validator
    attr_reader :errors

    def initialize(root_readme, readme_paths)
      @root_readme = root_readme
      @readme_paths = readme_paths
      @errors = []
    end

    def validate
      stable_version = stable_version_from_root
      return errors unless stable_version

      snapshots = @readme_paths.each_with_object([]) do |path, result|
        snapshot = readme_snapshot(path, stable_version)
        result << snapshot if snapshot
      end
      validate_locale_parity(snapshots)
      errors
    end

    private

    def stable_version_from_root
      source = File.read(@root_readme, encoding: "UTF-8")
      match = source.match(/Latest stable:\*{0,2}\s*`([^`]+)`/)
      unless match
        errors << "cannot derive Latest stable version from #{@root_readme}"
        return
      end

      match[1]
    rescue Errno::ENOENT => error
      errors << "cannot read root README #{@root_readme}: #{error.message}"
      nil
    end

    def readme_snapshot(path, stable_version)
      source = File.read(path, encoding: "UTF-8")
      dependencies = source.scan(ARTIFACT_PATTERN)
      expected_artifacts = %w[bluetape4k-exposed-spring-boot-jdbc bluetape4k-exposed-spring-modulith]
      actual_artifacts = dependencies.map(&:first)
      unless actual_artifacts == expected_artifacts
        errors << "#{path}: expected Spring Modulith dependency artifacts #{expected_artifacts.join(", ")}, got #{actual_artifacts.join(", ")}"
      end

      dependencies.each do |artifact, version|
        if version != stable_version
          errors << "#{path}: #{artifact} uses #{version}; expected stable #{stable_version}"
        end
      end

      Snapshot.new(path: path, dependencies: dependencies)
    rescue Errno::ENOENT => error
      errors << "cannot read README #{path}: #{error.message}"
      nil
    end

    Snapshot = Struct.new(:path, :dependencies, keyword_init: true)

    def validate_locale_parity(snapshots)
      pairs = snapshots.group_by { |snapshot| snapshot.path.sub(/\.ko(?=\.md\z)/, "") }
      pairs.each_value do |group|
        next unless group.length == 2

        left, right = group.sort_by(&:path)
        unless left.dependencies == right.dependencies
          errors << "Spring Modulith README dependency examples differ: #{left.path} vs #{right.path}"
        end
      end
    end
  end
end

if $PROGRAM_NAME == __FILE__
  if ARGV.length != 3
    warn "Usage: ruby #{File.basename(__FILE__)} ROOT_README.md README.md README.ko.md"
    exit 2
  end

  errors = SpringModulithReadmeContract::Validator.new(ARGV[0], ARGV[1..]).validate
  if errors.empty?
    puts "Spring Modulith README dependency contract is aligned"
  else
    warn errors.join("\n")
    exit 1
  end
end
