# Issue 320 DDD Contracts Review

## Scope

- `exposed/core/src/main/kotlin/io/bluetape4k/exposed/core/ddd/*`
- `exposed/core/src/test/kotlin/io/bluetape4k/exposed/core/ddd/AbstractAggregateRootTest.kt`
- `README.md`
- `README.ko.md`
- `docs/superpowers/specs/2026-07-09-issue-320-ddd-contracts-design.md`
- `docs/superpowers/plans/2026-07-09-issue-320-ddd-contracts-plan.md`

## Review Lanes

| Lane | Result | Notes |
|---|---:|---|
| Performance/allocation | P0/P1 = 0 | Nullable event buffer, empty fast path, and defensive copies only for non-empty snapshots. |
| Stability/transaction semantics | P0/P1 = 0 | Snapshot/order/mismatch tests pass; existing repositories are unaffected. |
| Security | P0/P1 = 0 | Event payload guidance avoids secrets/PII; mismatch exception does not echo IDs. |
| Operator/Ops | P0/P1 = 0 after fix | Replaced process-local handoff language with durable ownership and callback drain. |
| Developer/API | P0/P1 = 0 | Package, KDoc, and tests are acceptable; reflection-based private-buffer tests were removed. |
| User/caller | P0/P1 = 0 after fix | `drainDomainEvents(handoff)` clears only after successful handoff and keeps events on failure. |

## Fixes From Review

- Changed the drain API to require a handoff callback so the aggregate buffer is cleared only after the callback returns successfully.
- Added a failure-retention test for handoff exceptions.
- Removed reflection and `emptyList()` identity assertions from tests; tests now assert public behavior.
- Replaced ambiguous handoff wording with durable ownership: outbox, persisted retry queue, or transactionally recorded handoff.
- Added imports/context to README and KDoc snippets.
- Improved Korean README wording for the new DDD section.

## Verification Evidence

- RED: `:bluetape4k-exposed-core:compileTestKotlin` failed before production types existed, with unresolved `DomainEvent`/`domainEvents`/`drainDomainEvents` references.
- Focused GREEN: `repo-test-summary -- ./gradlew :bluetape4k-exposed-core:test --tests 'io.bluetape4k.exposed.core.ddd.AbstractAggregateRootTest' --no-configuration-cache --no-build-cache --no-parallel --console=plain` -> `BUILD SUCCESSFUL`, 9 tests.
- Full affected module: `repo-test-summary -- ./gradlew :bluetape4k-exposed-core:test --no-configuration-cache --no-build-cache --no-parallel --console=plain` -> `BUILD SUCCESSFUL`, 286 tests, 13 skipped.
- Static checks: `git diff --check` passed; framework-import negative grep passed for the new `ddd` package; stale handoff/API wording grep passed for changed source and docs.

## Gate

Step 6-R PASS. Final convergence: P0 = 0, P1 = 0.
