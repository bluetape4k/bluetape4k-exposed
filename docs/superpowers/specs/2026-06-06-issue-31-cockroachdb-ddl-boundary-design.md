# Issue #31 CockroachDB DDL Boundary Design

Date: 2026-06-06
Issue: https://github.com/bluetape4k/bluetape4k-exposed/issues/31
Parent epic: https://github.com/bluetape4k/bluetape4k-exposed/issues/24
Previous slice: https://github.com/bluetape4k/bluetape4k-exposed/issues/30

## Goal

Define the supported `exposed-cockroachdb` 1.11.0 DDL and PostgreSQL
compatibility boundary with executable CockroachDB evidence.

This issue must not turn CockroachDB into a broad PostgreSQL alias. It should
answer which Exposed-generated schema paths are accepted by a real CockroachDB
container, which PostgreSQL-derived paths are deferred, and whether a custom
`CockroachDbDialect` is required for the 1.11.0 contract.

## Current Evidence

- #30 landed a minimal `exposed-cockroachdb` module with `CockroachDatabase`,
  `CockroachServer` Testcontainers smoke coverage, and no custom dialect.
- CockroachDB stable docs currently expose v26.2.2. They state CockroachDB uses
  the PostgreSQL wire protocol and supports most PostgreSQL syntax, but also
  document unsupported or different PostgreSQL behavior.
- CockroachDB v26.2 docs list `CREATE DOMAIN`, PostgreSQL range types, events,
  dropping a single primary key, XML functions, column-level privileges, XA
  syntax, template database creation, single partition drop, foreign data
  wrappers, and advisory lock semantics as unsupported or different areas.
- CockroachDB SQL feature support docs list primary key, unique, check, foreign
  key, default value, indexes, `ALTER TABLE`, `RETURNING`, sequences, and
  identity columns as supported areas.
- JetBrains Exposed 1.3.0 docs do not list CockroachDB as a built-in supported
  database.
- Existing bluetape4k modules use custom dialect registration only when the
  accepted SQL surface needs a named Exposed dialect, metadata adapter, or
  disabled capabilities (`TrinoDialect`, `DuckDBDialect`, `StarRocksDialect`).

## Scope

### Implementation Scope

- Extend the existing `exposed/exposed-cockroachdb` tests with a focused
  compatibility suite.
- Add a source-visible compatibility matrix that README files can render and
  tests can validate against.
- Update `README.md` and `README.ko.md` with the accepted, deferred, and
  unsupported DDL boundary.
- Update `CHANGELOG.md`.
- If evidence shows PostgreSQLDialect needs local capability overrides, add a
  minimal `CockroachDbDialect` and register it from `CockroachDatabase`.

### Accepted Evidence Categories

The compatibility suite must cover these accepted or deferred categories:

| Category | Required Evidence |
|---|---|
| Primary key DDL | `SchemaUtils.create/drop` succeeds for a primary-key table. |
| Unique constraint/index DDL | Create/drop succeeds and duplicate insert fails. |
| Explicit index DDL | Create/drop succeeds and metadata/query path can see the table. |
| Generated IDs | Exposed insert can obtain a generated ID for the accepted table form. |
| `RETURNING` | A raw CockroachDB `INSERT ... RETURNING` smoke query succeeds through PostgreSQL JDBC. |
| Schema metadata | JDBC `DatabaseMetaData` can discover created table/columns. |

### Deferred Or Unsupported Evidence Categories

The README matrix must explicitly mark these as deferred or unsupported for
1.11.0 unless direct CockroachDB tests prove otherwise in this issue:

- Custom CockroachDB dialect parity.
- Full PostgreSQL type parity.
- PostgreSQL range types.
- `CREATE DOMAIN`.
- XML functions / XML type behavior.
- Drop-single-primary-key workflows.
- Schema migration diff no-op semantics. Implementation evidence showed
  `MigrationUtils` still proposes generated-ID sequence ownership changes after
  `SchemaUtils.create`, so #31 documents this as deferred instead of claiming
  no-op migration support.
- Advanced migration semantics beyond the observed sequence diff boundary.
- Serializable transaction retry helpers, which are owned by #32.
- R2DBC support.

## Dialect Decision Rule

Keep the helper-only module contract for 1.11.0 unless one of the accepted
evidence categories fails specifically because Exposed's default PostgreSQL
dialect advertises an unsupported CockroachDB capability or emits SQL that a
minimal CockroachDB dialect can safely fix.

If a custom dialect is added, it must be minimal:

- Register a separate dialect name without overriding global PostgreSQL
  behavior.
- Disable only proven unsafe capabilities.
- Preserve the working PostgreSQL-wire query and DDL subset.
- Add tests proving `db.dialect` is the custom dialect and accepted DDL still
  passes.

## Public API Contract

No new public API is required if the helper-only decision holds. If a dialect is
added, public API changes are limited to:

- `io.bluetape4k.exposed.cockroachdb.dialect.CockroachDbDialect`
- Optional metadata adapter only when direct evidence requires it.

Public KDoc must be English and must state the bounded 1.11.0 scope.

## Test Contract

- Use `CockroachServer.Launcher.cockroach`; do not instantiate raw containers.
- Keep Testcontainers-backed verification serial.
- Use bluetape4k assertion helpers.
- Prefer Exposed `SchemaUtils` for accepted DDL proof.
- Use raw SQL only when Exposed has no direct API for the evidence category
  (`RETURNING`, direct unsupported PostgreSQL feature checks, or metadata
  diagnostics).
- Use bluetape4k JDBC/HikariCP helpers for direct JDBC evidence instead of
  opening ad hoc `DriverManager` connections in tests.
- Use unique table names or cleanup guards so reruns are deterministic.
- Unsupported-path checks must not rely on fragile full error message strings;
  assert SQLSTATE or a stable, narrow error classification where available.

## Documentation Contract

Both module READMEs must contain:

- A compatibility matrix with `Supported`, `Deferred`, and `Out of scope`
  statuses.
- A short warning that CockroachDB is PostgreSQL-wire-compatible but not
  PostgreSQL-equivalent.
- A note that Exposed does not list CockroachDB as a built-in supported
  database.
- The exact verification command.
- Links to #30, #31, and #32 where relevant.

## Acceptance Criteria

- #31 GitHub issue body is refreshed with current documentation evidence.
- `./gradlew :bluetape4k-exposed-cockroachdb:test --rerun-tasks --no-configuration-cache --no-daemon`
  passes with the new compatibility suite.
- `./gradlew :bluetape4k-exposed-cockroachdb:koverXmlReport --no-configuration-cache --no-daemon`
  passes after tests.
- README locale pair documents the matrix and does not overclaim PostgreSQL
  parity.
- CHANGELOG records the compatibility boundary work.
- Step 2-R, Step 3-R, and Step 6-R local 7-tier reviews all close with
  `P0 = 0` and `P1 = 0`.
- PR body final `##` section is `## DoD Status`.
