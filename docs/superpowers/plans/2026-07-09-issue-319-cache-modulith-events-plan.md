# Issue #319 Cache Spring Modulith Events Plan

## Target

Deliver the issue #319 opt-in JDBC Caffeine + Spring Modulith event publication
path for milestone `1.12.0`.

Spec:

- `docs/superpowers/specs/2026-07-09-issue-319-cache-modulith-events-design.md`

## Success Criteria

- JDBC Caffeine exposes a protected post-persistence hook without changing the
  public cache repository interfaces.
- A Spring Modulith opt-in JDBC Caffeine base publishes mapped Spring events
  after durable cache writes only.
- `WRITE_THROUGH` and `WRITE_BEHIND` timing is covered by tests.
- README.md and README.ko.md explain the timing contract and unsupported
  suspended/R2DBC scope.
- A rendered sequence diagram shows cache enqueue, DB persistence, and event
  publication boundaries.
- Targeted module tests and diagram checks pass.

## Implementation Tasks

1. Lock JDBC hook behavior with tests.
   - Add a test repository subclass that records protected hook calls.
   - Cover `WRITE_THROUGH` success after DB write.
   - Cover `WRITE_BEHIND` success only after background flush completes.
   - Cover queue-full failure publishes no hook.
     Use a latch-based blocking flush fixture with `writeBehindBatchSize = 1`
     and `writeBehindQueueCapacity = 1`, then assert the failed entity id never
     appears in hook/event records.
   - Cover duplicate IDs as per accepted write item.
     Use sequential `put(sameId, v1)` and `put(sameId, v2)` because `Map`
     cannot represent duplicate keys.
   - Cover retained batch plus appended writes as one committed flush order.
     Use a transient failed flush followed by an appended write; assert no event
     on the failed flush and one ordered event per accepted write after retry.
   - Cover hook exceptions as post-commit notification failures, not DB flush
     failures.
   - Cover hook cancellation separately: broad hook catches must rethrow
     `CancellationException` before logging ordinary failures.

2. Add JDBC Caffeine hook.
   - Add `CachePersistedWrite<ID, E>` as a named serializable value type.
   - Add single-write and batch `afterPersisted(...)` hooks to
     `AbstractJdbcCaffeineRepository`.
   - Add English KDoc for `CachePersistedWrite`, single/batch
     `afterPersisted`, ordering, duplicate IDs, post-commit failure semantics,
     and no-sensitive-payload expectations.
   - Call it after `writeToDb(id, entity)` succeeds.
   - Exact write-behind order:
     `DB commit -> immutable persisted snapshot -> decrement queueDepth -> clear
     retained batch -> invoke afterPersisted/publisher`.
     Keep `flushBatch` responsible for DB success/failure only; worker and
     final-flush paths own queue-depth decrement, retained batch clear, and hook
     invocation.
   - Preserve `CancellationException` rethrow and ordinary failure retention.
   - Catch/log ordinary hook failures so they do not replay already committed
     batches, but rethrow `CancellationException`.
   - Static allocation guard: write-through must call
     `afterPersisted(id, entity)` directly and must not allocate `listOf`,
     `CachePersistedWrite`, or a batch snapshot on the single-write hot path.
     Write-behind may allocate one snapshot per successful flush.

3. Add Spring Modulith opt-in base.
   - Add `api(project(":bluetape4k-exposed-jdbc-caffeine"))` from
     `spring-boot/spring-modulith`.
   - Add `SpringModulithJdbcCaffeineRepository`.
   - Publish mapped non-null events through `ApplicationEventPublisher`.
   - Keep mapping abstract and payload ownership in the application subclass.
   - Require explicit `LocalCacheConfig`; document `READ_ONLY` as a
     no-publication mode.
   - Catch/log ordinary mapper and publisher exceptions as post-commit
     publication failures, but rethrow `CancellationException`.
   - Add English KDoc with write-through/write-behind contract, safe minimal
     event DTO guidance, and sample usage.
   - KDoc must also state: only synchronous JDBC Caffeine is supported; no
     suspended JDBC/R2DBC support; `READ_ONLY` publishes nothing; invalidation
     and delete paths publish nothing; no durable outbox; publication occurs
     outside the Exposed transaction; mapper/publisher failures are logged and
     swallowed.
   - Add English KDoc for `toDomainEvent(...)`, including stable DTO guidance,
     null suppression, duplicate-id behavior, and cancellation boundary.

4. Lock Spring integration behavior with tests.
   - Create a concrete test repository extending the opt-in base.
   - Use recording publisher/context capture for focused event order,
     skip-null, and failure-boundary tests.
   - Put Spring Modulith-specific test table/record/repository fixtures under
     `spring-boot/spring-modulith/src/test`; do not depend on
     `exposed/jdbc-caffeine/src/test` fixtures unless they are deliberately
     promoted to `testFixtures`.
   - Add one real Spring integration smoke test with a concrete repository bean,
     real `ApplicationEventPublisher`, and `@ApplicationModuleListener`.
     Verify at least one `WRITE_THROUGH` path reaches the listener/publication
     pipeline, and verify `WRITE_BEHIND` publishes only after flush.
   - Cover `put`, `putAll`, and write-behind flush timing.
   - Cover publisher exceptions in `WRITE_THROUGH` and `WRITE_BEHIND`.
   - For write-behind hook and publisher exceptions, assert `queueDepth == 0`,
     `lastFlushError == null`, worker still running before close, and a
     subsequent write flushes/publishes successfully.
   - Cover mapper/publisher cancellation in write-through and write-behind
     publication paths.
   - Cover `READ_ONLY` publishes nothing, including `LocalCacheConfig()` default
     misuse.
   - Cover close final flush success publishes events; failed final flush
     publishes none.
   - Cover close timeout with a latch/test seam that avoids waiting 30 seconds
     per dialect.
   - Add a sensitive cached entity fixture with a safe event DTO containing only
     minimal IDs/status/version/timestamp. Assert the published object is not
     the cached entity, not `Pair<ID, E>`, and exposes no credential/token/
     raw-secret/full-record fields.
   - Static bridge check: the new `SpringModulithJdbcCaffeineRepository` must
     accept only constructed event objects and must not contain `EventSerializer`,
     `ObjectMapper`, serialized payload parameters, event class-name parameters,
     `Class.forName`, `ClassUtils`, or classloader-based reconstruction.
   - Logging test with `RecordingLogbackAppender`: mapper/publisher failures log
     only sanitized context such as cache name, mode, batch count, event type,
     and exception type. Do not log entity/event `toString()`, serialized
     payloads, credentials, tokens, raw secrets, or full cached records.
   - Auto-configuration regression: existing `ExposedModulithAutoConfiguration`
     context loads without creating cache repository beans or auto-wrapping
     existing repositories.

5. Add performance/backpressure tests.
   - Use a slow/blocking `ApplicationEventPublisher` latch.
   - For write-behind, block publication after DB flush and assert
     `queueDepth == 0`, retained batch is not replayed, and worker remains
     alive.
   - Verify additional writes either enqueue normally or fail with the existing
     queue-full exception when configured capacity is exhausted.
   - For write-through, verify/document that publisher delay is included in
     caller latency with a bounded latch assertion.

6. Update docs and diagram.
   - Add a “Cache Write Event Publication” section to
     `spring-boot/spring-modulith/README.md`.
   - Add the Korean equivalent to
     `spring-boot/spring-modulith/README.ko.md`.
   - Include `WRITE_THROUGH`/`WRITE_BEHIND` config examples, a concrete
     subclass, a small event DTO, and an `@ApplicationModuleListener` listener.
     The event DTO must be top-level/public, stable-package, Jackson-serializable,
     and minimal. Add a warning not to publish cached entities, `Pair`s, full
     records, secrets, credentials, or raw email/token fields.
   - Add “Migration from existing JDBC Caffeine repository” guidance:
     feature version `1.12.0+`, dependency needs, base class change,
     `ApplicationEventPublisher` injection, explicit `WRITE_THROUGH` or
     `WRITE_BEHIND`, `toDomainEvent` implementation, listener addition,
     write-behind drain procedure, and rollback procedure.
   - Document unsupported scope and crash windows: process-local write-behind
     queue, suspended JDBC, R2DBC, delete/invalidation events, auto-wrapping,
     and durable queue/outbox.
   - Connect event DTO package/name stability to the existing unloadable event
     type guidance because Spring Modulith stores event type and serialized
     payload.
   - Add an “Operator Runbook” subsection covering deploy/rollback drain checks
     (`validateConsistency().queueDepth == 0`, `lastFlushError == null`), queue
     full response, close timeout response, when to switch to `WRITE_THROUGH`,
     and when an application-level durable outbox is required.
   - Add one sequence SVG/PNG:
     `docs/images/readme-diagrams/spring-boot-exposed-spring-modulith-cache-write-sequence-01`.
     Embed it from module README files as
     `../../docs/images/readme-diagrams/spring-boot-exposed-spring-modulith-cache-write-sequence-01.png`.
   - Validate the SVG/PNG using the diagram skill commands.

7. Verification.
   - Run:
     `./gradlew :bluetape4k-exposed-jdbc-caffeine:test :bluetape4k-exposed-spring-modulith:test --no-configuration-cache --no-build-cache --no-parallel --console=plain`
   - For Testcontainers-backed evidence, keep runs sequential. If the full
     module test is too broad for iteration, run H2 fast path first, then
     `EXPOSED_TEST_DB=POSTGRESQL` and `EXPOSED_TEST_DB=MYSQL_V8` targeted module
     runs with `--no-parallel`. Use latch/polling synchronization, not arbitrary
     sleeps, for async write-behind assertions.
   - Run diagram checks:
     `xmllint`, CairoSVG render, sequence style audit, connector/geometry/
     endpoint/mixed-corner audits, and full-size PNG inspection.
   - Run dependency/boundary checks:
     `rg -n "org\\.springframework|springframework\\.modulith" exposed/jdbc-caffeine/src/main`
     must return no Spring imports, and the existing Spring Modulith
     auto-configuration context must load without creating cache repository
     beans.
   - Run dependency surface checks:
     `./gradlew :bluetape4k-exposed-spring-modulith:dependencies --configuration apiClasspath --no-configuration-cache --console=plain`
     and verify `bluetape4k-exposed-jdbc-caffeine` appears as an exported API
     dependency. If publication metadata generation is available locally, check
     the generated POM contains the same API dependency.
   - Run `git diff --check`.
   - Check Nightly(full) status and include it in PR notes if still running.
   - Record stress/backpressure evidence in DoD: queue capacity, batch size,
     slow publisher latch duration, observed queue depth transition, worker
     running state, overflow exception assertion, and no replay/duplicate event
     counts.

## Compatibility And Rollback

- The only cache implementation API change is a protected open method on an
  abstract class; existing callers do not need code changes.
- Applications opt in by extending the Spring Modulith base class. Existing
  repositories are not auto-wrapped.
- If the Spring integration must be rolled back, the protected hook can remain
  as a no-op extension point without changing runtime behavior.
- Rollback for an application is switching back to
  `AbstractJdbcCaffeineRepository` or returning `null` from `toDomainEvent`.
  Drain write-behind queues before deploy/rollback when changing mapper output.

## Explicit Non-Goals

- No suspended JDBC or R2DBC publication path in this PR.
- No delete/invalidation event publication.
- No durable queue/outbox for write-behind entries before flush.
- No branch-wide migration to #320 aggregate/domain event contracts because
  #320 is still an unmerged draft PR.
