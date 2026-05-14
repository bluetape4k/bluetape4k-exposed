# Issue #28 Trino Paged Query Lessons

## Context

`exposed-trino` needed a large result set API that does not expose JDBC
`ResultSet` lifetimes outside Exposed transactions.

## Lessons

- True row-by-row `Flow` streaming is unsafe for the current JDBC + Exposed
  transaction boundary because collection can outlive the transaction that owns
  the `ResultSet`.
- Page-by-page materialization is the safer contract: each page is read inside a
  short transaction, then emitted after the transaction closes.
- Trino SELECT syntax accepts `ORDER BY ... OFFSET ... LIMIT ...`; Exposed's
  default `LIMIT ... OFFSET ...` order is rejected by real Trino. Testcontainers
  caught this during the first `pagedQueryFlow` smoke test.
- Replacing an Exposed dialect `FunctionProvider` drops inherited vendor
  function mappings. When a custom provider is needed for one SQL fragment,
  copy or adapt the vendor-specific mappings that the module already relied on.
- Trino 475 did not register `STRING_AGG` in the Testcontainers environment;
  `ARRAY_JOIN(ARRAY_AGG(...), separator)` is the verified group-concat mapping
  for this module.
- Cancellation tests should verify both emitted values and requested page
  offsets. This proves that collection cancellation does not start the next page
  request.
- For large result set docs, separate application memory bounds (`pageSize`)
  from Trino JDBC/cluster throughput mechanisms such as the spooling protocol.

## Verification

- First targeted run failed on Trino SQL syntax: `mismatched input 'OFFSET'`.
- Fixed `TrinoDialect` limit/offset SQL generation.
- `./gradlew :bluetape4k-exposed-trino:test --tests "io.bluetape4k.exposed.trino.TrinoExtensionsTest"` passed with 8 tests.
- Claude PR review then found `groupConcat`/`locate` regressions from the
  custom provider and a stale README roadmap row.
- Fixed Trino function mappings and added real Trino tests for `groupConcat`
  and `locate`.
- `./gradlew :bluetape4k-exposed-trino:test --tests "io.bluetape4k.exposed.trino.query.SelectTest"` passed with 8 tests.
- `./gradlew :bluetape4k-exposed-trino:test` passed with 61 tests.

## Follow-up Guidance

- When adding Trino query APIs, verify generated SQL against a real Trino
  Testcontainers run, not only Exposed DSL expectations.
- Keep cursor-style APIs out of public surface until there is an explicit owner
  for `ResultSet` lifetime, cancellation, and connection cleanup.
