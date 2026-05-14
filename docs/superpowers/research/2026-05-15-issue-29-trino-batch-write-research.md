# Trino Batch Write Research - Issue #29

- Issue: https://github.com/bluetape4k/bluetape4k-exposed/issues/29
- Scope: Trino `INSERT`, connector write capability, transaction and batch write
  implications for `exposed-trino`.
- Date: 2026-05-15

## Local Knowledge Lookup

`qmdq` was unavailable as a direct binary in the non-interactive shell because it
is defined in `~/.zshrc`. Retried with `source ~/.zshrc` and with direct
`qmd query ... --no-rerank`.

Relevant qmd result:

- `qmd://wiki/pages/database-dialects.md` records Trino as autocommit-only for
  this module and notes that the Trino Memory connector does not support
  `BEGIN` / `COMMIT` / `ROLLBACK`.

## Official Documentation Findings

- Trino `INSERT` syntax supports `INSERT INTO table_name ... query`, including
  single-row and multi-row `VALUES` examples:
  https://trino.io/docs/current/sql/insert.html
- Trino SQL statement support is connector-dependent. The documentation states
  that source system capabilities can limit SQL support, and that details for
  statements are documented per connector:
  https://trino.io/docs/current/language/sql-support.html
- Trino transactions are SQL statements, but most connectors do not support
  transactions:
  https://trino.io/docs/current/language/sql-support.html
- Supporting `INSERT` in a connector requires connector SPI support such as
  `beginInsert()`, `finishInsert()`, and a `ConnectorPageSinkProvider`:
  https://trino.io/docs/current/develop/insert.html
- JDBC-style connectors such as PostgreSQL/MySQL document connector-side
  `write.batch-size` and non-transactional insert options. These are Trino
  catalog settings, not a guarantee that client `PreparedStatement.executeBatch`
  is a connector bulk-loader protocol.

## Decision

Add a small `trinoBatchInsert` helper that:

- wraps Exposed JDBC `batchInsert`,
- chunks caller data explicitly,
- defaults generated-key retrieval to disabled,
- documents connector-dependent write support and partial-write risk,
- stays separate from any future connector-specific bulk-loader API.
