# Lessons Learned — Issue #27: TrinoDatabase.connect(DataSource) (2026-05-15)

**관련 PR**: #75
**영향 모듈**: `:bluetape4k-exposed-trino`

## L1: 기존 패턴 복사로 안전하게 구현

### 문제
`connect(dataSource)` 오버로드를 새로 설계할 때, 커넥션 풀과의 통합 방식 및 `TrinoConnectionWrapper` 생성 실패 시 leak 방지 코드를 어떻게 작성해야 할지 고민됨.

### 교훈
기존 `connect(host,...)` / `connect(jdbcUrl,...)` 구현의 `getNewConnection` 람다 패턴을 그대로 복사한 후, `DriverManager.getConnection()` 대신 `dataSource.connection`으로 교체하면 충분하다.
새로운 추상화나 설계가 필요 없었고, close-on-failure 로직도 그대로 재사용됨.

---

## L2: mock autoCommit 테스트 — 검증 시점은 트랜잭션 시작 후

### 문제
`TrinoDatabase.connect(mockDataSource)` 호출 직후 `verify { mockConn.autoCommit = true }` 를 실행했더니 통과하지 못했다. `Database.connect(getNewConnection = { ... })` 의 람다는 **트랜잭션이 시작될 때** 실행되기 때문.

### 교훈
`Database.connect(getNewConnection = { ... })` 패턴에서 람다는 lazy하게 동작한다. `autoCommit`나 다른 connection-level 동작을 mock 검증할 때는, 먼저 `transaction(db) { ... }` (또는 `runCatching { transaction(db) { ... } }`)를 실행해 람다를 트리거한 후 `verify` 해야 한다.

---

## L3: close-on-failure 테스트 — 예외 throw 경로 확인

### 문제
`every { mockConn.autoCommit = true } throws RuntimeException(...)` 설정 시 `TrinoConnectionWrapper` 생성자 안에서 `autoCommit = true` 를 실행하는 구조여야만 "래퍼 생성 실패" 시나리오가 된다. 이 경로가 래퍼 내부 구현에 암묵적으로 의존함.

### 교훈
close-on-failure 테스트 코드에 짧은 주석으로 "TrinoConnectionWrapper 생성자가 autoCommit=true 를 설정하므로 이 throw가 래퍼 생성 실패를 시뮬레이션한다"고 명시하면, 래퍼 내부가 바뀔 때 테스트 의도 추적이 쉬워진다.

---

## L4: Phase 2 Roadmap 항목 — 구현 즉시 제거 확인 필수

### 문제
README Phase 2 Roadmap에 `connect(dataSource)` 항목이 남아있음을 advisor review 후 `rg` 로 확인했다. 이전 세션에서 Korean README는 제거됐지만 English README는 누락됨.

### 교훈
Phase 2 Roadmap 항목을 구현할 때, 커밋 직전에 `rg "항목이름" README*.md` 로 두 언어 모두 확인해야 한다. 편집 시 한 파일만 열어두면 다른 locale README가 누락되기 쉽다.
