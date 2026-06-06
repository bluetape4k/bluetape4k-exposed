# Issue #31 CockroachDB DDL Boundary Research

Date: 2026-06-06
Issue: https://github.com/bluetape4k/bluetape4k-exposed/issues/31
Wiki note: `/Users/debop/work/bluetape4k/bluetape4k-wiki/research/2026-06-06-cockroachdb-exposed-ddl-boundary.md`

## Sources

- CockroachDB PostgreSQL compatibility:
  https://www.cockroachlabs.com/docs/stable/postgresql-compatibility
- CockroachDB SQL feature support:
  https://www.cockroachlabs.com/docs/v26.2/sql-feature-support
- JetBrains Exposed supported databases:
  https://www.jetbrains.com/help/exposed/about.html
- HikariCP configuration examples:
  https://github.com/brettwooldridge/HikariCP

## Findings

- CockroachDB supports the PostgreSQL wire protocol and many PostgreSQL syntax
  paths, but official docs still identify PostgreSQL features that are
  unsupported or different.
- CockroachDB documents common DDL areas such as primary keys, unique
  constraints, indexes, `RETURNING`, sequences, and identity columns as
  supported areas, but Exposed-generated SQL must be proven against CockroachDB
  directly.
- JetBrains Exposed 1.3.0 docs do not list CockroachDB as a built-in supported
  database.
- HikariCP is the expected JDBC pool option, and `bluetape4k-jdbc` already
  provides `hikariDataSourceOf`, `withConnect`, `withStatement`, and `runQuery`
  helpers for tests and examples.

## Decision

Keep `exposed-cockroachdb` helper-only for 1.11.0. Mark primary key DDL,
unique/index DDL, generated IDs, `RETURNING`, and metadata as supported only
where CockroachDB Testcontainers tests prove them. Mark migration diff no-op
semantics, PostgreSQL range types, and `CREATE DOMAIN` as deferred.
