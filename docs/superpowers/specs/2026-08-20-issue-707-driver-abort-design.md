# Issue #707 JDBC driver 취소·generation-bound 내부 handle 구현 계약

## 문서 상태

- 대상 이슈: [#707](https://github.com/bluetape4k/bluetape4k-exposed/issues/707)
- 상위 Epic: [#659](https://github.com/bluetape4k/bluetape4k-exposed/issues/659)
- 관련 runtime fix: [#697](https://github.com/bluetape4k/bluetape4k-exposed/issues/697), PR #706
- 비-H2 handoff: [#694](https://github.com/bluetape4k/bluetape4k-exposed/issues/694)
- 기준 base: `develop` `9fda4b0984d30d9e0f4514281e663d4bd4221e04`
- 구현 branch: `feat/issue-707-generation-handle`
- worktree: `.worktrees/design-issue-707-driver-abort`
- 분류: **Type A — 내부 lifecycle 기능과 backend runtime evidence**
- 안정 manual: `docs/manual/**` `1.12.1` 불변
- 별도 보류: public driver adapter, generic timeout, `Connection.abort()` fallback, PR/merge

이번 문서는 이전 Type D 조사·설계 결과를 구현 slot의 source/runtime evidence로 갱신한다.
public API/ABI를 확장하지 않고 production 내부 handle과 test-only runtime fixture만
추가했다. 공통 강제 abort의 의미와 pool ownership은 별도 설계가 필요한 비목표다.

## 문제 정의와 결정

PR #706은 취소 요청 뒤 child transaction·JDBC connection이 실제로 닫힐 때까지
`CountDownLatch`를 기다리는 계약을 고정했다. 외부 JDBC driver가 interrupt를 무시해도
generic timeout이나 공통 `forceAbort`로 terminal barrier를 우회하지 않는다.

이번 구현은 다음 두 가지를 고정한다.

1. 각 child에 monotonic generation을 가진 internal handle을 만들고, active JDBC
   `Connection`과 Exposed `JdbcPreparedStatementApi`를 `AtomicReference`로 추적한다.
2. driver capability는 source 확인과 real runtime fixture를 분리한다. cancel acknowledgement,
   rollback, 다음 query recovery, pool lease release를 한 row의 필수 관찰로 삼는다.

`JdbcParallelKeyEnumerationOptions`의 public field와 기본 sequential loader는 변경하지
않는다. internal test-source overload만 handle을 관찰할 수 있도록 추가하며
`@JvmSynthetic`으로 JVM 호출 surface를 제한한다.

## 조사 및 runtime 근거

확인한 exact dependency/version은 PostgreSQL JDBC `42.7.13`, MySQL Connector/J
`9.7.0`, MariaDB Connector/J `3.5.10`, CockroachDB image `v25.4.14`와 repository의
pgjdbc 경로다. source capability와 runtime 결과를 같은 PASS로 합치지 않았다.

| backend/driver | operation과 scope | runtime evidence | 상태와 경계 |
| --- | --- | --- | --- |
| PostgreSQL / pgjdbc `42.7.13` | `PGConnection.cancelQuery()` query scope | active `pg_sleep(30)`을 취소하고 SQLState `57014`, 명시적 `rollback()`, `SELECT 1`, Hikari `active == 0` 확인 | **PASS** — query cancel이며 `Connection.abort()` 증거는 아님 |
| MySQL / Connector/J `9.7.0` | `Statement.cancel()` query scope; driver source는 native session에서 `KILL QUERY` 전송 | `PROCESSLIST`에서 `SLEEP(30)`을 관찰한 뒤 `Statement.cancel()`을 호출하고, 실제 종료를 확인했다. Connector/J는 이 경우 예외 대신 `SLEEP()` 결과 `1`을 반환할 수 있으며, rollback·`SELECT 1`·tracker `active == 0`을 확인 | **PASS** — source claim과 실제 effect를 모두 확인 |
| MariaDB / Connector/J `3.5.10` | driver-specific `Connection.cancelCurrentQuery()` query scope | `PROCESSLIST` active query를 관찰하고 runtime-only driver를 reflection unwrap해 `cancelCurrentQuery()`를 호출했다. 종료 결과, rollback·`SELECT 1`·tracker `active == 0`을 확인 | **PASS** — MySQL 결과를 상속하지 않는 별도 fixture |
| CockroachDB `v25.4.14` + pgjdbc `42.7.13` | PostgreSQL-wire `PGConnection.cancelQuery()` query scope | `SHOW QUERIES`에서 `pg_sleep`를 관찰한 뒤 cancel을 호출하고 SQLState `57014`, rollback·`SELECT 1`·tracker `active == 0`을 확인 | **PASS** — Cockroach server fixture로 별도 증명 |

핵심 fixture는 다음 source에 있다.

- [generation-bound H2 lifecycle tests](../../../exposed/jdbc/src/test/kotlin/io/bluetape4k/exposed/jdbc/JdbcParallelKeyEnumerationTest.kt)
- [MySQL cancel/recovery test](../../../exposed/jdbc/src/test/kotlin/io/bluetape4k/exposed/jdbc/MySQLJdbcParallelKeyEnumerationTest.kt)
- [MariaDB cancel/recovery test](../../../exposed/jdbc/src/test/kotlin/io/bluetape4k/exposed/jdbc/MariaDBJdbcDriverCancellationTest.kt)
- [CockroachDB cancel/recovery test](../../../exposed/jdbc/src/test/kotlin/io/bluetape4k/exposed/jdbc/CockroachDbJdbcCancellationTest.kt)
- [production internal handle](../../../exposed/jdbc/src/main/kotlin/io/bluetape4k/exposed/jdbc/JdbcParallelKeyEnumeration.kt)

MariaDB와 CockroachDB test는 `testRuntimeOnly` driver classpath를 보존하기 위해
reflection unwrap을 사용한다. compile-time 직접 import가 필요하지 않으며, runtime에서
실제 method invocation이 성공해야 PASS가 된다.

## 채택한 lifecycle 계약

### Generation-bound registration

```text
ChildHandle {
  generation: monotonic Long
  activeConnection: AtomicReference<Registration<Connection>?>
  activeStatement: AtomicReference<Registration<JdbcPreparedStatementApi>?>
}
```

- child transaction이 시작되면 underlying `java.sql.Connection`을 등록한다.
- `StatementInterceptor.afterStatementPrepared`에서 현재 Exposed JDBC statement를
  등록하고 `afterExecution`에서 같은 object identity만 제거한다.
- statement 실패 시 Exposed가 `afterExecution`을 호출하지 않을 수 있으므로 다음
  statement 등록은 이전 registration을 교체한다. 늦은 callback은 identity 비교 때문에
  새 statement를 제거하지 못한다.
- transaction body의 `finally`에서 interceptor, statement, connection을 정리한다.
  이 정리 시점은 transaction wrapper의 commit/rollback 결과를 terminal로 선언하는
  것이 아니며, 기존 child completion/latch 경계는 그대로 유지한다.
- generation이 다르거나 이미 clear된 registration은 다른 child가 재대여한 pool
  connection을 대상으로 삼을 수 없다. synthetic H2 test가 stale registration과
  exact identity clear를 검증한다.

### Terminal 및 ownership 경계

```text
cancel request -> driver acknowledgement (선택적 관찰)
transaction rollback/close -> connection lease release
child completion latch -> parent terminal barrier
```

현재 구현은 query cancel adapter나 abort worker를 호출하지 않는다. caller-owned
executor를 닫지 않으며, generic timeout·session kill·Hikari eviction·TCP close를
fallback으로 추가하지 않는다.

## 불변 경계와 비목표

- `JdbcParallelKeyEnumerationOptions`에 `forceAbort`, timeout, driver callback을
  추가하지 않는다. defaulted data-class field는 constructor/copy/component ABI를
  바꿀 수 있다.
- `Statement.cancel()`을 모든 driver의 강제 연결 종료로 해석하지 않는다.
- `Connection.abort(Executor)`를 transaction cleanup보다 먼저 완료되는 terminal signal로
  취급하지 않는다.
- SQL session kill, query-id 추적, pool-wide close, 자동 retry를 generic fallback으로
  넣지 않는다.
- public API, `VirtualFuture`, executor 소유권, 기본 sequential loader,
  `docs/manual/**`, dependency/catalog/workflow를 변경하지 않는다.
- 이번 slot에서는 public driver-specific adapter와 PR/merge를 실행하지 않는다.

## 검증 matrix

| 검증 | 기대 | 현재 evidence |
| --- | --- | --- |
| generation stale registration | 다른 generation 또는 이전 identity의 clear가 새 registration을 지우지 않음 | H2 `JdbcParallelKeyEnumerationTest` generation test, handle integration test |
| H2 lifecycle regression | child/permit/latch/transaction cleanup 유지 | `JdbcParallelKeyEnumerationTest`: **12/12** |
| PostgreSQL query cancel | SQLState, rollback, next query, lease 0 | `PostgreSQLJdbcParallelKeyEnumerationTest`: **8/8** |
| MySQL query cancel | active query effect, rollback, next query, lease 0 | `MySQLJdbcParallelKeyEnumerationTest`: **12/12** |
| MariaDB query cancel | driver-specific invocation, rollback, next query, lease 0 | `MariaDBJdbcDriverCancellationTest`: **1/1** |
| CockroachDB query cancel | server visibility, pgjdbc cancel, rollback, next query, lease 0 | `CockroachDbJdbcCancellationTest`: **1/1** |
| static analysis | changed production/test Kotlin clean | `:bluetape4k-exposed-jdbc:detekt` **BUILD SUCCESSFUL** |

모든 Testcontainers backend test는 서로 다른 Gradle invocation으로 순차 실행했다.
Docker unavailable을 PASS로 승격하지 않았고, timeout 성공만으로 cancellation을
주장하지 않았다.

## 수용 기준 상태

- [x] 네 backend capability matrix에 exact version, operation, ownership,
  rollback/recovery, active lease, 실제 runtime evidence를 기록했다.
- [x] generation-bound internal connection/statement handle과 stale registration
  방지 test를 production lifecycle에 연결했다.
- [x] generic `forceAbort`/임의 timeout/`Connection.abort()` fallback과 public
  `JdbcParallelKeyEnumerationOptions` ABI를 변경하지 않았다.
- [x] MySQL, MariaDB, CockroachDB fixture는 PostgreSQL 결과를 상속하지 않고 각각
  실제 driver/server invocation을 수행한다.
- [x] H2/PG/MySQL/MariaDB/CockroachDB 및 JDBC detekt를 순차 fresh run으로 확인했다.
- [x] `docs/manual/**`는 변경하지 않았고, 문서·review·lesson artifact를 현재 source와
  결과에 맞춰 갱신한다.
- [~] public driver-specific adapter, exact-head PR/CI, merge/issue close는 별도
  권한·설계 gate가 필요하므로 이번 구현 slot의 비목표다.

## 설계 및 writer gate

- [x] SPW-01 — Issue/Epic/base, source/runtime scope, exact driver versions와
  non-goals를 fresh-read했다.
- [x] SPW-02 — capability matrix, generation lifecycle, ownership, terminal barrier,
  alternatives, acceptance를 고정했다.
- [x] SPW-03 — 한국어 technical register를 적용하고 `Statement.cancel`,
  `Connection.abort`, `PENDING`, `N/A`, `PASS` token을 보존했다.
- [x] SPW-04 — production source, Testcontainers fixture, Gradle output과 기존
  #697/#694 lifecycle contract를 대조했다.
- [x] SPW-05 — 표·상태·링크·checklist를 최종 read-back하고 terminology audit 대상에
  포함한다.

## 판정

**IMPLEMENTED / LOCAL-VERIFIED / DELIVERY-PENDING** — production public API를
확장하지 않은 내부 handle과 네 backend runtime evidence는 구현·검증되었다. 다음
delivery gate는 current head/CI/review를 fresh-read한 뒤 별도 PR 생성 및 merge 승인을
받는 단계이며, 이 문서는 그 외부 mutation을 수행하지 않는다.
