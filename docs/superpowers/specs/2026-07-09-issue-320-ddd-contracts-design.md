# Issue 320 DDD 계약 설계

## 문제

`bluetape4k-exposed`는 이미 Exposed 저장소 실행, 트랜잭션 경계, 캐시 동작,
Spring Boot/Ktor 어댑터를 담당한다. 후속 이슈 `#319`와 `#316`에는 Spring,
Spring Modulith, JaVers, Exposed DAO 런타임 타입에 의존하지 않고 애그리거트
도메인 이벤트를 전달할 수 있는 작은 도메인 계약이 필요하다.

현재 저장소는 `exposed/core`와 `exposed/dao`에서 감사 가능한 테이블/엔티티를
지원하지만 Spring 중립적인 `AggregateRoot`나 `DomainEvent` API는 없다.
`bluetape4k-javers`에는 더 넓은 `javers-ddd` 선례가 있으나, 그 설계에는
JaVers 커밋 속성 매핑, 발행 어댑터, 저장소 helper가 포함된다. 이 부분은
이번 이슈의 범위가 아니다.

## 현재 근거

- GitHub 이슈 `#320`은 milestone `1.12.0`에 `enhancement`, `feature`,
  `test` label로 열려 있다.
- 편집 전 baseline 명령:
  `repo-test-summary -- ./gradlew :bluetape4k-exposed-core:test --no-configuration-cache --no-build-cache --no-parallel --console=plain`
  실행 결과는 `BUILD SUCCESSFUL`, `277 tests`였다.
- 현재 가장 작은 모듈 경계는 `exposed/core`이며 패키지 루트는
  `io.bluetape4k.exposed.core`이다.
- `Auditable` 같은 기존 public core 계약은 일반 Kotlin 인터페이스이며
  프레임워크별 동작을 계약 밖에 둔다.
- 루트 README는 `bluetape4k-exposed`가 Exposed 저장소/캐시/트랜잭션 경계를
  담당하고 JaVers 감사/이력은 `bluetape4k-javers`가 담당한다고 이미 설명한다.

## 제약

- 새 API는 Spring, Spring Modulith, JaVers, Exposed DAO, Exposed JDBC/R2DBC
  의존성 없이 사용할 수 있어야 한다.
- public KDoc은 영어로 작성한다.
- README 변경은 `README.md`와 `README.ko.md`를 모두 갱신해야 한다.
- 테스트는 `bluetape4k-assertions`를 사용하며 새 테스트에
  JUnit/AssertJ/Kluent assertion API를 사용하지 않는다.
- 새 모듈, 새 의존성, 발행 어댑터, 영속 아웃박스, Exposed DAO 애그리거트
  기반 클래스는 범위에 포함하지 않는다.
- 호출자가 새 계약을 명시적으로 구현하기 전까지 기존 저장소는 영향을 받지
  않는다. 이번 이슈는 이벤트를 자동으로 발행, 저장, 관찰, 재생하지 않는다.

## 대안

### 대안 A: 최소 core DDD 패키지

`exposed/core`에 다음을 포함하는 `io.bluetape4k.exposed.core.ddd`를 추가한다.

- `DomainEvent<ID : Any>` interface.
- `AggregateRoot<ID : Any>` interface.
- `AbstractAggregateRoot<ID : Any>` event recording/draining base.

저장소와 발행 어댑터는 후속 작업으로 남긴다.

장점:
- 의존성 표면이 작다.
- `#320`을 직접 충족한다.
- Spring Modulith와 Ktor 후속 작업에서 쉽게 사용할 수 있다.

단점:
- 저장소 측 수집 helper는 아직 제공하지 않는다.

### 대안 B: JaVers DDD 모듈 형태 복제

`bluetape4k-javers`에서 `AggregateRepository`, `DomainEventPublisher`,
어댑터 개념을 이식한다.

장점:
- 더 완성된 예제 표면을 제공한다.

단점:
- 이번 이슈가 JaVers별 workflow와 발행 관심사로 확장된다.
- `bluetape4k-javers`의 책임과 겹칠 위험이 있다.
- 후속 Spring Modulith 계약에 비해 범위가 너무 넓다.

### 대안 C: Spring Modulith 모듈에 계약 배치

계약을 `spring-boot/spring-modulith` 아래에 추가한다.

장점:
- 첫 프레임워크 어댑터가 위치할 가능성이 큰 곳과 가깝다.

단점:
- Spring 중립 요구 사항을 위반한다.
- Ktor와 일반 저장소 소비자가 Spring 모듈 이름과 패키징에 의존하게 된다.

## 결정

대안 A를 사용한다.

API는 `exposed/core/src/main/kotlin/io/bluetape4k/exposed/core/ddd`에 배치한다.
인메모리 이벤트 기록과 비우기만 모델링한다. 이벤트 발행, JaVers 속성 변환,
영속 저장, Exposed DAO 수명주기 callback 연결은 하지 않는다.

## API 형태

`DomainEvent<ID : Any>`:

- `val aggregateId: ID`
- `val occurredAt: Instant`

`AggregateRoot<ID : Any>`:

- `val id: ID`
- `fun domainEvents(): List<DomainEvent<ID>>`
- `fun clearDomainEvents()`
- `fun drainDomainEvents(handoff: (List<DomainEvent<ID>>) -> Unit): List<DomainEvent<ID>>`

`AbstractAggregateRoot<ID : Any>`:

- `AggregateRoot<ID>`를
  `abstract class AbstractAggregateRoot<ID : Any> : AggregateRoot<ID>`로
  구현하며, 하위 클래스가 `abstract override val id: ID`를 제공한다.
- 이벤트가 없는 애그리거트마다 목록을 할당하지 않도록 내부 mutable 이벤트
  버퍼를 지연 초기화하거나 이에 상응하는 빈 이벤트 저장 경로를 사용한다.
- protected `recordDomainEvent(event: DomainEvent<ID>)`를 제공한다.
- 기록 전에 `event.aggregateId == id`를 검증한다. 불일치는 호출자 오류이며
  `IllegalArgumentException`으로 실패해야 한다.
- `domainEvents()`에서 방어적 immutable 스냅숏을 반환하고
  `drainDomainEvents { ... }`에도 같은 스냅숏을 전달한다.
- 기록된 이벤트가 없으면 복사 없이 `emptyList()`를 반환하고, 비어 있지 않은
  버퍼만 복사한다.
- 스냅숏과 비우기 모두 이벤트 기록 순서를 보존한다.
- `clearDomainEvents()`를 호출하거나 `drainDomainEvents { ... }`의 전달
  callback이 성공적으로 반환된 경우에만 이벤트를 지운다.

## 저장소 지침

저장소 구현은 `drainDomainEvents { ... }`를 발행 경계로 취급해서는 안 된다.
이 메서드는 로컬 버퍼 전달과 비우기만 수행한다. 이벤트 손실을 방지하려면
`domainEvents()`로 이벤트 스냅숏을 만들고, 애그리거트를 저장하고, 트랜잭션
커밋 경계를 기다린 뒤, 아웃박스·영속 재시도 큐·트랜잭션으로 기록한 전달 같은
영속 소유자에게 스냅숏을 넘겨야 한다. 애그리거트 버퍼는 그 영속 소유자가
이벤트 책임을 수락한 후에만 지우거나 비운다.

일반적인 저장소 순서는 다음과 같다.

1. 애그리거트를 변경하고 도메인 이벤트를 기록한다.
2. `domainEvents()`로 방어적 스냅숏을 만든다.
3. 애그리거트를 성공적으로 저장한다.
4. 트랜잭션 커밋 또는 이에 상응하는 영속 경계를 기다린다.
5. 아웃박스, 영속 재시도 큐, 트랜잭션으로 기록한 전달 같은 영속 소유자에게
   스냅숏을 넘긴다.
6. 그 영속 소유자가 이벤트 책임을 수락한 후에만 애그리거트 버퍼를 지우거나
   비운다.

`clearDomainEvents()`는 전달 전 성공 발행을 위한 메서드가 아니라
폐기/rollback 방식의 호출자 소유 정리에 사용한다.
`drainDomainEvents { ... }`는 callback이 성공적으로 반환된 후에만 비운다.
callback이 예외를 던지면 애그리거트는 이벤트를 유지한다. callback은
프로세스 로컬 재시도 큐가 아니라 영속 이벤트 소유권을 나타내야 한다.

write-behind 캐시 경로에서 인메모리 큐가 값을 수락하는 것은 영속 경계가
아니다. 트랜잭션이 여전히 rollback될 수 있다면 데이터베이스 flush도
충분하지 않다. 후속 이슈 `#319`는 커밋 후 경계 또는 이에 상응하는 영속
write-behind 전달 이후에만 발행해야 한다.

애그리거트 변경 후 저장이 실패해도 계약은 이벤트를 자동으로 발행하거나
폐기하지 않는다. command handler는 같은 애그리거트 인스턴스로 의도적으로
재시도하거나, 애그리거트 인스턴스를 폐기하거나, 명령을 포기할 때
`clearDomainEvents()`를 호출해야 한다. 이 선택 없이 명령 시도 사이에서
변경된 애그리거트를 재사용하는 것은 호출자 버그다.

Exposed DAO `EntityCache`는 트랜잭션 범위다. 다음 용도로 취급해서는 안 된다.

- 애플리케이션 수준 캐시,
- 영속 아웃박스,
- 도메인 이벤트 registry,
- Spring Modulith 이벤트 발행 저장소.

## 테스트

`exposed/core/src/test/kotlin/io/bluetape4k/exposed/core/ddd` 아래에 대상 테스트를 추가한다.

- 일반 애그리거트가 도메인 이벤트를 기록한다.
- `domainEvents()`는 스냅숏을 반환하고 이벤트를 지우지 않는다.
- `drainDomainEvents { ... }`는 이벤트를 전달하고 callback 성공 후 지운다.
- callback이 예외를 던지면 `drainDomainEvents { ... }`가 이벤트를 보존한다.
- 다시 비우면 빈 목록을 반환한다.
- 여러 이벤트를 기록 순서대로 비운다.
- 애그리거트 `id`와 다른 `aggregateId`를 가진 이벤트 기록은
  `IllegalArgumentException`으로 실패한다.
- 컴파일 시점 fixture로 ID 타입을 검증한다. 서로 다른
  `@JvmInline value class OrderId`와 `@JvmInline value class CustomerId` 값을
  정의하고, 일치하는 애그리거트/이벤트 쌍을 사용해 잘못된 ID가 런타임
  erasure 검사에 의존하지 않고 컴파일 시점에 실패하도록 한다.
- README/테스트의 모든 `data class` fixture는 `java.io.Serializable`을
  구현하고 `serialVersionUID`를 정의하는 bluetape4k data class 규칙을 따른다.

이 계약은 의도적으로 thread-safe하지 않으므로 이번 이슈에는 동시성 stress
테스트가 필요하지 않다. 애그리거트 인스턴스는 하나의 명령/트랜잭션 경계
안에서 변경하며 KDoc에 이를 명시해야 한다. 또한 같은 애그리거트 인스턴스에서
`recordDomainEvent`, `domainEvents`, `clearDomainEvents`,
`drainDomainEvents`를 동시에 호출하면 안 된다고 KDoc에 명시한다.

## 문서

루트 README 로케일 쌍에 짧은 DDD 계약 절을 추가한다.

- 새 Spring 중립 계약을 설명한다.
- 최소 애그리거트/이벤트 예제를 제시한다.
- 저장소 측 스냅숏, 커밋, 전달, 지우기/비우기 순서를 보여 준다.
- Exposed DAO 트랜잭션 캐시가 영속 이벤트 경계가 아니라고 명시한다.
- 이번 이슈가 영속 아웃박스, 발행 어댑터, Exposed DAO 수명주기 훅을
  제공하지 않는다고 명시한다.
- 이벤트 페이로드에 secret, credential, token, 불필요한 PII를 넣지 않아야
  한다고 명시한다. 예제는 전체 객체 스냅숏보다 식별자를 우선한다.
- 기존 저장소는 계약을 명시적으로 도입하기 전까지 영향을 받지 않는다고
  명시한다.
- JaVers와 Spring Modulith의 책임 경계를 분명히 유지한다.

계약만 바꾸는 이번 변경에는 런타임 metric이나 logging 표면이 없다.
관찰 가능성은 후속 저장소/발행 어댑터가 담당하며, 발행 성공/실패, 누락된
이벤트, 재시도/아웃박스 상태, 커밋 후 동작을 확인할 수 있어야 한다.

## 릴리스 호환성

`1.12.0` 릴리스 전에는 리뷰나 구현 근거가 설계를 기각하면 이 API를 되돌릴
수 있다. 릴리스 후 public 계약 제거 또는 이름 변경은 호환성을 깨뜨린다.
새 major 호환성 결정이 없다면 수정은 추가 방식 또는 deprecation 방식으로
진행해야 한다.

## 위험

1. **지나치게 넓은 API:** 여기에 발행자나 저장소 어댑터를 추가하면 후속
   이슈와 중복된다. 완화: 이번 이슈는 계약으로만 제한한다.
2. **잘못된 영속성 의미:** 소비자가 `drainDomainEvents { ... }`를 저장으로
   취급할 수 있다. 완화: 저장소는 커밋 경계와 영속 소유자의 수락 이후에만
   지우거나 비워야 하며 아웃박스를 제공하지 않는다고 KDoc과 README에 명시한다.
3. **프레임워크 누출:** Spring Modulith나 JaVers 용어가 core API에 들어갈 수
   있다. 완화: core 타입은 Kotlin/JDK 타입만 사용한다.
4. **thread-safety 모호성:** 이벤트 버퍼는 mutable이다. 완화: 명령/트랜잭션
   범위 사용을 문서화하고 thread-safe하다고 주장하지 않는다.

## 인수 기준

- `AggregateRoot`, `DomainEvent`, `AbstractAggregateRoot`가
  `io.bluetape4k.exposed.core.ddd`에 존재한다.
- 계약이 Spring, Spring Modulith, JaVers, Exposed DAO 의존성 없이 컴파일된다.
- 테스트가 기록, 스냅숏, 비우기, 반복 비우기, 순서 보존 비우기, 애그리거트
  ID 불일치 거부, 타입 지정 ID 동작을 검증한다.
- README 로케일 쌍이 API, 지원하지 않는 기능, 저장소 스냅숏/전달 순서,
  안전한 페이로드 지침, 선택적 도입, 영속 경계를 설명한다.
- 새 인터페이스/클래스마다 public KDoc이 있으며 프레임워크 중립성, 스냅숏
  의미, 비우기/지우기 동작, thread-safe하지 않음, 애그리거트 ID 검증,
  안전한 페이로드 지침, 발행자/아웃박스를 제공하지 않는다는 점을 설명한다.
- `:bluetape4k-exposed-core:test`가 통과한다.
