require "fileutils"
require "minitest/autorun"
require "tmpdir"

require_relative "publication_pom_audit"

class PublicationPomAuditTest < Minitest::Test
  MIT_LICENSE = "<licenses><license><name>MIT License</name><url>https://opensource.org/licenses/MIT</url></license></licenses>".freeze

  def test_accepts_mit_license_in_namespaced_pom
    with_pom('<project xmlns="http://maven.apache.org/POM/4.0.0"/>') do |path|
      assert_empty Publication::PomAudit.new([path]).validate.errors
    end
  end

  def test_rejects_missing_license
    with_pom("<project/>", license_xml: nil) do |path|
      assert_license_error(path)
    end
  end

  def test_rejects_apache_license
    apache = MIT_LICENSE.sub("MIT License", "The Apache License, Version 2.0")
      .sub("https://opensource.org/licenses/MIT", "https://www.apache.org/licenses/LICENSE-2.0.txt")
    with_pom("<project/>", license_xml: apache) { |path| assert_license_error(path) }
  end

  def test_rejects_wrong_license_url
    with_pom("<project/>", license_xml: MIT_LICENSE.sub("/MIT", "/Apache-2.0")) do |path|
      assert_license_error(path)
    end
  end

  def test_rejects_multiple_licenses
    duplicate = MIT_LICENSE.sub("</licenses>", "<license><name>Apache-2.0</name></license></licenses>")
    with_pom("<project/>", license_xml: duplicate) { |path| assert_license_error(path) }
  end

  def test_accepts_dependencies_with_explicit_versions
    with_pom(<<~XML) do |path|
      <project>
        <dependencies>
          <dependency>
            <groupId>org.example</groupId>
            <artifactId>example-core</artifactId>
            <version>1.2.3</version>
          </dependency>
        </dependencies>
      </project>
    XML
      result = Publication::PomAudit.new([path]).validate
      assert_empty result.errors
      assert_equal 1, result.file_count
      assert_equal 1, result.dependency_count
    end
  end

  def test_rejects_versionless_regular_and_managed_dependencies
    with_pom(<<~XML) do |path|
      <project>
        <dependencyManagement>
          <dependencies>
            <dependency>
              <groupId>org.example</groupId>
              <artifactId>example-bom</artifactId>
              <type>pom</type>
              <scope>import</scope>
            </dependency>
          </dependencies>
        </dependencyManagement>
        <dependencies>
          <dependency>
            <groupId>org.example</groupId>
            <artifactId>example-core</artifactId>
          </dependency>
        </dependencies>
      </project>
    XML
      errors = Publication::PomAudit.new([path]).validate.errors
      assert_equal 2, errors.length
      assert errors.any? { |error| error.end_with?("missing dependency version: org.example:example-bom") }
      assert errors.any? { |error| error.end_with?("missing dependency version: org.example:example-core") }
    end
  end

  def test_accepts_a_versionless_dependency_managed_by_the_same_pom
    with_pom(<<~XML) do |path|
      <project>
        <dependencyManagement>
          <dependencies>
            <dependency>
              <groupId>org.example</groupId>
              <artifactId>example-core</artifactId>
              <version>1.2.3</version>
            </dependency>
          </dependencies>
        </dependencyManagement>
        <dependencies>
          <dependency>
            <groupId>org.example</groupId>
            <artifactId>example-core</artifactId>
          </dependency>
        </dependencies>
      </project>
    XML
      result = Publication::PomAudit.new([path]).validate
      assert_empty result.errors
      assert_equal 2, result.dependency_count
    end
  end

  def test_accepts_a_versionless_dependency_when_a_versioned_bom_is_imported
    with_pom(<<~XML) do |path|
      <project>
        <dependencyManagement>
          <dependencies>
            <dependency>
              <groupId>org.example</groupId>
              <artifactId>example-bom</artifactId>
              <version>1.2.3</version>
              <type>pom</type>
              <scope>import</scope>
            </dependency>
          </dependencies>
        </dependencyManagement>
        <dependencies>
          <dependency>
            <groupId>org.example</groupId>
            <artifactId>example-core</artifactId>
          </dependency>
        </dependencies>
      </project>
    XML
      result = Publication::PomAudit.new([path]).validate
      assert_empty result.errors
      assert_equal 2, result.dependency_count
    end
  end

  def test_fails_closed_when_no_publication_poms_exist
    result = Publication::PomAudit.new([]).validate
    assert_equal ["no publication POM files found"], result.errors
    assert_equal 0, result.file_count
    assert_equal 0, result.dependency_count
  end

  private

  def assert_license_error(path)
    errors = Publication::PomAudit.new([path]).validate.errors
    assert_equal 1, errors.length
    assert_includes errors.first, "publication license must be MIT License (https://opensource.org/licenses/MIT)"
  end

  def with_pom(content, license_xml: MIT_LICENSE)
    Dir.mktmpdir("publication-pom-audit") do |root|
      path = File.join(root, "pom-default.xml")
      document = REXML::Document.new(content)
      document.root.add_element(REXML::Document.new(license_xml).root) if license_xml
      File.write(path, document.to_s)
      yield path
    end
  end
end
