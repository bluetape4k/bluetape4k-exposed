#!/usr/bin/env ruby

require "minitest/autorun"

class ValidateDownstreamConsumerTest < Minitest::Test
  SOURCE_PATH = File.expand_path("validate_downstream_consumer.rb", __dir__)
  R2DBC_DAO_FIXTURE = File.expand_path("fixtures/r2dbc-dao-kotlin", __dir__)

  def test_generated_consumer_declares_central_snapshot_repository
    source = File.read(SOURCE_PATH)
    generated_settings = source.match(
      /File\.join\(consumer, "settings\.gradle\.kts"\).*?<<~KOTLIN,\n(?<settings>.*?)^\s*KOTLIN$/m,
    )&.[](:settings)

    refute_nil(generated_settings, "generated consumer settings template must remain inspectable")
    assert_includes(generated_settings, 'name = "central-snapshots"')
    assert_includes(
      generated_settings,
      'url = uri("https://central.sonatype.com/repository/maven-snapshots/")',
    )
    metadata_sources = generated_settings.match(/metadataSources\s*\{(?<sources>.*?)\n\s*\}/m)&.[](:sources)
    refute_nil(metadata_sources, "generated consumer must require repository metadata sources")
    assert_equal(["gradleMetadata()"], metadata_sources.scan(/\w+\(\)/))

    local_repository_index = generated_settings.index('url = uri("#{repository_uri}")')
    snapshot_repository_index = generated_settings.index('name = "central-snapshots"')
    central_repository_index = generated_settings.index("mavenCentral {")
    snapshot_include_indexes = generated_settings.enum_for(
      :scan,
      'includeGroupAndSubgroups("io.github.bluetape4k")',
    ).map { Regexp.last_match.begin(0) }
    exposed_exclude_indexes = generated_settings.enum_for(
      :scan,
      'excludeGroupAndSubgroups("io.github.bluetape4k.exposed")',
    ).map { Regexp.last_match.begin(0) }

    refute_nil(local_repository_index)
    refute_nil(snapshot_repository_index)
    refute_nil(central_repository_index)
    assert_equal(2, snapshot_include_indexes.length)
    assert_operator(local_repository_index, :<, snapshot_repository_index)
    assert_operator(snapshot_repository_index, :<, central_repository_index)
    snapshot_include_index = snapshot_include_indexes.find { |index| index > snapshot_repository_index }
    refute_nil(snapshot_include_index)
    assert_operator(snapshot_include_index, :<, central_repository_index)
    assert_equal(2, exposed_exclude_indexes.length)
    assert_operator(snapshot_repository_index, :<, exposed_exclude_indexes.first)
    assert_operator(exposed_exclude_indexes.first, :<, central_repository_index)
    assert_operator(central_repository_index, :<, exposed_exclude_indexes.last)
  end

  def test_r2dbc_dao_fixture_requires_gradle_metadata_without_pom_fallback
    settings = File.read(File.join(R2DBC_DAO_FIXTURE, "settings.gradle.kts"))

    assert_includes(settings, "RepositoriesMode.FAIL_ON_PROJECT_REPOS")
    assert_includes(settings, 'url = uri(providers.gradleProperty("stagedRepository").get())')
    assert_includes(settings, 'includeGroupAndSubgroups("io.github.bluetape4k.exposed")')
    metadata_sources = settings.match(/metadataSources\s*\{(?<sources>.*?)\n\s*\}/m)&.[](:sources)
    refute_nil(metadata_sources, "R2DBC DAO consumer must declare repository metadata sources")
    assert_includes(metadata_sources, "gradleMetadata()")
    assert_equal(["gradleMetadata()"], metadata_sources.scan(/\w+\(\)/))
    assert_equal(2, settings.scan('excludeGroupAndSubgroups("io.github.bluetape4k.exposed")').length)
    refute_includes(settings, "mavenLocal()")
  end

  def test_r2dbc_dao_fixture_has_opt_in_positive_and_negative_kotlin_consumers
    build = File.read(File.join(R2DBC_DAO_FIXTURE, "build.gradle.kts"))
    positive = File.read(File.join(R2DBC_DAO_FIXTURE, "src/main/kotlin/probe/R2dbcDaoConsumer.kt"))
    negative = File.read(File.join(R2DBC_DAO_FIXTURE, "src/no-opt-in/kotlin/probe/NoOptInConsumer.kt"))

    assert_includes(build, 'io.github.bluetape4k.exposed:bluetape4k-exposed-bom:${stagedVersion}')
    assert_includes(build, 'io.github.bluetape4k.exposed:bluetape4k-exposed-r2dbc-dao')
    assert_includes(build, 'io.r2dbc:r2dbc-h2:${r2dbcH2Version}')
    assert_includes(positive, "@file:OptIn(org.jetbrains.exposed.v1.dao.r2dbc.ExperimentalR2dbcDaoApi::class)")
    assert_includes(positive, "newSuspend")
    assert_includes(positive, "children.toList()")
    assert_includes(positive, "children.count()")
    assert_includes(positive, "Parent.attach")
    refute_includes(negative, "@OptIn")
    assert_includes(negative, "newSuspend")
  end

  def test_validator_records_pom_graph_and_requires_expected_opt_in_diagnostic
    source = File.read(SOURCE_PATH)

    assert_includes(source, "compileClasspath")
    assert_includes(source, "runtimeClasspath")
    assert_includes(source, "ExperimentalR2dbcDaoApi")
    assert_includes(source, "negative_settings_text")
    assert_includes(source, "Kotlin plugin version placeholder was not replaced in negative consumer")
    assert_includes(source, "exposed-dao-r2dbc:1.6.1")
    assert_includes(source, "SHA-256")
    assert_includes(source, '"dependencyVerificationMetadata" => verification_status')
  end

  def test_validator_report_requires_gradle_metadata_without_pom_fallback
    source = File.read(SOURCE_PATH)

    assert_includes(
      source,
      '"metadataSources" => ["Gradle module metadata (required, no POM fallback)"]',
    )
    refute_includes(source, '"metadataSources" => ["Gradle module metadata", "Maven POM fallback"]')
  end

  def test_verification_metadata_status_has_one_prefix
    source = File.read(SOURCE_PATH)

    assert_includes(source, 'r2dbc-dao-consumer: dependency-verification metadata: #{verification_status}')
    assert_includes(source, '"N/A (gradle/verification-metadata.xml is absent)"')
    refute_includes(
      source,
      '"dependency-verification metadata: N/A (gradle/verification-metadata.xml is absent)"',
    )
  end
end
