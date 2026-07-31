# 코드 리뷰 - 이슈 #288 R2DBC 선언 쿼리 트랜잭션 경계

## 범위

- 이슈: #288 `fix(r2dbc): open transaction boundary for declared @Query methods`
- 모듈: `:bluetape4k-exposed-spring-boot-r2dbc`
- 리뷰한 파일:
  - `spring-boot/r2dbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/r2dbc/repository/query/DeclaredExposedR2dbcQuery.kt`
  - `spring-boot/r2dbc/src/test/kotlin/io/bluetape4k/spring/data/exposed/r2dbc/DeclaredExposedR2dbcQueryTest.kt`

## 판정

APPROVE

## 지적 사항

- P0: 없음
- P1: 없음
- P2: 없음
- P3: 없음

## 근거

- RED 재현: 새로 추가한 트랜잭션 외부 선언 쿼리 테스트는 프로덕션 수정 전에 `DeclaredExposedR2dbcQuery 'findByEmailNative' must be called within an active R2DBC suspendTransaction { }` 오류로 실패했다.
- 프로덕션 수정:
  - 활성 트랜잭션이 있으면 `DeclaredExposedR2dbcQuery.executeSuspending`이 `TransactionManager.currentOrNull()`을 재사용한다.
  - 활성 트랜잭션이 없을 때만 `suspendTransaction { ... }`을 열어 PartTree/기본 저장소의 트랜잭션 경계와 일치시킨다.
- 테스트 범위:
  - 활성 트랜잭션 외부: `@Query native - active transaction 없이 호출해도 자체 transaction 에서 조회된다`
  - 활성 트랜잭션 내부: `@Query native - active transaction 내부에서는 미커밋 row 를 조회한다`
- 로컬 검증:
  - `./gradlew :bluetape4k-exposed-spring-boot-r2dbc:test --tests 'io.bluetape4k.spring.data.exposed.r2dbc.DeclaredExposedR2dbcQueryTest'`: `BUILD SUCCESSFUL`
  - `./gradlew :bluetape4k-exposed-spring-boot-r2dbc:test`: `206 passing`, `BUILD SUCCESSFUL`
  - `./gradlew :bluetape4k-exposed-spring-boot-r2dbc:build detekt`: `BUILD SUCCESSFUL`, `:detekt NO-SOURCE`
  - `git diff --check`: 통과
- 독립 리뷰:
  - 네이티브 `code-reviewer` 경로에서 `APPROVE` 판정을 반환했다.
  - P0/P1/P2/P3 지적 사항: 없음.

## 잔여 위험

- 트랜잭션이 없을 때 Exposed는 현재/기본 R2DBC 데이터베이스에서 `suspendTransaction {}`을 결정하므로, 트랜잭션 외부 테스트는 `TransactionManager.defaultDatabase`를 일시적으로 설정한다. 테스트는 `finally`에서 이전 기본값을 복원하며, 활성화된 방언 집합 전체에서 전체 모듈 테스트가 통과했다.
