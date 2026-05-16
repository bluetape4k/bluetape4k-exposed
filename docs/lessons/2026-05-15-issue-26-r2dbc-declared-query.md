# Lessons Learned — R2DBC @Query raw SQL 지원 (2026-05-15)

**관련 PR**: #71
**영향 모듈**: `spring-boot/exposed-r2dbc` (`:exposed-spring-boot-r2dbc`)

## L1: 에러 메시지를 먼저 읽어라

### 문제
`DeclaredExposedR2dbcQueryTest` 24/27 실패. 원인 분석에서 트랜잭션 컨텍스트, `suspendTransaction` 설계,
Exposed 내부 ThreadLocal 구조 등 복잡한 방향으로 수 차례 시도함.
실제 원인은 `@Query` SQL에서 테이블명을 `users` 로 잘못 적었고,
실제 테이블은 `LongIdTable("coroutine_users")` 였음.

### 교훈
에러 메시지 `Table "users" not found` 가 처음부터 나왔다.
디버깅 시작 전에 에러 메시지를 충분히 읽고, 가장 단순한 가설(잘못된 테이블명)부터 검증해야 한다.
복잡한 구조적 문제를 먼저 의심하면 시간 낭비가 심하다.

---

## L2: JDBC 패턴을 먼저 확인하면 R2DBC 설계가 빨라진다

### 문제
R2DBC `DeclaredExposedR2dbcQuery` 초기 구현에서 `suspendTransaction {}` 으로 새 트랜잭션을 열었음.
이는 호출자 트랜잭션을 무시하고 별도 커넥션을 열어 미커밋 데이터가 보이지 않는 문제가 있었음.

### 교훈
JDBC 대응 클래스 `DeclaredExposedQuery.kt` 를 먼저 확인했다면 `TransactionManager.current()` 패턴을
즉시 파악할 수 있었다. R2DBC 구현 시 항상 JDBC 구현을 reference로 먼저 검토할 것.

---

## L3: Spring Data 프록시 파이프라인 우회 시 전략 로직이 중복된다

### 문제
`ExposedR2dbcRepositoryFactory.getRepository()` 가 Spring Data 의 프록시 체인을 완전히 우회하여
`createDirectProxy` 를 직접 호출함. 따라서 `getQueryLookupStrategy()` 와 `ExposedR2dbcQueryLookupStrategy` 는
실제로 호출되지 않는 dead code 상태임.

`createDirectProxy` 내부에 전략 라우팅 로직이 중복 구현되어 있고,
`queryLookupStrategyKey` 를 `createDirectProxy` 가 직접 읽어야 한다.

### 교훈
Spring Data 의 `RepositoryFactorySupport` 를 크게 오버라이드할 때는
어느 훅이 실제로 호출되는지 검증해야 한다. Dead code는 별도 이슈로 정리 예정.

---

## L4: Two-query 패턴의 제약을 KDoc과 README에 명시해야 한다

### 문제
`DeclaredExposedR2dbcQuery` 는 raw SQL로 ID만 추출한 뒤
`selectAll().where { id inList ids }` 로 엔티티를 reload 함.
ORDER BY, JOIN, GROUP BY, LIMIT 등은 최종 결과에 반영되지 않음.
이 제약이 코드 리뷰 전까지 문서화되지 않았음.

### 교훈
Two-query 패턴처럼 사용자가 직관적으로 예상하는 동작과 실제 동작이 다를 경우,
KDoc + README 양쪽에 **명시적 경고**로 즉시 문서화해야 한다.
나중에 추가하면 사용자가 이미 잘못된 방식으로 사용하고 있을 수 있다.
