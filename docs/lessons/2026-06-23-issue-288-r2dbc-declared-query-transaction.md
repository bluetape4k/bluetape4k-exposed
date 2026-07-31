# 교훈 - 이슈 #288 R2DBC 선언형 쿼리 트랜잭션 경계 (2026-06-23)

관련 이슈: #288
영향 모듈: `:bluetape4k-exposed-spring-boot-r2dbc`

## L1: 선언형 repository 쿼리도 base repository와 같은 트랜잭션 경계를 가져야 한다

### 문제

R2DBC 선언형 `@Query` 경로는 custom repository proxy에서 직접 실행되므로 Spring Data의 일반 transaction interceptor 체인을 타지 않는다. 그런데 기존 구현은 `TransactionManager.current()`를 직접 호출해서, service/controller에서 repository suspend method를 일반적으로 호출하면 active Exposed transaction이 없다는 예외가 발생했다.

### 교훈

Repository API가 호출자에게 `suspendTransaction {}` 래핑을 요구하지 않는 계약이라면, 모든 query path가 같은 boundary를 가져야 한다. PartTree/base repository처럼 active transaction이 있으면 재사용하고, 없으면 repository가 직접 `suspendTransaction`을 열어야 한다.

### 보호 장치

선언형 쿼리 테스트는 두 축을 모두 가져야 합니다.

- active transaction 내부에서 미커밋 row가 보이는지
- active transaction 밖에서도 repository method가 자체 transaction으로 조회되는지

## L2: transaction 밖 테스트는 Exposed 기본 DB 선택을 명시해야 합니다

### 문제

`withTables(testDB, ..., dropTables = false)`로 데이터를 만든 뒤 transaction 밖에서 `suspendTransaction {}`을 열면, Exposed는 active transaction이 없으므로 `TransactionManager.defaultDatabase` 또는 primary database를 사용합니다. multi-dialect 테스트에서는 이 기본 DB가 현재 parameter의 `testDB`와 달라질 수 있습니다.

### 교훈

transaction 밖 동작을 검증하는 multi-dialect 테스트는 테스트가 만든 `testDB.db`를 `TransactionManager.defaultDatabase`로 임시 지정하고 반드시 `finally`에서 복원해야 합니다. 그래야 테스트가 실제 production fallback 경로를 검증하면서도 DB 선택이 deterministic합니다.
