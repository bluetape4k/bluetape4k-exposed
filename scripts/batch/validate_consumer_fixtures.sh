#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SOURCE_HEAD="$(git -C "$ROOT_DIR" rev-parse HEAD)"
MODULE_VERSION="${ISSUE731_MODULE_VERSION:-2.0.0}"
LEGACY_MODULE_VERSION="${ISSUE731_LEGACY_MODULE_VERSION:-1.12.1}"
BLUETAPE_BOM_VERSION="${ISSUE731_BLUETAPE_BOM_VERSION:-2.0.0-SNAPSHOT}"
BLUETAPE_VERSION="${ISSUE731_BLUETAPE_VERSION:-2.0.0-SNAPSHOT}"
KEEP_REPO="${ISSUE731_MAVEN_LOCAL_REPO:-}"

if [[ -n "$KEEP_REPO" ]]; then
  MAVEN_LOCAL_REPO="$KEEP_REPO"
  CLEANUP_REPO=0
else
  MAVEN_LOCAL_REPO="$(mktemp -d "${TMPDIR:-/tmp}/issue-731-maven-local.XXXXXX")"
  CLEANUP_REPO=1
fi

cleanup() {
  if [[ "$CLEANUP_REPO" -eq 1 ]]; then
    rm -rf "$MAVEN_LOCAL_REPO"
  fi
}
trap cleanup EXIT

mkdir -p "$MAVEN_LOCAL_REPO"
printf '%s\n' "$SOURCE_HEAD" > "$MAVEN_LOCAL_REPO/issue731-source-head.txt"

stage_cached_coordinate() {
  local group="$1"
  local module="$2"
  local version="$3"
  local cache_dir="$HOME/.gradle/caches/modules-2/files-2.1/$group/$module/$version"
  local target_dir="$MAVEN_LOCAL_REPO/${group//.//}/$module/$version"
  [[ -d "$cache_dir" ]] || return 0
  mkdir -p "$target_dir"
  find "$cache_dir" -type f \
    \( -name "$module-$version.pom" -o -name "$module-$version.module" -o -name "$module-$version.jar" \) \
    -exec cp -f {} "$target_dir/" \;
}

# The exposed POMs intentionally import the current projects BOM. Stage only
# the exact ecosystem coordinates required by this checkout; do not copy a
# user's entire Maven local repository into the isolated fixture repository.
for module_dir in "$HOME/.gradle/caches/modules-2/files-2.1/io.github.bluetape4k"/*/2.0.0-SNAPSHOT; do
  [[ -d "$module_dir" ]] || continue
  module_name="${module_dir%/2.0.0-SNAPSHOT}"
  module_name="${module_name##*/}"
  stage_cached_coordinate "io.github.bluetape4k" "$module_name" "2.0.0-SNAPSHOT"
done
stage_cached_coordinate "io.github.bluetape4k" "bluetape4k-dependencies" "$BLUETAPE_BOM_VERSION"

# The dependency-repository checkout is a separate release train and its
# 2.0.0-SNAPSHOT may not be published yet. For this isolated check, provide a
# task-local alias that imports the exact cached projects BOM; no source or
# user Maven local state is modified.
if [[ ! -f "$MAVEN_LOCAL_REPO/io/github/bluetape4k/bluetape4k-dependencies/$BLUETAPE_BOM_VERSION/bluetape4k-dependencies-$BLUETAPE_BOM_VERSION.pom" ]]; then
  dependency_bom_dir="$MAVEN_LOCAL_REPO/io/github/bluetape4k/bluetape4k-dependencies/$BLUETAPE_BOM_VERSION"
  mkdir -p "$dependency_bom_dir"
  cat > "$dependency_bom_dir/bluetape4k-dependencies-$BLUETAPE_BOM_VERSION.pom" <<EOF
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>io.github.bluetape4k</groupId>
  <artifactId>bluetape4k-dependencies</artifactId>
  <version>$BLUETAPE_BOM_VERSION</version>
  <packaging>pom</packaging>
  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>io.github.bluetape4k</groupId>
        <artifactId>bluetape4k-bom</artifactId>
        <version>$BLUETAPE_BOM_VERSION</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>
</project>
EOF
fi

gradle_args=(
  --no-configuration-cache
  --no-daemon
  --no-build-cache
  --console=plain
  "-Dmaven.repo.local=$MAVEN_LOCAL_REPO"
)

(
  cd "$ROOT_DIR"
  ./gradlew exportPublicationInventory publishPublicationValidation "${gradle_args[@]}"
)

# Resolve non-project dependencies once into the same task-local cache, then
# repeat every fixture in offline mode. The second pass is the acceptance gate.
fixtures=(aggregator-runtime core-custom-json jdbc-runtime r2dbc-jackson3-runtime legacy-binary-runtime)

for fixture in "${fixtures[@]}"; do
  fixture_tasks=(verifyProvenance compileKotlin)
  if [[ "$fixture" == "legacy-binary-runtime" ]]; then
    fixture_tasks+=(compileLegacyBinary)
  fi
  (
    cd "$ROOT_DIR"
    ISSUE731_MODULE_VERSION="$MODULE_VERSION" \
    ISSUE731_LEGACY_MODULE_VERSION="$LEGACY_MODULE_VERSION" \
    ISSUE731_BLUETAPE_BOM_VERSION="$BLUETAPE_BOM_VERSION" \
    ISSUE731_BLUETAPE_VERSION="$BLUETAPE_VERSION" \
    ISSUE731_CONSUMER_REPO="$MAVEN_LOCAL_REPO" \
    ISSUE731_SOURCE_HEAD="$SOURCE_HEAD" \
    ISSUE731_EXPECTED_HEAD="$SOURCE_HEAD" \
      ./gradlew -p "utils/batch/consumer-fixtures/$fixture" \
        "${fixture_tasks[@]}" "${gradle_args[@]}"
  )
done

for fixture in "${fixtures[@]}"; do
  (
    cd "$ROOT_DIR"
    ISSUE731_MODULE_VERSION="$MODULE_VERSION" \
    ISSUE731_LEGACY_MODULE_VERSION="$LEGACY_MODULE_VERSION" \
    ISSUE731_BLUETAPE_BOM_VERSION="$BLUETAPE_BOM_VERSION" \
    ISSUE731_BLUETAPE_VERSION="$BLUETAPE_VERSION" \
    ISSUE731_CONSUMER_REPO="$MAVEN_LOCAL_REPO" \
    ISSUE731_SOURCE_HEAD="$SOURCE_HEAD" \
    ISSUE731_EXPECTED_HEAD="$SOURCE_HEAD" \
      ./gradlew -p "utils/batch/consumer-fixtures/$fixture" \
        clean verifyProvenance compileKotlin test \
        "${gradle_args[@]}" --offline
  )
done

(
  cd "$ROOT_DIR"
  export ISSUE731_MODULE_VERSION="$MODULE_VERSION"
  export ISSUE731_BLUETAPE_BOM_VERSION="$BLUETAPE_BOM_VERSION"
  export ISSUE731_CONSUMER_REPO="$MAVEN_LOCAL_REPO"
  export ISSUE731_SOURCE_HEAD="$SOURCE_HEAD"
  export ISSUE731_EXPECTED_HEAD="$SOURCE_HEAD"
  mvn -Dmaven.repo.local="$MAVEN_LOCAL_REPO" -f \
    utils/batch/consumer-fixtures/maven-jdbc-runtime/pom.xml dependency:go-offline \
    -DincludePluginDependencies=true
  mvn -o -Dmaven.repo.local="$MAVEN_LOCAL_REPO" \
    -f utils/batch/consumer-fixtures/maven-jdbc-runtime/pom.xml \
    clean package dependency:build-classpath \
    -DskipTests -Dmdep.outputFile=target/classpath.txt
  java -cp \
    "utils/batch/consumer-fixtures/maven-jdbc-runtime/target/classes:$(< utils/batch/consumer-fixtures/maven-jdbc-runtime/target/classpath.txt)" \
    issue731.consumer.Consumer
)

printf 'consumer-fixtures: PASS sourceHead=%s repository=%s\n' "$SOURCE_HEAD" "$MAVEN_LOCAL_REPO"
