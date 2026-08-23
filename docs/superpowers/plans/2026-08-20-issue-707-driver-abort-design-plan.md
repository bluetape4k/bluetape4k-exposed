# Issue #707 JDBC driver 취소·generation-bound 내부 handle 실행 계획

> 이 계획은 Type D 조사·설계에서 승인된 범위를 Type A 내부 lifecycle 구현과
> backend runtime evidence로 확장한 실행 기록이다. public API, generic timeout,
> `Connection.abort()` fallback, PR/merge는 이번 slot의 비목표다.

## 기준과 허용 범위

- base: `develop` `9fda4b0984d30d9e0f4514281e663d4bd4221e04`
- branch/worktree: `feat/issue-707-generation-handle` /
  `.worktrees/design-issue-707-driver-abort`
- live issue: #707, Epic #659, milestone `2.0.0`, assignee `debop`
- 허용 파일: `exposed/jdbc/src/main/**`의 internal lifecycle, 해당 test fixture,
  `exposed/jdbc/build.gradle.kts` test dependency, `docs/superpowers/**`,
  `docs/review/**`, `docs/lessons/**`
- 금지: public/API/ABI, timeout/abort fallback, workflow/catalog, `docs/manual/**`
  `1.12.1`, PR/merge/issue mutation

## Task 0 — root-cause와 ownership preflight — 완료

1. `JdbcParallelKeyEnumeration.kt`의 child future/latch/permit/cancel 경계를 읽었다.
2. Hikari tracker와 #694 PostgreSQL/MySQL fixture의 active lease 관찰 지점을
   확인했다.
3. `JdbcParallelKeyEnumerationOptions`의 constructor/copy/component ABI를 보존할
   public field 금지를 확정했다.

**결과:** generic timeout/abort로 #697 terminal barrier를 우회하지 않으며, child별
   active statement/connection identity와 generation이 필요하다는 근거를 확보했다.

## Task 1 — capability source ledger — 완료

1. PostgreSQL JDBC `42.7.13`, MySQL Connector/J `9.7.0`, MariaDB Connector/J
   `3.5.10`, CockroachDB image `v25.4.14`와 pgjdbc 경로를 exact version으로
   기록했다.
2. `Statement.cancel`, driver-specific cancel, `Connection.abort(Executor)`를
   query/connection scope와 destructive 여부로 분리했다.
3. 공식 API/source와 local capability 확인을 runtime fixture 결과와 분리했다.

**결과:** source capability는 runtime PASS의 충분조건이 아니며, 네 backend에 별도
fixture가 필요하다는 규칙을 spec에 반영했다.

## Task 2 — generation-bound production handle — 완료

1. child마다 monotonic `Long` generation을 발급하는
   `JdbcEnumerationChildHandle`을 추가했다.
2. underlying `Connection`과 `JdbcPreparedStatementApi`를 registration 객체로
   보관하고, exact identity와 `AtomicReference` CAS로 늦은 clear를 차단했다.
3. `StatementInterceptor`를 child transaction에 등록해 prepare/execute lifecycle을
   연결했다. statement 실패 뒤 다음 statement가 실행될 수 있도록 active registration은
   교체하고, 이전 callback은 identity 비교로 무해하게 만들었다.
4. transaction body `finally`에서 interceptor/statement/connection을 지우고, 기존
   permit/latch/future cleanup과 caller-owned executor 경계를 유지했다.

**검증:** H2 generation stale registration과 transaction integration test가 통과했다.

## Task 3 — backend runtime fixture — 완료

1. MySQL `Statement.cancel()` fixture에서 `PROCESSLIST` active query, 실제 query
   종료, rollback, 다음 query, tracker lease를 확인했다. Connector/J 특성상
   `SLEEP()` 결과 `1`을 반환할 수 있어 예외만을 성공 조건으로 사용하지 않았다.
2. MariaDB `cancelCurrentQuery()` fixture를 별도 추가하고 runtime-only driver를
   reflection unwrap해 invocation을 증명했다.
3. CockroachDB `CockroachServer` fixture를 추가하고 `SHOW QUERIES` 관찰과 pgjdbc
   `PGConnection.cancelQuery()`를 별도 검증했다.
4. CockroachDB Testcontainers alias만 testImplementation으로 추가했으며, central
   catalog나 production dependency를 변경하지 않았다.

**검증:** 각 Testcontainers backend는 독립 Gradle invocation으로 순차 실행했다.

## Task 4 — test/compile/static verification — 완료

| 명령 | 결과 |
| --- | --- |
| `:bluetape4k-exposed-jdbc:test --tests JdbcParallelKeyEnumerationTest` | **12/12**, `BUILD SUCCESSFUL` |
| `:bluetape4k-exposed-jdbc:test --tests MySQLJdbcParallelKeyEnumerationTest` | **12/12**, `BUILD SUCCESSFUL` |
| `:bluetape4k-exposed-jdbc:test --tests PostgreSQLJdbcParallelKeyEnumerationTest` | **8/8**, `BUILD SUCCESSFUL` |
| `:bluetape4k-exposed-jdbc:test --tests MariaDBJdbcDriverCancellationTest` | **1/1**, `BUILD SUCCESSFUL` |
| `:bluetape4k-exposed-jdbc:test --tests CockroachDbJdbcCancellationTest` | **1/1**, `BUILD SUCCESSFUL` |
| `:bluetape4k-exposed-jdbc:detekt --no-daemon --rerun-tasks` | **BUILD SUCCESSFUL** |

TDD 기록도 보존한다. 구현 전 handle symbol 부재로 test compile이 실패하는 RED를
확인했고, 구현 후 H2 integration이 GREEN이 됐다. MySQL 첫 runtime 시도는
`SLEEP()`이 예외 없이 결과 `1`을 반환해 assertion이 실패했으며, driver의 실제
semantics를 반영해 결과/elapsed-time 경로를 보강한 뒤 12/12가 통과했다.

## Task 5 — performance/stability와 code review — 완료

- generation registration은 child별 `AtomicReference` 두 개와 prepare/execute callback만
  사용하며, 새로운 global lock·unbounded queue·반복 retry를 추가하지 않았다.
- virtual thread child는 기존 transaction executor 경계를 유지하고, caller-owned
  executor를 닫지 않는다. transaction body와 future completion의 모든 exit path에서
  statement/interceptor/connection/permit/latch cleanup을 확인했다.
- 네 backend fixture는 active query polling, cancellation invocation, rollback,
  recovery, pool lease 0을 모두 관찰한다. timeout 성공만으로 PASS를 만들지 않았다.
- current implementation review artifact에서 six perspective lane과 integration
  결과를 기록하며, 최종 P0=0/P1=0을 확인한다. P2는 public adapter 미구현과 fixture
  helper 중복처럼 이번 scope 밖인 항목만 rationale과 함께 남긴다.

## Task 6 — 문서·lesson·delivery 경계 — 완료(외부 delivery 보류)

1. spec, plan, lesson을 현재 source/runtime evidence와 일치하도록 갱신한다.
2. current review artifact를 추가하고 SPW-01~05, terminology audit, diff/manual
   guard evidence를 기록한다.
3. Lore commit 직전의 local diff와 evidence를 재검증했다. PR 생성·CI 대기·merge·Issue
   close는 별도 explicit authority 없이는 실행하지 않는다.

## Rollback/stop

- public `forceAbort`, 임의 timeout, pool-wide close가 요구되면 이 branch에서 범위를
  확장하지 않고 별도 API/ABI 설계로 분리한다.
- runtime driver semantics가 재현되지 않으면 해당 row를 `PENDING/N/A`로 낮추고
  source claim만으로 PASS를 유지하지 않는다.
- generation stale clear, transaction cleanup, caller-owned executor 보존 중 하나가
  깨지면 code review와 문서를 먼저 되돌려 원인을 고정한다.

## Plan DoD

- [x] Task 0 ownership/root-cause evidence
- [x] Task 1 four-driver capability ledger
- [x] Task 2 generation-bound production internal handle
- [x] Task 3 MySQL/MariaDB/CockroachDB runtime fixture와 기존 PG/H2 regression
- [x] Task 4 순차 Testcontainers, targeted tests, Detekt, TDD red/green evidence
- [x] Task 5 performance/stability scan과 P0/P1 convergence review artifact
- [~] Task 6 문서·lesson·review와 local verification은 완료했다. Lore commit은
  다음 단계이며, PR/CI/merge/Issue close는 별도 권한 대기

## 현재 판정

**IMPLEMENTED / LOCAL-VERIFIED / DELIVERY-PENDING** — 구현과 local evidence는
완료되었고, external delivery mutation만 남았다. 다음 단계는 commit 후 fresh head/CI/
review metadata를 읽고 PR 생성 권한이 명시될 때 delivery gate를 여는 것이다.
