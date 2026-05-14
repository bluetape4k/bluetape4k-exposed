# Claude PR Review Request - Issue #28 / PR #67

- PR: https://github.com/bluetape4k/bluetape4k-exposed/pull/67
- Local checkout: `/Users/debop/work/bluetape4k/bluetape4k-exposed/.worktrees/issue-28-trino-paged-query`
- Requested scope: CRITICAL/HIGH correctness issues only for Kotlin public API,
  DB/Exposed behavior, Trino limit/offset SQL generation, `pagedQueryFlow`
  cancellation/resource behavior, transaction boundaries, tests, and README
  claims.

## Result

Claude Code CLI was invoked again and allowed to run for more than five minutes.
It completed and reported two HIGH findings plus one informational test note.
The raw stdout was captured at `/tmp/claude-pr67-review.out` during the session.

## Findings Integrated

- HIGH: replacing Exposed's PostgreSQL function provider with
  `TrinoFunctionProvider` fixed `OFFSET ... LIMIT`, but dropped working
  mappings for `groupConcat` and `locate`.
  - Accepted.
  - Fixed with Trino-compatible `ARRAY_JOIN(ARRAY_AGG(...), separator)` for
    `groupConcat` and `POSITION(substring IN expr)` for `locate`.
  - Added real Trino Testcontainers coverage for both functions.
- HIGH: README Phase 2 Roadmap still listed `pagedQueryFlow` as future work even
  though the public API and usage docs were implemented in this PR.
  - Accepted.
  - Removed the stale roadmap row from both English and Korean README files.
- Informational: the `.take(3)` cancellation test proves collector
  short-circuiting/no additional page fetch after truncation, but does not prove
  job-cancellation semantics.
  - Accepted as a wording/test-scope note.
  - Kept the test focused on no-extra-page-fetch behavior; `ensureActive()`
    guards remain in the production flow for cancellation responsiveness.

## Review Status

- `pagedQueryFlow` keeps JDBC `ResultSet` access inside page-scoped Exposed
  transactions.
- Trino `OFFSET ... LIMIT ...` SQL order is covered by real Trino
  Testcontainers tests.
- Trino `groupConcat` and `locate` mappings are covered by real Trino
  Testcontainers tests after the Claude review fix.
- Cancellation behavior is covered by a collector-side `take(3)` test that
  proves no third page is requested.
- README claims were adjusted to separate application `pageSize` memory bounds
  from Trino JDBC/cluster throughput mechanisms such as spooling.
