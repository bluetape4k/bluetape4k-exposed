# Issue #323 트랜잭션 인식 도메인 이벤트 발행자 설계

## 맥락

이슈 #323은 `exposed/core`가 Spring 또는 Spring Modulith에 의존하지 않으면서
#320의 Spring 중립 DDD 계약을 Spring Boot 저장소 workflow에 연결하도록
`bluetape4k-exposed`에 요구한다.

현재 저장소 근거:

- `AggregateRoot`, `DomainEvent`, `AbstractAggregateRoot`는 이미 `exposed/core`에
  있으며 의도적으로 인메모리 이벤트 버퍼만 제공한다.
- #319의 `SpringModulithJdbcCaffeineRepository`는 동기 JDBC Caffeine
  `WRITE_THROUGH`와 `WRITE_BEHIND` 저장 경계의 이벤트를 발행한다. 이 기능은
  캐시 전용이며 일반 `AggregateRoot`가 기록한 이벤트는 발행하지 않는다.
- `examples/ddd-spring-modulith-demo`는 현재 `OrderApplicationService` 안에서
  `ApplicationEventPublisher`를 수동 반복 호출하고
  `TransactionTemplate.execute`가 반환된 뒤 애그리거트를 비운다.
- `spring-boot/jdbc`는 이미 Spring 트랜잭션 연동을 담당하며 Exposed JDBC
  저장소가 사용하는 `springTransactionManager`를 제공한다.
- 편집 전에 통과한 baseline 명령:
  `./gradlew :bluetape4k-exposed-spring-boot-jdbc:test :bluetape4k-exposed-spring-modulith:test :examples-ddd-spring-modulith-demo:test --no-configuration-cache --no-daemon --console=plain`.

## 목표

저장소 쓰기 후 Spring 중립 `AggregateRoot`를 받아 주변 트랜잭션이 활성인 동안
기록된 이벤트를 Spring에 전달하고, 그 트랜잭션이 커밋된 뒤에만 애그리거트
버퍼를 비우는 명시적인 트랜잭션 인식 Spring Boot 서비스를 제공한다.

연동은 일반 Spring Boot에서 동작해야 한다. 애플리케이션 classpath에 Spring
Modulith가 있으면 `spring-boot/jdbc`의 컴파일 시점 Spring Modulith 의존성 없이
같은 Spring 애플리케이션 이벤트가 listener 발행 흐름에 들어갈 수 있어야 한다.

## 범위 제외

- 이번 이슈에는 R2DBC 또는 coroutine 트랜잭션 synchronization을 포함하지 않는다.
- 영속 아웃박스, 재시도 큐, 이벤트 저장소, exactly-once 보장을 제공하지 않는다.
- AOP interception이나 임의 저장소 bean 자동 wrapping을 하지 않는다.
- 새 저장소 기반 클래스를 만들지 않고 애그리거트에 Exposed DAO `Entity` 타입을
  요구하지 않는다.
- 추가 계약 없이는 어댑터를 안전하게 구현할 수 없다고 리뷰가 입증하지 않는 한
  #320의 Spring 중립 DDD 계약을 바꾸지 않는다.
- #319의 캐시 전용 이벤트 발행을 대체하지 않는다.

## 설계 대안

### 대안 A: 명시적 트랜잭션 인식 발행자 서비스

선택한다.

애플리케이션은 서비스 하나를 주입받아 같은 Spring 트랜잭션 안에서 저장소
저장이 성공한 후 호출한다.

장점:

- custom Exposed 저장소와 Spring Data Exposed 저장소에서 모두 동작한다.
- 트랜잭션 timing을 애플리케이션 경계에서 볼 수 있다.
- 상속이나 proxy 저장소 내부 구현을 강제하지 않는다.
- 일반 Spring Boot 지원을 Spring Modulith와 독립적으로 유지한다.

단점:

- 애플리케이션 서비스가 명시적 등록 호출을 한 번 해야 한다.
- 호출자가 등록 전에 실제로 애그리거트를 저장했는지 증명할 수 없으므로 문서와
  예제가 필요한 순서를 보여 줘야 한다.

### 대안 B: 저장소 기반 클래스 또는 decorator

기각한다.

장점:

- 한 저장소 형태에서는 저장과 이벤트 등록을 한 메서드로 결합할 수 있다.

단점:

- custom JDBC, Spring Data DAO, 캐시 기반 구현을 아우르는 공통 애그리거트
  `save` 계약이 없다.
- 상속은 애플리케이션 저장소 설계에 Spring 관심사를 노출하면서도 모든 저장
  경로를 다루지 못한다.

### 대안 C: 저장소 AOP interception

기각한다.

장점:

- 명시적 애플리케이션 호출이 줄어든다.

단점:

- 메서드 이름 매칭으로 저장 완료를 신뢰성 있게 식별할 수 없다.
- Exposed와 Spring 트랜잭션을 기준으로 한 proxy 순서를 추론하고 테스트하기 어렵다.
- 숨겨진 발행으로 rollback과 중복 동작을 이해하기 어려워진다.

## 모듈과 API 배치

public API를 다음 위치에 추가한다.

```text
spring-boot/jdbc/src/main/kotlin/
  io/bluetape4k/spring/data/exposed/jdbc/ddd/
```

제안하는 public 타입:

```kotlin
class ExposedAggregateEventPublisher(
    private val applicationEventPublisher: ApplicationEventPublisher,
) {
    fun <ID : Any> publishAfterSave(aggregate: AggregateRoot<ID>)
}
```

영문 KDoc은 트랜잭션 내부 전달 시점, 같은 트랜잭션에서 영속화하고 전달해야 하는
호출자의 의무, 기본 `AFTER_COMMIT` listener 시점, 커밋된 버퍼 정리, 빈 버퍼
no-op, 이벤트가 있는 트랜잭션의 전제 조건, poison 동작, 지원하지 않는
NESTED/savepoint 사용, 지원하지 않는 동일 인스턴스의 중첩 `REQUIRES_NEW`,
`@throws IllegalStateException`을 다뤄야 한다. 또한 즉시 발생하는 검증/발행
예외와 poison된 `beforeCommit` 실패를 구분하고, 동기 listener가 커밋 전에
실행된다는 점을 경고하며, 불변이고 안정적인 이벤트 reference 요구 사항을
명시해야 한다. 애그리거트 인스턴스와 트랜잭션마다 이벤트가 있는 호출 한 번만
지원한다.

`io.bluetape4k.spring.data.exposed.jdbc.config` 아래에 전용 자동 구성 클래스를
추가하고 `AutoConfiguration.imports`에 직접 등록한다. 필요한 Spring 트랜잭션
및 DDD 계약 클래스가 존재하고 같은 타입의 사용자 bean이 없을 때만 발행자를
생성한다.

구현은 모듈 의존성 그래프에 이미 있는 Spring Framework 트랜잭션
synchronization API를 사용한다. `spring-boot/jdbc`에 Spring Modulith 의존성을
추가해서는 안 된다.

## 검증된 Spring 트랜잭션 제약

로컬 의존성 소스에서 필요한 timing을 확인했다.

- Spring Modulith 2.0.6 `@ApplicationModuleListener`는 `REQUIRES_NEW` listener
  트랜잭션을 사용하는 비동기 `@TransactionalEventListener`다.
- Spring Framework 7.0.8
  `TransactionalApplicationListenerSynchronization.register(...)`는
  `publishEvent(...)` 시점에 트랜잭션 synchronization과 실제 트랜잭션이 모두
  활성일 때만 AFTER_COMMIT callback을 등록한다.

따라서 다른 `afterCommit` callback에서 새 이벤트를 발행하면 일반 트랜잭션
listener 등록 경로에는 너무 늦다. bridge는 명령 트랜잭션이 활성인 동안
Spring 전달을 발행해야 한다. 기본 `AFTER_COMMIT` 트랜잭션 listener와 Spring
Modulith listener는 명령이 커밋된 뒤 실행된다.

## 트랜잭션 수명주기

필요한 애플리케이션 흐름:

1. 애그리거트를 변경하고 도메인 이벤트를 기록한다.
2. Spring 관리 JDBC 트랜잭션을 시작하거나 참여한다.
3. Exposed 저장소로 애그리거트를 저장한다.
4. 트랜잭션 완료 전에 `publishAfterSave(aggregate)`를 호출한다.
5. 트랜잭션이 활성인 동안 캡처한 이벤트를 기록 순서대로 Spring
   `ApplicationEventPublisher`에 전달한다.
6. 기본 `AFTER_COMMIT` Spring 트랜잭션 listener가 작업을 등록하고, Spring
   Modulith는 명령 트랜잭션에 listener 발행을 기록할 수 있다.
7. 데이터베이스 트랜잭션을 커밋한다.
8. `afterCompletion(STATUS_COMMITTED)`에서 애그리거트 버퍼를 비운다.

애그리거트에 이벤트가 없으면 트랜잭션 밖에서도 등록은 no-op이다. 이벤트가 있는
스냅숏을 전달하기 전에 이 메서드는 활성 트랜잭션 synchronization과 실제 활성
트랜잭션을 요구한다. 어느 검사든 실패하면 `IllegalStateException`을 던져
synchronization만 있는 context를 거부한다.

Spring의 thread-local API로는 현재 트랜잭션을 어느 transaction-manager bean이
소유하는지, 앞선 저장소 호출이 이 애그리거트를 영속화했는지 증명할 수 없다.
호출자는 Exposed 저장소 쓰기와 전달이 같은 명령 트랜잭션에 참여하도록 보장해야
한다. 발행자는 의도적으로 transaction-manager 또는 `DataSource` identity를
주장하지 않는다.

빈 버퍼 no-op은 등록 전에만 적용된다. 애그리거트가 현재 트랜잭션에 이미
등록됐는데 호출자 코드가 수동으로 비우거나 drain했다면, 두 번째 호출도 먼저
identity 예약에 걸려 트랜잭션을 poison하고 실패한다. 수명주기 위반을 빈 버퍼로
가리지 않는다.

rollback은 기본 `AFTER_COMMIT` 트랜잭션 listener 전달과 커밋된 Spring Modulith
발행을 막으며 애그리거트 버퍼를 비우지 않는다. 일반 동기 listener는 이미
트랜잭션 내부 전달을 관찰했을 수 있으므로, 해당 애그리거트 인스턴스의 재시도
또는 폐기는 호출자가 책임진다.

## 스냅숏과 변경 계약

이벤트가 있는 단일 호출은 `domainEvents()`가 반환한 불변 list 스냅숏을 별도
복사 없이 유지하고 트랜잭션이 활성인 동안 발행한다. 애그리거트를 두 번 이상
변경하고 저장하는 애플리케이션은 마지막 저장 뒤에만 `publishAfterSave`를
호출해야 한다. 한 트랜잭션에서 같은 애그리거트 객체로 이벤트가 있는 호출을
두 번째 수행하면 registry를 poison하고 `IllegalStateException`을 던진다.

synchronization은 커밋 전에 현재 스냅숏의 크기와 이벤트 객체 reference가 같은
순서로 일치하는지 검증한다. 불일치하면 커밋을 실패시키고 기본 `AFTER_COMMIT`
listener 전달과 커밋된 Spring Modulith 발행을 막으며 애그리거트 버퍼를 그대로
둔다. 일반 동기 listener는 이미 트랜잭션 내부 전달을 관찰했을 수 있다.

이는 깊은 payload fingerprint가 아니라 얕은 identity 검사다. 이 bridge와 함께
쓰는 `DomainEvent` 인스턴스는 등록 후 깊이 불변이어야 하며,
`AggregateRoot.domainEvents()`는 지울 때까지 기록된 이벤트 객체 reference와
순서를 보존해야 한다. 구현은 `AggregateRoot`, `domainEvents`,
`drainDomainEvents`, `clearDomainEvents`, `AbstractAggregateRoot`,
`DomainEvent`, public 자동 구성 클래스의 영문 KDoc을 직접 갱신한다.
`drainDomainEvents`는 즉시 비우기가 rollback 보존을 깨므로 이 bridge와 함께
사용하는 것을 명시적으로 금지해야 한다. `clearDomainEvents`는 등록부터 완료까지
호출자가 사용하는 것을 금지해야 한다. 이 bridge에서 `domainEvents()`는 부수
효과가 없어야 하며 O(E) 이하여야 한다. 전달 후 이벤트 payload 변경은 지원하지
않고 이 bridge가 신뢰성 있게 감지할 수도 없다.

## 중복 등록

발행자는 자체 Spring `TransactionSynchronization` 안에 트랜잭션 범위 registry
하나를 저장한다. 현재 트랜잭션의
`TransactionSynchronizationManager.getSynchronizations()`에서만 해당
synchronization을 찾으며 별도 thread-local resource를 bind하지 않는다.

- 발행자 빈과 Spring 트랜잭션 조합마다 synchronization 하나를 등록한다.
- ID가 같더라도 서로 다른 애그리거트 인스턴스는 별도의 애플리케이션 작업을
  나타낼 수 있으므로 애그리거트는 `equals`가 아니라 객체 identity로 추적한다.
- 평균 O(1) identity 조회를 위해 `IdentityHashMap`에 해당하는 트랜잭션 로컬
  구조를 사용한다. 발행자 빈은 공유 mutable registry를 보유하지 않고 lock도
  사용하지 않는다.
- 이미 등록된 애그리거트 identity는 `domainEvents()`를 다시 호출하기 전에
  검사한다. 따라서 이벤트가 있는 반복 등록은 추가 snapshot이나 publication
  없이 거부된다.
- 메서드 순서는 고정한다. synchronization이 활성일 때는 먼저 현재 발행자
  synchronization에 이미 있는 identity를 거부한다. snapshot을 얻고 빈 버퍼면
  반환한다. 이벤트가 있는 트랜잭션을 검증하고 synchronization을 찾거나 등록한
  뒤 identity를 다시 검사한다. 애그리거트 identity를 예약한 다음
  `publishEvent` loop에 진입한다. 첫 이벤트 전에 identity를 예약하면 동기
  listener의 재진입이 같은 애그리거트를 재귀적으로 발행하는 일을 막을 수 있다.
- commit 전에 현재 애그리거트 snapshot은 list 크기와 element reference
  identity가 최근 등록한 snapshot과 일치해야 한다.
- publication 예외나 반복 등록은 registry를 poison 상태로 만든다. application
  코드가 원래 예외를 잡아도 `beforeCommit`이 예외를 던진다.
- Spring은 `REQUIRES_NEW`에서 외부 synchronization list를 자동으로 suspend한다.
  따라서 custom resource binding 없이 내부 트랜잭션이 별도의 발행자
  synchronization을 받는다.
- `afterCompletion(STATUS_COMMITTED)`은 각 애그리거트 버퍼를 한 번씩 비운다.
  clear 실패는 각각 독립적으로 포착하고 다른 애그리거트의 cleanup을 계속한다.
  rollback과 `STATUS_UNKNOWN`에서는 버퍼를 비우지 않는다.

`PROPAGATION_NESTED`와 savepoint 범위 handoff는 지원하지 않는다. 기본 Exposed
`SpringTransactionManager`는 이미 등록한 Spring listener synchronization을
안전하게 철회하거나 poison 상태로 만드는 데 필요한 savepoint callback을
노출하지 않는다. 동일한 애그리거트 객체를 서로 겹치는 외부 트랜잭션과
`REQUIRES_NEW` 트랜잭션에 전달해서는 안 된다. 내부 commit이 공유 객체의 버퍼를
비우기 때문이다. 트랜잭션 경계마다 서로 다른 애그리거트 인스턴스와 idempotent
consumer를 사용한다.

이 방식은 한 트랜잭션에서 같은 객체를 반복 등록해 발생하는 중복 publication을
거부한다. 애그리거트 ID나 이벤트가 같더라도 서로 다른 애그리거트 객체는 중복
제거하지 않는다. bridge는 객체 재로딩, process retry, 별도 트랜잭션 사이의
전역 이벤트 중복 제거를 제공하지 않으므로 consumer는 idempotent해야 한다.

## 발행 실패 의미

각 `DomainEvent`는 이미 Spring과 호환되는 이벤트 객체이므로 이 bridge는 mapping이나
serialization 계층을 추가하지 않는다. 선택적인 내구성 Spring Modulith publication은
계속 애플리케이션에 구성된 serializer를 사용한다.

- `publishEvent`는 repository save 직후와 commit 전에 명령 트랜잭션 안에서 실행된다.
- `publishEvent` 호출이 예외를 던지면 예외가 전파되고 registry는 poison 상태가
  된다. 호출자가 이를 잡아도 `beforeCommit`은 commit을 거부한다. 애그리거트
  버퍼는 그대로 유지된다.
- 이후 트랜잭션이 rollback되면 기본 `AFTER_COMMIT` listener는 실행되지 않고,
  Spring Modulith publication 쓰기도 명령과 함께 rollback되며, 애그리거트
  버퍼는 그대로 유지된다.
- 트랜잭션이 commit되면 synchronization이
  `afterCompletion(STATUS_COMMITTED)`에서 애그리거트 버퍼를 비운다.
- 일반 동기 `@EventListener` consumer는 commit 전에 실행되어 나중에 rollback될
  트랜잭션을 관찰할 수 있다. commit에 안전한 application 부수 효과는
  `@TransactionalEventListener`, `@ApplicationModuleListener`, 내구성 outbox
  중 하나를 사용해야 한다.

구현은 이벤트 payload, aggregate ID, credential, token, secret, 개인 식별 정보를
log에 남겨서는 안 된다. 진단 context에는 예외 category, 이벤트 type 이름,
이벤트 수, 애그리거트 type 이름이면 충분하다. application 예외에 민감한 값이
포함될 수 있으므로 Throwable message와 cause를 logging하지 않는다. commit 후
clear 실패는 안정적인 committed-cleanup category와 함께 error level로 logging한다.
운영자는 이미 commit된 명령을 retry해서는 안 되며 영향을 받은 애그리거트
인스턴스를 폐기해야 한다.

동기 기본 `AFTER_COMMIT` listener 실패는 persistence가 commit된 뒤 발생한다.
Spring은 트랜잭션 완료 callback에서 발생한 실패를 logging하고 격리한다. 비동기
Spring Modulith listener 실패도 명령 반환과 분리된다. 두 경우 모두 commit된
애그리거트 버퍼를 비우며, 호출자는 listener 실패를 근거로 명령을 retry해서는
안 된다. listener retry/replay는 이 발행자가 아니라 listener 또는 Spring
Modulith publication infrastructure의 책임이다.

`STATUS_UNKNOWN`은 안정적인 category `aggregate-event-completion-unknown`으로
error log를 남기고, committed cleanup 실패는
`aggregate-event-cleanup-failed`를 사용한다. log는 `category`,
`aggregateType`, `eventType`, `eventCount`, `traceId`, `spanId`, `requestId`
이름의 구조화된 field를 사용한다. 테스트는 formatted message text가 아니라
structured/MDC field를 검사한다. 등록 시 synchronization은 allowlist에 있는
correlation key `traceId`, `spanId`, `requestId`만 캡처한다. 각 값은 ASCII
문자/숫자와 `.`, `_`, `:`, `-`를 합쳐 128자로 제한한다. raw header, 임의 MDC
entry, domain identifier, throwable text는 복사하지 않는다. 없는 correlation
field는 그대로 생략한다. 이 category는 운영자가 alert를 설정할 수 있는 신호다.
완료 anomaly 두 개 때문에 새로운 observability dependency나 public SPI를
추가하지 않도록 #323에서는 내장 metric/callback API를 기각한다.

README troubleshooting에는 다음 결정 표 하나를 포함한다.

| 결과 | Persistence | 버퍼 | 명령 retry |
|---|---|---|---|
| 활성 트랜잭션 없음 또는 같은 트랜잭션 전제 조건 위반 | 미확정 | 보존 | 자동 retry 금지, 먼저 대조한다 |
| 전체 rollback 또는 poison된 handoff | rollback됨 | 보존 | 새 트랜잭션에서만 허용, 동기 부수 효과는 중복 제거가 필요할 수 있다 |
| commit된 listener 실패 | commit됨 | 비움 | 명령 retry 금지, listener retry/replay를 사용한다 |
| commit된 cleanup 실패 | commit됨 | 남을 수 있음 | retry 금지, 애그리거트 인스턴스를 폐기한다 |
| `STATUS_UNKNOWN` | 미확정 | 보존 | 자동 retry 금지, 먼저 대조한다 |

production rollout에는 allowlist에 있는 correlation field 하나 이상과, 해당 opaque
값을 명령 및 persistence key에 연결하는 application 소유 audit/trace mapping이
필요하다. correlation이 없으면 운영자는 영향을 받은 시간 구간을 격리하고
application audit record를 사용해야 하며 자동 복구는 금지한다. 대조 과정은
애그리거트 persistence, 활성화된 경우 Spring Modulith publication, listener
부수 효과를 검사한다.

- persistence 있음 + publication 있음: 명령을 replay하지 않고 Modulith replay
  또는 listener 전용 복구를 사용한다.
- persistence 있음 + publication 없음: 명령을 replay하지 않고 영속 상태에서
  이벤트를 도출하는 application 소유 idempotent 복구를 실행한다.
- persistence 없음 + publication 없음: 되돌릴 수 없는 동기 부수 효과가 없음을
  확인한 뒤 새 명령으로만 retry한다.
- persistence 없음 + publication 있음: invariant 위반으로 격리하고 수동으로
  보상하며 어느 경로도 replay하지 않는다.

## Spring Modulith 경계

`ExposedAggregateEventPublisher`는 Spring Framework와 Spring 중립 DDD 계약에만
의존한다.

Spring Modulith가 있으면 command 트랜잭션이 활성인 동안 application listener와
publication registry가 `ApplicationEventPublisher`를 통해 같은 이벤트를 받는다.
`@ApplicationModuleListener`는 commit 후 자체 트랜잭션에서 실행된다. 발행자는
Spring Modulith API를 호출하지 않고 listener나 publication repository 상태를
검사하지 않는다.

bridge는 serializer를 제공하지 않으며 기존 Spring Modulith publication-store
신뢰 경계를 약화하지 않는다. 내구성 발행을 사용하는 애플리케이션은 발행하는
모든 이벤트 타입을 지원하는 `EventSerializer`를 제공해야 한다. 직렬화된
payload와 이벤트 클래스 이름은 저장되고 재생될 수 있으므로 이벤트는 민감한
데이터를 최소화해야 하고 운영자는 적절한 접근 제어, 암호화, 무결성, 보존
정책으로 publication database를 보호해야 한다. 애플리케이션 전제 조건으로
구성된 serializer는 애플리케이션 외부 또는 지원하지 않는 이벤트 클래스를
거부해야 한다. bridge는 이 allowlist를 강제하지 않으며 기존 모듈을 넘어서는
저장소 강화는 #323 범위 밖이다.

commit 후 호출이 필요한 일반 Spring Boot consumer는 기본
`@TransactionalEventListener(phase = AFTER_COMMIT)`를 사용한다. database 쓰기를
수행하는 listener는 예를 들어 `REQUIRES_NEW`로 명시적인 새 트랜잭션을 시작해야
한다. after-commit 호출만으로는 해당 쓰기를 위한 트랜잭션이 생기지 않는다.
다른 listener phase와 일반 동기 `@EventListener`도 Spring에서 지원하지만 이
bridge의 commit-safe consumer는 아니다.

기존 `spring-boot/spring-modulith` 모듈은 계속 Exposed 기반
`EventPublicationRepository`, 재시작 재생, 완료 mode, 발행 관측성을 책임진다.

기존 `SpringModulithJdbcCaffeineRepository`는 계속 #319의 cache 쓰기 이벤트를
책임진다. 영속화된 cache record에서 만든 애플리케이션 소유 이벤트를 발행하며
애그리거트 발행자로 대체되지 않는다.

## 자동 구성

별도 단계 클래스를 추가한다.

```text
ExposedAggregateEventPublisherAutoConfiguration
```

요구 사항:

- 클래스를 `AutoConfiguration.imports`에 직접 등록한다.
- 기본 transaction manager를 먼저 평가하도록
  `@AutoConfiguration(after = [ExposedSpringDataAutoConfiguration::class])`를
  선언한다.
- `AggregateRoot`, `ApplicationEventPublisher`, Spring 트랜잭션 synchronization
  API에 `@ConditionalOnClass`를 사용한다.
- Spring이 autowire candidate 하나를 선택할 수 있을 때만 기본 bean을 만들도록
  `@ConditionalOnSingleCandidate(PlatformTransactionManager::class)`를 사용한다.
  manager가 여러 개여도 `@Primary` 하나가 있으면 이 조건을 충족한다. manager
  bean이 정확히 하나라는 뜻은 아니다.
- 애플리케이션이 교체할 수 있도록
  `@ConditionalOnMissingBean(ExposedAggregateEventPublisher::class)`을 사용한다.
- bean은 manager를 주입하거나 식별하지 않는다. 이 조건은 모호한 context에서
  기본값을 암시하지 않도록 할 뿐이다. runtime 발행은 호출 지점에서 활성인
  트랜잭션에 bind된다.
- transaction manager가 여러 개이고 단일 autowire candidate가 없는
  애플리케이션은 발행자 bean을 명시적으로 제공한다. `@Primary`가 하나 있으면
  자동 구성 발행자는 manager를 주입하지 않고 호출 지점의 활성 트랜잭션에
  계속 bind되므로 유효하다. README 예제는 저장소와 명령 경계에 대한
  `transactionManagerRef`/`@Qualifier` 선택을 보여 줘야 한다. 발행자 자체에는
  `ApplicationEventPublisher`만 필요하다.
- 이 이슈에서 구성 property를 추가하지 않는다. 구성할 수 있는 안전한 즉시
  발행 mode나 재시도 mode는 없다.

## 예제 마이그레이션

`examples/ddd-spring-modulith-demo`의 `OrderApplicationService`를 다음과 같이 갱신한다.

1. `TransactionTemplate` 안에서 애그리거트를 저장한다.
2. `ExposedAggregateEventPublisher.publishAfterSave(order)`를 호출한다.
3. rollback 동작을 증명하기 위해 선택적으로 예외를 던진다.
4. 도메인 이벤트를 수동으로 순회하거나 비우지 않고 반환한다.

이렇게 하면 예제가 수명주기 logic을 복제하지 않고 public 연동을 실제로 사용한다.

예제는 계속 Spring Modulith listener와 Exposed 기반 publication repository를
사용한다. 기존 재시작 replay와 idempotent listener 테스트도 계속 필수다. 기존
`OrderHandoffFailedException`은 테스트와 호출자가 보존된 이벤트 버퍼를 검사할
수 있도록 실패한 애그리거트를 계속 보유한다. 다만 log에 안전한 메시지는
aggregate ID나 중첩 예외 text가 없는 안정적인 category로 바꾼다.

migration은 이중 publication이 아니라 교체다. `publishAfterSave`를 추가하는 같은
배포에서 수동 `ApplicationEventPublisher` loop와 수동 clear를 제거한다. 두
경로를 함께 실행하면 이벤트가 중복된다. application version을 rollback하면
완전한 수동 경로를 복원한다. 혼합 instance는 consumer가 idempotent할 때만
호환된다.

rollout 전에 소스 검색으로 예제의 수동 loop와 수동 clear가 사라졌음을 증명해야
한다. canary 전에 구조화된 anomaly category 두 개에 대한 alert rule, allowlist
correlation 전파, audit 조회, database/publication 읽기 권한, 대조 결정 표를
준비해야 한다. canary는 영속화된 애그리거트 하나, 내구성 publication 하나,
listener 부수 효과 하나, anomaly category log 0개를 검증한다. 중복 publication,
누락된 publication, 완료 anomaly가 발생하면 rollout을 중지하고 log와 영향을
받은 record를 보존한 뒤 canary 명령을 대조하고 복구한다. 결함이 version과
관련된 경우에만 application binary 전체를 rollback한다. binary rollback만으로는
이미 미확정 상태인 명령을 복구할 수 없다. 한 instance에서 기존 경로와 새 경로를
혼용하지 않는다.

## 테스트

실제 Spring transaction manager와 결정적인 in-memory database 트랜잭션 경계를
사용하는 집중 테스트를 `spring-boot/jdbc`에 추가한다.

- Spring handoff는 repository persistence 후 트랜잭션 안에서 일어난다.
- 기본 `AFTER_COMMIT` transactional listener는 commit 후에만 이벤트 하나를
  관찰한다.
- 여러 이벤트는 애그리거트 기록 순서를 보존한다.
- 성공한 commit은 애그리거트 이벤트 버퍼를 비운다.
- rollback은 버퍼를 보존하면서 기본 `AFTER_COMMIT` listener 전달을 막는다.
- 같은 애그리거트의 두 번째 이벤트 포함 등록은 트랜잭션을 poison 상태로 만들고
  두 번째 snapshot을 발행하지 않는다.
- 등록 후 다른 이벤트를 기록하면 commit 전에 실패하고 기본 `AFTER_COMMIT`
  listener 전달을 막으며 버퍼를 보존한다.
- 이벤트가 없는 애그리거트 등록은 no-op이다.
- 수동 clear/drain 후 두 번째로 호출하면 빈 버퍼 no-op으로 처리하지 않고 예약된
  identity 경로에서 거부한다.
- synchronization이 비활성이거나 실제 트랜잭션이 활성 상태가 아니면 이벤트가
  있는 등록은 실패한다. 빈 버퍼 호출은 트랜잭션 밖에서도 no-op으로 유지한다.
- application 코드가 원래 예외를 잡아도 publisher 실패는 트랜잭션을 poison
  상태로 만든다. 앞선 동기 listener는 성공적으로 전달된 prefix를 관찰했을 수 있다.
- `PROPAGATION_NESTED`/savepoint handoff는 지원하지 않는다고 문서화하며 기본
  Exposed 통합 테스트에서 사용하지 않는다.
- 외부 및 내부 `REQUIRES_NEW` 트랜잭션은 서로 다른 synchronization registry를
  받는다. 테스트는 서로 다른 애그리거트 인스턴스를 사용하고 내부 commit/rollback을
  다룬다.
- commit과 rollback 완료 후 같은 thread의 다음 트랜잭션은 각각 새 registry를
  받는다.
- 예외를 던지는 `clearDomainEvents()` 구현은 다른 애그리거트의 registry cleanup
  시도를 막을 수 없다. commit된 명령을 rollback에 안전한 retry 작업으로 보고하지
  않는다.
- `STATUS_UNKNOWN`은 버퍼를 보존하고 synchronization 상태를 폐기하며, 미확정
  상태이자 자동 retry에 안전하지 않은 상태로 문서화한다.
- 예외를 던지는 기본 `AFTER_COMMIT` listener는 persistence를 rollback하지 않는다.
  commit된 버퍼 cleanup은 계속 실행되고 명령은 retry하지 않는다.
- synchronization 하나가 여러 애그리거트를 처리한다. 정상 애그리거트마다
  등록 시 한 번과 commit 전 한 번 `domainEvents()`를 호출하며, 중복 등록은
  snapshot을 다시 호출하기 전에 identity를 검사한다.
- 같은 애그리거트로 `publishAfterSave`에 재진입한 동기 listener는 예약된
  identity를 보고 트랜잭션을 poison 상태로 만들며 다른 이벤트를 재귀적으로
  발행할 수 없다.
- cleanup log capture는 정확한 structured key/category, allowlist correlation
  capture, throwable message, payload, aggregate ID, 임의 MDC 및 기타 민감한 값이
  없음을 검증한다.
- unknown completion log capture는 정확한 structured category, 보존된 버퍼,
  등록 시점 correlation 보존, correlation 누락 동작, 동일한 민감 데이터 제외를
  검증한다.
- application 빈이 있으면 자동 구성이 물러난다.
- 자동 구성은 `ExposedSpringDataAutoConfiguration` 뒤에 배치한다. Spring이
  단일 transaction-manager autowire 후보를 결정할 수 있으면 Spring Modulith
  class 없이 기본 발행자를 생성한다.
- `ApplicationContextRunner`는 manager 없음, manager 하나, primary 없는 복수
  manager, primary 하나가 있는 복수 manager, 사용자 override, Spring Modulith
  없음 사례를 다룬다.
- 수동 multi-manager 명령 테스트는 호출자가 선택한 repository 트랜잭션에서
  명시적으로 제공한 발행자를 사용할 수 있음을 증명한다. manager identity는
  runtime 주장이 아니라 문서화된 호출자 전제 조건으로 남는다.

DDD Spring Modulith 예제 테스트를 갱신해 다음을 증명한다.

- commit은 order를 영속화하고 domain event 하나만 발행한다.
- 등록 후 rollback하면 order, listener 부수 효과, publication 행이 남지 않는다.
- 트랜잭션이나 handoff가 실패하면 애그리거트가 이벤트를 보존한다.
- 내구성 publication은 구성된 serializer를 사용하며 기존 민감 payload 회귀
  coverage를 유지한다.
- 예제 serializer는 지원하지 않는 이벤트 class를 거부하고 payload를 log에
  포함하지 않는다.
- 기존 재시작 replay와 idempotency 동작이 계속 통과한다.

동시성 stress 테스트는 필요하지 않다. registry는 Spring synchronization manager를
통해 트랜잭션과 thread에 bind되며, 이 설계는 동시 명령 간 애그리거트 인스턴스
하나의 공유를 허용하지 않는다. 테스트는 임의 thread나 sleep을 사용해서는 안
된다. 이벤트가 있는 각 애그리거트는 등록 시와 commit 전에 한 번씩 스냅숏을
만든다. 발행자는 반환된 불변 스냅숏을 별도 복사 없이 보존한다. Identity registry
연산은 평균 O(1)을 유지하며 bean-global lock이나 공유 mutable registry가 없다.
발행자 synchronization 탐색은 Spring의 정렬된 synchronization 스냅숏을 사용하므로
호출 비용은 시간 O(E + S log S), 임시/reference 저장 공간 O(E + S)다. 여기서 E는
애그리거트 이벤트 수, S는 현재 synchronization 수다. 기존 synchronization이 여러
개인 테스트에서도 여러 애그리거트에 대해 발행자 synchronization을 정확히 하나만
관찰해야 한다.

## 문서와 다이어그램

`spring-boot/jdbc` 영문/한국어 README 쌍을 다음 내용으로 갱신한다.

- dependency와 자동 구성 동작
- 명시적인 save 후 publish 사용법
- 활성 트랜잭션이 없을 때의 실패 동작
- rollback, 동기 listener, 중복 등록 의미
- 일반 Spring Boot와 선택적 Spring Modulith의 경계
- 불변 이벤트와 안정적인 이벤트 reference 요구 사항
- Spring Modulith persistence를 활성화했을 때의 serializer, 민감 데이터,
  신뢰할 수 있는 publication store 경계
- `AFTER_COMMIT` listener가 database에 쓸 때 새 트랜잭션이 필요하다는 점
- 기본 단일 autowire 후보 자동 구성과 multi-manager 구성에서
  `transactionManagerRef`/`@Qualifier`로 repository와 command 트랜잭션을 맞추는
  실행 가능한 Kotlin 예제
- 교체 전용 migration과 rollback 지침
- 지원하지 않는 nested/savepoint handoff와 동일 인스턴스의 외부/
  `REQUIRES_NEW` 재사용
- commit된 listener/cleanup 실패와 `STATUS_UNKNOWN` no-retry 지침
- 통합된 결과/retry 결정 표와 unknown completion 대조 절차
- R2DBC와 내구성 outbox 동작 제외

DDD Spring Modulith 예제 README 쌍이 새 발행자를 사용하도록 갱신하고 수동
publication 지침을 제거한다. 전체 계약을 중복하지 않고 Spring Modulith README
쌍에 짧은 cross-link를 추가한다.

주요 동작 변경이 timing이므로 수명주기 diagram이 필요하다. 서로 일치하는 SVG와
PNG asset을 생성한다.

```text
docs/images/readme-diagrams/
  spring-boot-exposed-jdbc-domain-event-sequence-01.svg
  spring-boot-exposed-jdbc-domain-event-sequence-01.png
```

diagram은 repository persistence, 트랜잭션 내부 Spring handoff, transaction
commit, commit 후 transactional listener 실행, 선택적 Spring Modulith 처리,
rollback을 구분해야 한다. 영문 label을 사용하고 지역화된 README 쌍에 같은
asset을 삽입한다.

## 검증

필수 로컬 명령:

```text
./gradlew :bluetape4k-exposed-spring-boot-jdbc:test \
  :bluetape4k-exposed-spring-modulith:test \
  :examples-ddd-spring-modulith-demo:test \
  --no-configuration-cache --no-daemon --console=plain
```

다음도 실행한다.

- 가능하면 변경한 파일에 대한 Kotlin/IDE diagnostics
- `git diff --check`
- `bluetape4k-diagram`을 통한 렌더링된 SVG/PNG 검증
- `P0 = 0`, `P1 = 0`인 local 7-Tier code review
- PR 본문과 metadata 검증 후 CI monitoring

모듈을 추가하거나 이름을 바꾸지 않으므로 workflow 등록 변경은 예상하지 않는다.
소스 검사에서 기존 CI 경로 filter가 변경 대상 모듈을 누락한 것으로 드러나면
구현 전에 계획을 수정해야 한다.

## 위험과 완화책

1. **동기 listener 부수 효과:** 일반 `@EventListener` consumer는 commit 전에
   실행되며 신뢰성 있게 rollback할 수 없다. 완화책: commit 후 효과에는 기본
   `AFTER_COMMIT` `@TransactionalEventListener`, `@ApplicationModuleListener`,
   outbox 중 하나를 사용하도록 요구한다. 일반 after-commit listener의 database
   쓰기에는 새 트랜잭션이 필요하다.
2. **retry 간 중복 전달:** 별도 트랜잭션이 같은 business event를 두 번 이상
   발행할 수 있다. 완화책: idempotent consumer를 문서화하고 exactly-once 동작을
   주장하지 않는다.
3. **등록 후 애그리거트 변경:** 이후 이벤트가 영속화되지 않은 상태를 설명할 수
   있다. 완화책: commit 전 snapshot 검증과 마지막 save 후 명시적인 단일 등록을
   적용한다. 이벤트 payload는 깊이 불변이어야 하고 이벤트 객체 reference는
   안정적이어야 한다.
4. **트랜잭션 registry 누출:** custom thread-bound registry가 완료 후에도 남거나
   `REQUIRES_NEW`로 누출될 수 있다. 완화책: 상태를 Spring의 현재 트랜잭션
   synchronization list 안에만 저장하고 반복 트랜잭션과 nested-new 트랜잭션을
   테스트한다.
5. **Spring Modulith 결합:** bridge를 Modulith 모듈에 두면 일반 Spring Boot에서
   사용할 수 없다. 완화책: `spring-boot/jdbc`에 두고
   `ApplicationEventPublisher`만 사용한다.
6. **#319와의 중복:** 일반 애그리거트 publication이 cache 쓰기 의미를 실수로
   대체할 수 있다. 완화책: API 두 개를 모두 유지하고 서로 다른 persistence
   경계를 문서화한다.
7. **savepoint rollback:** 기본 Exposed manager에서 nested handoff 후 Spring
   listener synchronization을 신뢰성 있게 철회할 수 없다. 완화책:
   `PROPAGATION_NESTED`/savepoint handoff를 지원하지 않는다고 선언한다.
8. **commit 후 clear 실패:** persistence가 이미 commit됐으므로 명령을 retry하면
   전달이 중복될 수 있다. 완화책: 각 cleanup 실패를 격리해 logging하고
   synchronization 상태와 애그리거트 인스턴스를 폐기하며 commit된 명령을
   retry에 안전하다고 보고하지 않는다.
9. **내구성 payload 노출:** Spring Modulith가 직렬화된 payload와 class 이름을
   저장할 수 있다. 완화책: 이벤트 데이터를 최소화하고 application 소유
   serializer와 신뢰할 수 있는 이벤트 type을 사용하며 publication database를
   보호한다.
10. **transaction-manager 모호성:** Spring thread-local 상태는 manager identity의
    증거가 아니다. 완화책: runtime 소유권을 주장하지 않고 Spring이 단일 manager
    autowire 후보를 결정할 수 있을 때만 자동 구성하며, 호출자가 repository
    persistence와 handoff를 한 트랜잭션에 맞추도록 요구한다.
11. **완료 불확실성:** listener 실패는 commit 후 발생하지만 `STATUS_UNKNOWN`은
    commit과 rollback 중 어느 것도 증명할 수 없다. 완화책: commit된
    listener/cleanup 실패는 절대 retry하지 않는다. unknown completion에서는
    버퍼를 보존하되 운영자 대조를 요구한다.

## 인수 기준

- `spring-boot/jdbc`는 영문 KDoc을 갖춘 명시적인 트랜잭션 인식 애그리거트 이벤트
  발행자를 노출한다.
- 발행자는 Spring이 단일 transaction-manager autowire 후보를 결정할 수 있을
  때만 자동 구성되고 기존 JDBC 자동 구성 뒤에 배치되며 application 빈이 있으면
  물러난다.
- 이벤트는 활성 트랜잭션 안에서 순서대로 Spring에 전달된다. 기본
  `AFTER_COMMIT` listener는 commit 후에만 실행되고 전체 rollback에서는 절대
  실행되지 않는다.
- 이벤트가 있는 handoff에는 synchronization과 실제 트랜잭션이 필요하다. 같은
  트랜잭션의 repository persistence는 검증할 수 없는 runtime 주장이 아니라
  문서화된 호출자 전제 조건이다.
- 성공한 commit은 committed completion에서 애그리거트 버퍼를 비운다. rollback,
  commit 전 검증 실패, publisher 실패는 버퍼를 보존한다.
- 한 애그리거트의 이벤트 포함 반복 등록은 트랜잭션을 poison 상태로 만들며
  snapshot을 중복 발행할 수 없다.
- application 코드가 publication 실패를 잡아도 fail-closed 동작을 유지한다.
- publisher 상태는 현재 Spring synchronization list에만 있으므로 외부와 내부
  `REQUIRES_NEW` registry가 격리된다. 동일한 애그리거트 객체가 서로 겹치는
  트랜잭션 경계를 넘어서는 안 된다.
- `PROPAGATION_NESTED`/savepoint handoff를 명시적으로 지원하지 않는다.
- commit된 listener/cleanup 실패와 unknown completion에는 서로 구분되고
  문서화된 자동 retry 금지 동작이 있다.
- 변경과 반복 save는 단일 등록 전에 끝나야 한다. 이벤트는 깊이 불변이며
  clear할 때까지 안정적인 객체 identity를 유지한다.
- 일반 Spring Boot 사용에는 Spring Modulith가 필요하지 않다.
- 내구성 Spring Modulith 사용 시 serializer 소유권, 신뢰할 수 있는 이벤트 type,
  민감 payload 제어, idempotent consumer를 문서화한다.
- DDD Spring Modulith 예제는 새 public 발행자를 사용한다.
- README 로케일 쌍과 수명주기 diagram은 정확한 timing과 내구성 한계를 설명한다.
- merge를 요청하기 전에 관련 테스트, diagnostics, diagram 검증, diff 검사,
  7-Tier review, PR 검사, CI가 통과한다.
