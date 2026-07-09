# Issue #316 Plan Review

## Summary

- Scope: `examples/ddd-spring-modulith-demo` implementation plan for a DDD + Spring Modulith + Exposed sample.
- Review gate: Step 3-R multi-perspective plan review.
- Final result: P0=0, P1=0.
- Blocker status: none.

## Review Results

| Perspective | Final Result | Notes |
|---|---:|---|
| Performance | P0=0 / P1=0 | Added concrete row-count assertions, bounded polling, transaction-clear expectations, and no benchmark claims. |
| Stability | P0=0 / P1=0 | Made restart republication deterministic and added H2/table cleanup and lifecycle constraints. |
| Security | P0=0 / P1=0 | Restricted event serialization to `OrderAcceptedEvent` only and required no unsafe polymorphic/default typing. |
| Operator | P0=0 / P1=0 | Added PR `Fixes #316`, issue metadata mirroring, CI wait, and Nightly full dispatch evidence. |
| Developer | P0=0 / P1=0 | Moved invalid-boundary fixture skeleton into Task 1 so all test sources compile before filtered GREEN runs. |
| User | P0=0 / P1=0 | Added README, diagram, discoverability, and migration guidance requirements. |

## Plan Revisions

- Task 1 now creates a minimal invalid-boundary fixture skeleton before writing tests because Gradle compiles all test sources before applying `--tests` filters.
- Task 3 validates only the valid-app event/publication subset after production wiring.
- Task 4 is limited to completing or adjusting the invalid fixture for the negative verifier assertion.
- Restart republication uses two Spring contexts over the same H2 database and deterministically clears the matching publication completion date before restart.
- Rollback and handoff-failure tests are separated so transactional rollback uses the real `ApplicationEventPublisher`, while handoff-buffer retention uses a throwing publisher.
- Documentation requirements include unsupported guarantees, operational security, migration from direct calls, and no benchmark or throughput claims.

## Decision

Proceed to implementation under the approved TDD plan. No P0/P1 items remain.
