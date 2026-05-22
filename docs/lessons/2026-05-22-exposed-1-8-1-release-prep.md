# Exposed 1.8.1 Release Prep

## Context

`bluetape4k-exposed` 1.8.1 must be published before the ecosystem moves to the
`bluetape4k-bom` 1.9.0 release line.

## Decision

Prepare the 1.8.1 release tag with `snapshotVersion=` and keep this release on
`io.github.bluetape4k:bluetape4k-bom:1.8.0`. The later 1.9.0 release line will
move to `bluetape4k-bom:1.9.0`.

## Outcome

Release metadata now matches the release workflow gate:

- `baseVersion=1.8.1`
- `snapshotVersion=`
- `bluetape4kVersion=1.8.0`
- `gradle/libs.versions.toml` `bluetape4k-bom = "1.8.0"`

## Verification

- `actionlint .github/workflows/release.yml .github/workflows/publish-snapshot.yml .github/workflows/nightly-tests.yml .github/workflows/ci.yml`
- `./gradlew generatePomFileForBluetapeExposedPublication --no-daemon --no-configuration-cache --no-build-cache`
- Generated 31 publication POMs and scanned them for `SNAPSHOT`.
- Confirmed generated publication POMs use artifact version `1.8.1` and `bluetape4k-bom:1.8.0`.
- `./gradlew build -x test -x koverVerify publishToMavenLocal --parallel --no-daemon --no-configuration-cache --no-build-cache`
- Removed the unused `opentelemetry-bom-alpha` snapshot catalog reference by
  pinning it to the current Maven Central release `1.62.0-alpha`.

Known follow-up: GitHub release workflow still needs to run after tag `1.8.1`
is pushed.

## Future Guidance

Do not let the 1.8.1 release tag consume `bluetape4k-bom:1.8.1-SNAPSHOT` or
`1.9.0`. Start the 1.9.0 development branch after the immutable 1.8.1 release is
published.
