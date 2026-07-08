# Issue 320 DDD Contracts Lesson

## Context

Issue #320 adds Spring-neutral aggregate/domain-event contracts to
`bluetape4k-exposed-core`.

## Decision

The base contracts stay framework-neutral and do not publish, persist, replay,
or observe events. `drainDomainEvents(handoff)` clears the aggregate buffer only
after the handoff callback returns successfully.

## Outcome

The API avoids the natural but unsafe shape where a caller fetches and clears
events before publisher ownership is durable. Repository adapters must snapshot
events, commit aggregate state, wait for an after-transaction-commit or
equivalent durability boundary, hand events to a durable owner, and only then
clear or drain the aggregate buffer.

## Verification

- Focused DDD tests passed with 9 tests.
- Full `:bluetape4k-exposed-core:test` passed with 286 tests and 13 skipped.
- Step 6-R review converged with P0 = 0 and P1 = 0.

## Future Guard

Do not drain or clear domain events before aggregate state commit and durable
handoff acceptance. A process-local retry queue is not a durable event owner.
