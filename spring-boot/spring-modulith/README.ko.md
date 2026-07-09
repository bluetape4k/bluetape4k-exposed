# exposed-spring-modulith

[English](./README.md) | 한국어

Exposed DSL 기반 JDBC-only Spring Modulith `EventPublicationRepository`를 제공하는 Spring Boot 자동 설정
모듈입니다. Spring Modulith event publication을 애플리케이션의 JDBC 데이터베이스에 저장하면서 Exposed
`springTransactionManager`를 그대로 사용합니다.

artifact는 공식 Spring Modulith 저장소 모듈처럼 보이지 않도록
`spring-modulith-exposed`가 아니라 `exposed-spring-modulith` 형태를
사용합니다.

## 의존성

```kotlin
dependencies {
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-spring-boot-jdbc:1.12.0")
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-spring-modulith:1.12.0")
}
```

## 런타임 구성

![Spring Modulith Exposed JDBC wiring diagram](../../docs/images/readme-diagrams/spring-boot-exposed-spring-modulith-diagram-01.png)

Repository는 애플리케이션과 같은 `DataSource` 및 Exposed
`springTransactionManager`를 사용합니다. Spring Modulith 2.x의
`EventPublicationRepository` SPI가 동기 인터페이스이므로 R2DBC 또는
`suspend` 구현은 제공하지 않습니다.

## Publication 생명주기

![Spring Modulith publication lifecycle sequence diagram](../../docs/images/readme-diagrams/spring-boot-exposed-spring-modulith-sequence-01.png)

`create(publication)`은 active publication row를 추가합니다. Listener 실행 중에는 row가 `PROCESSING`,
`FAILED`, `RESUBMITTED` 상태를 거칠 수 있고, `markCompleted(...)`는 설정된 completion mode에 따라 처리합니다.

- `UPDATE`: active row를 유지하고 `COMPLETED`와 `COMPLETION_DATE`를 기록합니다.
- `DELETE`: 완료된 active row를 제거합니다.
- `ARCHIVE`: savepoint로 archive table에 복사한 뒤 active row를 삭제합니다.

Completion 연산은 중복 retry 호출에 대해 idempotent하게 동작합니다. `UPDATE` mode는 최초
`COMPLETION_DATE`를 보존하고, `DELETE` mode는 완료 row를 남기지 않으며, `ARCHIVE` mode는 archive row를
하나만 유지합니다. `markResubmitted(...)`를 반복 호출해도 첫 resubmission에서만 attempts와 timestamp가
갱신됩니다.

### Outstanding publication restart

Spring Modulith는 애플리케이션 시작 시 incomplete publication을 다시 발행할 수 있습니다.

```yaml
spring:
  modulith:
    events:
      republish-outstanding-events-on-restart: true
```

이 property를 켜면 Spring Modulith가 Exposed-backed publication table에서 incomplete row를 읽고, 매칭되는
`@ApplicationModuleListener`를 다시 호출합니다. 완료된 row는 건너뛰므로 이미 완료 처리된 listener는 restart 중에
재실행되지 않습니다. Restart replay가 성공한 뒤에도 completion mode는 그대로 적용됩니다. `UPDATE`는 완료 row를
유지하고, `DELETE`는 제거하며, `ARCHIVE`는 archive table로 이동합니다.

Republished event는 이전 process가 중단되기 전에 이미 발생한 외부 side effect를 반복할 수 있습니다.
`@ApplicationModuleListener` consumer는 idempotent하게 작성하고, stable listener id를 사용하며, 중복이 위험한
outbound call, projection write, message send에는 application-level deduplication key를 두세요. Event type을
로드할 수 없는 row는 event class를 복구하거나 저장 row를 마이그레이션할 때까지 incomplete 상태로 남습니다.

## Observability

Micrometer가 classpath에 있고 `MeterRegistry` bean이 있으면 module이 Exposed store gauge를 자동 등록합니다.
같은 운영 view를 다른 component가 이미 제공한다면 끌 수 있습니다.

```yaml
bluetape4k:
  spring:
    modulith:
      exposed:
        observability:
          enabled: true
          include-unloadable: true
          tags:
            application: orders
```

주요 meter는 `bluetape4k.exposed.modulith.publications`입니다. 낮은 cardinality tag만 사용합니다.

- `state`: `incomplete`, `completed`, `failed`, `unloadable`.
- `completion.mode`: `update`, `delete`, `archive`.
- 추가 `tags`는 모든 meter에 붙습니다. application, region, environment처럼 배포 단위로 제한된 값만 사용하세요.

Spring Modulith의 기본 event publishing metric인 `module.events.published` 계열은 application event 발행을
설명합니다. 이 Exposed gauge는 durable publication store 상태를 설명합니다. 즉 pending row, completion mode에 따른
completed row, failed row, event type을 더 이상 로드할 수 없는 incomplete row를 운영자가 확인할 수 있게 합니다.

Kotlin 코드에서는 Spring Modulith의 Java-style static factory 대신 package function을 사용할 수 있습니다.

```kotlin
val publication = targetEventPublicationOf(
    event = "order-1",
    targetIdentifier = publicationTargetIdentifierOf("listener.order-submitted"),
    publicationDate = Instant.now(),
)
```

## Cache write event publication

![JDBC Caffeine cache write event publication sequence diagram](../../docs/images/readme-diagrams/spring-boot-exposed-spring-modulith-cache-write-sequence-01.png)

`SpringModulithJdbcCaffeineRepository`는 동기 JDBC Caffeine repository를 위한 opt-in base class입니다. 이벤트는
cache write가 JDBC persistence boundary에 도달한 뒤에만 Spring application event로 발행됩니다.

- `WRITE_THROUGH`: 동기 DB write가 성공한 뒤 발행합니다.
- `WRITE_BEHIND`: background flush가 commit되고 queue depth 감소 및 retained batch clear가 끝난 뒤 발행합니다.
- `READ_ONLY`, `invalidate`, `invalidateAll`, `clear`: 이벤트를 발행하지 않습니다.

write-behind queue는 process-local이며 durable outbox가 아닙니다. process가 flush 전에 종료되면 queued write와
이벤트가 유실될 수 있습니다. DB commit은 성공했지만 event publication이 실패한 경우에도 committed batch를
재실행하지 않고 post-commit notification failure로 기록합니다. Consumer가 `@ApplicationModuleListener`를
사용한다면, 특히 `WRITE_BEHIND` mode에서는 Spring `TransactionOperations`를 전달해 Spring application event가
Spring Modulith가 commit 이후 완료 처리할 수 있는 transaction 안에서 발행되도록 하세요.

```kotlin
data class ActorRenamedEvent(val actorId: Long) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

class ActorRepository(
    events: ApplicationEventPublisher,
    transactions: TransactionOperations,
) :
    SpringModulithJdbcCaffeineRepository<Long, ActorRecord>(
        config = LocalCacheConfig.WRITE_THROUGH,
        eventPublisher = events,
        transactionOperations = transactions,
    ) {
    override fun toDomainEvent(id: Long, entity: ActorRecord): Any =
        ActorRenamedEvent(actorId = id)
}

@Component
class ActorProjection {
    @ApplicationModuleListener
    fun on(event: ActorRenamedEvent) {
        // projection 갱신
    }
}
```

event DTO는 stable package/name을 가진 public top-level type으로 두고, Jackson 직렬화가 가능한 최소 payload만
담는 것을 권장합니다. cached entity, `Pair`, credential, token, raw secret, raw email address, full record를
그대로 발행하지 마세요. Spring Modulith는 event type과 serialized payload를 저장하므로 package rename 또는 DTO
shape 변경은 unloadable event row를 만들 수 있습니다.

### JDBC Caffeine migration

이 기능은 `1.12.0+`에서 사용할 수 있습니다. 기존 JDBC Caffeine repository는 base class를
`AbstractJdbcCaffeineRepository`에서 `SpringModulithJdbcCaffeineRepository`로 바꾸고,
`ApplicationEventPublisher`를 주입한 뒤 `LocalCacheConfig.WRITE_THROUGH` 또는 `WRITE_BEHIND`를 명시하고,
transactional Modulith listener가 필요하면 `TransactionOperations`를 함께 주입하고, `toDomainEvent(...)`와
`@ApplicationModuleListener` consumer를 추가하면 됩니다.

`WRITE_BEHIND` mode에서 배포 또는 rollback 전에는 queue를 drain하고
`validateConsistency().queueDepth == 0`, `lastFlushError == null`을 확인하세요. Rollback은
`toDomainEvent(...)`에서 `null`을 반환하거나 repository base class를 `AbstractJdbcCaffeineRepository`로 되돌리는
방식으로 수행할 수 있습니다. 기존 Spring Modulith publication row는 설정된 completion mode를 계속 따릅니다.

### Operator runbook

- Queue full: write rate를 줄이거나 `writeBehindQueueCapacity`를 늘리세요. 거부된 write는 cache에 저장되지 않고
  event도 발행되지 않습니다.
- Close timeout: 로그에서 flush되지 않은 write-behind entry를 확인하고, write replay 전에 DB 상태를 검증하세요.
- write와 event coupling이 write latency보다 중요하면 `WRITE_THROUGH`를 우선 사용하세요.
- queued write-behind entry가 process crash 이후에도 살아야 한다면 application-level durable outbox를 사용하세요.
- 이 통합이 지원하지 않는 범위: suspended JDBC Caffeine, R2DBC Caffeine, delete/invalidation event, 기존 repository
  bean auto-wrapping, durable write-behind queue.

## 로드할 수 없는 이벤트 타입

`EVENT_TYPE`을 더 이상 로드할 수 없는 row도 incomplete, failed, status query에서 계속 보입니다. package rename,
dependency drift, classpath 문제 이후에도 미전달 publication을 운영자가 확인할 수 있게 하기 위해서입니다.
이런 row에서 `publication.event`에 접근하면 publication id, listener id, event type을 담은
`UnloadableEventPublicationException`이 발생합니다.

운영자는 event class를 classpath에 복구하거나, event type과 payload를 마이그레이션하거나, 저장된 publication을
수정한 뒤 명시적으로 삭제 또는 재전송해야 합니다. 로드할 수 없는 event type을 전달 완료로 간주하면 안 됩니다.

## 설정

```yaml
bluetape4k:
  spring:
    modulith:
      exposed:
        table-name: EVENT_PUBLICATION
        archive-table-name: EVENT_PUBLICATION_ARCHIVE
        completion-mode: update
        initialize-schema: false
```

`completion-mode`는 Spring Modulith의 `UPDATE`, `DELETE`, `ARCHIVE`를
지원합니다. 운영 스키마 관리는 Flyway 또는 Liquibase 사용을 권장합니다.
`initialize-schema`는 Exposed `SchemaUtils`를 사용하므로 테스트나 작은 로컬
애플리케이션에 적합합니다.

기본 table은 Spring Modulith JDBC schema 형태를 따릅니다. event id, listener id, event type, serialized
payload, publication date, completion date, status, completion attempts, last resubmission date를 저장합니다.

## 검증

통합 테스트는 `exposed-jdbc-tests`의 `TestDB.enabledDialects()`를
사용하므로 기본 범위는 H2, PostgreSQL, MySQL 8입니다. CI에서는
`EXPOSED_TEST_DB=POSTGRESQL` 또는 `EXPOSED_TEST_DB=MYSQL_V8`로 매트릭스를
줄일 수 있습니다.
