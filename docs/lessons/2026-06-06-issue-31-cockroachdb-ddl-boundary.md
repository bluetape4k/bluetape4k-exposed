# Issue #31 CockroachDB DDL Boundary Lesson

Date: 2026-06-06
Repo: `bluetape4k-exposed`

## Context

Issue #31 needed an executable boundary for `exposed-cockroachdb` without
turning CockroachDB into a broad PostgreSQL alias.

## Decision

- Use CockroachDB Testcontainers evidence for accepted DDL paths.
- Use `bluetape4k-jdbc` and HikariCP for direct JDBC evidence instead of ad hoc
  `DriverManager.getConnection` in new tests and examples.
- Keep the existing simple URL factory helper-only; hidden HikariCP creation
  would make pool ownership and close responsibility unclear.
- Treat `MigrationUtils` generated-ID sequence ownership output as a deferred
  migration diff boundary, not a failing accepted DDL path.

## Outcome

The module now documents and tests the supported boundary:

- Primary key DDL.
- Unique/index DDL.
- Generated IDs.
- Raw `INSERT ... RETURNING`.
- JDBC metadata.
- Deferred migration diff semantics.
- Deferred unsupported PostgreSQL constructs such as `CREATE DOMAIN` and range
  types.

## Verification Evidence

- `./gradlew :bluetape4k-exposed-cockroachdb:compileKotlin :bluetape4k-exposed-cockroachdb:compileTestKotlin --no-configuration-cache --no-daemon`
- `./gradlew :bluetape4k-exposed-cockroachdb:test --rerun-tasks --no-configuration-cache --no-daemon`
- `./gradlew :bluetape4k-exposed-cockroachdb:koverXmlReport --no-configuration-cache --no-daemon`
- `git diff --check`

## Future Guard

When adding compatibility tests in bluetape4k repositories, search for and use
ecosystem helpers first: `bluetape4k-jdbc`, `bluetape4k-junit5`,
`bluetape4k-testcontainers`, and Exposed helper modules. Record the reason when
a raw third-party or JDK API is intentionally kept.
