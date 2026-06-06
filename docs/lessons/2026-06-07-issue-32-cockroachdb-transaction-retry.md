# Issue #32 CockroachDB Transaction Retry

## Context

`exposed-cockroachdb` needed serializable transaction retry support after #30
created the module and #31 bounded the DDL compatibility surface.

## Decision

Add a CockroachDB-specific helper instead of relying on Exposed's generic
transaction retry knobs. Exposed 1.3.0 retries `SQLException` broadly, so the
helper forces the inner Exposed transaction to one attempt and retries only
SQLSTATE `40001` errors whose message starts with `restart transaction`.

## Outcome

The module now provides:

- `CockroachTransactionRetryOptions`
- `Throwable.isCockroachRetryableTransactionError()`
- `withCockroachTransaction(...)`

README locale pair and CHANGELOG document the supported retry path.
PR review feedback renamed the helper to `withCockroachTransaction`, made it
inline, and added a `Duration`-based companion `invoke` overload for options.

## Verification

- Compile: PASS
- Test: PASS, 24 tests
- Kover XML: PASS
- `git diff --check`: PASS
- wiki research note: indexed, embedded, and queryable

## Future Guidance

For CockroachDB retry work, do not enable Exposed `maxAttempts` as the only
solution when non-retryable SQL errors must stay outside retry. Keep retry
classification tied to CockroachDB's documented SQLSTATE/message signature
unless a later issue adopts the savepoint-based advanced retry protocol.
