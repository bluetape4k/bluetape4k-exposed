#!/usr/bin/env ruby

require "minitest/autorun"

class ValidateDownstreamConsumerTest < Minitest::Test
  SOURCE_PATH = File.expand_path("validate_downstream_consumer.rb", __dir__)

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

    local_repository_index = generated_settings.index('url = uri("#{repository_uri}")')
    snapshot_repository_index = generated_settings.index('name = "central-snapshots"')
    central_repository_index = generated_settings.index("mavenCentral {")
    snapshot_include_index = generated_settings.index(
      'includeGroupAndSubgroups("io.github.bluetape4k")',
    )
    exposed_exclude_indexes = generated_settings.enum_for(
      :scan,
      'excludeGroup("io.github.bluetape4k.exposed")',
    ).map { Regexp.last_match.begin(0) }

    refute_nil(local_repository_index)
    refute_nil(snapshot_repository_index)
    refute_nil(central_repository_index)
    refute_nil(snapshot_include_index)
    assert_operator(local_repository_index, :<, snapshot_repository_index)
    assert_operator(snapshot_repository_index, :<, central_repository_index)
    assert_operator(snapshot_repository_index, :<, snapshot_include_index)
    assert_operator(snapshot_include_index, :<, central_repository_index)
    assert_equal(2, exposed_exclude_indexes.length)
    assert_operator(snapshot_repository_index, :<, exposed_exclude_indexes.first)
    assert_operator(exposed_exclude_indexes.first, :<, central_repository_index)
    assert_operator(central_repository_index, :<, exposed_exclude_indexes.last)
  end
end
