# Lessons Learned — exposed-cache Nightly configuration cache (2026-06-04)

**Related issues**: #240, #242, #244
**Affected module**: `bluetape4k-exposed-cache`

## Context

Post-merge Nightly smoke failed in `Test / exposed-cache (H2)` after the
snapshot-refresh workflow fix had already passed PR CI. The failing GitHub
runner discarded a configuration-cache entry and resolved
`io.github.bluetape4k:bluetape4k-logging:.` without a version.

The follow-up PR then failed in the CI `Build (compile only)` job because CI
still resolved snapshot artifacts without `--refresh-dependencies`, so stale
Central metadata can break PR checks before Nightly runs. After that fix merged,
post-merge Nightly smoke failed again in `Test / exposed-core + exposed-dao
(H2)`, proving the configuration-cache/BOM-empty-version failure is not limited
to the cache module.

After `--no-configuration-cache` was applied to every Nightly Gradle command,
run `26963387223` still failed in the same GitHub runner path. The failed jobs
showed `gradle/actions/setup-gradle@v6` restoring caches before the affected
test commands and then resolving BOM-managed bluetape4k dependencies with empty
versions such as `io.github.bluetape4k:bluetape4k-junit5:.`.

## Decision

Keep dependency refresh enabled and keep Nightly commands on
`--no-configuration-cache`, but do not restore Gradle caches in the Nightly
workflow while snapshot BOM metadata is being refreshed. Local macOS runs and a
clean temporary `GRADLE_USER_HOME` both passed, so the failure is runner
cache-path specific rather than a source test failure.

Mirror snapshot refresh and GitHub runner configuration-cache avoidance in CI
Gradle invocations so PR checks and Nightly use the same dependency-resolution
policy. For Nightly, apply `--no-configuration-cache` to every test and Kover
Gradle command, and set `cache-disabled: true` on every
`gradle/actions/setup-gradle@v6` step.

## Outcome

The Nightly smoke path no longer depends on restored Gradle cache state while
resolving refreshed snapshot BOM metadata for BOM-managed bluetape4k
dependencies.

## Verification

- `./gradlew --refresh-dependencies :bluetape4k-exposed-cache:test --no-daemon`
- `env GRADLE_USER_HOME=/tmp/bt4k-exposed-gradle-home ./gradlew --refresh-dependencies :bluetape4k-exposed-cache:test --no-daemon`
- `actionlint .github/workflows/ci.yml .github/workflows/nightly-tests.yml`
- CI/Nightly Gradle audit: every `./gradlew` call includes
  `--refresh-dependencies`.
- Nightly Gradle audit: every `./gradlew` run block includes
  `--no-configuration-cache`.
- Nightly setup-gradle audit: every setup step includes `cache-disabled: true`.

## Future Rule

When a Nightly-only workflow change passes PR CI but post-merge smoke still
fails, inspect whether changed-module PR CI skipped the affected module test.
Keep exposed Nightly commands on `--no-configuration-cache` unless the
configuration-cache failure is fixed and verified on GitHub runners across the
core smoke path, not just `exposed-cache`.
Do not re-enable Nightly Gradle cache restore until a post-merge smoke run proves
snapshot BOM dependency resolution is stable on GitHub runners.
When changing snapshot dependency policy, audit both `.github/workflows/ci.yml`
and `.github/workflows/nightly-tests.yml`.
