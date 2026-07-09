# Issue #316 Spec Review

Date: 2026-07-09
Scope: `docs/superpowers/specs/2026-07-09-issue-316-ddd-spring-modulith-sample-design.md`

## Gate

Step 2-R used six read-only native review lanes plus current-session
integration review.

| Tier | Perspective | Result | Notes |
| --- | --- | --- | --- |
| 1 | Performance | P0=0, P1=0 | Added one-event/one-listener row-count and bounded-wait expectations. Benchmark/stress explicitly out of scope. |
| 2 | Stability | P0=0, P1=0 | Added stable listener id, idempotent reservation, restart republication, rollback, isolation, and bounded async assertions. |
| 3 | Security | P0=0, P1=0 | Added opaque event payload rule, internal publication-table trust boundary, serializer guidance, schema-init warning, and safe table-name constraints. |
| 4 | Operator/Ops | P0=0, P1=0 | Added operational resources/runbook, metric/state names, failure triage, workflow grep evidence, and root README requirement. |
| 5 | Developer/API | P0=0, P1=0 | Resolved DDD event-clear order, invalid fixture isolation, and concrete Gradle/Spring bean wiring. |
| 6 | User/Caller | P0=0, P1=0 | Added supported/not-supported README section, migration guidance, and numbered diagram flow requirement. |

## Integrated Findings

No P0/P1 findings remain after revision.

Key revisions:

- Event buffer handling now snapshots events, records the Modulith handoff in
  the command transaction, and clears only after successful transaction return.
- The invalid Modulith fixture must live outside the valid application package
  root and use a separate `ApplicationModules.of(...)` entrypoint.
- The example must expose the Exposed-backed `EventPublicationRepository` and
  document the required `springTransactionManager` and `EventSerializer` beans.
- README and tests must address idempotency, restart republication, rollback,
  safe serialized payloads, production schema migration guidance, and
  publication-store diagnostics.

## Deferred Items

- No benchmark or stress test is required because this is an educational H2
  example. Hot-path evidence is limited to one event, one listener, bounded
  wait, and expected publication row count.
