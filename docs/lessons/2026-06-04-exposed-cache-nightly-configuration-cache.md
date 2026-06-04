# Lessons Learned — exposed-cache Nightly configuration cache (2026-06-04)

**Related issue**: #240
**Affected module**: `bluetape4k-exposed-cache`

## Context

Post-merge Nightly smoke failed in `Test / exposed-cache (H2)` after the
snapshot-refresh workflow fix had already passed PR CI. The failing GitHub
runner discarded a configuration-cache entry and resolved
`io.github.bluetape4k:bluetape4k-logging:.` without a version.

The follow-up PR then failed in the CI `Build (compile only)` job because CI
still resolved snapshot artifacts without `--refresh-dependencies`, so stale
Central metadata can break PR checks before Nightly runs.

## Decision

Keep dependency refresh enabled, but disable configuration cache only for the
`exposed-cache` Nightly test and Kover commands. Local macOS runs and a clean
temporary `GRADLE_USER_HOME` both passed, so the failure is runner/cache-path
specific rather than a source test failure.

Mirror snapshot refresh and GitHub runner configuration-cache avoidance in CI
Gradle invocations so PR checks and Nightly use the same dependency-resolution
policy.

## Outcome

The Nightly smoke path no longer depends on storing a configuration-cache entry
for `:bluetape4k-exposed-cache:compileKotlin` while resolving BOM-managed
bluetape4k dependencies.

## Verification

- `./gradlew --refresh-dependencies :bluetape4k-exposed-cache:test --no-daemon`
- `env GRADLE_USER_HOME=/tmp/bt4k-exposed-gradle-home ./gradlew --refresh-dependencies :bluetape4k-exposed-cache:test --no-daemon`
- `actionlint .github/workflows/ci.yml .github/workflows/nightly-tests.yml`
- CI/Nightly Gradle audit: every `./gradlew` call includes
  `--refresh-dependencies`.

## Future Rule

When a Nightly-only workflow change passes PR CI but post-merge smoke still
fails, inspect whether changed-module PR CI skipped the affected module test.
For `exposed-cache`, keep Nightly commands on `--no-configuration-cache` unless
the configuration-cache failure is fixed and verified on GitHub runners.
When changing snapshot dependency policy, audit both `.github/workflows/ci.yml`
and `.github/workflows/nightly-tests.yml`.
