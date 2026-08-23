# Issue #707 JDBC driver abort 증거 lesson

## Context

Issue #707은 interrupt를 무시하는 JDBC driver 때문에 child transaction cleanup을
임의 timeout이나 공통 `forceAbort`로 우회할 수 있는지 조사한다. 현재
`JdbcParallelKeyEnumeration.kt:210-275`는 `VirtualFuture`, `CountDownLatch`,
`Semaphore`를 사용하고 active `Statement`·`Connection` identity는 등록하지 않는다.
따라서 이번 slot은 production API가 아니라 driver capability와 후속 contract를
검증하는 Type D 범위로 한정했다.

## Decision or Finding

- `Statement.cancel()`, driver-specific query cancel, `Connection.abort(Executor)`,
  rollback, pool lease release를 서로 다른 관찰 대상으로 기록한다.
- PostgreSQL pgjdbc `42.7.13`만 real Testcontainers fixture로 검증했다. active
  `pg_sleep(30)`을 `PGConnection.cancelQuery()`로 취소하고 SQLState `57014`, 명시적
  `rollback()`, 후속 `SELECT 1`, tracker `active == 0`을 확인했다.
- MySQL Connector/J `9.7.0`은 source capability를 확인했지만 runtime cancel fixture가
  없어 `PENDING`으로 남겼다. MariaDB `3.5.10`과 CockroachDB도 같은 이유로
  `PENDING/N/A`다. H2 lifecycle 결과를 driver cancel PASS로 승격하지 않는다.
- public `forceAbort`, 임의 timeout, `JdbcParallelKeyEnumerationOptions` field,
  production `JdbcParallelKeyEnumeration.kt`는 변경하지 않는다. 후속 구현은
  generation-bound active handle과 adapter 소유권을 먼저 고정해야 한다.

## Outcome

PostgreSQL targeted test `1/1`, 전체 PostgreSQL class `8/8`, MySQL class `11/11`, H2
class `10/10`이 Docker/Testcontainers에서 순차 `BUILD SUCCESSFUL`이다. PostgreSQL
driver가 `testRuntimeOnly`라 직접 `PGConnection` import를 사용한 첫 compile은 실패했고,
reflection unwrap으로 classpath 계약을 보존한 뒤 재실행해 통과했다.

## Verification

- test fixture: `exposed/jdbc/src/test/kotlin/io/bluetape4k/exposed/jdbc/PostgreSQLJdbcParallelKeyEnumerationTest.kt:51-98`
- active query polling: 같은 파일 `:427-448`
- runtime-only driver reflection: 같은 파일 `:450-458`
- source capability: PostgreSQL `PGConnection`, MySQL `JdbcStatement`, MariaDB
  `Connection` local source/javap와 공식 API 링크를 spec에 기록했다.
- 최종 문서 갱신 뒤 `git diff --check`, Korean terminology audit 4개 파일 findings `[]`,
  affected JDBC detekt와 `docs/manual/**` guard를 모두 통과했다.

## Miss or Surprise

driver가 test runtime에만 있는 상태에서 compile-time import를 추가하면 test compile이
깨진다. 또한 source에 `cancel()`이 있어도 active query cancellation과 rollback/recovery가
증명되는 것은 아니다. 설치된 `bluetape-flow` helper의 `mutation-check`는 manifest의
`_run` metadata와 helper가 기대하는 `run` key가 달라 `coordinator_conflict`를 반환했지만,
receipt `verify`와 `receipt-diagnose`는 정상 상태를 확인했다. 이 helper 오류를 mutation
성공의 근거로 사용하지 않고, 이후 evidence receipt와 독립적인 test output을 함께
기록한다.
최종 회귀에서 H2를 `H2JdbcParallelKeyEnumerationTest`로 선택해 `0 passing`이 된 것은
코드 실패가 아니라 실제 클래스명이 `JdbcParallelKeyEnumerationTest`인 selector 오류였다.
클래스 목록을 다시 읽고 올바른 selector로 실행한 결과 `10/10`이 통과했다.

## Future Guidance

1. driver capability matrix에는 source status와 runtime status를 별도 열로 둔다.
2. Testcontainers cancel fixture는 active query 관찰, cancel invocation, SQLState/cause,
   rollback, 다음 query, pool lease 0을 한 테스트에서 확인한다.
3. `PENDING`, `N/A`, `UNSUPPORTED`, `FAIL`을 Docker skip이나 timeout 성공으로 바꾸지
   않는다.
4. generation-bound active handle과 stale pool lease 방지가 증명되기 전에는 public
   `forceAbort`나 arbitrary timeout을 추가하지 않는다.

## Writer DoD (SPW-01~05)

- [x] SPW-01 — Issue/Epic, source/runtime scope, exact driver versions와 미실행 행을
  고정했다.
- [x] SPW-02 — context, decision, outcome, verification, miss, future guard를 채웠다.
- [x] SPW-03 — 한국어 technical register와 `PENDING`, `N/A`, `testRuntimeOnly`, API
  token을 보존했다.
- [x] SPW-04 — test line anchors, Gradle 결과, source/runtime boundary와 helper 오류를
  교차 대조했다.
- [x] SPW-05 — Markdown read-back 대상과 최종 diff/audit 검증을 명시했다.
