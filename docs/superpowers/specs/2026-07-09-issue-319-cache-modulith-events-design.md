# Issue #319 Cache Spring Modulith Events 설계

## 배경

GitHub issue #319는 milestone `1.12.0`을 대상으로 하며, cache write 경로에서
domain event를 발행하는 Spring Modulith 통합을 요청한다. 이때 cache durability
semantics를 약화해서는 안 된다.

현재 source에서 확인되는 사실:

- `JdbcCacheRepository`, `SuspendedJdbcCacheRepository`,
  `R2dbcCacheRepository`는 cache operation을 제공하지만 persistence 이후 hook은
  제공하지 않는다.
- `AbstractJdbcCaffeineRepository`가 JDBC Caffeine `WRITE_THROUGH` 및
  `WRITE_BEHIND` persistence 지점을 소유한다.
- `spring-boot/spring-modulith`는 이미 Spring Modulith publication state를 위한
  JDBC 전용 `EventPublicationRepository`를 제공한다.
- Spring Modulith 문서는 `ApplicationEventPublisher.publishEvent(...)`를 일반적인
  event entrypoint로 설명하며, `@ApplicationModuleListener`는 event publication
  registry와 listener 실행을 통합한다.

## 목표

cache write가 durable database boundary에 도달한 후에만 Spring application
event를 발행하는 opt-in JDBC Caffeine 통합을 추가한다.

구현에서는 다음 timing을 명시해야 한다:

- `WRITE_THROUGH`: 동기 DB write가 성공한 후 발행한다.
- `WRITE_BEHIND`: 보존된 async batch flush가 성공한 후 발행한다.
- DB write 실패 또는 보존된 flush 실패 시에는 아무것도 발행하지 않는다.
- Queue acceptance는 publication boundary가 아니다.

## 범위

포함 범위:

- persistence 이후 cache write를 위한 protected extension point를
  `AbstractJdbcCaffeineRepository`에 추가한다.
- JDBC Caffeine repository를 위한 Spring Modulith opt-in repository base class를
  `spring-boot/spring-modulith`에 추가한다.
- 기존 JDBC Caffeine write path를 통해 `put` 및 `putAll`을 지원한다.
- persisted entity마다 event 하나를 발행하고 repository write order를 보존한다.
- Invalidation-only operation은 cache-only로 유지하며 event를 발행하지 않는다.
- `spring-boot/spring-modulith/README.md` 및 `README.ko.md`에 timing contract를
  문서화한다.
- 기존 diagram은 publication repository lifecycle을 설명하고 cache write
  publication boundary를 설명하지 않으므로 README sequence diagram 하나를
  추가한다.

이 PR의 제외 범위:

- Suspended JDBC Caffeine 및 R2DBC Caffeine event hook.
- Process-local write-behind queue를 위한 durable outbox.
- 임의의 기존 repository bean을 자동으로 wrapping하는 기능.
- Delete/invalidation event 발행.

## API 설계

### JDBC Caffeine Hook

`exposed/jdbc-caffeine`에 이름이 지정된 persisted-write value type을 추가한다:

```kotlin
data class CachePersistedWrite<ID : Any, E : Serializable>(
    val id: ID,
    val entity: E,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = ...
    }
}
```

`AbstractJdbcCaffeineRepository`에 다음 protected open hook을 추가한다:

```kotlin
protected open fun afterPersisted(id: ID, entity: E) {
}

protected open fun afterPersisted(writes: List<CachePersistedWrite<ID, E>>) {
}
```

단일 write hook은 `WRITE_THROUGH` hot path에서 추가 collection allocation을
피한다. Batch hook은 성공한 write-behind flush를 위한 것이며, 기본 구현은
iteration order에 따라 단일 write hook에 위임한다.

호출 지점:

- `WRITE_THROUGH`에서 `writeToDb(id, entity)`가 반환한 후
- `WRITE_BEHIND`에서 `flushBatch(batch)`가 commit되고 queue depth를 감소시킨 뒤
  보존된 batch를 지운 후

Batch hook은 실제로 persisted된 named write의 immutable snapshot을 받는다. 일반적인
DB failure에서는 hook을 건너뛴다. DB write/flush path에서 발생한
`CancellationException`은 계속 re-throw한다.

Hook 및 publisher failure는 post-commit notification failure다. 성공한 DB write
또는 flush를 실패한 cache write로 바꾸거나, 이미 commit된 write-behind batch를
보존하거나 replay하거나, write-behind worker를 중단해서는 안 된다. Repository는
민감하지 않은 context를 log하고 계속 실행한다.

### Spring Modulith Opt-In Base

`spring-boot/spring-modulith`는 다음을 제공한다:

```kotlin
abstract class SpringModulithJdbcCaffeineRepository<ID : Any, E : Serializable>(
    config: LocalCacheConfig,
    private val eventPublisher: ApplicationEventPublisher,
) : AbstractJdbcCaffeineRepository<ID, E>(config) {

    protected abstract fun toDomainEvent(id: ID, entity: E): Any?
}
```

`afterPersisted(...)`를 override하고, mapping된 event 중 null이 아닌 각각에 대해
`ApplicationEventPublisher.publishEvent(...)`를 호출한다. `null`을 반환하면 두
번째 predicate API를 만들지 않고도 호출자가 선택한 entity의 publication을
억제할 수 있다.

`toDomainEvent(...)`는 application이 소유하는 최소한의 event DTO를 반환해야 한다.
Cached entity, `Pair<ID, E>`, credential, token, raw secret 또는 민감한 field를
포함한 전체 record를 반환해서는 안 된다. Bridge는 이미 생성된 event object만
받아들여야 하며 serialized payload, event class name 또는 caller가 제어하는
reflective type information을 받아서는 안 된다.

이렇게 하면 Spring dependency는 Spring Modulith module에 격리되고 JDBC Caffeine
module은 Spring-neutral 상태로 유지된다.

Spring Modulith module은 다음을 선언한다:

```kotlin
api(project(":bluetape4k-exposed-jdbc-caffeine"))
```

새 public base class가 `AbstractJdbcCaffeineRepository`를 확장하고 constructor를
통해 `LocalCacheConfig`를 노출하므로 dependency를 export한다. 이 변경은 Spring
Modulith artifact dependency surface를 바꾸지만 Spring dependency를
`exposed/jdbc-caffeine`으로 역누출하지 않는다. 이 기능은 auto-configuration이나
bean auto-wrapping을 추가하지 않으며, application은 base class를 확장해 opt in한다.

Constructor에 의도적으로 `LocalCacheConfig` default를 두지 않는다. 호출자는 event
publication을 위해 `WRITE_THROUGH` 또는 `WRITE_BEHIND`를 명시적으로 선택해야 한다.
`READ_ONLY`는 상속된 cache mode behavior로만 허용되며 durable DB write가 발생하지
않으므로 아무것도 발행하지 않는다.

## 동작 계약

- `WRITE_THROUGH`의 `put(id, entity)`는 DB transaction이 성공한 후에만 발행한다.
  Publisher 또는 mapper exception은 log하며 이미 commit된 DB write를 rollback하거나
  cache write call을 실패시키지 않는다.
- `WRITE_BEHIND`의 `put(id, entity)`는 background flush가 보존된 batch를 commit하고,
  queue depth를 감소시키고, 보존된 batch를 지운 후에만 발행한다. Publisher 또는
  mapper exception은 log하며 이미 commit된 batch를 보존하거나 replay하지 않는다.
- `putAll(entities, batchSize)`는 각 entry에 대해 `put`으로 위임하는 기존
  repository behavior를 유지한다. Publication은 각 entry의 persistence boundary를
  따른다. 순서는 전달된 `Map` iteration order를 따르며 deterministic publication
  order가 필요하면 `linkedMapOf`와 같은 ordered map을 사용한다.
- Write-behind queue가 가득 차면 cache write와 publication을 모두 수행하지 않는다.
- Write-behind flush가 실패하면 batch를 보존하고 queue depth를 유지하며, 이후
  성공한 flush가 발생할 때까지 publication하지 않는다.
- 보존된 write-behind batch가 나중에 새로 append된 write와 함께 성공하면 hook은
  worker가 flush한 순서대로 complete committed batch snapshot을 발행한다.
- Duplicate ID는 coalesce하지 않는다. 여러 write가 동일한 ID를 대상으로 하더라도
  accepted write item 각각은 해당 item이 DB boundary에 도달한 후 mapping된 event
  하나를 발행할 수 있다.
- `close()` final flush는 동일한 successful-flush publication rule을 사용한다.
  Final flush가 실패하면 publication하지 않고 `lastFlushError`에 DB failure를
  기록한다. Close가 timeout되면 background worker가 scope cancellation 완료 전에
  실제로 commit한 경우에만 이후 publication을 허용한다.
- `invalidate`, `invalidateAll`, `clear`는 local cache만 변경한다.
- `READ_ONLY` mode는 in-process cache만 갱신하며 event를 발행하지 않는다.

Publication fanout은 DB persistence 후 repository worker/caller와 동기적으로
수행된다. Exposed DB transaction의 외부에서 수행된다. 느린 event publication은
write-through call latency와 write-behind worker cycle latency를 증가시킬 수 있지만,
이미 persisted된 item을 queue depth 또는 retained-batch state에 남겨 두어서는 안 된다.

## Publication failure semantics

Mapper 및 publisher exception은 database persistence failure와 별개다.

- `WRITE_THROUGH`: DB write success는 success로 유지한다. Mapper 또는 publisher
  failure는 가능한 경우 repository context와 event type을 함께 log한다. Exception은
  caller에게 propagate하지 않는다.
- `WRITE_BEHIND`: DB flush success는 success로 유지한다. Queue depth를 감소시키고
  retained batch를 지우며 mapper 또는 publisher failure를 log한다. Worker는 계속
  실행하고 committed batch를 replay하지 않는다.
- Database write/flush path의 `CancellationException`은 계속 rethrow한다. Mapper 또는
  publisher implementation은 일반적인 event rejection에 cancellation signal을
  throw하지 말고 publication을 억제하려면 `null`을 반환해야 한다.

따라서 cache/database write는 성공했지만 post-commit event가 Spring에 전달되지
않을 수 있다. Spring Modulith durable listener tracking은
`ApplicationEventPublisher.publishEvent(...)`가 event를 accept한 후에만 시작된다.

## Crash window

Process-local write-behind queue는 durable outbox가 아니다.

- Write-behind flush 전: process crash가 발생하면 queued write와 해당 event를 잃을 수
  있다.
- DB commit 후 `publishEvent` 전: DB row는 durable하지만 Spring이 아직 event를 받지
  않았으므로 event를 잃을 수 있다.
- `publishEvent` 반환 후: Spring Modulith publication durability는 listener/publication
  repository configuration을 따른다.
- Shutdown final flush 중: 성공한 final flush는 event를 발행할 수 있고, 실패한 final
  flush는 아무것도 발행하지 않으며 `lastFlushError`를 기록한다. Close timeout은
  queued write/event를 flush하지 않은 상태로 남길 수 있다.

더 강한 write/event coupling이 필요한 operator는 process-local write-behind queue에
의존하는 대신 `WRITE_THROUGH` 또는 application-level durable outbox를 사용해야 한다.

## 관찰 가능성

이 PR에서는 새 metric API를 추가하지 않는다. 운영 신호는 다음과 같다:

- 기존 `validateConsistency()` field: mode, queue depth, worker running state 및
  last DB flush error
- Enqueue backpressure에서 발생하는 기존 queue-full exception
- Retained DB flush retry, mapped-null skip, publication success 또는 failure, close
  timeout에 대한 log

Log에는 serialized event payload, credential, token 또는 full cached record를 포함해서는
안 된다.

## 문서 및 Diagram 결정

#319가 public integration behavior를 변경하므로 README 업데이트가 필요하다.

새 diagram도 필요하다. 현재 Spring Modulith diagram은 다음을 보여 준다:

- JDBC `EventPublicationRepository`의 runtime wiring
- application event가 이미 publish된 후의 publication row lifecycle

그러나 cache enqueue, DB persistence, Spring event publication 사이의 차이는 보여
주지 않는다. 새 sequence diagram은 `WRITE_THROUGH`와 `WRITE_BEHIND` timing을 모두
보여 주어 사용자가 process-local queue acceptance를 durable publication으로
오해하지 않게 해야 한다.

README와 KDoc에는 concrete subclass, 작은 event DTO,
`WRITE_THROUGH` 또는 `WRITE_BEHIND` configuration 및
`@ApplicationModuleListener` example을 포함해야 한다. 이 기능은 synchronous JDBC
Caffeine repository만 지원하며, 이 PR에서는 suspended JDBC Caffeine 및 R2DBC
Caffeine repository가 Spring Modulith event를 발행하지 않는다는 점을 명시해야 한다.
또한 지원하지 않는 범위인 `READ_ONLY` publication, delete 또는 invalidation event,
기존 bean auto-wrapping 및 durable write-behind queue/outbox도 명시해야 한다.

Diagram asset:

- `docs/images/readme-diagrams/spring-boot-exposed-spring-modulith-cache-write-sequence-01.svg`
- `docs/images/readme-diagrams/spring-boot-exposed-spring-modulith-cache-write-sequence-01.png`

## 검증 요구사항

- JDBC Caffeine protected hook의 unit/integration test:
  - `WRITE_THROUGH` success는 DB write 후 hook을 발행한다.
  - `WRITE_BEHIND` success는 flush 후에만 hook을 발행한다.
  - Write-behind queue full failure는 아무것도 발행하지 않는다.
  - Duplicate ID는 last-write-wins가 아니라 accepted write item별로 발행한다.
  - Retained batch와 이후 appended write가 combined commit 후 flushed order대로
    발행된다.
  - Hook exception은 이미 commit된 write-behind batch를 retain/replay하지 않으며
    successful write-through persistence를 rollback하지 않는다.
- Spring Modulith test:
  - Mapped event는 `ApplicationEventPublisher`를 통해 발행된다.
  - `null` mapped event는 건너뛴다.
  - `putAll`은 전달된 map iteration order로 모든 mapped event를 발행한다.
  - Publisher exception은 이미 persisted된 write-behind batch를 retain/replay하지
    않으며 successful write-through persistence를 rollback하지 않는다.
  - Example event DTO는 minimal identifier만 포함하고 full cached record 또는 민감한
    credential data를 포함하지 않는다.
  - Bridge는 serialization, deserialization 또는 reflective event class loading을
    수행하지 않는다.
  - Slow/blocking publisher fixture는 publication fanout이 끝나기 전에 이미 persisted된
    write-behind item이 queue depth에서 제거됨을 입증한다.
  - `READ_ONLY` mode는 아무것도 발행하지 않는다.
- Auto-configuration regression:
  - 기존 Spring Modulith auto-configuration은 cache repository bean을 생성하지
    않고도 계속 load된다.
- 현재 test fixture로 가능한 경우 failed write-through 및 retained write-behind
  failure에 대한 regression test.
- Test synchronization은 concrete fixture를 사용해야 한다. 예를 들면 test
  repository의 latch, `validateConsistency()` queue depth polling 및 필요한 경우
  `close()` final flush behavior를 사용한다. 임의의 sleep을 primary assertion
  mechanism으로 의존하지 않는다.
- Targeted Gradle verification:
  - `:bluetape4k-exposed-jdbc-caffeine:test`
  - `:bluetape4k-exposed-spring-modulith:test`
- Documentation verification:
  - SVG XML validation.
  - CairoSVG PNG render.
  - Sequence diagram style 및 connector audit.
  - Full-size PNG inspection.
  - `git diff --check`.

## 위험

- `WRITE_BEHIND` flush는 asynchronous하므로 가능한 경우 test는 timing sleep 대신
  repository가 제공하는 lifecycle method를 기다려야 한다.
- Publication은 DB persistence 후 process 안에서 수행되므로 Spring Modulith
  durability는 Spring이 event를 받은 시점부터 시작한다. Process death를 넘어
  write-behind queue 자체를 durable하게 만들지는 않는다.
- Public abstract class에 protected hook을 추가하는 것은 source-compatible하지만,
  비슷한 이름의 member를 override하는 subclass가 현재 존재하지 않는지 확인하고
  compilation으로 점검해야 한다.

## Migration 및 Rollback

- 기존 Spring Modulith publication table을 넘어서는 database schema change는
  도입하지 않는다.
- Existing repository는 명시적으로 `SpringModulithJdbcCaffeineRepository`를
  확장하기 전까지 영향을 받지 않는다.
- Rollback option:
  - publication을 억제하려면 `toDomainEvent(...)`에서 `null`을 반환한다.
  - Application repository를 `AbstractJdbcCaffeineRepository`로 되돌린다.
  - Mapped-event 변경을 deploy 또는 rollback하기 전에 write-behind queue를 drain한다.
- Existing Spring Modulith publication row는 configured Spring Modulith repository와
  completion mode가 계속 관리한다. 이 기능은 해당 row를 migrate하거나 delete하지
  않는다.
