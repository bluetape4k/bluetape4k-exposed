# 이슈 #326 Ktor R2DBC 캐시 및 DDD 데모 설계

날짜: 2026-07-17
이슈: #326
마일스톤: 1.12.0
브랜치: `feat/issue-326-ktor-r2dbc-ddd-demo`

## 문제

`examples/ktor-exposed-demo`는 현재 호출자가 소유하는 H2 JDBC 및
H2 R2DBC 리소스, Exposed health/readiness, 하나의 JDBC 트랜잭션 라우트를
보여준다.
하지만 R2DBC 트랜잭션 헬퍼, 캐시 기반 저장소, Spring 중립적인 DDD
계약, 명시적인 비-Spring 이벤트 전달을 보여주지는 않는다.

이슈 #326은 이러한 기능을 연결하는 실행 가능한 Ktor 예제를 요청한다.
이 예제는 서로 관련 없는 API 카탈로그가 아니라 애플리케이션 시나리오로서
쉽게 따라갈 수 있어야 한다.

## 목표

**Order Confirmation** 시나리오를 중심으로 데모를 확장한다:

1. HTTP 호출자가 클라이언트가 생성한 UUID로 식별되는 주문을 확인한다;
2. Ktor 라우트가 HTTP 입력을 `OrderCommandService`로 매핑한다;
3. 서비스가 대기 중인 `DemoOrder` aggregate를 조회하거나 생성한다;
4. aggregate가 `OrderConfirmed` domain event를 기록한다;
5. R2DBC Caffeine 저장소가 확인된 `OrderRecord`를 기록한다;
6. 저장소 작업이 성공적으로 반환된 후에만 서비스가 이벤트 snapshot을
   애플리케이션이 소유하는 Spring-free publisher로 전달한다;
7. 캐시된 GET 라우트가 read-through를 통해 확인된 주문을 조회한다;
8. readiness가 PostgreSQL R2DBC 연결 상태와 저장소의 정제된 인메모리
   일관성 상태를 보고한다.

결과물은 bilingual README 쌍, 정적 Architecture Diagram,
시간 순서가 표시된 Sequence Diagram, 복사하여 붙여 넣을 수 있는 로컬
명령어, 집중된 테스트를 통해 이해할 수 있어야 한다.

## 범위 경계

### 포함

- `examples/ktor-exposed-demo` production/test/docs 표면과 canonical
  README diagram 디렉터리만 확장한다. Workflow evidence는 추가로
  `docs/superpowers/`, `docs/review/`, `docs/lessons/` 아래의 이슈별
  spec, plan, checklist, review, lesson 파일을 업데이트할 수 있다.
- 기존 H2 JDBC 리소스와 `/transactions/jdbc-count` 라우트를 유지한다.
- 데모의 H2 R2DBC runtime을 PostgreSQL R2DBC로 교체한다.
- PostgreSQL 기반 R2DBC count 라우트를 추가한다.
- 결정적인 `WRITE_THROUGH` 모드를 사용하는 UUID 기반 R2DBC Caffeine
  order repository를 추가한다.
- `:bluetape4k-exposed-r2dbc-caffeine`에 대한 직접 프로젝트
  dependency를 추가한다. 예제는 기본 repository abstraction에 대해
  transitive dependency에 의존해서는 안 된다.
- 기존 repository의
  `alias(bt4k.plugins.kotlin.serialization)` plugin을 적용하여 Ktor
  response DTO serializer가 생성되도록 한다. 이 작업은 dependency
  version authority를 추가하지 않는다.
- Spring 중립적인 aggregate, domain event, command service,
  application-owned event publisher port를 추가한다.
- component name이 `orders`인 cache readiness를 추가한다.
- 로컬 PostgreSQL을 위한 module-local `compose.yaml`을 추가한다.
- 빠른 non-Docker service test와 전용 Gradle task를 통한 순차적인
  PostgreSQL Testcontainers application/integration test를 추가한다.
- `README.md`와 `README.ko.md`를 semantic parity로 업데이트한다.
- 일치하는 canonical SVG/PNG Architecture 및 Sequence Diagram 쌍을
  추가한다.

### 제외

- production library API 변경 없음.
- 새로운 Gradle module 또는 published artifact 없음.
- Ktor demo event path에 Spring Boot, Spring
  `ApplicationEventPublisher`, Spring Modulith 또는 JaVers type 없음.
- write-behind cache mode 없음.
- durable outbox, event store, replay queue, retry worker 또는
  exactly-once guarantee 없음.
- cache/database atomicity claim 없음.
- production authentication/authorization design 없음. 데모는
  loopback에 바인딩하고 permissive CORS를 설치하지 않으며, 아래에
  설명된 작은 browser-origin guard를 적용한다. 외부 바인딩에는 이 예제
  외부에서 애플리케이션이 소유하는 authentication, authorization 및 TLS가
  필요하다.
- 해당 manual은 release-pinned 상태이므로 안정적인 `1.11.0` manual
  edit 없음.
- 퍼블리시 집계, BOM 제약, 카탈로그 업그레이드, Exposed 1.3.1
  마이그레이션 또는 이슈 #322 작업 없음.
- CI/nightly workflow edit 없음. 일반 `test` task는 빠르고
  non-Docker 상태로 유지하며, 필수 PostgreSQL evidence는
  `--no-parallel`을 사용하는 전용 로컬 `postgresIntegrationTest`
  invocation으로 수행한다.

## 현재 근거

### Ktor 데모

- `KtorExposedDemoResources`는 호출자가 소유하는 H2 JDBC 및 H2
  R2DBC 리소스를 생성하고 닫지만, JDBC `DemoItems` table만 초기화한다.
- `installKtorExposedDemo`는 Ktor core/Exposed integration을 설치하고
  health/readiness 외에는 `/transactions/jdbc-count`만 노출한다.
- 현재 test는 health, readiness 및 JDBC count만 검증한다.
- 이 예제는 이미 publishing aggregation에서 제외되어 있다.
- examples CI job은 `:examples-ktor-exposed-demo:test`를 실행한다. 전용
  `postgresIntegrationTest` source set/task는 해당 task의 discovery/
  dependency graph 외부에 유지되어야 기존 invocation이 빠르고
  non-Docker 상태로 남는다.

### 재사용 가능한 R2DBC/cache 계약

- `ApplicationCall.exposedR2dbcTransaction`은 이미 cancellation을
  보존하고 Ktor integration을 통해 일반적인 transaction failure를
  매핑한다.
- `AbstractR2dbcCaffeineRepository`는 read-through `get`,
  write-through `put`, `invalidate`, `validateConsistency` 및 명시적인
  `close` lifecycle을 제공한다.
- `TimebasedUUIDTable`과 기존 credential test repository는 new-row
  `put`과 호환되는 client-generated UUID table을 보여준다.
- `ExposedKtorCacheContributor.r2dbcRepository("orders")`는 repository의
  side-effect-free O(1) in-memory consistency report를 허용한다.

### 재사용 가능한 DDD 계약

- `AbstractAggregateRoot<UUID>`는 순서가 보장되는 Spring-neutral event를
  기록한다.
- `domainEvents()`는 삭제하지 않고 순서가 보장되는 snapshot을 반환하며,
  `clearDomainEvents()`는 호출자가 소유하는 명시적인 discard operation이다.
- `drainDomainEvents`는 durable owner로 전달하기 위한 용도로 예약되어
  있으며, 의도적으로 non-durable한 이 in-memory demo publisher에서는
  사용하지 않는다.
- `DomainEvent`는 immutable하고 최소화되며 민감하지 않은 payload를
  요구하고 durable publication을 제공하지 않는다.
### 문서화 및 다이어그램 규칙

- 예제 문서는 상호 링크가 연결되고 명령어, 경로, 제한 사항이 의미적으로 동등하도록 `README.md`와 `README.ko.md`를 쌍으로 유지합니다.
- 정식 README 시각 자료는 일치하는 SVG/PNG 쌍으로 `docs/images/readme-diagrams/` 아래에 저장하며, README 파일에는 PNG를 삽입합니다.
- 가장 가까운 시각적 참조 자료는 R2DBC Caffeine 아키텍처 및 시퀀스 쌍입니다:
  `docs/images/readme-diagrams/exposed-r2dbc-caffeine-diagram-01.png` 및 `docs/images/readme-diagrams/exposed-r2dbc-caffeine-sequence-01.png` (일치하는 SVG 소스 포함).
- PostgreSQL Testcontainers 예제는 `postgres:16-alpine`을 사용하며 명시적인 시작/중지 소유권을 가집니다.

## 선택한 아키텍처

사용자는 **아키텍처 B — 명령 경계(Command Boundary)**를 선택했습니다.

```text
HTTP Client
    |
    v
Ktor Routes (HTTP mapping only)
    |
    v
OrderCommandService
    |-- load/create DemoOrder aggregate
    |-- repository.put() must return successfully
    |-- snapshot events, publish, then clear only on success
    |
    +--> OrderR2dbcCaffeineRepository
    |       |--> Caffeine local cache
    |       `--> PostgreSQL R2DBC source of truth
    |
    `--> application-owned OrderEventPublisher

Exposed readiness
    |-- PostgreSQL connectivity via the existing R2DBC probe
    `-- cache.orders via repository.validateConsistency()
```

### 책임 경계

| 컴포넌트 | 책임 | 수행해서는 안 되는 작업 |
|---|---|---|
| Ktor routes | UUID 경로 매개변수를 파싱하고, 애플리케이션 서비스를 호출하며, 안정적인 응답/상태를 매핑합니다 | repository 메서드 주변에서 Exposed 트랜잭션을 열거나, 이벤트를 발행하거나, 풀을 소유하지 않습니다 |
| `OrderCommandService` | 애그리거트를 로드/생성하고, 확정하고, 레코드를 저장하며, 실패한 쓰기를 보상한 후 이벤트를 인계합니다 | 영속적/원자적 전달을 보장한다고 주장하지 않습니다 |
| `DemoOrder` | pending에서 confirmed로의 전환을 강제하고 `OrderConfirmed`를 한 번 기록합니다 | 스스로 영속화하거나, Ktor/cache/Spring을 알지 않습니다 |
| `OrderRecord` | 직렬화 가능한 PostgreSQL/Caffeine 표현입니다 | 애그리거트의 pending-event buffer를 포함하지 않습니다 |
| `OrderR2dbcCaffeineRepository` | 읽기 통과/쓰기 통과 저장 및 일관성 보고를 담당합니다 | 애플리케이션 이벤트를 발행하지 않습니다 |
| `OrderEventPublisher` | 동기식이며 Spring에 종속되지 않는 애플리케이션 인계를 담당합니다 | 애그리거트 이벤트를 직접 삭제하거나 재생(replay)을 보장한다고 주장하지 않습니다 |
| `KtorExposedDemoResources` | 애플리케이션 리소스를 순서에 따라 생성, 초기화, 노출 및 종료합니다 | Ktor 라이브러리 내부에 소유권을 숨기지 않습니다 |

## 거부된 대안

### 대안 A: Routes가 repository와 publisher를 직접 호출

중요한 순서 및 보상 규칙이 HTTP 핸들러 전반에 분산되어 독립적으로 테스트하기 어려워지므로 거부했습니다.

### 대안 C: Repository decorator가 자동으로 이벤트를 발행

캐시 repository는 이벤트를 포함하는 애그리게이트가 아니라 `OrderRecord`를 영속화하므로 거부했습니다. 숨겨진 발행은 비원자적 경계를 불명확하게 만들고, 일반적인 영속성 추상화를 이 데모의 이벤트 정책에 결합하게 됩니다.

### `WRITE_BEHIND` 캐시 모드

비동기 플러시, drain, 재시도 및 종료 의미론이 예제를 지배하게 되므로 거부했습니다. 주문 확정에는 `repository.put()`이 성공적으로 반환된 후에만 이벤트 인계가 시작된다는 결정론적 규칙이 필요합니다.

### H2 PostgreSQL 호환 모드

사용자가 실제 PostgreSQL R2DBC 예제를 선택했으므로 주문 경로에서는 거부했습니다. H2는 독립적인 JDBC 스모크 경로에서만 유지합니다.

### `main`에서 Testcontainers 자동 시작

애플리케이션 런타임이 테스트 라이브러리에 의존해서는 안 되므로 거부했습니다. 로컬 런타임은 Docker Compose를 사용하고, 테스트가 Testcontainers를 명시적으로 소유합니다.

## 도메인 모델

### `DemoOrder`

```kotlin
class DemoOrder private constructor(
    override val id: UUID,
    status: OrderStatus,
    updatedAt: Instant,
) : AbstractAggregateRoot<UUID>() {
    var status: OrderStatus = status
        private set

    var updatedAt: Instant = updatedAt
        private set

    fun confirm(occurredAt: Instant): Boolean

    companion object {
        fun pending(id: UUID, createdAt: Instant): DemoOrder
        fun rehydrate(record: OrderRecord): DemoOrder
    }
}
```

- 상태 값은 `PENDING` 및 `CONFIRMED`입니다.
- `status`와 `updatedAt`에는 private setter가 있으므로 호출자가 애그리게이트 전환을 우회할 수 없습니다.
- `confirm(occurredAt)`은 `PENDING`을 `CONFIRMED`로 변경하고, `updatedAt`을 동일한 시각으로 설정하며, 정확히 하나의 `OrderConfirmed`를 기록하고 `true`를 반환합니다.
- 이미 확정된 애그리게이트에서 `confirm()`을 호출하면 순차적으로 멱등적인 no-op이 되어 `false`를 반환하며, 중복 이벤트를 기록하지 않습니다.
- 재수화는 과거 이벤트/발행된 이벤트를 절대 다시 생성하지 않습니다.
### `OrderConfirmed`

```kotlin
data class OrderConfirmed(
    override val aggregateId: UUID,
    override val occurredAt: Instant,
) : DomainEvent<UUID>, Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
```

페이로드에는 주문 식별자와 발생 시각만 포함됩니다. 변경할 수 없으며 고객, 테넌트, 자격 증명 또는 결제 데이터는 포함하지 않습니다.

### `OrderRecord`

```kotlin
data class OrderRecord(
    val id: UUID,
    val status: OrderStatus,
    val updatedAt: Instant,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
```

`OrderRecord`는 PostgreSQL과 Caffeine에 저장되는 유일한 객체입니다. `AggregateRoot`를 상속하지 않으며 도메인 이벤트 버퍼도 없습니다.
`OrderCommandService`는 주입된 `Clock` 하나를 소유합니다. 성공적인 상태 전이는 하나의 `clock.instant()` 값을 `OrderConfirmed.occurredAt`과 저장되는 `OrderRecord.updatedAt` 양쪽에 사용합니다.

## 영속성과 캐시 설계

- `DemoOrders : TimebasedUUIDTable("ktor_demo_orders")`는 클라이언트에서 생성한
  UUID를 사용하므로 새 레코드는 제네릭 리포지토리에 의해 삽입됩니다.
- `OrderR2dbcCaffeineRepository`는
  `AbstractR2dbcCaffeineRepository<UUID, OrderRecord>`를 확장하며, 리포지토리
  사용 전에 프로세스 전체의 R2DBC 기본값으로
  `org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager`를 통해 명시적인
  데모 `R2dbcDatabase`를 바인딩합니다.
- `LocalCacheConfig(writeMode = CacheWriteMode.WRITE_THROUGH)`는 예제에서
  결정적인 완료를 제공합니다.
- `get(id)`는 읽기 통과를 보여줍니다: 캐시 미스 → PostgreSQL 로드 → 캐시
  채우기.
- `put(id, record)`는 쓰기 통과를 보여주지만, 기존 구현은 데이터베이스 쓰기를
  시도하기 전에 Caffeine을 업데이트합니다. 따라서 캐시와 데이터베이스는 원자적이지
  않습니다.
- 동시 리더는 캐시 업데이트와 PostgreSQL 실패/무효화 사이의 구간에 새로 캐시된
  값을 관찰할 수 있습니다. 데모는 이 일시적인 더티 읽기 구간을 허용하고 문서화하며,
  키별 읽기/쓰기 직렬화가 아니라 실패 후 최선의 무효화만 보장합니다.
- ID가 없는 경우 제네릭 교육 경로는 읽기 통과 SELECT를 수행한 다음 UPDATE를
  수행하고, 업데이트된 행이 0개이면 INSERT를 수행합니다. 데모는 실제 리포지토리
  추상화를 재사용하기 위해 이 세 문장을 허용합니다. 데모 로컬 upsert를 추가하지
  않으며 정확한 SQL 개수를 공개적인 회귀 계약으로 만들지도 않습니다.

### 쓰기 실패 보상

`repository.put()`이 예외를 발생시키면:

1. `OrderCommandService`는 오염되었을 가능성이 있는 미영속 캐시 값을 제거하기 위해
   `repository.invalidate(order.id)`를 호출합니다;
2. 퍼블리셔는 호출되지 않습니다;
3. 애그리거트의 이벤트 버퍼는 그대로 유지됩니다;
4. 서비스는 원래 영속성 예외를 원인으로 하는, 상수 메시지의
   `OrderPersistenceException`을 발생시킵니다. 무효화도 실패하면 해당 실패는 원래
   원인에 suppressed evidence로 첨부됩니다.

이 보상은 프로세스 로컬이며 최선의 노력으로 수행됩니다. 원자적인 데이터베이스/캐시 트랜잭션을 생성하지 않습니다.

취소는 일반적인 영속성 실패와 별도로 처리됩니다. `put()`이 `CancellationException`을 발생시키면 서비스는 짧은 `NonCancellable` 정리 경계에서 로컬 Caffeine 무효화만 수행한 후 동일한 취소 인스턴스를 다시 발생시킵니다. PostgreSQL을 재시도하거나, 이벤트를 발행하거나, 취소를 애플리케이션 오류로 변환하거나, 치명적인 JVM `Error` 값을 catch하지 않습니다. 무효화 실패는 원래의 일반 실패 또는 취소를 대체하지 않고 원래 예외에 suppressed로 첨부됩니다.

취소로 인해 커밋 결과가 모호해질 수 있습니다. `put()`의 반환을 취소가 막기 직전에 PostgreSQL이 커밋했을 수 있기 때문입니다. 이 경우 캐시 키는 무효화되고, 이벤트는 발행되지 않으며, 요청은 실패하지만 PostgreSQL에는 확인된 레코드가 존재할 수 있습니다. 데모는 이 경계에 대해 재시도하거나 정확히 한 번의 복구를 주장하지 않습니다. 호출자는 데이터베이스에서 조정하거나 프로덕션 환경에서 내구성 있는 outbox를 사용해야 합니다.
## 이벤트 전달 설계

`OrderEventPublisher`는 동기식 애플리케이션 포트입니다.

```kotlin
fun interface OrderEventPublisher {
    fun publish(events: List<DomainEvent<UUID>>)
}
```

데모 런타임 구현은 Spring 없이 메모리에 최신 불변 이벤트 스냅샷만
유지합니다. 퍼블리셔 계약은 즉시 처리되는 동기식 논블로킹 계약입니다.
데이터베이스, 네트워크, 파일, 블로킹 로거, sleep 또는 디스패처 전환은
허용되지 않습니다. I/O를 수행하는 프로덕션 퍼블리셔는 이 데모 외부에서
명시적인 디스패처/큐/아웃박스를 소유해야 합니다. 테스트에서는 기록용 및
실패를 발생시키는 퍼블리셔를 사용합니다.

라우트가 사용하는 명령 API는 다음과 같이 고정됩니다.

```kotlin
suspend fun confirm(orderId: UUID): OrderConfirmationResult
```

`OrderConfirmationResult`에는 저장된 `OrderRecord`와
`eventPublished: Boolean`이 포함됩니다. 서비스는
`repository.get(orderId)`로 레코드를 로드하고, 레코드가 있으면
재수화하거나 없으면 대기 중인 aggregate를 생성합니다. 내부 aggregate
인자를 받는 seam은 집중적인 실패 테스트를 위해서만 존재할 수 있으며,
라우트는 aggregate를 직접 로드하거나 변경하지 않습니다.

일반적인 repository 및 publisher 실패는 원래 throwable을 cause로
유지하면서, 상수 메시지를 사용하는 `OrderPersistenceException` 및
`OrderEventHandoffException`으로 서비스 경계를 통과합니다. 라우트는
이 타입을 사용해 안정적인 `503` 코드를 선택합니다. 그 밖의 모든
취소가 아닌 `Exception`은 cause를 노출하지 않고
`ORDER_CONFIRMATION_FAILED`로 매핑합니다. 취소와 치명적인 JVM `Error`
값은 절대로 래핑하지 않습니다.

`OrderCommandService.confirm(orderId)`는 다음의 고정된 순서를 따릅니다.

1. aggregate를 로드하고 재수화하거나 생성한 다음, 주입된 `Clock`에서
   전이 시각을 가져옵니다.
2. `order.confirm(instant)`를 호출합니다.
3. 이미 confirmed 상태였다면 추가 write나 이벤트 없이 현재 record를
   반환합니다.
4. `OrderRecord`로 변환합니다.
5. 명시적인 pre-write 취소 게이트로
   `currentCoroutineContext().ensureActive()`를 호출합니다.
6. `repository.put(order.id, record)`를 호출합니다.
7. 명시적인 post-persistence 취소 게이트로
   `currentCoroutineContext().ensureActive()`를 호출합니다.
8. 성공적으로 반환되고 취소 게이트를 통과한 후에만
   `order.domainEvents()`를 가져와 `publisher.publish(events)`를 호출합니다.
9. `publish`가 성공적으로 반환된 후 `order.clearDomainEvents()`를
   호출합니다.
10. `eventPublished = events.isNotEmpty()`와 함께 저장된 record를
    반환합니다.

코루틴이 repository 반환 전 또는 반환 과정에서 취소되면
`ensureActive()`가 publication을 결정적으로 방지하고, cache key를
무효화하며, 동일한 취소를 다시 throw합니다. 게이트를 통과한 후에는
동기식 논서스펜딩 퍼블리셔와 이벤트 clear가 하나의 즉시 처리되는
프로세스 내 단계로 의도적으로 완료됩니다. 해당 handoff 이후에는
추가적인 취소 체크포인트가 없습니다.

퍼블리셔 실패는 persistence 이후에 발생합니다. 서비스는 퍼블리셔 실패를
cause로 포함하는 `OrderEventHandoffException`을 throw하고, confirmed
record는 PostgreSQL/cache에 남아 있으며, 서비스는
`clearDomainEvents()`를 호출하지 않습니다. 따라서 요청이 해당 aggregate를
소유하는 동안 ordered buffer가 유지됩니다.
order 라우트는 상수로 정제된 failure를 반환하며 HTTP retry/republication
endpoint를 노출하지 않습니다. 요청이 종료되면 애플리케이션은 해당
메모리 aggregate를 유지하지 않습니다. 이후 confirmation은 이미
confirmed 상태인 record를 다시 로드하고 이벤트를 emit하지 않습니다.
Buffer retention은 서비스 seam에서 시연할 뿐, durable recovery 또는
HTTP-level recovery로 광고하지 않습니다.
프로덕션의 atomic delivery에는 이 예제 외부에서 outbox 또는 다른
durable publication 설계가 필요합니다.

순차적으로 반복되는 confirmation은 idempotent합니다. 동시 confirmation은
보장 범위 밖입니다. 이 예제에는 ID별 lock이나 conditional database
transition이 없으므로 두 요청이 모두 `PENDING`을 읽고 write와 publish를
수행할 수 있습니다. 프로덕션 호출자에게는 optimistic
version/conditional update, idempotency key 또는 직렬화된 command
boundary가 필요합니다.
## HTTP 라우트

| 메서드 | 경로 | 동작 | 성공 |
|---|---|---|---|
| `GET` | `/healthz/exposed` | 프로브 없는 프로세스 생존성 확인; PostgreSQL 또는 캐시를 조회하지 않음 | 애플리케이션이 서비스 중일 때 `200` health JSON |
| `GET` | `/readyz/exposed` | JDBC, PostgreSQL R2DBC 및 `cache.orders` 준비 상태 확인 | 모두 UP이면 `200`; 그 외에는 기존 `503` 계약 |
| `GET` | `/transactions/jdbc-count` | 기존 H2 JDBC 헬퍼 | `200 text/plain` count |
| `GET` | `/transactions/r2dbc-count` | 기존 Ktor R2DBC 헬퍼가 `DemoOrders`를 카운트 | `200 text/plain` count |
| `POST` | `/orders/{orderId}/confirm` | 본문 없는 명령; demo 헤더를 검증하고, pending aggregate를 로드하거나 생성한 뒤 confirm, persist, publish 수행 | `200 application/json` `OrderConfirmationResponse` |
| `GET` | `/orders/{orderId}` | repository read-through 조회 | `200 application/json` `OrderResponse`; 없으면 `404` |

`orderId`는 `UUID.toString()`이 반환하는 표준 소문자 하이픈 포함 36자 표현과 정확히 일치해야 하며 nil UUID여서는 안 된다. Exposed를 통해 타입이 지정된 UUID로 바인딩되므로 nil이 아닌 모든 UUID 버전이 허용된다. 라우트 입력은 SQL에 절대 연결되지 않는다. 지나치게 긴 값, 표준 형식이 아닌 값, nil 값 및 파싱할 수 없는 값은 입력을 그대로 포함하지 않고 repository 또는 publisher에 접근하지 않은 채 상수 `400` 응답을 반환한다.

변경 라우트는 추가로 `X-Demo-Command: confirm-order`를 요구한다. 이 단순하지 않은 요청 헤더로 인해 악성 브라우저 origin은 CORS preflight를 수행하게 된다. 애플리케이션은 허용 범위가 넓은 CORS 정책을 설치하지 않으므로 브라우저 origin에서의 상태 변경은 기본적으로 허용되지 않는다. 헤더가 없거나 올바르지 않으면 dependency에 접근하지 않고 상수 `403` 응답을 반환한다. 이는 로컬 demo guard이며 인증이 아니다. 검증 우선순위는 결정적이다. POST는 먼저 command header를 확인한 다음 `orderId`를 파싱하고 검증한다. 따라서 헤더가 없거나 올바르지 않으면서 ID도 유효하지 않은 요청은 `403 DEMO_COMMAND_REQUIRED`를 반환하며, 유효한 헤더가 있어야만 `400 INVALID_ORDER_ID` 경로로 진행된다.

이 예제는 애플리케이션에 이미 설치된 Ktor core JSON composition과 작은 response DTO를 사용하며, persistence entity를 직접 노출하지 않는다. `updatedAt`은 ISO-8601 UTC `Instant` 문자열이다.

`POST /orders/{orderId}/confirm`의 응답 예시:

```json
{
  "orderId": "018f6f95-7f4a-7a20-8b52-70ad30c30f36",
  "status": "CONFIRMED",
  "updatedAt": "2026-07-17T00:00:00Z",
  "eventPublished": true
}
```

순차적으로 confirmation을 반복하면 `eventPublished`는 `false`이다. GET 응답은 처음 세 필드만 사용한다.

정확한 DTO 타입은 다음과 같다:

```kotlin
@kotlinx.serialization.Serializable
data class OrderResponse(
    val orderId: String,
    val status: String,
    val updatedAt: String,
)

@kotlinx.serialization.Serializable
data class OrderConfirmationResponse(
    val orderId: String,
    val status: String,
    val updatedAt: String,
    val eventPublished: Boolean,
)

@kotlinx.serialization.Serializable
data class DemoErrorResponse(
    val code: String,
    val message: String,
    val correlationId: String? = null,
)
```

정확한 JSON 오류 계약:

| 상태 | 코드 | 메시지 | Correlation ID |
|---|---|---|---|
| `400` | `INVALID_ORDER_ID` | `Order id must be a canonical non-nil UUID.` | 없음 |
| `403` | `DEMO_COMMAND_REQUIRED` | `Required demo command header is missing or invalid.` | 없음 |
| `404` | `ORDER_NOT_FOUND` | `Order was not found.` | 없음 |
| `503` | `ORDER_PERSISTENCE_FAILED` | `Order could not be stored.` | 있음 |
| `503` | `ORDER_EVENT_HANDOFF_FAILED` | `Order was stored but its event was not handed off.` | 있음 |
| `503` | `ORDER_CONFIRMATION_FAILED` | `Order confirmation failed.` | 있음 |
| `503` | `ORDER_READ_FAILED` | `Order could not be loaded.` | 있음 |

모든 order-route 성공 및 오류 응답은 `application/json`을 사용한다. 오류 JSON에는 위에서 명시한 `code`, `message` 및 선택적 UUID `correlationId`만 포함된다. health/readiness 및 count 라우트는 기존 Ktor integration과 위 route table에 정의된 media type을 유지하며, 두 count 라우트는 계속 `text/plain`을 사용한다.

GET 라우트는 일반적인 repository failure를 `ORDER_READ_FAILED`로 매핑한다. 그 외 order-route persistence/publication/unexpected failure는 random correlation ID와 함께 위의 상수 allowlisted `503` 변형으로 매핑된다. demo는 correlation ID, 고정 component, allowlisted operation 및 고정 outcome 필드만 포함하는 sanitized structured diagnostic 하나를 출력한다. POST는 `operation=confirm`을 사용하고, GET은 `operation=read`를 사용한다. 정확한 runtime shape은 `code=<allowlisted code> correlationId=<UUID> component=order-command operation=<confirm|read> outcome=failed`이다. 응답과 애플리케이션 로그에는 raw primary 또는 suppressed exception text, SQL, R2DBC URL, database/user/password 값, stack trace 또는 제출된 입력이 포함되어서는 안 된다. Cause는 이미 throw된 exception을 소유하는 direct service/startup tests와 in-process callers에서만 사용할 수 있으며, demo logger는 이를 렌더링하지 않는다. `CancellationException`은 항상 다시 throw하며 응답으로 매핑하지 않는다.

Startup failure는 동일한 diagnostic allowlist를 사용하고 uncaught throwable/stack trace를 출력하지 않은 채 종료한다. Startup wrapper는 direct tests를 위해 original cause와 suppressed cleanup failures를 유지하지만, `main`은 stable record `code=DEMO_STARTUP_FAILED correlationId=<UUID> component=ktor-exposed-demo phase=startup outcome=failed`만 보고한다. Testable runner는 clean normal stop 후 exit status `0`, startup failure 후 `1`, startup은 성공했지만 application-resource shutdown이 degraded된 경우 `2`를 반환한다. Cleanup도 실패하더라도 startup failure 상태는 `1`로 유지된다. `main`은 해당 status를 `exitProcess`에 전달하므로 automation이 sanitized failure를 성공으로 오인하지 않는다. Throwable은 logger argument로 절대 전달되지 않는다. README는 운영자에게 `docker compose ps`, PostgreSQL health/logs, configured environment names 및 correlation ID를 확인하도록 안내하며, raw application exception을 노출하도록 요구하지 않는다.

POST는 `OrderCommandService`만 호출한다. GET은 read-only cache demonstration을 위해 repository를 직접 호출할 수 있다. 어느 라우트도 cache repository를 `exposedR2dbcTransaction`으로 감싸지 않는데, repository가 이미 자체 `suspendTransaction`을 열기 때문이다. 전용 count 라우트는 nested transaction을 시연하지 않고 Ktor R2DBC transaction helper를 가르치기 위해 존재한다.
## PostgreSQL 런타임 구성

`KtorExposedDemoConfig`은 다음 로컬 기본값을 읽으며 명시적인 테스트 생성을 허용합니다:

| 환경 변수 | 기본값 |
|---|---|
| `DEMO_POSTGRES_R2DBC_URL` | `r2dbc:postgresql://localhost:5432/ktor_exposed_demo` |
| `DEMO_POSTGRES_USER` | `demo` |
| `DEMO_POSTGRES_PASSWORD` | `demo` |

`examples/ktor-exposed-demo/compose.yaml`은 일치하는 데이터베이스/사용자/비밀번호 기본값과 PostgreSQL health check를 사용하는 하나의
`postgres:16-alpine` 서비스를 정의합니다. 게시된 포트는 모든 네트워크 인터페이스가 아닌
`127.0.0.1`에 바인딩됩니다. 이러한 자격 증명은 로컬 데모 전용 값입니다.
자격 증명과 R2DBC URL은 HTTP 응답, 이벤트 페이로드, readiness 세부 정보 또는 로그에 표시되어서는 안 됩니다.

내장 Ktor 서버는 기본적으로 `127.0.0.1`에 바인딩됩니다. README의 제한 사항에는 호스트를 변경하거나 PostgreSQL을 loopback 외부에 노출하려면 애플리케이션이 인증, 권한 부여, TLS, 시크릿 관리 및 네트워크 정책을 직접 소유해야 한다고 명시되어 있습니다.

복사하여 붙여 넣을 수 있는 시작 순서는 repository root에서 실행되며 Gradle이 시작되기 전에 PostgreSQL을 기다립니다:

```bash
docker compose -f examples/ktor-exposed-demo/compose.yaml \
  up -d --wait --wait-timeout 60 postgres
./gradlew :examples-ktor-exposed-demo:run
```

대기가 실패하면 README는 사용자가
`docker compose -f examples/ktor-exposed-demo/compose.yaml ps` 및
`docker compose -f examples/ktor-exposed-demo/compose.yaml logs postgres`를 실행하고,
포트/container 문제를 해결한 후 동일한 제한 시간의 대기를 다시 실행하도록 안내합니다. Compose 포트는
`127.0.0.1:${DEMO_POSTGRES_PORT:-5432}:5432`이며, 다른 포트를 선택하는 사용자는 일치하는 `DEMO_POSTGRES_R2DBC_URL`을 설정해야 합니다.

Compose는 이름이 지정된 PostgreSQL 데이터 볼륨을 사용합니다. 일반적인 중지는
`docker compose -f examples/ktor-exposed-demo/compose.yaml down`이며, 이 명령은 해당 볼륨을 유지합니다. 의도적인 완전 초기화는
`docker compose -f examples/ktor-exposed-demo/compose.yaml down -v --remove-orphans`이며, 로컬 데모 데이터를 삭제합니다.
README에는 두 명령이 모두 포함되어 있고, 삭제를 수행하는 초기화를 명시하며, 두 로케일에서 포트 충돌/재시작 복구 방법을 설명합니다.

R2DBC 런타임 의존성은 H2에서 repository에 기존에 사용하던
`libs.r2dbc.postgresql` alias로 변경됩니다. Testcontainers는 기존
`libs.testcontainers.postgresql` alias를 사용합니다. 이 이슈에서는 의존성 버전이나 catalog authority를 변경하지 않습니다.
## 리소스 소유권 및 수명 주기

애플리케이션은 다음을 생성하고 소유합니다.

- Hikari 기반 H2 JDBC `Database`;
- JDBC 블로킹 디스패처;
- PostgreSQL R2DBC `ConnectionPool`;
- `R2dbcDatabase`;
- `OrderR2dbcCaffeineRepository`;
- `OrderEventPublisher` 구현체.

일반 캐시 리포지토리는 Exposed의 프로세스 전역 기본
R2DBC 데이터베이스를 확인하므로, 데모는 JVM당 활성
`KtorExposedDemoResources` 수명 주기를 하나만 허용합니다. 프로세스 로컬
원자적 임대는 기본값을 변경하기 전에 획득되며, 겹치는 두 번째 데모 수명
주기를 안정적인 시작 실패로 거부하고, 정상적인 정리와 부분적인 시작
정리 모두에서 기본값을 복원한 후 해제됩니다. 이 데모 외부의 코드는 해당
임대를 획득하지 않고도 Exposed의 전역 기본값을 변경할 수 있으므로,
README는 데모가 실행되는 동안 기본 데이터베이스의 유일한 소유자여야
한다고 명시적으로 요구합니다. 운영 환경의 통합에서는 애플리케이션
전체의 소유권 정책 또는 명시적인 데이터베이스 바인딩을 사용해야 합니다.

시작 순서:

1. JDBC 리소스를 생성합니다.
2. R2DBC `TransactionManager.defaultDatabase`를 캡처합니다.
3. PostgreSQL 연결 팩토리/풀과 `R2dbcDatabase`를 생성하고 등록한 뒤,
   매개변수가 없는 리포지토리 트랜잭션에서 사용하는 명시적 R2DBC
   기본값으로 설정합니다.
4. R2DBC 스키마를 생성합니다.
5. Caffeine 리포지토리와 이벤트 퍼블리셔를 생성합니다.
6. Ktor 통합/라우트를 설치하고 임베디드 서버를 생성 및 시작합니다.

`main`은 임베디드 서버와 `KtorExposedDemoResources`를 모두 소유하는
테스트 가능한 최상위 러너에 위임합니다. 러너는 일반적인
`ApplicationStopped` close hook을 등록한 다음, 엔진 생성, 바인딩 및
`start(wait = true)`를 `try/finally`로 감쌉니다. `finally` 경로는
필요한 경우 일부 생성되었거나 시작된 엔진을 중지하고, 동일한 멱등적
리소스 close를 대체 경로로 호출합니다. 따라서 라우트 설치, 엔진 생성,
바인딩 또는 시작이 실패하더라도, 시작 과정은 이미 획득한 리소스에 대해
실패 원자성을 유지합니다. 최초 시작 실패가 주된 실패로 유지되며, 각
정리 실패는 직접 진단할 수 있도록 suppressed evidence로 첨부되지만
데모 HTTP 표면에서 렌더링되거나 로그에 기록되지는 않습니다.

종료 순서:

1. `OrderR2dbcCaffeineRepository`를 닫아 연결 풀이 사라지기 전에 캐시와
   scope가 먼저 중지되도록 합니다.
2. R2DBC
   `TransactionManager.closeAndUnregister(r2dbcDatabase)`를 호출한 다음,
   데모를 등록 해제한 후 현재 기본값이 null인 경우에만 이전에 캡처한
   기본 R2DBC 데이터베이스를 복원합니다. 해당 호출자가 소유한 이전
   데이터베이스를 닫거나 등록 해제하지 않으며, 외부 코드가 설치한
   서로 다른 non-null 기본값을 절대로 덮어쓰지 않습니다.
3. 기존의 제한된 대기 시간을 사용하여 R2DBC 풀을 폐기합니다.
4. Hikari를 닫습니다.
5. JDBC 디스패처를 닫습니다.

리소스 close는 리소스 전반에서 멱등적이고 best-effort로 유지되며, 이전
close가 실패하더라도 이후 정리를 계속 시도합니다. 내부 close 보고서는
리포지토리 close, R2DBC 등록 해제/기본값 복원, 풀 폐기, Hikari close 및
디스패처 close에서 발생한 실패를 보존하므로, 최상위 러너가 throwable
텍스트를 로그에 기록하지 않고 최종 종료를 분류할 수 있습니다.

이 loopback 교육용 데모는 readiness-drain 상태를 노출하지 않습니다.
정상 종료 시 임베디드 엔진은 먼저 새로운 트래픽 수락을 중지하며,
one-second grace period와 five-second stop timeout을 사용합니다. Ktor는
해당 timeout 이후 엔진 종료를 강제로 완료하고, 그다음
`ApplicationStopped`가 위 순서대로 애플리케이션 리소스를 닫습니다. 엔진
중지 실패나 timeout이 발생해도 리포지토리/기본 데이터베이스/풀/JDBC
정리를 건너뛰지 않습니다. 데모가 관찰한 애플리케이션 리소스 정리
실패는 하나의
`code=DEMO_SHUTDOWN_FAILED correlationId=<UUID> component=ktor-exposed-demo phase=shutdown outcome=degraded`
로 집계됩니다. 러너는 이 단일한 정제된 레코드를 출력한 후 상태 `2`를
반환합니다. 최상위 `finally`는 시작/엔진 실패에 대한 멱등적 대체
경로입니다. Ktor 3.5의 `EmbeddedServer.stop()`은 내부 엔진 중지 예외를
호출자에게 노출하는 대신 잡아서 프레임워크 로그에 기록하므로, 이 예제는
해당 프레임워크 소유 실패를 종료 상태 `2`로 분류한다고 주장하지
않습니다. 대신 실제 one-second/five-second 설정과 계속 수행되는
`ApplicationStopped` 리소스 정리를 입증합니다. 운영 배포에서는
프레임워크 로그 정책과 엔진 수준 종료 관찰 가능성을 운영 환경이
소유합니다. 운영 오케스트레이터는 트래픽 철회, 더 긴 in-flight 요청
예산 및 프로세스 종료 전 readiness drain을 담당합니다.

풀은 기존 local-demo 범위(`initialSize = 1`,
`maxSize = 2`)를 유지하고, 최대 acquire 시간을 five-second로
추가합니다. README는 이것이 소규모 교육용 설정이며 운영 용량 지침이
아님을 명시합니다.

## Readiness 의미

설치 프로그램은 다음을 사용합니다.

```kotlin
ExposedKtorCacheReadinessConfig(
    listOf(
        ExposedKtorCacheContributor.r2dbcRepository("orders") {
            repository.validateConsistency()
        }
    )
)
```

`/readyz/exposed`는 안정적인 설치 순서에 따라 `jdbc`, `r2dbc`, 그리고
`cache.orders`를 포함합니다. R2DBC probe는 PostgreSQL 연결성을
확인합니다. 캐시 contributor는 리포지토리의 O(1)이면서 부작용이 없는
인메모리 일관성 상태만 읽습니다. 이는 Caffeine backend probe가 아니며
데이터베이스, 캐시, 네트워크 또는 파일 I/O를 수행하지 않습니다.

`WRITE_THROUGH`에서는 캐시 worker 상태가 `NOT_APPLICABLE`이며, 다른
일관성 실패가 보고되지 않는 한 UP으로 매핑됩니다. README 텍스트는
이를 모든 캐시 값이 PostgreSQL과 같다는 증거로 설명해서는 안 됩니다.
PostgreSQL을 사용할 수 없게 되면 `/healthz/exposed`는 probe-free
liveness로 유지되는 반면, `/readyz/exposed`는 설정된 제한된 R2DBC
timeout 내에 기존의 정제된 `503`을 반환합니다.

## 실패 모드

### 1. Caffeine이 업데이트된 후 PostgreSQL 쓰기 실패

- 신호: `repository.put()`이 throw됩니다.
- 동작: order key를 무효화하고 publisher를 호출하지 않으며 aggregate
  event buffer를 보존한 뒤, 원래 persistence failure를 cause로 유지하는
  `OrderPersistenceException`을 throw합니다.
- 입증: repository double을 사용하는 service test와, 실패 후 저장되지
  않은 캐시 값이 반환되지 않음을 확인하는 PostgreSQL 통합
  검증.
### 2. 영속화 후 이벤트 발행자 실패

- 신호: 동기식 발행자가 예외를 발생시킨다.
- 동작: 서비스가 실패를 전파한다. 라우트는
  `503 ORDER_EVENT_HANDOFF_FAILED`를 반환한다. 확인된 레코드는 영속화된
  상태로 남고, aggregate 이벤트는 호출자가 해당 요청 로컬
  aggregate를 보유하는 동안에만 유지된다.
- 증명: 서비스 테스트가 aggregate 참조를 유지하고 버퍼/순서를 검증하며,
  저장소 상태도 검증한다.

### 3. 유효하지 않거나 반복된 확인 요청

- 유효하지 않은/비표준/너무 큰/nil UUID: 서비스 의존성을 호출하지 않고
  상수 `400`을 반환한다.
- 누락되었거나 잘못된 `X-Demo-Command`: 서비스 의존성을 호출하지 않고
  상수 `403`을 반환한다.
- 이미 확인된 주문: 다른 저장소 쓰기나 중복 이벤트 없이 순차적으로 현재
  표현을 반환한다.
- 동시 확인은 명시적으로 멱등적이지 않으며 더 강력한 프로덕션 명령
  경계가 필요하다.
- 증명: 라우트 테스트, 순차 멱등성 테스트, 지원되지 않는 중복 발행 위험을
  기록하는 동시성 테스트.

### 4. 시작 또는 readiness 시 PostgreSQL을 사용할 수 없음

- 시작 시 스키마 초기화에 실패하면 데모 서버가 시작되지 않으며, 로컬
  진단을 위해 원래 원인을 보존한다.
- 런타임 readiness는 기존의 정제된 R2DBC DOWN/timeout 응답을 사용하며
  URL, 자격 증명, SQL 또는 예외 텍스트를 절대 노출하지 않는다.
- 증명: 통합 시작이 성공한 후 PostgreSQL을 중지하고, probe-free liveness는
  `200`으로 유지되는 한편 readiness가 제한된 timeout 내에 정제된
  `503` 세부 정보와 함께 완료되는지 검증한다.

### 5. 부분 실패 후 리소스 종료

- 저장소가 pool보다 먼저 닫힌다.
- 데모 R2DBC 데이터베이스의 등록을 해제하고, pool을 폐기하기 전에 이전의
  Exposed 기본값을 복원한다.
- 이전 단계가 실패하더라도 이후 리소스는 계속 닫힌다.
- 반복된 close는 이미 닫힌 리소스를 이중으로 사용하지 않으며 Ktor의
  application shutdown hook으로 예외를 전파하지 않는다.
- 동시 close 호출은 동일한 report를 반환하고 각 closer를 한 번씩만 실행한다.
- 겹치는 두 번째 데모 lifecycle은 거부된다. 첫 번째 lifecycle이 소유권
  lease를 해제한 후에는 순차적인 두 번째 lifecycle이 성공한다.
- 증명: 집중된 lifecycle barrier/double과 정확히 관찰 가능한 close 순서.

시작 테스트는 R2DBC 획득, 스키마 초기화, embedded-engine
create/bind/start에서 실패를 주입한다. 테스트는 역순 정리, R2DBC 기본값
복원/등록 해제, engine/resource fallback 정리, primary cause 보존,
시작 종료 상태 `1`, 유용한 allowlisted 진단 필드, 그리고 캡처된 출력에서
primary/suppressed 비밀 포함 텍스트가 제외되는지를 검증한다.
실제 loopback `EmbeddedServer` 테스트는 설정된 one-second/five-
second 값과 `ApplicationStopped` 정리가 blocking start 반환 전에
완료되는지를 입증한다. 별도의 repository/pool/JDBC 정리 실패 double은
이후 정리가 계속 실행되고 동일한 집계 레코드/status `2`를 사용하는지를
입증한다.

### 6. Testcontainers 또는 Docker를 사용할 수 없음

- Docker 없이도 빠른 도메인/서비스 테스트를 실행할 수 있다.
- Docker를 로컬에서 사용할 수 있을 때 PostgreSQL 통합 증거는
  `postgresIntegrationTest`를 통해 순차적으로 실행된다. Docker를 사용할 수
  없으면 명시적으로 요청된 해당 작업이 실패하며 H2로 조용히 대체되지 않는다.
- 기존 CI는 빠른 비-Docker `test` task를 계속 실행한다. 전용 Docker task를
  CI에 연결하는 것은 의도적으로 이슈 범위 밖이므로, PR 생성 전에 로컬에서
  직렬화된 Testcontainers 증거가 필요하다.

## 테스트 전략

### 빠른 테스트

- `DemoOrder`는 확인 후 하나의 이벤트를 기록하고 멱등적이다.
- rehydration은 과거 이벤트를 생성하지 않는다.
- 성공적인 서비스 확인은 발행 전에 쓰기를 수행하고 이벤트를 지운다.
- 영속화 실패는 key를 무효화하고 발행을 건너뛰며 이벤트를 유지하고,
  원래 throwable을 typed failure의 cause로 노출한다.
- 발행자 실패는 저장된 레코드를 남기고 순서가 유지된 이벤트를 보존한다.
- 영속화 중 cancellation은 제한된 non-cancellable 정리에서 로컬 key를
  무효화하고 동일한 cancellation 인스턴스를 다시 발생시킨다.
- 쓰기 전, 모호한 commit 중, 성공적인 repository 반환 후의 cancellation은
  `ensureActive()`로 제어된다. gate에서 cancellation이 관찰되면 무효화하고
  발행하지 않는다. gate 이후에는 추가 suspension point 없이 즉시 동기식
  발행이 의도적으로 완료된다.
- 이미 확인된 input은 순차 호출에서 쓰기와 발행을 수행하지 않는다. 동시성
  테스트는 이 예제가 동시에 대기 중인 두 확인을 직렬화하지 않음을 문서화한다.
- 유효하지 않은, 비표준, 너무 큰, nil UUID input은 상수 `400`을 반환한다.
  누락되었거나 잘못된 command header는 상수 `403`을 반환한다. 어느 쪽도
  의존성에 접근하지 않는다.
- POST는 ID보다 먼저 command header를 검증하며, invalid-header/invalid-ID
  결합 사례도 포함한다.
- primary/suppressed 비밀 포함 실패는 상수 `503` body를 생성하며, 캡처된
  application logs에 비밀, SQL, URL, 자격 증명, stack trace 또는 submitted-input
  텍스트를 포함하지 않는다.
- 시작 획득/스키마 실패는 획득한 리소스를 역순으로 닫고 원래 원인을 보존한다.
- engine create/bind/start 실패는 멱등적인 top-level fallback을 호출하고,
  리소스를 닫으며 allowlisted 시작 진단 필드만 출력한다.

정확한 Docker-free 명령은 다음과 같다.

```bash
./gradlew :examples-ktor-exposed-demo:test --no-daemon --console=plain
```

집중된 event-handoff 증명은 다음과 같다.

```bash
./gradlew :examples-ktor-exposed-demo:test \
  --tests "*OrderCommandServiceTest" --no-daemon --console=plain
```

### 순차 PostgreSQL Testcontainers 테스트

- 일반 시나리오, readiness, 기본값 복원 케이스에서 suite 수준의 `postgres:16-alpine` 컨테이너 하나를 순차적으로 재사용하고, 해당 컨테이너의 host, mapped port, database, user, password를 `KtorExposedDemoConfig`에 주입한다;
- 파괴적인 outage 케이스만 중지할 수 있는 독립적인 두 번째 컨테이너를 소유하도록 한다. 각 테스트는 여전히 새로운 애플리케이션 리소스를 생성하고 닫으며, 모든 리소스는 자신이 속한 컨테이너보다 먼저 닫힌다;
- `/transactions/jdbc-count` 및 `/transactions/r2dbc-count`를 검증한다;
- 클라이언트가 생성한 UUID order를 확인하고 PostgreSQL persistence를 검증한다;
- Caffeine key를 무효화하고 order를 GET한 다음, read-through가 PostgreSQL에서 이를 다시 채우는 것을 입증한다;
- 해당 GET 전후에 repository의 public Caffeine cache를 검사한 다음, 두 번째 direct repository GET을 수행하고 cache entry가 변경되지 않은 상태에서 동일한 cached `OrderRecord` instance를 반환하는 것을 입증한다. 이는 driver SQL text/count를 public contract로 만들지 않는 demo 수준의 warm-cache proof이다;
- 동일한 order를 다시 확인하고 duplicate event가 없음을 입증한다;
- readiness details에 `jdbc`, `r2dbc`, `cache.orders`가 UP으로 포함되는지 확인한다;
- startup 후 PostgreSQL을 중지하고 liveness `200`, readiness sanitized
  `503`, bounded completion을 검증한다;
- 리소스와 컨테이너를 순차적으로 닫는다.
- 두 번째 create/use/close lifecycle을 순차적으로 실행하여 process-wide
  R2DBC default가 교체되고, 닫힌 pool state가 다음 lifecycle을 오염시키지 않음을 입증한다.

Testcontainers/real database commands는 이 repository workflow에서 다른
heavy backend test와 병렬로 실행해서는 안 된다.

`build.gradle.kts`는 `src/postgresIntegrationTest/kotlin`을 소유하는 별도의
`postgresIntegrationTest` source set/task를 정의한다. 일반 `test` task는 해당
클래스에 의존하지도 않고 해당 클래스를 discover하지도 않는다. Docker가 필요한
정확한 command는 다음과 같다:

```bash
./gradlew :examples-ktor-exposed-demo:postgresIntegrationTest \
  --no-parallel --no-daemon --console=plain
```

모든 container/resource lifecycle은 `try/finally`를 사용한다. repository/resources는
container보다 먼저 닫힌다. integration test class는 한 번에 하나의 active
PostgreSQL lifecycle만 소유하며, generic repository가 Exposed의 process-wide
default R2DBC database를 사용하므로 parallel execution을 비활성화한다.
## 문서 설계

두 README 로케일은 다음 순서를 사용한다:

1. 개요
2. 예제 시나리오
3. 아키텍처
4. 주문 확인 시퀀스
5. 프로젝트 구조
6. Resource 소유권
7. 라우트
8. PostgreSQL로 실행
9. 테스트
10. 동작 및 제한
11. 같이 보기

두 파일은 route 경로, 환경 변수 이름, Compose/Gradle/curl
명령, 응답 예시, 다이어그램 경로 및 제한 사항을 의미상·기술적으로 동일하게 유지한다. 한국어
문장은 기계적으로 번역하지 않고 자연스럽게 작성하며, 코드 식별자는 변경하지 않는다.

두 README는 터미널별로 표시된 다음 저장소 루트 기준의 정확한 walkthrough를 제공한다:

```bash
# Terminal 1: start PostgreSQL, then run the demo
docker compose -f examples/ktor-exposed-demo/compose.yaml \
  up -d --wait --wait-timeout 60 postgres
./gradlew :examples-ktor-exposed-demo:run

# Terminal 2: inspect the running demo
BASE_URL=http://127.0.0.1:8080
ORDER_ID="$(uuidgen | tr '[:upper:]' '[:lower:]')"

curl -i "$BASE_URL/healthz/exposed"
curl -i "$BASE_URL/readyz/exposed"
curl -i "$BASE_URL/transactions/jdbc-count"
curl -i "$BASE_URL/transactions/r2dbc-count"

curl -i -X POST \
  -H 'X-Demo-Command: confirm-order' \
  "$BASE_URL/orders/$ORDER_ID/confirm"
curl -i "$BASE_URL/orders/$ORDER_ID"
curl -i "$BASE_URL/transactions/r2dbc-count"

# Sequential retry: the response stays CONFIRMED and eventPublished is false
curl -i -X POST \
  -H 'X-Demo-Command: confirm-order' \
  "$BASE_URL/orders/$ORDER_ID/confirm"
```

예상되는 증거는 명시적이다. health/readiness/count 요청은 `200`을 반환하고,
readiness에는 `jdbc`, `r2dbc`, `cache.orders`가 포함된다. 첫 번째 POST는
`CONFIRMED`와 `eventPublished: true`를 포함한 `200`을 반환하고, GET은 동일한 ID,
상태 및 timestamp를 반환한다. 새 random ID에 대해 R2DBC count가 증가하며, 두 번째
POST는 `eventPublished: false`를 포함한 `200`을 반환한다. 그런 다음 README는
사용자에게 Ctrl-C로 Gradle을 중지하고 다음 Compose 명령 중 하나를 선택하도록 안내한다:

```bash
# Normal stop: keep the named PostgreSQL volume
docker compose -f examples/ktor-exposed-demo/compose.yaml down

# Destructive clean reset: delete demo data and remove orphans
docker compose -f examples/ktor-exposed-demo/compose.yaml \
  down -v --remove-orphans
```

제한 사항 섹션은 loopback 전용 기본값, 필수 demo command header, permissive CORS
미지원, production auth/TLS 소유권, cache-first transient dirty reads,
sequential-only idempotency, 모호한 commit cancellation, request-local event
retention, generic three-SQL missing-order 경로 및 소규모 two-connection pool을 명시적으로 다룬다.

또한 caller recovery 지침도 제공한다. order-command에서 `503`이 발생하면 GET으로
order를 조회하여 관찰된 database state를 조정해야 한다. POST를 반복하는 것은
sequential confirmed state에 대해서는 안전하지만, 손실된 event를 복구하거나 다시
publish하지는 않는다. 특히 `ORDER_EVENT_HANDOFF_FAILED`는 record는 저장되었지만
non-durable event handoff가 실패했음을 의미한다. production recovery에는 outbox 또는
그 밖의 durable delivery boundary가 필요하다.

Startup schema DDL은 명시적으로 demo 전용이며, DDL permission이 있는 PostgreSQL
role이 필요하다. 또한 versioned production migrations나 rolling-deployment
compatibility를 대신할 수 없다. 보존된 local volume에 호환되지 않는 demo schema가
포함되어 있는 경우, README는 Compose를 다시 실행하기 전에 명확히 destructive한
`down -v --remove-orphans` reset을 수행하도록 안내한다.

두 README는 service와 publisher source 및 집중 테스트인
`OrderCommandServiceTest` 명령으로 연결되는 링크를 제공한다. HTTP는 요청별
`eventPublished` outcome만 노출하며 event history/replay endpoint는 제공하지 않는다고 명시한다.

## 다이어그램 설계

표준 쌍:

```text
docs/images/readme-diagrams/
  examples-ktor-exposed-demo-architecture-01.svg
  examples-ktor-exposed-demo-architecture-01.png
  examples-ktor-exposed-demo-sequence-01.svg
  examples-ktor-exposed-demo-sequence-01.png
```

### 아키텍처 다이어그램

정적인 책임과 소유권만 보여준다:

- HTTP 클라이언트;
- Ktor 라우트;
- `OrderCommandService`;
- `DemoOrder` 애그리거트;
- `OrderR2dbcCaffeineRepository`;
- Caffeine 로컬 캐시;
- PostgreSQL R2DBC 기준 데이터 저장소;
- 애플리케이션 소유의 Spring 비의존 이벤트 퍼블리셔;
- 캐시 준비 상태 기여자;
- application ownership boundary와 repository-before-pool shutdown note.
### 시퀀스 다이어그램

시간 순서에 따른 주문 확인 과정을 보여 줍니다.

1. 요청이 Ktor route에 도달합니다;
2. service가 repository를 통해 로드합니다;
3. `alt` 캐시 적중과 미적중 → PostgreSQL → 캐시 채우기를 구분합니다;
4. aggregate가 `OrderConfirmed`를 기록합니다;
5. repository write-through가 Caffeine을 업데이트한 다음 PostgreSQL을 시도합니다;
6. `alt` persistence 성공과 실패 보상/무효화를 구분합니다;
7. repository가 성공적으로 반환된 후에만 publisher handoff가 시작됩니다;
8. `alt` publisher 성공 시 이벤트를 삭제하고 publisher 실패 시 이벤트를 유지합니다;
9. route가 성공 또는 정제된 실패를 반환합니다.

이는 일반적인 flowchart가 아니라 실제 시퀀스 시각화입니다. 모든 participant에는 레이블이 지정된 lifeline이 있고, 활성 작업에는 activation bar가 사용되며, 주요 학습 단계에는 번호가 표시된 pill이 보입니다. 또한 cache/persistence/publisher 대안에는 connector를 가리지 않는 투명한 branch frame과 레이블이 사용됩니다.

다이어그램은 atomic cache/database 시각화를 명시적으로 피해야 합니다. approved dark visual family, approved fonts, fixed marker units, 읽기 쉬운 arrowhead, 그리고 repository의 full SVG/XML, CairoSVG `-s 2`, connector, geometry, endpoint, mixed-corner, sequence-style 및 full-size PNG inspection gate를 사용합니다. sequence SVG는
`~/.codex/skills/bluetape-diagram/scripts/diagram-sequence-style-audit.py`를 통과해야 합니다.

각 locale은 자연스럽고 의미적으로 동등한 alt text와 함께 PNG를 포함하고, 바로 아래에 canonical SVG source를 링크합니다. 인접한 prose는 접근 가능한 legend 역할을 하며 solid request/data flow, dashed readiness 또는 compensation flow, 모든 `alt` branch, success/failure 색상, repository-before-pool shutdown boundary를 설명합니다. 동일한 prose는 write-through mode에서 Caffeine이 PostgreSQL보다 먼저 업데이트되며 두 store가 atomic하지 않으므로, 중요한 sequence나 failure 의미가 색상이나 pixel에만 존재하지 않는다는 점도 명시합니다.

## 호환성과 마이그레이션

- 이는 unpublished example module이므로 binary compatibility surface가 추가되지 않습니다.
- 기존 JDBC route/behavior는 계속 사용할 수 있습니다.
- 이제 R2DBC runtime에는 in-memory H2 database 대신 PostgreSQL이 필요하므로 Compose와 environment configuration도 동일한 변경에 포함됩니다.
- 기존 production library 또는 catalog version은 변경되지 않습니다.
- Stable manuals는 이전 release behavior에 계속 고정되며 `develop`에서 다시 작성되지 않습니다.
- Rollback은 로컬에서 수행합니다. example의 H2 R2DBC dependency/resources를 복원하고 order/cache/event routes와 assets를 제거하며 변경되지 않은 Ktor library modules는 유지합니다.

## 설계 검토 기록

최종 2026-07-17 review pass는 모든 finding이 수정된 후 합의에 도달했습니다.

| 검토 관점 | P0 | P1 | P2 | P3 | 결과 |
|---|---:|---:|---:|---:|---|
| 성능 | 0 | 0 | 0 | 0 | READY |
| 안정성/동시성 | 0 | 0 | 0 | 0 | READY |
| 보안/개인정보 | 0 | 0 | 0 | 0 | READY |
| 운영/운영자 | 0 | 0 | 0 | 0 | READY |
| 개발자/API | 0 | 0 | 0 | 0 | READY |
| 사용자/호출자, 이중 언어 문서, 다이어그램 | 0 | 0 | 0 | 0 | READY |
| 주 세션 통합 | 0 | 0 | 0 | 0 | READY |

주 세션 통합 검토에서는 실제 aggregate/event signatures, R2DBC
`TransactionManager` lifecycle, Caffeine repository suspend/public-cache
contracts, Ktor kotlinx serialization/plugin surface, exact route media types, Compose commands, Markdown fence balance 및 scope exclusions를 다시 확인했습니다. 설계는 실행 가능한 TDD plan을 진행할 준비가 되었으며, 해당 plan도 동일한 검토를 받았습니다. 결정 아티팩트가 commit된 후 implementation을 시작할 수 있습니다.

## 승인 기준

- Ktor demo는 Ktor route, `OrderCommandService`, Spring-neutral aggregate, R2DBC Caffeine repository 및 application-owned event publisher를 사용하는 실행 가능한 Order Confirmation scenario를 제공합니다.
- order path는 R2DBC를 통해 PostgreSQL을 사용하며, 기존 JDBC smoke path는 H2-backed 상태로 독립적으로 관리됩니다.
- module은 R2DBC Caffeine project dependency를 직접 선언하고 PostgreSQL/Testcontainers versions는 기존 catalog authority의 관리 하에 둡니다.
- example은 기존 Kotlin serialization plugin을 적용하며 모든 HTTP DTO에는 Ktor ContentNegotiation이 사용하는 generated kotlinx serializer가 있습니다.
- `/transactions/r2dbc-count`는 cache repository operations를 nested transaction으로 감싸지 않고 기존 Ktor R2DBC transaction helper를 실행합니다.
- order reads는 read-through를 시연하며 order confirmation은 deterministic write-through를 사용합니다.
- command service는 repository operation이 성공한 후에만 domain events를 publisher에 전달합니다.
- 성공적인 handoff는 event buffer를 삭제하며 publisher failure는 caller-owned aggregate에 ordered events를 보존합니다.
- persistence failure는 best-effort cache invalidation을 수행하고 event handoff를 하지 않으며 원래의 persistence failure와 aggregate events를 유지합니다.
- 반복되는 sequential confirmation은 idempotent이며 duplicate event를 발생시키지 않습니다. concurrent confirmation은 stronger production boundary 없이는 지원되지 않는 것으로 문서화됩니다.
- `/readyz/exposed`는 문서화된 의미에 따라 `jdbc`, `r2dbc` 및 정제된 `cache.orders` readiness를 보고합니다.
- application이 resources를 소유하고 repository를 닫으며 demo R2DBC database를 unregister하고 이전 default를 복원한 후에만 pool을 dispose합니다.
- Engine create/bind/start failure는 이미 획득한 resources를 닫고 stable sanitized startup diagnostic만 사용하여 non-zero로 종료합니다.
- Local execution은 module-local Docker Compose, environment configuration, Gradle run 및 copy-paste 가능한 curl sequence로 문서화됩니다.
- Ktor/PostgreSQL은 기본적으로 loopback에 bind됩니다. mutating route에는 exact demo command header가 필요하고 permissive CORS를 노출하지 않으며 constant secret-free failures만 반환합니다.
- Fast tests는 Docker 없이도 계속 사용할 수 있으며 PostgreSQL behavior는 sequential Testcontainers integration test로 검증됩니다.
- PostgreSQL outage evidence는 probe-free liveness `200`과 bounded sanitized readiness `503`을 구분합니다.
- README locales에는 동등한 scenario, diagrams, ownership, routes, run, test 및 limitation sections가 포함됩니다.
- 일치하는 canonical SVG/PNG architecture 및 sequence assets는 모든 diagram audits와 full-size PNG inspection을 통과합니다.
- Spring, Spring Modulith, JaVers, production API, module, publishing, catalog 또는 issue #322 변경은 도입되지 않습니다.
## 완료 정의

- 성능, 안정성, 보안, Ops, 개발자/API, 사용자/호출자 및 주요 통합 전반에서 사양 및 계획 검토가 P0=0/P1=0으로 수렴한다.
- TDD 근거가 성공, 영속성 실패 보상, publisher 실패 보존, 멱등성, 잘못된 입력, read-through, readiness 및 리소스 수명 주기를 다룬다.
- 대상 fast 및 순차 PostgreSQL Testcontainers 테스트가 새로 통과한다.
- 최종 구현/docs 커밋 후, clean worktree에서
  `postgresIntegrationTest --no-parallel`을 다시 실행하고, 정확한 `git rev-parse HEAD`, 명령어 및 결과를 PR의 최종 DoD 근거에 기록한다. 이후 head가 변경되면 해당 로컬 PostgreSQL 검증은 무효가 되며 다시 실행해야 한다.
- README 패리티, Kotlin 진단, Gradle 컴파일/테스트, 가능한 경우 영향 범위에 대한 `detekt`, 그리고 `git diff --check`가 통과한다.
- 두 SVG/PNG 쌍이 XML/render/connector/geometry/endpoint/mixed-corner 감사를 통과한다. 또한 sequence SVG는
  `diagram-sequence-style-audit.py`도 통과해야 하며, 최종 렌더링 후 두 PNG를 모두 전체 크기로 검사한다.
- PR 생성 전에 write-through 보상 및 event-handoff 경계를 기록한 지속 가능한 교훈을 남긴다.
- PR 대상이 `develop`이고, issue #326 메타데이터를 반영하며, 최종 `## DoD Status`로 끝나고, 새 merge 승인을 요청하기 전에 정확한 head에서 필수 CI가 green 상태에 도달한다.
