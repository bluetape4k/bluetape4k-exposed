# Issue #323 Design Spec Review

## Scope

- Artifact: `docs/superpowers/specs/2026-07-10-issue-323-domain-event-publisher-design.md`
- Review gate: Full Feature Step 2-R
- Perspectives: performance, stability, security, operator/Ops,
  developer/API, user/caller, and main-session integration
- Research basis: current repository source and tests, Spring Framework 7.0.8
  transaction synchronization source, Spring Modulith 2.0.6 listener behavior,
  and the Exposed 1.3.1 transaction-manager implementation
- Implementation state: no production or test code changed

## Convergence

| Iteration | Scope | P1 result | Resolution |
|---|---|---:|---|
| 1 | All six lanes | 22 raw | Reworked transaction timing, mutation checks, lifecycle, trust boundaries, auto-configuration, retry, and operator guidance. |
| 2 | All affected lanes | 9 raw | Closed actual-transaction, poison, cleanup, listener-write, KDoc, manager-ownership, and recovery gaps. |
| 3 | All six lanes | 4 integrated | Performance and stability passed; normalized duplicated findings in security, Ops, API, and caller contracts. |
| 4 | Four affected lanes | 6 raw | Corrected `@ConditionalOnSingleCandidate` semantics, retry classification, correlation, reconciliation, and serializer ownership. |
| 5 | Four affected lanes | 0 | Security, Ops, API, and caller lanes passed. One caller P2 terminology inconsistency was fixed before closure. |

Raw lane counts can overlap. The main-session integration count is the
deduplicated gate result used for progression.

## Final Findings

| Priority | Performance | Stability | Security | Ops | API | Caller | Integrated |
|---|---:|---:|---:|---:|---:|---:|---:|
| P0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| P1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| P2 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| P3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |

The documented synchronous-listener, serializer, transaction-manager identity,
savepoint, completion-uncertainty, and exactly-once limitations are accepted
design risks or explicit non-goals, not open review findings.

## Resolved Blockers

- Publish Spring events while the command transaction is active; publishing
  from `afterCommit` cannot register normal transactional listeners.
- Require both transaction synchronization and an actual active transaction
  for event-bearing calls.
- Reserve aggregate object identity before the first publication and poison
  commit on re-entry, repeated handoff, mutation, or publication failure.
- Keep the transaction registry only in the current Spring synchronization
  list so `REQUIRES_NEW` suspension does not leak custom thread-bound state.
- Clear buffers only from committed completion; preserve them for rollback and
  unknown completion, while isolating per-aggregate cleanup failures.
- Treat save/handoff transaction alignment as a caller precondition rather
  than claiming manager or `DataSource` identity proof.
- Define application-owned serializer trust, idempotent recovery, structured
  anomaly logging, and four-state reconciliation boundaries.
- Specify the actual `@ConditionalOnSingleCandidate` behavior, including
  multiple manager beans with one `@Primary`.

## Rejected Alternatives

- `afterCommit` publication: too late for normal transactional-listener
  registration.
- Repeated suffix publication: permits duplicate or unpersisted event handoff;
  one final event-bearing call is required.
- Runtime manager or `DataSource` identity proof: unavailable from the Spring
  thread-local synchronization contract.
- A separately bound transaction resource registry: creates suspension and
  cleanup complexity without adding correctness.
- `PROPAGATION_NESTED` or savepoint handoff: the listener registration cannot
  be safely retracted with the current Exposed transaction manager.
- A new metric or callback SPI: structured anomaly logs provide the required
  signal without adding a dependency or public extension surface.
- Bridge-owned serializer allowlisting: durable publication serialization is
  an existing application and Spring Modulith boundary.

## Evidence

- Baseline before design edits:
  `./gradlew :bluetape4k-exposed-spring-boot-jdbc:test :bluetape4k-exposed-spring-modulith:test :examples-ddd-spring-modulith-demo:test --no-configuration-cache --no-daemon --console=plain`
  completed successfully.
- Final artifact validation: `git diff --check`.
- Open user decisions: none.

## Verdict

**PASS: P0 = 0, P1 = 0.** The design spec is ready for user review before the
implementation plan is written.
