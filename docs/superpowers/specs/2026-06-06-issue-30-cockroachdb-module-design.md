# Issue #30 CockroachDB Module Design

Date: 2026-06-06
Issue: https://github.com/bluetape4k/bluetape4k-exposed/issues/30
Parent epic: https://github.com/bluetape4k/bluetape4k-exposed/issues/24

## Goal

Add a minimal `exposed/exposed-cockroachdb` module that proves
`bluetape4k-exposed` can connect to CockroachDB over JDBC and pass a real
Testcontainers-backed smoke test.

This is the first bounded CockroachDB slice. It must not claim full PostgreSQL
dialect parity.

## Scope

- Add a publishable Gradle module named `:bluetape4k-exposed-cockroachdb`.
- Provide a small `CockroachDatabase` connection helper for PostgreSQL-wire
  CockroachDB JDBC URLs.
- Reuse the existing `bluetape4k-testcontainers` `CockroachServer` test fixture.
- Add a single-node smoke test that verifies:
  - connection through `CockroachServer`;
  - `SELECT 1`;
  - simple table create, insert, select, and drop through Exposed where feasible.
- Add `README.md` and `README.ko.md` with explicit limitations.
- Update root README locale set, `CHANGELOG.md`, `AGENTS.md`, CI, Nightly, and
  coverage aggregation registration.

## Non-Goals

- Do not implement a custom CockroachDB Exposed dialect in this issue.
- Do not override global PostgreSQL dialect registration.
- Do not provide transaction retry helper APIs; #32 owns retry guidance.
- Do not document or test a complete PostgreSQL compatibility matrix; #31 owns
  DDL and compatibility boundaries.
- Do not add R2DBC support.

## API Contract

`CockroachDatabase` exposes these stable entry points:

- `DRIVER`: PostgreSQL JDBC driver class name.
- `connect(jdbcUrl, user, password, databaseConfig)`.
- `connect(host, port, database, user, password, databaseConfig)`.
- `connect(dataSource, databaseConfig)`.
- `buildJdbcUrl(host, port, database)`.

The helper validates blank host, database, user, and JDBC URL values using
bluetape4k validation helpers. It accepts only `jdbc:postgresql://` URLs because
the existing `CockroachServer` and CockroachDB JDBC path use the PostgreSQL JDBC
driver.

## Test Contract

- The smoke test must use `CockroachServer.Launcher.cockroach`, not a raw
  `GenericContainer`.
- Testcontainers-backed verification must run serially.
- The test must use bluetape4k assertion helpers.
- Test resources must include `junit-platform.properties` and
  `logback-test.xml`.

## Documentation Contract

- Public API KDoc is English.
- `README.md` is English and `README.ko.md` is Korean.
- Both READMEs must state that CockroachDB is PostgreSQL-wire-compatible but not
  PostgreSQL-equivalent, and that custom dialect/DDL boundary and retry guidance
  remain follow-up work.
- Contributor-facing GitHub issue, PR, and commit text remains English.

## Acceptance Criteria

- `./gradlew projects` lists `:bluetape4k-exposed-cockroachdb`.
- `./gradlew :bluetape4k-exposed-cockroachdb:test --no-configuration-cache --no-daemon`
  passes locally or a concrete environment blocker is recorded.
- `actionlint .github/workflows/ci.yml .github/workflows/nightly-tests.yml`
  passes.
- `git diff --check` passes.
- Step 6-R local 7-tier review has `P0 = 0` and `P1 = 0`.
- PR body ends with `## DoD Status`.
