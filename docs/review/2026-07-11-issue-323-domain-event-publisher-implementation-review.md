# Issue 323 Domain Event Publisher Implementation Review

## Scope

- `exposed/core/src/main/kotlin/io/bluetape4k/exposed/core/ddd/*`
- `spring-boot/jdbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/jdbc/{ddd,config,repository}/*`
- `examples/ddd-spring-modulith-demo/*`
- Public README locale pairs, changelog, and the lifecycle diagram
- Approved design and implementation plan for issue #323

## Contract Resolution

The original issue used `publish after commit` as shorthand. The approved design
made the executable Spring contract more precise: call `ApplicationEventPublisher`
inside the command transaction so transactional listeners and Spring Modulith can
register durable work, then let default `AFTER_COMMIT` listeners execute only
after commit. Synchronous listeners run immediately and are part of the command
failure boundary. Full rollback prevents default transactional-listener delivery
and preserves the aggregate buffer.

This review uses that approved contract. Moving `publishEvent` into
`afterCommit` was rejected because it would be too late for transaction-bound
publication registration and would introduce a persistence/publication crash
window. A durable outbox is a separate design.

## Review Lanes

| Lane | Final result | Evidence |
|---|---:|---|
| Architecture | P0/P1 = 0 after fixes | Core KDoc is adapter-neutral; transaction-aware and post-commit durable handoff patterns are separated in both root README locales. |
| Security | P0/P1 = 0 | Logs expose only category, aggregate/event class names, event count, and validated `traceId`/`spanId`/`requestId`; payload and exception text are excluded. |
| Performance | P0/P1 = 0 | One retained snapshot per aggregate, `IdentityHashMap` reservations, one synchronization-list scan per publisher call, and no lock, polling, retry loop, or global mutable registry. |
| Stability/Ops | P0/P1 = 0 after fixes | Commit clears, rollback/unknown preserve, cleanup failures are isolated, fatal `Error` is rethrown, and completion always discards registry state. |
| Developer/API | P0/P1 = 0 after fixes | One-final-call contract, object-identity duplicate scope, same-ID distinct-instance behavior, manager ambiguity, and unsupported transaction boundaries are explicit and tested. |
| User/caller | P0/P1 = 0 after fixes | English/Korean lifecycle docs, recovery matrix, rollout sequence, publication-store controls, example adoption, and sequence diagram converge on the implementation. |
| Build/CI/tests | P0/P1 = 0 | Four affected suites and Kover reports pass; CI/Nightly paths, task names, summary dependencies, and coverage uploads remain present. |

## Review Findings And Disposition

| Severity | Finding | Disposition |
|---|---|---|
| P1 | Original issue wording could be read as invoking Spring only after commit. | Resolved by the approved design contract above. The PR payload must state this clarification so the live issue shorthand is not repeated without context. |
| P1 | `PROPAGATION_NESTED` and same-instance overlapping `REQUIRES_NEW` cannot be proved safe by thread-local synchronization ownership. | Accepted scope boundary from the approved design; both are explicitly unsupported. Adding savepoint ownership or a global registry would materially change the design. |
| P2 | Throwing `AFTER_COMMIT` listener test did not prove command and row counts stay at one. | Fixed in `37b3e25`; the test now asserts one callback and one committed row. |
| P2 | Committed cleanup caught every `Throwable`, hiding fatal JVM errors. | Fixed in `d72de0b`; only `Exception` is isolated and a fatal-error regression test passes. |
| P2 | Core KDoc overstated list immutability and named Spring-specific adapter/propagation concepts. | Fixed in `429672c`; core now describes independent read-only snapshots and neutral ownership scopes. |
| P2 | Duplicate wording did not distinguish object identity from aggregate ID. | Fixed in `429672c` and `45f87f0`; same-ID distinct instances are documented and tested as independent registrations. |
| P2 | Example rollback test did not assert the returned aggregate buffer was retained. | Fixed in `45f87f0`; the typed exception now exposes an aggregate with one retained event. |
| P2 | Diagram registration/delivery timing and source-trace path were ambiguous. | Fixed in `45f87f0`; step 6 registers `AFTER_COMMIT`, step 8 delivers, and the source path is valid. |
| P2 | Root README described only post-commit durable ownership. | Fixed in `7ecdbad`; both locale files now separate that pattern from in-transaction publication registration. |
| P2 | Restart replay does not induce an actual listener exception before restart. | Deferred with rationale: the example deterministically resets the durable publication to incomplete, then proves restart replay, completion, and idempotent reservation. JDBC tests independently prove throwing `AFTER_COMMIT` listener semantics. A failure-injection fixture would add asynchronous timing without changing the replay contract. |

Final convergence: **P0 = 0, P1 = 0** against the approved design.

## Acceptance Mapping

| Criterion | Implementation | Test/docs evidence |
|---|---|---|
| In-transaction Spring handoff; default listener after commit | `ExposedAggregateEventPublisher.publishAfterSave` | Commit, rollback, ordering, and real `@TransactionalEventListener` tests; JDBC README and diagram steps 3, 6, and 8. |
| Clear only committed buffers | `AggregateEventTransactionSynchronization.afterCompletion` | Commit, rollback, `STATUS_UNKNOWN`, cleanup failure, and fatal-error tests. |
| Fail closed on duplicate, mutation, or publication failure | Identity reservation, retained snapshot verification, poison state | Duplicate, clear/drain-after-registration, mutation, caught exception/error, and reentrant publication tests. |
| Guarded auto-configuration | `ExposedAggregateEventPublisherAutoConfiguration` | Missing class, single manager, `@Primary`, ambiguous manager, and custom bean tests. |
| Repository manager propagation | `transactionManagerRef` configuration path | Compiled multi-manager example and repository auto-configuration tests. |
| Optional Modulith integration | Plain Spring publisher in JDBC; Modulith remains downstream | Modulith-absent auto-config test, publication persistence, rollback, replay, and example tests. |
| No JaVers/R2DBC coupling | Core and JDBC dependency boundaries | Negative source/classpath scans and README boundary sections. |
| Operational recovery and security | Sanitized anomaly logging and documented reconciliation | Log allowlist tests, five-outcome table, four-state reconciliation, rollout ordering, and publication-store controls. |

## Performance And Stability Evidence

- Event publication and snapshot identity verification are `E` operations.
- The current synchronization scan is `S`; Spring synchronization ordering makes
  the transaction path `O(E + S log S)` overall.
- Retained event references plus synchronization references require `O(E + S)`
  temporary/reference storage.
- Three sentinel synchronizations and two aggregates still produce one publisher
  synchronization; each aggregate's `domainEvents()` calls transition `1 -> 2`.
- Duplicate registration rejects before a second snapshot, leaving its call count
  at `1`.
- Anomaly event-type aggregation is reachable only for cleanup failure or
  `STATUS_UNKNOWN`, never for normal publication, commit, or rollback.

## Verification Evidence

- Current HEAD forced suites: core 287 tests (13 skipped), JDBC 186, Spring
  Modulith 61, DDD example 10; `BUILD SUCCESSFUL`.
- Publisher-focused suite: 33 tests; `BUILD SUCCESSFUL`.
- Kover XML: core 80,529 bytes; JDBC 80,319; Spring Modulith 46,375; example
  44,536.
- Static boundaries: no Spring/JaVers in core, no JaVers/Modulith in JDBC, no
  legacy manual example publication/clear path.
- CI/Nightly evidence: 163 matching path/task/coverage/summary lines and all
  four test/Kover task pairs present.
- Diagram: 4,360 x 3,360 PNG; sequence, connector, geometry, endpoint, and
  mixed-corner audits pass with 11 connectors and zero failures.
- README source example, locale links, anchors, outcome/reconciliation markers,
  rollout order, and security controls pass.
- `git diff --check origin/develop...HEAD` passes; worktree is clean and no build
  output is tracked.

CodeGraph returned zero indexed nodes and Kotlin diagnostics were unavailable
because the transport closed. Exact call-site/import scans and fresh Gradle
compilation/tests were used instead. The canonical `verifier` agent could not
start because its selected model was at capacity. Per workflow fallback, the
main session performed the acceptance mapping above; an attempted legacy-model
substitution was stopped and its output was not used.

## Gate

Task 9 review gate: **PASS**. Implementation and local evidence are ready for PR
preparation. Push, PR creation, issue-body mutation, workflow dispatch, and merge
remain external authority boundaries.
