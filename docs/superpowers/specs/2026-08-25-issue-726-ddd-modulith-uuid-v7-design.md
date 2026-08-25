# Issue #726 DDD Spring Modulith 예제 UUID v7 전환 설계

## 목적

`examples/ddd-spring-modulith-demo`의 aggregate ID와 `OrderAcceptedEvent`의
event ID를 bluetape4k `Uuid.V7` helper로 통일한다. 기존 `order-`·`event-`
접두사, canonical UUID 문자열, Spring Modulith 이벤트 직렬화 필드와 DDD 모듈
경계는 그대로 유지하면서 ID 생성 경로만 ecosystem 표준으로 바꾼다.

## 현재 근거와 범위

- `OrderDomain.kt`의 `OrderId.newId()`와 `Order.accept()`가
  `UUID.randomUUID()`를 직접 호출한다.
- `examples/ddd-spring-modulith-demo/build.gradle.kts`는
  `:bluetape4k-exposed-core`를 사용하지만 ID generator를 직접 선언하지 않는다.
  이 이슈는 직접 사용하는 `bt4k.bluetape4k.idgenerators` dependency를 명시할
  것을 요구하므로 Type B의 신규 dependency 경계를 넘어 Type A로 분류했다.
- `DddSpringModulithDemoApplication.kt`는 `aggregateId`, `eventId`, `occurredAt`
  세 필드만 JSON으로 직렬화한다. `DddSpringModulithDemoApplicationTest.kt`는
  opaque payload와 민감값 비노출을 이미 검증한다.
- 대상은 production domain 파일, 해당 module build file, domain 회귀 테스트와
  기존 application serialization 테스트다. schema, Spring Modulith listener
  계약, repository API, README의 의미 있는 설명은 바꾸지 않는다.

## 선택한 설계

### 1. ID 생성

`io.bluetape4k.idgenerators.uuid.Uuid.V7.nextId().toString()`을 사용한다.
`nextIdAsString()`은 Base62 문자열이므로 canonical UUID를 요구하는 현재
serialization·저장 계약과 맞지 않는다. `UUID.toString()` 결과는 기존처럼
36자 canonical 문자열이며 접두사 조립은 domain 책임으로 유지한다.

### 2. 직접 dependency

build catalog의 기존 alias인 `bt4k.bluetape4k.idgenerators`를
`examples/ddd-spring-modulith-demo/build.gradle.kts`에 `implementation`으로
명시한다. transitively 노출된 API에 기대지 않고 production import와 build
계약을 일치시킨다. 새 artifact나 버전은 추가하지 않는다.

### 3. 회귀 테스트

새 `OrderDomainTest`는 Spring context 없이 다음을 검증한다.

- aggregate ID와 event ID가 각각 `order-`, `event-` 접두사를 유지한다.
- 접두사를 제거한 값이 canonical `UUID`로 파싱되고 `version() == 7`이다.
- 반복 생성한 aggregate/event UUID 전체 값이 모두 유일하다.
- UUID v7의 48-bit Unix timestamp 부분이 생성 순서에서 감소하지 않는다.
  UUID v7의 random tail까지 엄격한 전체 UUID 순서를 단정하지 않는다.

기존 application test에는 직렬화된 payload의 `order-`·`event-` 접두사 검증을
추가한다. `aggregateId`, `eventId`, `occurredAt` 필드와 opaque payload 검증은
그대로 유지한다. 모든 검증은 이미 module이 선언한
`bluetape4k-assertions` matcher를 사용한다.

## 대안과 선택 이유

| 대안 | 장점 | 제외 이유 |
|---|---|---|
| `Uuid.V7.nextId().toString()` | canonical UUID, repository 표준, 최소 변경 | 선택 |
| `Uuid.V7.nextIdAsString()` | 문자열 생성이 짧음 | Base62라 기존 canonical UUID/직렬화 계약을 깨뜨림 |
| domain 전용 `IdGenerator` wrapper 추가 | 향후 교체 지점 확보 | 이번 범위에 새 abstraction을 추가하고 DDD 경계를 넓힘 |

## 실패 모드와 보호 조치

| 위험 | 보호 조치 |
|---|---|
| UUID 문자열 형식 회귀 | 접두사 제거 후 `UUID.fromString`과 canonical `toString` 검증 |
| UUID v7 버전 누락 | aggregate와 event 각각 `version() == 7` 검증 |
| 단조성 오해 | 전체 UUID 비교가 아니라 RFC v7 timestamp field만 비교 |
| event payload 계약 회귀 | 기존 opaque JSON 필드 검증과 접두사 검증을 함께 실행 |
| transitive dependency 재발 | module build에 `bt4k.bluetape4k.idgenerators` 직접 선언 |
| DDD 경계 회귀 | `OrderAcceptedEvent`, listener, repository, module verifier 코드는 수정하지 않음 |

## 호환성과 비목표

- `OrderId`의 public value class, `OrderAcceptedEvent`의 필드명·타입, event
  serializer와 listener 흐름은 유지한다.
- UUID 생성 시점·접두사·canonical 문자열만 바뀌며 기존 데이터 migration은
  필요하지 않다. 새 ID 형식은 기존 문자열 저장 폭 안에 들어간다.
- README 두 로케일, workflow, CI, schema, release metadata, 다른 examples의
  `UUID.randomUUID()`는 이번 이슈에서 수정하지 않는다. 이슈 범위 밖 잔여 호출은
  후속 이슈 후보로 기록한다.

## 검증 계약

1. 대상 production 범위에 `UUID.randomUUID()`가 없고 `Uuid.V7.nextId()`가 두
   경로에 존재한다.
2. `compileTestKotlin`이 새 직접 dependency와 domain test를 통과한다.
3. focused domain/event tests와 전체 `:examples-ddd-spring-modulith-demo:test`
   를 실행해 failure/error/skipped 수를 기록한다.
4. 대상 module에 Detekt task가 있으면 실행하고, 없으면 task surface를 N/A로
   기록한다. `git diff --check`와 7-Tier(Kotlin API/test, DDD boundary,
   stability, security, operations, performance, integration) review를 수행한다.

## 완료 정의

- production에서 `UUID.randomUUID()`를 제거하고 직접 ID generator dependency를
  선언한다.
- aggregate/event prefix, canonical UUID serialization, opaque payload 계약을
  fresh tests로 입증한다.
- UUID v7 version·timestamp monotonicity·uniqueness를 bluetape4k assertions로
  검증한다.
- 한국어 spec/plan/review/lesson이 SPW-01~05를 충족하고 Lore 커밋과 Korean PR을
  만든다. PR hosted checks/review와 merge는 별도 gate이며 이 작업에서 merge하지
  않는다.
