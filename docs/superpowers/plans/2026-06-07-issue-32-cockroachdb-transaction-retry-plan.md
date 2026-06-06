# Issue #32 CockroachDB Transaction Retry Plan

Spec: `docs/superpowers/specs/2026-06-07-issue-32-cockroachdb-transaction-retry-design.md`

## Decision

Implement a CockroachDB-specific retry wrapper instead of enabling Exposed's
generic transaction retry globally. The wrapper will set the inner Exposed
transaction to one attempt and retry only SQL exceptions classified by
CockroachDB's documented retryable transaction signature.

## Tasks

1. Add retry support source.
   - Add `CockroachTransactionRetryOptions`.
   - Add `Throwable.isCockroachRetryableTransactionError()`.
   - Add `withCockroachTransaction(...)`.
   - Add an internal retry executor for fake SQLException regression tests.
   - Validate options with bluetape4k support helpers.

2. Add regression tests.
   - Add predicate tests for exact, wrapped, wrong SQLSTATE, and wrong message
     cases.
   - Add retry executor tests for success, exhaustion, non-retryable SQL,
     cancellation, and interruption.
   - Add CockroachDB Testcontainers transaction helper smoke tests for commit,
     rollback, and inner Exposed `maxAttempts = 1`.

3. Update documentation.
   - Update `README.md`.
   - Update `README.ko.md`.
   - Update `CHANGELOG.md`.

4. Verify locally.
   - Compile touched module.
   - Run module tests with `--rerun-tasks`.
   - Run Kover XML report.
   - Run `git diff --check`.
   - Validate wiki research note with GNO commands.

5. Review and delivery.
   - Add Step 6-R final review evidence with `P0 = 0`, `P1 = 0`.
   - Add `docs/lessons/2026-06-07-issue-32-cockroachdb-transaction-retry.md`.
   - Commit using the Lore protocol.
   - Push branch and create PR assigned to `debop`.
   - Set PR milestone `1.11.0` and relevant labels where available.
   - Verify the live PR body and ensure the final `##` section is
     `## DoD Status`.

## Risks And Controls

| Risk | Control |
|---|---|
| Exposed internal retry widens the classification boundary | Set inner transaction `maxAttempts = 1`. |
| Non-retryable SQL errors are retried | Retry only when SQLSTATE/message match CockroachDB retry errors. |
| Wrapped `ExposedSQLException` hides the PostgreSQL cause | Walk the cause chain. |
| Exhaustion loses attempt evidence | Rethrow the final SQL exception and attach prior SQL failures as suppressed exceptions. |
| Tests rely on nondeterministic CockroachDB contention | Use fake SQLException regression tests for retry mechanics and Testcontainers only for smoke commit/rollback behavior. |

## Validation Expectations

- Fake retry tests prove classification and retry mechanics deterministically.
- Testcontainers smoke tests prove the public helper works with real
  CockroachDB and Exposed JDBC.
- README examples reuse bluetape4k ecosystem helpers where relevant.

