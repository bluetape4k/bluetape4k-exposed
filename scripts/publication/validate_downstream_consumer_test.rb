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
  end
end
