# Issue 126 Redisson `upsertAll` 설계

## 배경

Milestone 1.8.1의 issue #126은 Redisson 기반 JDBC/R2DBC 저장소에
bulk `upsertAll(Map<ID, E>)` API를 추가하도록 요구한다. cache warming이
단일 entry write를 반복하는 대신 하나의 명시적인 bulk 연산을 사용할 수 있게 한다.

현재 Redisson 저장소 인터페이스는 이미 `putAll(entities, batchSize)`를
제공한다. 부족한 부분은 API의 의도다. caller가 일반 cache write 이름과
의도적인 bulk upsert/warm 경로를 구분할 수 없다.

## 결정

Redisson 전용 public API로 `upsertAll`을 추가한다.

- JDBC: `fun upsertAll(entities: Map<ID, E>, batchSize: Int = 100)`
- Suspended JDBC parity: `suspend fun upsertAll(entities: Map<ID, E>, batchSize: Int = 100)`
- R2DBC: `suspend fun upsertAll(entities: Map<ID, E>, batchSize: Int = 100)`

동작을 한곳에 모으기 위해 `putAll`은 `upsertAll`에 위임한다.

## Redisson API 근거

local Redisson 4.4.0 jar를 확인한 결과 `RMap.putAll(Map, Int)`과
`RMapAsync.putAllAsync(Map, Int)`를 사용할 수 있다. 이 version에는
`fastPutAllAsync`가 없으므로 별도 `RBatch` wrapper를 만들지 않고 Redisson이
제공하는 기존 batched map write 경로를 사용한다.

## 동작

- 빈 map에는 아무 작업도 하지 않는다.
- `batchSize <= 0`이면 `requirePositiveNumber("batchSize")`를 통해 `IllegalArgumentException`을 던진다.
- write-through 및 write-behind persistence는 설정된 Redisson map writer에 계속 위임한다.
- warm 경로가 같은 API를 사용하도록 `findAll` cache population을 `upsertAll(..., DEFAULT_BATCH_SIZE)`로 연결한다.

## 범위 밖

- Spring Boot 또는 Actuator 통합을 추가하지 않는다.
- 새 Redisson dependency 또는 version 변경을 추가하지 않는다.
- auto-increment ID에 대한 writer semantics를 변경하지 않는다.
