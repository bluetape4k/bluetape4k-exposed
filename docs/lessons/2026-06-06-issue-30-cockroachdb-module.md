# Issue #30 CockroachDB Module Lesson

## Context

Issue #30 is the first CockroachDB slice under the CockroachDB epic. The module
must prove a bounded JDBC path before later PostgreSQL compatibility, custom
dialect, DDL boundary, transaction retry, or R2DBC work.

## Decision Or Finding

Use CockroachDB through the PostgreSQL JDBC driver and keep `exposed-cockroachdb`
to a small `CockroachDatabase` connection factory plus a real Testcontainers
smoke test. Reuse `CockroachServer` from `bluetape4k-testcontainers`; do not
instantiate raw Testcontainers containers in this repo.

## Outcome

The new module is auto-registered by `settings.gradle.kts`, documented in both
README locales, listed in `AGENTS.md`, added to CI/Nightly coverage, and recorded
in `CHANGELOG.md`. The smoke test covers connection readiness, `SELECT 1`, and
basic schema create/insert/select/drop behavior against a real CockroachDB
container.

## Verification

- `./gradlew projects --console=plain | rg "bluetape4k-exposed-cockroachdb|Root project"`
- `./gradlew :bluetape4k-exposed-cockroachdb:test --rerun-tasks --no-configuration-cache --no-daemon`
- `./gradlew :bluetape4k-exposed-cockroachdb:koverXmlReport --no-configuration-cache --no-daemon`
- `./gradlew :bluetape4k-exposed-cockroachdb:compileKotlin :bluetape4k-exposed-cockroachdb:compileTestKotlin --no-configuration-cache --no-daemon`
- `actionlint .github/workflows/ci.yml .github/workflows/nightly-tests.yml`
- `git diff --check`

## Future Guidance

Keep issue #31 and #32 separate. Do not broaden #30's minimal JDBC smoke module
into custom dialect or serializable transaction retry support without fresh
compatibility evidence and tests.
