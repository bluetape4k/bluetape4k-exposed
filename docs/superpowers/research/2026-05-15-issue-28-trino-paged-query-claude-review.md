# Claude Advisor Review - Issue #28 Trino Paged Query

- Scope: current diff for `exposed-trino` Issue #28.
- Requested focus: CRITICAL/HIGH correctness issues in Kotlin public API, DB/Exposed behavior, Trino SQL limit/offset generation, `pagedQueryFlow` cancellation/resource behavior, transaction boundaries, KDoc, tests, and README claims.
- Result: Claude Code CLI did not return output after an extended wait and was terminated.
- Status: advisor unavailable for this pass; local Tier 4 review and targeted/full module tests are the authoritative verification for this branch.

## Local Review Notes

- `queryFlow` continues to materialize inside the Exposed transaction and now checks cancellation before each emit.
- `pagedQueryFlow` fetches each page inside a short transaction, emits after the transaction closes, validates options, checks cancellation before page fetch and emit, and stops requesting pages when collection is cancelled.
- `TrinoDialect` now emits `OFFSET ... LIMIT ...` through `queryLimitAndOffset`, matching Trino SELECT syntax and fixing the failed Testcontainers smoke test caused by Exposed's default `LIMIT ... OFFSET ...` order.
- Module test evidence: `./gradlew :bluetape4k-exposed-trino:test` passed with 59 tests.
