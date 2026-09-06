require 'digest'
require 'fileutils'
require 'json'
require 'open3'
require 'optparse'

module TestSupportConsumer
  module_function

  def options(arguments)
    result = {}
    OptionParser.new do |parser|
      %w[baseline-ref candidate-root output-dir].each do |name|
        parser.on("--#{name} VALUE") { |value| result[name.tr('-', '_').to_sym] = value }
      end
    end.parse!(arguments)
    raise ArgumentError, 'baseline-ref, candidate-root and output-dir are required' unless result.size == 3
    raise ArgumentError, 'unexpected positional arguments' unless arguments.empty?
    result
  end

  def digest(path)
    raise ArgumentError, "required file missing: #{path}" unless File.file?(path)
    Digest::SHA256.file(path).hexdigest
  end

  def run!(command, directory, log)
    File.open(log, 'w') do |output|
      success = system(*command, chdir: directory, out: output, err: output)
      raise "command failed; inspect #{log}" unless success
    end
  end

  def validate_main_runtime!(files)
    leaked = files.grep(/bluetape4k-exposed-(?:jdbc|r2dbc)-tests|exposed-spring-boot4-starter/)
    raise ArgumentError, 'test-support leaked into main runtime' unless leaked.empty?
  end

  def verify_digests!(expected)
    raise ArgumentError, 'baseline class inventory is empty' if expected.empty?
    expected.each do |path, checksum|
      raise ArgumentError, "baseline checksum changed: #{path}" unless digest(path) == checksum
    end
  end

  MODULES = %w[bluetape4k-exposed-jdbc-tests bluetape4k-exposed-r2dbc-tests].freeze

  def gradle!(root, arguments, log)
    run!([File.join(root, 'gradlew'), *arguments, '--no-parallel', '--no-configuration-cache',
          '--console=plain'], root, log)
  end

  # 외부 배포 설정 대신 이 검증 전용 file repository만 등록한다.
  def publish!(source, repository, output, kind)
    init = File.join(output, "#{kind}-publish.gradle")
    File.write(init, <<~GROOVY)
      import groovy.json.JsonOutput
      gradle.beforeProject { p ->
        if (p.rootProject.projectDir.canonicalPath == new File(#{source.to_json}).canonicalPath && #{MODULES.to_json}.contains(p.name)) {
          p.pluginManager.withPlugin('maven-publish') {
            p.publishing.repositories.maven {
              name = 'Issue815'
              url = new File(#{repository.to_json}).toURI()
            }
          }
        }
      }
      gradle.projectsEvaluated {
        if (gradle.rootProject.projectDir.canonicalPath != new File(#{source.to_json}).canonicalPath) return
        #{MODULES.to_json}.each { name ->
          def p = gradle.rootProject.project(':' + name)
          p.tasks.register('issue815Facts') {
            doLast {
              def facts = [group: p.group.toString(), version: p.version.toString(),
                kotlin: p.extensions.getByType(org.gradle.api.artifacts.VersionCatalogsExtension).named('bt4k').findVersion('kotlin').get().requiredVersion,
                supplemental: p.configurations.compileOnly.allDependencies.findAll {
                  it.group && it.version
                }.collect { it.group + ':' + it.name + ':' + it.version }]
              new File(#{output.to_json}, '#{kind}-' + name + '.json').text = JsonOutput.toJson(facts)
            }
          }
        }
      }
    GROOVY
    tasks = MODULES.flat_map do |name|
      [":#{name}:publishBluetapeExposedPublicationToIssue815Repository", ":#{name}:issue815Facts"]
    end
    gradle!(source, ['-I', init, *tasks], File.join(output, "#{kind}-publish.log"))
    MODULES.to_h do |name|
      facts = JSON.parse(File.read(File.join(output, "#{kind}-#{name}.json")))
      directory = File.join(repository, facts.fetch('group').tr('.', '/'), name, facts.fetch('version'))
      jar = File.join(directory, "#{name}-#{facts.fetch('version')}.jar")
      [name, facts.merge('jar' => jar, 'sha256' => digest(jar))]
    end
  end

  def write_consumer!(directory, facts)
    FileUtils.mkdir_p(File.join(directory, 'src/test/kotlin'))
    File.write(File.join(directory, 'settings.gradle.kts'), "rootProject.name = \"issue815-consumer\"\n")
    dependencies = facts.map do |name, value|
      "testImplementation(#{"#{value.fetch('group')}:#{name}:#{value.fetch('version')}".to_json})"
    end
    # provider의 compileOnly 연결 공급원은 consumer가 명시적으로 소유한다.
    supplemental = facts.values.flat_map { |value| value.fetch('supplemental') }.uniq
    dependencies.concat(supplemental.map { |coordinate| "testImplementation(#{coordinate.to_json})" })
    File.write(File.join(directory, 'build.gradle.kts'), <<~KOTLIN)
      import java.security.MessageDigest
      plugins { kotlin("jvm") version #{facts.values.first.fetch('kotlin').to_json} }
      repositories {
          exclusiveContent {
              forRepository { maven { url = uri(providers.gradleProperty("providerRepository").get()) } }
              filter { #{MODULES.map { |name| "includeModule(\"io.github.bluetape4k.exposed\", #{name.to_json})" }.join('; ')} }
          }
          mavenCentral()
          maven { url = uri("https://central.sonatype.com/repository/maven-snapshots/") }
      }
      dependencies { #{dependencies.join("\n")} }
      tasks.register<JavaExec>("verifyLinkage") {
          classpath = sourceSets.test.get().runtimeClasspath
          mainClass.set("consumer.LegacyConsumerKt")
          doFirst {
              val providers = classpath.files.filter { file -> listOf(#{MODULES.map(&:to_json).join(', ')}).any { file.name.startsWith(it + "-") } }
              check(providers.size == 2)
              val hashes = providers.associate { file ->
                  file.name to MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                      .joinToString("") { "%02x".format(it) }
              }
              file("resolved-provider-hashes.json").writeText(hashes.entries.joinToString(",", "{", "}") { "\\\"${it.key}\\\":\\\"${it.value}\\\"" })
              file("main-runtime.txt").writeText(sourceSets.main.get().runtimeClasspath.files.joinToString("\\n") { it.name })
          }
      }
    KOTLIN
    FileUtils.cp(File.join(__dir__, 'test_support_consumer/LegacyConsumer.kt'),
                 File.join(directory, 'src/test/kotlin/LegacyConsumer.kt'))
  end

  def execute(arguments)
    args = options(arguments)
    root = File.realpath(args.fetch(:candidate_root))
    ref, status = Open3.capture2('git', 'rev-parse', '--verify', "#{args.fetch(:baseline_ref)}^{commit}", chdir: root)
    raise ArgumentError, 'invalid baseline commit' unless status.success?
    ref = ref.strip
    output = File.expand_path(args.fetch(:output_dir))
    raise ArgumentError, 'output must be inside candidate build/issue815/' unless output.start_with?(File.join(root, 'build/issue815/'))
    manifest_path = File.join(output, 'manifest.json')
    if File.exist?(output)
      raise ArgumentError, 'existing output lacks baseline manifest' unless File.file?(manifest_path)
      manifest = JSON.parse(File.read(manifest_path))
      raise ArgumentError, 'baseline ref mismatch' unless manifest.fetch('baseline_ref') == ref
      verify_digests!(manifest.fetch('classes'))
    else
      FileUtils.mkdir_p(output)
      baseline = File.join(output, 'baseline-source')
      run!(['git', 'clone', '--no-hardlinks', '--no-checkout', root, baseline], root, File.join(output, 'clone.log'))
      run!(['git', 'checkout', '--detach', ref], baseline, File.join(output, 'baseline-checkout.log'))
      baseline_repo = File.join(output, 'm2-baseline')
      baseline_facts = publish!(baseline, baseline_repo, output, 'baseline')
      consumer = File.join(output, 'consumer')
      write_consumer!(consumer, baseline_facts)
      gradle!(root, ['-p', consumer, "-PproviderRepository=#{baseline_repo}", 'testClasses', 'verifyLinkage'], File.join(output, 'baseline-consumer.log'))
      classes = Dir.glob(File.join(consumer, 'build/classes/**/*')).select { |path| File.file?(path) }.to_h { |path| [path, digest(path)] }
      verify_digests!(classes)
      manifest = { 'baseline_ref' => ref, 'classes' => classes, 'baseline' => baseline_facts }
      File.write(manifest_path, JSON.pretty_generate(manifest))
    end
    candidate_repo = File.join(output, 'm2-candidate')
    candidate = publish!(root, candidate_repo, output, 'candidate')
    consumer = File.join(output, 'consumer')
    # 컴파일 task를 제외하고 이전 class를 그대로 실행한다.
    gradle!(root, ['-p', consumer, "-PproviderRepository=#{candidate_repo}", '--refresh-dependencies',
                  'verifyLinkage', '-x', 'compileTestKotlin', '-x', 'compileTestJava'], File.join(output, 'candidate-consumer.log'))
    verify_digests!(manifest.fetch('classes'))
    resolved = JSON.parse(File.read(File.join(consumer, 'resolved-provider-hashes.json')))
    candidate.each_value do |facts|
      raise 'resolved provider checksum mismatch' unless resolved[File.basename(facts.fetch('jar'))] == facts.fetch('sha256')
    end
    validate_main_runtime!(File.readlines(File.join(consumer, 'main-runtime.txt'), chomp: true))
    File.write(File.join(output, 'result.json'), JSON.pretty_generate({ 'baseline_ref' => ref, 'candidate' => candidate, 'linkage' => 'PASS', 'baseline_classes_unchanged' => true, 'main_runtime' => 'PASS' }))
    puts 'PASS: baseline classes unchanged; candidate linkage and main runtime verified'
  end
end

if $PROGRAM_NAME == __FILE__
  begin
    TestSupportConsumer.execute(ARGV)
  rescue ArgumentError, RuntimeError, KeyError, JSON::ParserError, SystemCallError => error
    warn "FAIL: #{error.message}"
    exit 1
  end
end
