# Issue 326 Ktor R2DBC Cache and DDD Demo Review

## Scope

- `examples/ktor-exposed-demo` Order Confirmation implementation and tests
- PostgreSQL Compose and serialized Testcontainers verification
- English/Korean README pair
- Architecture and sequence SVG/PNG pairs
- Approved design, plan, delivery checklist, and durable lesson

The review excludes production library APIs, publishing aggregation, version
catalogs, CI workflow changes, Spring, Spring Modulith, JaVers, and issue #322.

## Contract Resolution

Architecture B is implemented as Ktor route -> `OrderCommandService` ->
`OrderR2dbcCaffeineRepository` -> PostgreSQL, followed by an application-owned
publisher. `WRITE_THROUGH` changes Caffeine before PostgreSQL and is explicitly
non-atomic. Event handoff occurs only after persistence returns and is
request-local, not durable. Repeating the command is characterized as
idempotent only for sequential calls.

H2 remains only for the original JDBC transaction-count route. The R2DBC path,
readiness probe, schema, repository, and order-count route use PostgreSQL.

## Review Lanes

| Lane | Final result | Evidence |
|---|---:|---|
| Performance | P0/P1 = 0 | Two-connection demonstration pool, bounded acquire/dispose waits, O(1) cache readiness, no polling or retry loop, and explicit sizing caveats. |
| Stability/concurrency | P0/P1 = 0 | Failure-atomic acquisition, reverse cleanup, single lifecycle lease, concurrent idempotent close, default-database restoration, cancellation compensation, and sequential-idempotency boundary tests. |
| Security/privacy | P0/P1 = 0 | Loopback binding, no permissive CORS, bodyless teaching header, canonical UUID validation, stable error bodies, allowlisted diagnostics, and no exception text or credentials in responses. |
| Operations | P0/P1 = 0 | Probe-free liveness, JDBC/R2DBC/cache readiness, bounded pool waits, explicit runner status, shutdown order, Compose health check, volume-preserving and destructive commands, and production caveats. |
| Developer/API | P0/P1 = 0 | Example-local types only, existing repository/cache/DDD contracts reused, no public library API or new module, and no Spring/Modulith/JaVers dependency. |
| User/docs/diagrams | P0/P1 = 0 | Scenario-first bilingual guides, byte-identical Bash blocks, exact route/error tables, non-atomic and non-durable limitations, readable architecture and sequence assets, and all diagram audits. |
| Build/tests | P0/P1 = 0 | 32 Docker-free tests, 4 PostgreSQL tests, actual curl walkthrough, Compose smoke/reset proof, render parity, link checks, and diff check. |

Final convergence: **P0 = 0, P1 = 0** against the approved design.

## Findings And Disposition

| Severity | Finding | Disposition |
|---|---|---|
| P1 | The reviewed implementation head `9ea8e575` did not yet contain the final review, lesson, or completed checklist, so it could not be the PR head under its own plan. | Resolved by the evidence commit containing this review, lesson, and 16/19 checklist state. The resulting exact head is re-verified before push. |
| P2 | A normal local Testcontainers invocation lets Ryuk try to mount the Colima Docker socket and fails before the PostgreSQL test starts. | Environment-specific, not a product failure. Re-running with the repository-documented `TESTCONTAINERS_RYUK_DISABLED=true` executes all four PostgreSQL tests successfully. |
| P2 | The module-local `detekt` task does not exist, while root `detekt` is `NO-SOURCE`. | Recorded as a static-analysis gap. Fresh Kotlin compilation plus 36 behavior/integration tests are the executable proof; adding a module-only static-analysis convention is outside issue #326. |
| P3 | Generic diagram audits cannot classify text labels in these custom SVGs and report `labels=0`. | Targeted invariants prove 44 architecture labels and 57 sequence labels, nonzero cards/paths, 14 numbered badges, 3 alt frames, and two `userSpaceOnUse` markers per asset. |
| P3 | One real PostgreSQL lifecycle test used virtual-time `runTest`. | Fixed to use repository-standard `runSuspendIO(timeout = 30.seconds)` for real R2DBC I/O. |
| P3 | Two Docker-free tests imported `kotlin.test.assertFailsWith` instead of the repository assertion helper. | Fixed to use `io.bluetape4k.assertions.assertFailsWith`; behavior is unchanged. |

## Acceptance Mapping

| Criterion | Implementation | Proof |
|---|---|---|
| JDBC and R2DBC request paths | H2 JDBC count plus PostgreSQL order count/commands | Docker-free route tests, PostgreSQL suite, actual walkthrough |
| Cache-backed repository scenario | `OrderR2dbcCaffeineRepository` in `WRITE_THROUGH` mode | Persistence, cache invalidation, cache-hit, and count assertions |
| Spring-neutral aggregate/events | `DemoOrder`, `OrderConfirmed`, `OrderCommandService`, `OrderEventPublisher` | Domain and command-service tests; no forbidden framework references |
| Publish only after persistence | Service snapshots, saves, publishes, then clears | Ordering, persistence failure, publisher failure, and cancellation tests |
| Non-Spring boundary documented | Application-owned non-durable publisher | README locale pair, architecture, sequence, limitations |
| PostgreSQL replaces H2 R2DBC | App-owned pool/database and schema | Dependency scan, Testcontainers suite, Compose walkthrough |
| Explicit lifecycle ownership | `KtorExposedDemoResources` and close report | Acquisition failure, external default, concurrent close, second lifecycle tests |
| Easy-to-follow example | Scenario, architecture, sequence, commands, errors, limitations | Bilingual parity matrix and full-size diagram inspection |

## Verification Evidence

- `:examples-ktor-exposed-demo:test --rerun-tasks`: 32 tests passed;
  `testcontainers-lines=0`; `BUILD SUCCESSFUL`.
- `TESTCONTAINERS_RYUK_DISABLED=true
  :examples-ktor-exposed-demo:postgresIntegrationTest --no-parallel
  --rerun-tasks`: 4 tests passed; `BUILD SUCCESSFUL`.
- Actual server walkthrough: readiness included `jdbc`, `r2dbc`, and
  `cache.orders`; JDBC count `2`; R2DBC count `1 -> 2`; first/GET/repeat state
  matched and the repeat did not publish.
- Compose config and health passed on `127.0.0.1:5432`; disposable volume reset
  removed `bt4k-issue-326-reset_ktor-exposed-demo-postgres`.
- Both PNGs match fresh CairoSVG scale-2 renders byte-for-byte. Every diagram
  audit passed with zero geometry/endpoint/mixed-corner/style failures.
- Targeted diagram counts: architecture `rects=16 paths=11 labels=44`; sequence
  `rects=36 paths=21 labels=57 badges=14 alt=3`; two fixed-unit markers each.
- English/Korean command blocks, mutual links, route/error identifiers, local
  paths, and `git diff --check` passed.
- No diff under `.github`, `gradle/libs.versions.toml`, `gradle.properties`, or
  `settings.gradle.kts`; no Spring/Modulith/JaVers/H2-R2DBC reference in the
  example implementation.

The code-review graph returned zero indexed nodes. Direct source scans, exact
diff inspection, compiled tests, real PostgreSQL, and the loopback walkthrough
were used as the fallback.

## Remaining Risks

- Cache and PostgreSQL are deliberately not atomic.
- Concurrent confirmations do not claim exactly-once behavior.
- PostgreSQL cancellation can leave commit outcome ambiguous.
- Event delivery is non-durable and cannot be recovered by repeating POST.
- Startup DDL, demonstration pool sizing, synchronous stderr, and lack of
  readiness drain are documented example constraints, not production defaults.

## Gate

Final pre-PR review gate: **PASS**. The scoped implementation and local
evidence are ready for exact-head verification and PR creation. Merge remains a
separate fresh-approval gate after live CI, reviews, and threads converge.
