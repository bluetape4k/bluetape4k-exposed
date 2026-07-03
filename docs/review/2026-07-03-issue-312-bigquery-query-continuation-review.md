# Issue #312 BigQuery Query Continuation Review

## Scope

- Issue: #312 `test(bigquery): lock query-job pagination and partial completion contracts`
- Files reviewed:
  - `exposed/bigquery/src/test/kotlin/io/bluetape4k/exposed/bigquery/BigQueryQueryContinuationUnitTest.kt`

## Findings

- P0/P1: none.
- Tier 4 implementation review: tests exercise the existing `BigQueryContext`
  continuation paths without production-code changes.
- Tier 5 test review: coverage locks `jobComplete=false`, `pageToken`,
  continuation schema fallback, page-level errors, missing `jobReference`, and
  Flow cancellation before the next page fetch.
- MockK pattern review: BigQuery operation mocks are class fields and
  `@BeforeEach` uses `clearMocks(...)` before restubbing shared behavior.
- Ecosystem reuse review: assertions use `bluetape4k-assertions`, including
  `assertFailsWith` and `coInvoking { ... } shouldThrow`.
- Follow-up code-pattern review: collection cardinality assertions use
  `shouldHaveSize` instead of `collection.size shouldBeEqualTo n`.

## Verification

- `git diff --check`: PASS.
- `./gradlew :bluetape4k-exposed-bigquery:test --tests "io.bluetape4k.exposed.bigquery.BigQueryQueryContinuationUnitTest"`:
  PASS, 5 tests.
- `rg ".size shouldBeEqualTo|.shouldBeEqualTo\\(|shouldBeEqualTo (true|false)"`
  against the BigQuery continuation test: PASS.

## Residual Risk

- The change is mock-based by issue scope and does not run the BigQuery
  emulator integration suite locally.
