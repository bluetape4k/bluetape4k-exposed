require 'minitest/autorun'
require 'tmpdir'
require_relative 'validate_test_support_consumer'

class TestSupportConsumerTest < Minitest::Test
  def test_missing_arguments_fail_closed
    assert_raises(ArgumentError) { TestSupportConsumer.options([]) }
  end

  def test_missing_artifact_fails_closed
    assert_raises(ArgumentError) { TestSupportConsumer.digest('/missing/provider.jar') }
  end

  def test_failed_command_is_not_a_success
    Dir.mktmpdir('issue815-command') do |dir|
      assert_raises(RuntimeError) do
        TestSupportConsumer.run!(['ruby', '-e', 'exit 7'], dir, File.join(dir, 'command.log'))
      end
    end
  end

  def test_main_runtime_rejects_test_support_and_starter
    assert_raises(ArgumentError) do
      TestSupportConsumer.validate_main_runtime!(['bluetape4k-exposed-jdbc-tests-2.1.0.jar'])
    end
    assert_raises(ArgumentError) do
      TestSupportConsumer.validate_main_runtime!(['exposed-spring-boot4-starter-1.5.0.jar'])
    end
    TestSupportConsumer.validate_main_runtime!(['kotlin-stdlib-2.3.20.jar'])
  end

  def test_changed_baseline_classes_fail_closed
    Dir.mktmpdir('issue815-classes') do |dir|
      path = File.join(dir, 'Consumer.class')
      File.write(path, 'baseline')
      expected = { path => TestSupportConsumer.digest(path) }
      File.write(path, 'recompiled')
      assert_raises(ArgumentError) { TestSupportConsumer.verify_digests!(expected) }
    end
  end
end
