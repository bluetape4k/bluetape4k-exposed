# Issue 323 Domain Event Publisher Lesson

## Context

Issue #323 connects Spring-neutral aggregate event buffers to Spring Boot JDBC
transactions and optional Spring Modulith publication tracking.

## Decision

The publisher hands events to Spring inside the command transaction immediately
after aggregate persistence. This timing lets default transactional listeners
and Spring Modulith register work against the current transaction. Only
committed completion clears the aggregate buffer; rollback and unknown
completion preserve it.

Publishing for the first time from `afterCommit` was rejected. It cannot enlist
durable publication in the command transaction and creates a crash window after
aggregate persistence. Synchronous listeners therefore remain an explicit
in-transaction boundary and must not perform non-idempotent irreversible work.

## Ownership Insight

The publisher reserves aggregate object identity, not aggregate ID. It retains
the exact read-only snapshot and verifies event-reference identity before
commit. Duplicate registration, snapshot mutation, and publication failure
poison the transaction even when caller code catches the immediate exception.

Spring's synchronization thread-local does not prove transaction-manager or
DataSource identity. Auto-configuration may require one selectable manager, but
the publisher never claims which manager owns the current transaction.

## Completion Insight

Committed cleanup failures are observable but cannot roll back persistence.
Recoverable `Exception` failures are isolated per aggregate and logged without
payload or exception text. Fatal `Error` values are rethrown. `STATUS_UNKNOWN`
preserves buffers, logs one sanitized anomaly per aggregate, and requires
reconciliation before retry.

`PROPAGATION_NESTED`/savepoint handoff and same-instance reuse across overlapping
`REQUIRES_NEW` transactions remain unsupported. Solving them requires a broader
ownership model, not a local callback tweak.

## Outcome

- Core contracts remain adapter-neutral and distinguish read-only list snapshots
  from deeply immutable event objects.
- JDBC auto-configuration backs off for ambiguous manager contexts and custom
  publisher beans.
- The DDD Modulith example uses one save-then-handoff transaction and no manual
  publication/clear loop.
- English/Korean docs, recovery guidance, rollout controls, and the lifecycle
  diagram match the implementation.

## Verification

- Forced tests passed: core 287 (13 skipped), JDBC 186, Modulith 61, example 10.
- Publisher-focused tests passed with 33 tests.
- Four Kover XML reports were generated and verified non-empty.
- Final review converged at P0 = 0 and P1 = 0 against the approved design.

## Future Guard

Never clear aggregate events before committed completion. Never claim
transaction-manager identity from Spring thread-local synchronization. Keep
synchronous listener side effects idempotent, and use a durable outbox when the
application requires post-commit dispatch without an in-transaction handoff.
