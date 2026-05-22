# Exposed 1.9.0 Release Prep

## Context

`bluetape4k-projects` 1.9.0 was released and visible on Maven Central, so the
Exposed 1.9.0 release line could move from the 1.8.x core BOM to
`io.github.bluetape4k:bluetape4k-bom:1.9.0`.

## Decision

Prepare the release tag with `baseVersion=1.9.0`, `snapshotVersion=`, and both
the Gradle property and version catalog pinned to `bluetape4k-bom:1.9.0`.

## Outcome

The generated publication metadata publishes immutable
`io.github.bluetape4k.exposed` 1.9.0 artifacts and imports the immutable
`io.github.bluetape4k:bluetape4k-bom:1.9.0` dependency BOM.

## Verification

- `curl -fsSL https://repo.maven.apache.org/maven2/io/github/bluetape4k/bluetape4k-bom/1.9.0/bluetape4k-bom-1.9.0.pom`
- `actionlint .github/workflows/release.yml .github/workflows/publish-snapshot.yml .github/workflows/nightly-tests.yml .github/workflows/ci.yml`
- `./gradlew properties --no-configuration-cache --no-daemon --quiet`
- `./gradlew generatePomFileForBluetapeExposedPublication --no-daemon --no-configuration-cache --no-build-cache`
- Generated POM scan for `SNAPSHOT|examples|demo|benchmark`.
- Generated POM scan for `io.github.bluetape4k:bluetape4k-bom:1.9.0`.
- `./gradlew build -x test -x koverVerify publishToMavenLocal --parallel --no-daemon --no-configuration-cache --no-build-cache`
- `./gradlew :bluetape4k-exposed-jdbc:test --no-daemon --no-configuration-cache --no-build-cache`

## Future Guard

Do not tag a release while `snapshotVersion` is non-empty or while generated
POMs import snapshot upstream BOMs.
