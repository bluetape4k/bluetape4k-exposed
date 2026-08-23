# Issue #707 JDBC driver 취소와 generation-bound handle lesson

## Context

Issue #707은 interrupt를 무시하는 JDBC driver 때문에 child transaction cleanup을
임의 timeout이나 공통 `forceAbort`로 우회할 수 있는지 조사한다. 이번 구현 slot은
public API를 확장하지 않고, 기존 `VirtualFuture`·`CountDownLatch`·`Semaphore` 경계에
production 내부 handle을 연결하며 네 backend의 실제 query cancellation evidence를
추가했다.

## Decision or Finding

- child마다 monotonic generation을 가진 `JdbcEnumerationChildHandle`을 만들고 active
  JDBC `Connection`과 Exposed `JdbcPreparedStatementApi`를 `AtomicReference`로 추적한다.
- `StatementInterceptor`가 prepare/execute lifecycle을 handle에 연결한다. statement
  실패 시 `afterExecution`이 호출되지 않을 수 있으므로 새 registration을 교체하되,
  늦은 callback은 object identity 비교로 새 statement를 지우지 못한다.
- transaction body의 `finally`에서 interceptor·statement·connection을 정리하지만,
  parent의 terminal 조건은 기존 child completion/latch다. caller-owned executor는
  계속 caller가 소유한다.
- PostgreSQL pgjdbc `42.7.13`, MySQL Connector/J `9.7.0`, MariaDB Connector/J
  `3.5.10`, CockroachDB image `v25.4.14`를 각각 실제 fixture에서 검증했다.
- public `forceAbort`, 임의 timeout, `Connection.abort()` fallback,
  `JdbcParallelKeyEnumerationOptions` field, public driver adapter는 추가하지 않았다.

## Runtime outcome

| backend | operation | 관찰 | 결과 |
| --- | --- | --- | --- |
| PostgreSQL `42.7.13` | `PGConnection.cancelQuery()` | active `pg_sleep(30)`, SQLState `57014`, rollback, `SELECT 1`, Hikari `active == 0` | **PASS** |
| MySQL `9.7.0` | `Statement.cancel()` | `PROCESSLIST` active query, prompt 종료, Connector/J `SLEEP()` 결과 `1` 또는 cancellation exception, rollback, recovery, lease 0 | **PASS** |
| MariaDB `3.5.10` | `Connection.cancelCurrentQuery()` | reflection unwrap 후 active `PROCESSLIST` query cancel, rollback, recovery, lease 0 | **PASS** |
| CockroachDB `v25.4.14` + pgjdbc | `PGConnection.cancelQuery()` | `SHOW QUERIES` active `pg_sleep`, SQLState `57014`, rollback, recovery, lease 0 | **PASS** |

fresh sequential result:

- H2 `JdbcParallelKeyEnumerationTest`: **12/12**
- MySQL `MySQLJdbcParallelKeyEnumerationTest`: **12/12**
- PostgreSQL `PostgreSQLJdbcParallelKeyEnumerationTest`: **8/8**
- MariaDB `MariaDBJdbcDriverCancellationTest`: **1/1**
- CockroachDB `CockroachDbJdbcCancellationTest`: **1/1**
- JDBC module Detekt: **BUILD SUCCESSFUL**

## TDD and miss or surprise

1. handle symbol을 아직 추가하지 않은 상태에서 generation test를 compile해 unresolved
   `JdbcEnumerationChildHandle` RED를 확인했다.
2. handle 구현 후 stale generation과 transaction statement cleanup test가 GREEN이 됐다.
3. MySQL 첫 runtime assertion은 `Statement.cancel()` 뒤 예외를 반드시 기대했지만,
   Connector/J가 `SLEEP()`을 중단하고 함수 결과 `1`을 반환해 실패했다. 실패를 숨기지
   않고 elapsed-time과 결과 경로를 추가해 실제 driver semantics를 고정했다.
4. PostgreSQL direct `PGConnection` import는 driver가 `testRuntimeOnly`인 classpath와
   충돌했다. reflection unwrap으로 compile-time dependency를 늘리지 않고 runtime
   invocation을 검증했다. MariaDB와 CockroachDB도 같은 원칙을 사용했다.
5. 초기 H2 selector를 잘못된 `H2JdbcParallelKeyEnumerationTest`로 지정해 `0 passing`이
   나온 것은 code failure가 아니라 클래스명 오류였다. 실제 클래스
   `JdbcParallelKeyEnumerationTest`를 다시 읽고 올바른 selector로 12/12를 확인했다.
6. `bluetape-flow` helper의 `mutation-check`는 manifest `_run`과 helper `run` key
   불일치로 `coordinator_conflict`를 반환했다. receipt verify/diagnose와 독립 test
   output을 기준으로 삼았고, helper 오류를 mutation 성공 근거로 사용하지 않았다.

## Verification references

- [production handle](../../exposed/jdbc/src/main/kotlin/io/bluetape4k/exposed/jdbc/JdbcParallelKeyEnumeration.kt)
- [H2 generation/lifecycle tests](../../exposed/jdbc/src/test/kotlin/io/bluetape4k/exposed/jdbc/JdbcParallelKeyEnumerationTest.kt)
- [MySQL runtime fixture](../../exposed/jdbc/src/test/kotlin/io/bluetape4k/exposed/jdbc/MySQLJdbcParallelKeyEnumerationTest.kt)
- [MariaDB runtime fixture](../../exposed/jdbc/src/test/kotlin/io/bluetape4k/exposed/jdbc/MariaDBJdbcDriverCancellationTest.kt)
- [CockroachDB runtime fixture](../../exposed/jdbc/src/test/kotlin/io/bluetape4k/exposed/jdbc/CockroachDbJdbcCancellationTest.kt)
- `./gradlew :bluetape4k-exposed-jdbc:detekt --no-daemon --rerun-tasks`
- `git diff --check`와 `docs/manual/**` stable guard

## Future Guidance

1. capability matrix는 source status와 runtime status를 별도 열로 유지한다.
2. cancellation fixture는 active query 관찰, invocation, SQLState/cause 또는 driver가
   반환하는 결과, rollback, 다음 query, pool lease 0을 모두 확인한다.
3. `PENDING`, `N/A`, `UNSUPPORTED`, `FAIL`을 Docker skip이나 timeout 성공으로 바꾸지
   않는다.
4. generation-bound handle과 pool ownership이 public contract로 별도 승인되기 전에는
   `forceAbort`, arbitrary timeout, 공통 `Connection.abort()` fallback을 추가하지 않는다.
5. PR/merge를 진행할 때는 local branch의 검증 결과와 exact head를 fresh-read하고,
   merge는 별도 explicit approval 뒤에만 수행한다.

## Writer DoD (SPW-01~05)

- [x] SPW-01 — Issue/Epic, source/runtime scope, exact driver versions와 non-goals를
  fresh-read했다.
- [x] SPW-02 — context, decision, runtime matrix, TDD/miss, future guard를 채웠다.
- [x] SPW-03 — 한국어 technical register와 `PASS`, `PENDING`, `N/A`,
  `testRuntimeOnly` 및 API token을 보존했다.
- [x] SPW-04 — source, fixture, Gradle output, Detekt result, helper limitation을
  교차 대조했다.
- [x] SPW-05 — 최종 Markdown read-back, terminology audit, diff/manual guard 대상에
  포함한다.
