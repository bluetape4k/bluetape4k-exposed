# Issue #32 CockroachDB Transaction Retry Design

Date: 2026-06-07
Issue: https://github.com/bluetape4k/bluetape4k-exposed/issues/32
Parent epic: https://github.com/bluetape4k/bluetape4k-exposed/issues/24
Previous slices:
- https://github.com/bluetape4k/bluetape4k-exposed/issues/30
- https://github.com/bluetape4k/bluetape4k-exposed/issues/31

## Goal

Add bounded CockroachDB transaction retry support for Exposed JDBC without
expanding the module into a custom CockroachDB dialect.

The helper must retry only CockroachDB transaction retry errors and must keep
non-retryable SQL errors, cancellation, and interruption outside the retry
boundary.

## Current Evidence

- #30 added the `exposed-cockroachdb` module, `CockroachDatabase`, and
  CockroachDB Testcontainers smoke coverage.
- #31 documented the DDL compatibility boundary and explicitly left
  serializable transaction retry helpers to #32.
- CockroachDB stable docs define transaction retry errors as SQLSTATE `40001`
  with messages beginning with `restart transaction`.
- CockroachDB transactions are `SERIALIZABLE` by default and require
  client-side retry handling for client-visible retry errors under
  multi-statement serializable transactions.
- JetBrains Exposed 1.3.0 has `maxAttempts`, `minRetryDelay`, and
  `maxRetryDelay` transaction knobs, but the JDBC retry loop catches
  `SQLException` broadly. Using that loop directly would retry non-retryable
  SQL errors, which violates #32.

## Public API Contract

Add public API under `io.bluetape4k.exposed.cockroachdb`:

- `CockroachTransactionRetryOptions`
  - serializable data class
  - bounded attempt count
  - minimum and maximum retry delay in milliseconds
  - companion `invoke` overload for Kotlin `Duration` arguments
  - optional query timeout in seconds
  - transaction isolation defaulting to `Connection.TRANSACTION_SERIALIZABLE`
- `Throwable.isCockroachRetryableTransactionError(): Boolean`
  - walks the cause chain
  - classifies SQL exceptions only when SQLSTATE is `40001` and the message
    starts with `restart transaction`
- `withCockroachTransaction(...)`
  - wraps an Exposed JDBC `transaction`
  - is inline so ordinary helper usage does not allocate an extra public API
    wrapper call around the transaction block
  - sets `maxAttempts = 1` inside the Exposed transaction so the helper owns
    retry classification
  - retries only classified CockroachDB transaction retry errors
  - preserves thrown SQL exceptions; on exhaustion, attaches prior retry
    failures as suppressed exceptions

Public KDoc must be English and must include the bounded helper-only contract.

## Non-Goals

- Custom CockroachDB Exposed dialect.
- R2DBC retry support.
- Savepoint-based advanced retry protocol.
- Retrying every `SQLException`.
- Changing `CockroachDatabase.connect` behavior or default transaction retry
  settings globally.

## Test Contract

- Use `CockroachServer.Launcher.cockroach`; do not instantiate raw
  Testcontainers containers.
- Use bluetape4k assertion helpers and JUnit 5.
- Add fake SQLException regression coverage for:
  - exact retryable CockroachDB signature
  - cause-chain retryable SQL exception
  - wrong SQLSTATE
  - wrong message prefix
  - retry succeeds before exhaustion
  - retry exhaustion with suppressed attempt evidence
  - non-retryable SQL exception is not retried
  - cancellation and interruption are not retried
- Add CockroachDB Testcontainers smoke coverage for:
  - normal helper commit
  - rollback on failure
  - wrapped Exposed transaction uses one internal Exposed attempt

## Documentation Contract

Update both `exposed/exposed-cockroachdb/README.md` and
`exposed/exposed-cockroachdb/README.ko.md`:

- Add transaction retry support to the scope/compatibility boundary.
- Show `withCockroachTransaction(db) { ... }` usage.
- State that Exposed's generic `maxAttempts` exists but retries
  `SQLException` broadly, while this helper limits retry classification to
  CockroachDB transaction retry errors.
- Keep the out-of-scope list accurate.

Update `CHANGELOG.md` under `[Unreleased]`.

## Acceptance Criteria

- #32 issue body is refreshed with current evidence.
- Spec review closes with `P0 = 0` and `P1 = 0`.
- Plan review closes with `P0 = 0` and `P1 = 0`.
- `./gradlew :bluetape4k-exposed-cockroachdb:compileKotlin :bluetape4k-exposed-cockroachdb:compileTestKotlin --no-configuration-cache --no-daemon`
  passes.
- `./gradlew :bluetape4k-exposed-cockroachdb:test --rerun-tasks --no-configuration-cache --no-daemon`
  passes.
- `./gradlew :bluetape4k-exposed-cockroachdb:koverXmlReport --no-configuration-cache --no-daemon`
  passes.
- `git diff --check` passes.
- A concise lesson is added under `docs/lessons/`.
- PR body final `##` section is `## DoD Status`.
