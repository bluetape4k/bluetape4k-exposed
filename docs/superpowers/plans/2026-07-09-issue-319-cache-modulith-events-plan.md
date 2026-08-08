# Issue #319 Cache Spring Modulith Events 계획

## 대상

milestone `1.12.0`에 issue #319 opt-in JDBC Caffeine + Spring Modulith event
publication path를 제공한다.

명세:

- `docs/superpowers/specs/2026-07-09-issue-319-cache-modulith-events-design.md`

## 성공 기준

- JDBC Caffeine이 public cache repository interface를 변경하지 않고 protected
  post-persistence hook을 노출한다.
- Spring Modulith opt-in JDBC Caffeine base가 durable cache write 이후에만
  mapped Spring event를 publish한다.
- `WRITE_THROUGH` 및 `WRITE_BEHIND` timing을 test로 검증한다.
- README.md와 README.ko.md가 timing contract와 지원하지 않는
  suspended/R2DBC 범위를 설명한다.
- 렌더링한 sequence diagram이 cache enqueue, DB persistence, event publication
  boundary를 보여 준다.
- 대상 module test와 diagram check가 통과한다.

## 구현 작업

1. test로 JDBC hook 동작을 고정한다.
   - protected hook 호출을 기록하는 test repository subclass를 추가한다.
   - DB write 후 `WRITE_THROUGH` 성공을 검증한다.
   - background flush가 완료된 뒤에만 `WRITE_BEHIND`가 성공하는지 검증한다.
   - queue-full failure에서는 hook을 publish하지 않는지 검증한다.
     `writeBehindBatchSize = 1`, `writeBehindQueueCapacity = 1`인 latch 기반
     blocking flush fixture를 사용하고 실패한 entity id가 hook/event record에
     절대 나타나지 않는지 단언한다.
   - 수락된 write item별 duplicate ID를 검증한다.
     `Map`은 duplicate key를 표현할 수 없으므로 `put(sameId, v1)`과
     `put(sameId, v2)`를 순차적으로 사용한다.
   - retained batch와 appended write가 하나의 committed flush order가 되는지
     검증한다.
     일시적인 failed flush 후 write를 append하고, failed flush에서는 event가
     없으며 retry 후 수락된 각 write마다 순서가 있는 event 하나가 발생하는지
     단언한다.
   - hook exception이 DB flush failure가 아닌 post-commit notification failure로
     처리되는지 검증한다.
   - hook cancellation은 별도로 검증한다. broad hook catch는 ordinary failure를
     logging하기 전에 `CancellationException`을 다시 던져야 한다.

2. JDBC Caffeine hook을 추가한다.
   - named serializable value type으로 `CachePersistedWrite<ID, E>`를 추가한다.
   - `AbstractJdbcCaffeineRepository`에 single-write 및 batch
     `afterPersisted(...)` hook을 추가한다.
   - `CachePersistedWrite`, single/batch `afterPersisted`, ordering, duplicate ID,
     post-commit failure semantics, no-sensitive-payload 기대 사항에 대한
     English KDoc을 추가한다.
   - `writeToDb(id, entity)`가 성공한 뒤 호출한다.
   - 정확한 write-behind 순서:
     `DB commit -> immutable persisted snapshot -> decrement queueDepth -> clear
     retained batch -> invoke afterPersisted/publisher`.
     `flushBatch`는 DB success/failure만 책임진다. worker와 final-flush path가
     queue-depth decrement, retained batch clear, hook invocation을 소유한다.
   - `CancellationException` rethrow와 ordinary failure retention을 유지한다.
   - 이미 commit된 batch를 replay하지 않도록 ordinary hook failure를 catch/log하되,
     `CancellationException`은 다시 던진다.
   - static allocation guard: write-through는 `afterPersisted(id, entity)`를
     직접 호출해야 하며 single-write hot path에서 `listOf`,
     `CachePersistedWrite`, batch snapshot을 할당하지 않아야 한다.
     write-behind는 성공한 flush마다 snapshot 하나를 할당할 수 있다.

3. Spring Modulith opt-in base를 추가한다.
   - `spring-boot/spring-modulith`에서
     `api(project(":bluetape4k-exposed-jdbc-caffeine"))`를 추가한다.
   - `SpringModulithJdbcCaffeineRepository`를 추가한다.
   - mapped non-null event를 `ApplicationEventPublisher`를 통해 publish한다.
   - mapping은 abstract로 유지하고 payload ownership은 application subclass에
     둔다.
   - `LocalCacheConfig`를 명시적으로 요구하고 `READ_ONLY`를 no-publication
     mode로 문서화한다.
   - ordinary mapper 및 publisher exception을 post-commit publication failure로
     catch/log하되 `CancellationException`은 다시 던진다.
   - write-through/write-behind contract, safe minimal event DTO guidance,
     sample usage를 담은 English KDoc을 추가한다.
   - KDoc에 다음도 명시해야 한다: synchronous JDBC Caffeine만 지원하며
     suspended JDBC/R2DBC는 지원하지 않는다. `READ_ONLY`는 아무것도 publish하지
     않는다. invalidation 및 delete path는 아무것도 publish하지 않는다. durable
     outbox는 없다. publication은 Exposed transaction 외부에서 발생한다.
     mapper/publisher failure는 log한 뒤 삼킨다.
   - stable DTO guidance, null suppression, duplicate-id behavior, cancellation
     boundary를 포함한 `toDomainEvent(...)` English KDoc을 추가한다.

4. test로 Spring integration 동작을 고정한다.
   - opt-in base를 확장하는 concrete test repository를 생성한다.
   - event order, skip-null, failure boundary를 집중 검증하도록 recording
     publisher/context capture를 사용한다.
   - Spring Modulith 전용 test table/record/repository fixture는
     `spring-boot/spring-modulith/src/test` 아래에 둔다. 의도적으로
     `testFixtures`로 승격하지 않는 한 `exposed/jdbc-caffeine/src/test` fixture에
     의존하지 않는다.
   - concrete repository bean, 실제 `ApplicationEventPublisher`,
     `@ApplicationModuleListener`를 사용하는 실제 Spring integration smoke test
     하나를 추가한다. 적어도 하나의 `WRITE_THROUGH` path가
     listener/publication pipeline에 도달하고 `WRITE_BEHIND`는 flush 뒤에만
     publish하는지 검증한다.
   - `put`, `putAll`, write-behind flush timing을 검증한다.
   - `WRITE_THROUGH`와 `WRITE_BEHIND`에서 publisher exception을 검증한다.
   - write-behind hook 및 publisher exception에서 `queueDepth == 0`,
     `lastFlushError == null`, close 전 worker가 계속 실행 중인지 단언하고,
     후속 write가 성공적으로 flush/publish되는지 단언한다.
   - write-through 및 write-behind publication path에서 mapper/publisher
     cancellation을 검증한다.
   - `LocalCacheConfig()` default misuse를 포함해 `READ_ONLY`가 아무것도
     publish하지 않는지 검증한다.
   - close final flush 성공은 event를 publish하고 실패한 final flush는 아무것도
     publish하지 않는지 검증한다.
   - dialect마다 30초를 기다리지 않도록 latch/test seam으로 close timeout을
     검증한다.
   - minimal ID/status/version/timestamp만 포함하는 safe event DTO와 민감한
     cached entity fixture를 추가한다. publish된 object가 cached entity도,
     `Pair<ID, E>`도 아니며 credential/token/raw-secret/full-record field를
     노출하지 않는지 단언한다.
   - static bridge check: 새 `SpringModulithJdbcCaffeineRepository`는 생성된
     event object만 받아야 하며 `EventSerializer`, `ObjectMapper`, serialized
     payload parameter, event class-name parameter, `Class.forName`, `ClassUtils`,
     classloader 기반 reconstruction을 포함하지 않아야 한다.
   - `RecordingLogbackAppender`를 사용한 logging test: mapper/publisher failure는
     cache name, mode, batch count, event type, exception type 같은 sanitized
     context만 log하는지 확인한다. entity/event `toString()`, serialized
     payload, credential, token, raw secret, full cached record는 log하지 않는다.
   - auto-configuration regression: 기존 `ExposedModulithAutoConfiguration`
     context가 cache repository bean을 생성하거나 기존 repository를 auto-wrap하지
     않고 로드되는지 검증한다.

5. performance/backpressure test를 추가한다.
   - slow/blocking `ApplicationEventPublisher` latch를 사용한다.
   - write-behind에서는 DB flush 후 publication을 block하고 `queueDepth == 0`,
     retained batch가 replay되지 않으며 worker가 살아 있는지 단언한다.
   - 추가 write가 정상적으로 enqueue되거나 설정한 capacity가 소진되면 기존
     queue-full exception으로 실패하는지 검증한다.
   - write-through에서는 bounded latch assertion으로 publisher delay가 caller
     latency에 포함됨을 검증하고 문서화한다.

6. docs와 diagram을 갱신한다.
   - `spring-boot/spring-modulith/README.md`에 “Cache Write Event Publication”
     절을 추가한다.
   - `spring-boot/spring-modulith/README.ko.md`에 한국어 동등 절을 추가한다.
   - `WRITE_THROUGH`/`WRITE_BEHIND` config example, concrete subclass, 작은 event
     DTO, `@ApplicationModuleListener` listener를 포함한다. event DTO는
     top-level/public, stable-package, Jackson-serializable, minimal이어야 한다.
     cached entity, `Pair`, full record, secret, credential, raw email/token field를
     publish하지 말라는 경고를 추가한다.
   - “Migration from existing JDBC Caffeine repository” guidance를 추가한다:
     feature version `1.12.0+`, dependency requirement, base class 변경,
     `ApplicationEventPublisher` injection, 명시적인 `WRITE_THROUGH` 또는
     `WRITE_BEHIND`, `toDomainEvent` implementation, listener 추가,
     write-behind drain procedure, rollback procedure.
   - 지원하지 않는 범위와 crash window를 문서화한다: process-local write-behind
     queue, suspended JDBC, R2DBC, delete/invalidation event, auto-wrapping,
     durable queue/outbox.
   - Spring Modulith가 event type과 serialized payload를 저장하므로 event DTO
     package/name stability를 기존 unloadable event type guidance와 연결한다.
   - “Operator Runbook” subsection을 추가해 deploy/rollback drain check
     (`validateConsistency().queueDepth == 0`, `lastFlushError == null`), queue
     full response, close timeout response, `WRITE_THROUGH`로 전환할 시점,
     application-level durable outbox가 필요한 시점을 다룬다.
   - sequence SVG/PNG 하나를 추가한다:
     `docs/images/readme-diagrams/spring-boot-exposed-spring-modulith-cache-write-sequence-01`.
     module README에서 다음 경로로 embed한다:
     `../../docs/images/readme-diagrams/spring-boot-exposed-spring-modulith-cache-write-sequence-01.png`.
   - diagram skill command로 SVG/PNG를 검증한다.

7. 검증.
   - 다음을 실행한다:
     `./gradlew :bluetape4k-exposed-jdbc-caffeine:test :bluetape4k-exposed-spring-modulith:test --no-configuration-cache --no-build-cache --no-parallel --console=plain`
   - Testcontainers 기반 증거는 실행을 순차적으로 유지한다. 전체 module test가
     iteration에 너무 넓으면 먼저 H2 fast path를 실행한 다음
     `EXPOSED_TEST_DB=POSTGRESQL`과 `EXPOSED_TEST_DB=MYSQL_V8` 대상 module run을
     `--no-parallel`로 실행한다. async write-behind assertion에는 임의의 sleep이
     아니라 latch/polling synchronization을 사용한다.
   - diagram check를 실행한다:
     `xmllint`, CairoSVG render, sequence style audit,
     connector/geometry/endpoint/mixed-corner audit, full-size PNG inspection.
   - dependency/boundary check를 실행한다:
     `rg -n "org\\.springframework|springframework\\.modulith" exposed/jdbc-caffeine/src/main`
     는 Spring import를 반환하지 않아야 하며 기존 Spring Modulith
     auto-configuration context는 cache repository bean을 생성하지 않고
     로드되어야 한다.
   - dependency surface check를 실행한다:
     `./gradlew :bluetape4k-exposed-spring-modulith:dependencies --configuration apiClasspath --no-configuration-cache --console=plain`
     를 실행하고 `bluetape4k-exposed-jdbc-caffeine`이 exported API dependency로
     표시되는지 확인한다. local에서 publication metadata generation이 가능하면
     generated POM에도 같은 API dependency가 있는지 확인한다.
   - `git diff --check`를 실행한다.
   - Nightly(full) 상태를 확인하고 아직 실행 중이면 PR note에 포함한다.
   - DoD에 stress/backpressure 증거를 기록한다: queue capacity, batch size,
     slow publisher latch duration, 관찰한 queue depth transition, worker
     running state, overflow exception assertion, replay/duplicate event가 없는
     count.

## 호환성 및 rollback

- cache implementation API 변경은 abstract class의 protected open method 하나뿐이며
  기존 caller는 code를 변경할 필요가 없다.
- application은 Spring Modulith base class를 확장해 opt-in한다. 기존 repository는
  auto-wrap하지 않는다.
- Spring integration을 rollback해야 한다면 protected hook을 runtime behavior를
  변경하지 않는 no-op extension point로 남길 수 있다.
- application rollback은 `AbstractJdbcCaffeineRepository`로 되돌리거나
  `toDomainEvent`에서 `null`을 반환하는 방식이다. mapper output을 변경해
  deploy/rollback할 때는 write-behind queue를 drain한다.

## 명시적 비목표

- 이 PR에는 suspended JDBC 또는 R2DBC publication path를 포함하지 않는다.
- delete/invalidation event를 publish하지 않는다.
- flush 전 write-behind entry를 위한 durable queue/outbox를 추가하지 않는다.
- #320은 아직 merge되지 않은 draft PR이므로 branch 전체를 #320
  aggregate/domain event contract로 migration하지 않는다.
