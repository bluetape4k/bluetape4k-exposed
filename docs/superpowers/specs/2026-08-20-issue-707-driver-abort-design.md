# Issue #707 JDBC driver 강제 abort 계약 설계

## 문서 상태

- 대상 이슈: [#707](https://github.com/bluetape4k/bluetape4k-exposed/issues/707)
- 상위 Epic: [#659](https://github.com/bluetape4k/bluetape4k-exposed/issues/659)
- 관련 runtime fix: [#697](https://github.com/bluetape4k/bluetape4k-exposed/issues/697), PR #706
- 비-H2 handoff: [#694](https://github.com/bluetape4k/bluetape4k-exposed/issues/694)
- 기준 base: `develop` `9fda4b0984d30d9e0f4514281e663d4bd4221e04`
- 구현 branch: `design/issue-707-driver-abort`
- worktree: `.worktrees/design-issue-707-driver-abort`
- 분류: **Type D — driver capability 조사·계약 설계**
- 안정 manual: `docs/manual/**` `1.12.1` 불변

## 문제 정의

PR #706은 취소 요청 뒤 child transaction·JDBC connection이 실제로 닫힐 때까지
`CountDownLatch`를 기다리는 계약을 고정했다. 그러나 외부 JDBC driver가 interrupt를
무기한 무시하면 caller가 영원히 기다릴 수 있다. 이 동작을 일반적인
`forceAbort` 또는 임의 timeout으로 숨기면 `Statement.cancel`, `Connection.abort`,
서버 측 query cancel의 의미와 연결 소유권이 driver마다 달라 안전하지 않다.

현재 child lifecycle handle은 future/latch/permit만 보유하고 active
`Statement`·`Connection` identity를 노출하지 않는다. 연결이 Hikari pool로 반환된
뒤 stale handle이 새 child의 연결을 abort하는 race도 방지해야 한다.

## 조사 근거

현재 dependency cache와 source를 줄 단위로 확인했다.

| driver | 확인한 capability | 이번 설계에서의 의미 |
| --- | --- | --- |
| PostgreSQL JDBC 42.7.13 | `org.postgresql.PGConnection.cancelQuery()` | active query cancel 후보. 연결 강제 종료와 동일하다고 해석하지 않는다. |
| MySQL Connector/J 9.7.0 | `JdbcStatement.cancel()`; 표준 `Connection.abort(Executor)`는 연결 종료 경계 | statement cancel과 destructive abort를 분리한다. `queryTimeoutKillsConnection` 정책을 generic 계약으로 복사하지 않는다. |
| MariaDB Connector/J 3.5.10 | `Statement.cancel()`, `Connection.cancelCurrentQuery()`, `Connection.abort(Executor)` | driver-specific adapter 후보지만 MySQL과 같은 의미라고 일반화하지 않는다. |
| CockroachDB + pgjdbc | repository가 pgjdbc를 사용하지만 서버 취소·JDBC 지원 동작은 별도 검증 필요 | PostgreSQL 행을 상속하지 않고 real container 또는 `N/A`로 고정한다. |

외부 API 의미는 다음 공식 문서와 구현을 기준으로 한다.

- [Java `Statement.cancel`](https://docs.oracle.com/en/java/javase/25/docs/api/java.sql/java/sql/Statement.html#cancel())
- [Java `Connection.abort`](https://docs.oracle.com/en/java/javase/25/docs/api/java.sql/java/sql/Connection.html#abort(java.util.concurrent.Executor))
- [pgjdbc `PGConnection`](https://github.com/pgjdbc/pgjdbc/blob/master/pgjdbc/src/main/java/org/postgresql/PGConnection.java)
- [MySQL Connector/J `JdbcStatement`](https://github.com/mysql/mysql-connector-j/blob/release/9.7.0/src/main/user-impl/java/com/mysql/cj/jdbc/JdbcStatement.java)
- [MariaDB Connector/J `Connection`](https://github.com/mariadb-corporation/mariadb-connector-j/blob/master/src/main/java/org/mariadb/jdbc/Connection.java)

## 실행 증거 — 2026-08-23

이번 slot에서는 source capability와 실제 driver 동작을 같은 PASS로 합치지 않았다.
dependency cache에서 확인한 exact version은 PostgreSQL JDBC `42.7.13`, MySQL
Connector/J `9.7.0`, MariaDB Connector/J `3.5.10`이다. CockroachDB는 별도 JDBC
driver alias 없이 Testcontainers module만 확인되며, 실제 연결 경로는 pgjdbc와 서버
동작을 함께 증명해야 한다.

| backend/driver | source capability | runtime evidence | 상태와 경계 |
| --- | --- | --- | --- |
| PostgreSQL / pgjdbc `42.7.13` | `PGConnection.cancelQuery()`, `getBackendPID()` | `PostgreSQLJdbcParallelKeyEnumerationTest.PostgreSQL cancelQuery rolls back cancelled transaction and releases leases`가 active `pg_sleep(30)`을 취소하고 SQLState `57014`를 확인한 뒤 명시적으로 `rollback()`하고 `SELECT 1` recovery 및 Hikari `active == 0`을 확인 | **PASS** — query cancel proof이며 `Connection.abort()` proof가 아님 |
| MySQL / Connector/J `9.7.0` | `JdbcStatement.cancel()` source가 새 native session에서 `KILL QUERY <threadId>`를 전송; 표준 `Connection.abort(Executor)`는 연결 종료 경계 | 기존 lifecycle/rollback/interrupt-ignore 11개 테스트는 PASS지만 `Statement.cancel()`의 active query effect와 recovery fixture는 아직 없음 | **PENDING** — source만으로 runtime PASS를 만들지 않음 |
| MariaDB / Connector/J `3.5.10` | `Connection.cancelCurrentQuery()`, `Statement.cancel()`, `Statement.abort()`, `Connection.abort(Executor)` | 이 저장소에 MariaDB cancel fixture와 직접 driver alias가 없음 | **PENDING/N/A** — MySQL semantics를 상속하지 않음 |
| CockroachDB + pgjdbc | Testcontainers Cockroach module과 pgjdbc 경로는 확인했지만 server-side cancel/query recovery는 별도 계약 | real Cockroach cancel fixture를 실행하지 않음 | **PENDING/N/A** — PostgreSQL PASS를 대체하지 않음 |

H2 targeted lifecycle `10/10`, PostgreSQL class `8/8`, MySQL class `11/11`은 모두
`BUILD SUCCESSFUL`이다. 다만 H2와 기존 PG/MySQL 행은 transaction/lease lifecycle
증거이고, driver cancel capability의 PASS로 승격하지 않는다. PostgreSQL fixture는
다음 명령을 Docker/Testcontainers에서 순차 실행했다.

```bash
./gradlew :bluetape4k-exposed-jdbc:test \
  --tests 'io.bluetape4k.exposed.jdbc.PostgreSQLJdbcParallelKeyEnumerationTest.PostgreSQL cancelQuery rolls back cancelled transaction and releases leases' \
  --no-daemon
./gradlew :bluetape4k.exposed-jdbc:test \
  --tests 'io.bluetape4k.exposed.jdbc.PostgreSQLJdbcParallelKeyEnumerationTest' \
  --no-daemon
```

fixture의 핵심 관찰 지점은
[PostgreSQLJdbcParallelKeyEnumerationTest.kt:51](../../../exposed/jdbc/src/test/kotlin/io/bluetape4k/exposed/jdbc/PostgreSQLJdbcParallelKeyEnumerationTest.kt#L51),
active query polling은
[같은 파일:427](../../../exposed/jdbc/src/test/kotlin/io/bluetape4k/exposed/jdbc/PostgreSQLJdbcParallelKeyEnumerationTest.kt#L427),
runtime-only driver 반사는
[같은 파일:450](../../../exposed/jdbc/src/test/kotlin/io/bluetape4k/exposed/jdbc/PostgreSQLJdbcParallelKeyEnumerationTest.kt#L450)에
있다. 처음에는 `org.postgresql.PGConnection`을 직접 import해 `compileTestKotlin`이
실패했는데, PostgreSQL driver가 `testRuntimeOnly`인 현재 classpath 계약을 유지하도록
reflection 기반 unwrap으로 고쳤고, 수정 후 targeted test와 전체 PG class가 통과했다.

각 evidence row는 `driver/version`, `operation`, `scope`, `destructive 여부`,
`active lease`, `rollback`, `next-query recovery`, `실행 명령`, `status`,
`unsupported/pending 근거`를 보유한다. `PASS`는 해당 row의 모든 필수 관찰을 실제로
확인한 경우에만 사용하며, Docker·권한·driver 부재는 `PENDING` 또는 `N/A`로 남긴다.

## 목표

1. driver별 query cancel, connection abort, rollback, active lease 회수, 다음 query
   recovery를 분리한 capability matrix를 만든다.
2. unsupported capability를 정상 완료나 timeout 성공으로 가장하지 않고 `UNSUPPORTED`,
   `N/A`, `PENDING`, `FAIL`을 구분한다.
3. active statement/connection을 generation identity와 함께 등록·해제하는 lifecycle
   경계를 정의해 pool 반환 후 stale abort를 차단한다.
4. abort 요청은 terminal completion이 아니며, 기존 transaction cleanup latch가 유일한
   terminal barrier라는 규칙을 고정한다.
5. caller-owned child executor를 닫지 않고, abort 작업은 별도 소유권의 bounded executor
   또는 명시적 driver adapter가 책임지도록 한다.

## 불변 경계와 비목표

- 이번 설계 slot에서는 `JdbcParallelKeyEnumerationOptions`에 `forceAbort`, timeout,
  driver callback을 추가하지 않는다. defaulted data-class field는 constructor/copy/
  component ABI를 바꿀 수 있다.
- `java.sql.Statement.cancel()`을 모든 driver에서 강제 abort로 취급하지 않는다.
- `Connection.abort(Executor)`를 transaction 성공·rollback·pool 반환보다 먼저 끝난
  terminal signal로 취급하지 않는다.
- SQL session kill, query-id 추적, Hikari eviction, TCP close를 generic fallback으로
  넣지 않는다. 권한·세대 race·다른 요청 영향이 별도 계약이다.
- public API, `VirtualFuture`, executor 소유권, 기본 sequential loader,
  `docs/manual/**`, dependency/catalog/workflow를 변경하지 않는다.

## 채택 설계

### 1. Capability matrix와 상태 모델

내부 설계 모델은 다음 상태를 구분한다.

```text
UNSUPPORTED -> CANCEL_REQUESTED -> DRIVER_ACKNOWLEDGED
                          \-> ABORT_REQUESTED -> CONNECTION_CLOSED
child transaction cleanup + lease release -> TERMINAL
```

`DRIVER_ACKNOWLEDGED`나 `CONNECTION_CLOSED`만으로 parent 반환을 허용하지 않는다.
실제 transaction wrapper의 `finally`에서 latch가 내려간 뒤에만 terminal이다.

각 capability row는 driver/version, operation, statement/connection scope, destructive
여부, active lease 관찰, rollback, next-query recovery, real fixture 명령, unsupported
근거를 필수 필드로 갖는다.

### 2. Generation-bound active handle

후속 구현이 필요할 때 child마다 다음 internal handle을 만든다.

```text
ChildHandle {
  generation: UUID/monotonic token
  statement: AtomicReference<Statement?>
  connection: AtomicReference<Connection?>
  lifecycle: CountDownLatch
}
```

statement와 connection은 execute 직전 등록하고 transaction/connection close 직후
원자적으로 해제한다. abort adapter는 generation이 일치하는 handle만 대상으로 삼고,
이미 clear되었거나 다른 child가 대여한 connection에는 동작하지 않는다.

### 3. API 경계

첫 PR은 public API를 추가하지 않고 test-only capability probe와 design artifact로
끝낸다. 실제 opt-in adapter가 필요하면 다음 별도 issue에서 driver-specific sealed
adapter를 검토한다.

- PostgreSQL: `PGConnection.cancelQuery` adapter 후보
- MySQL/MariaDB: 각각의 `Statement.cancel`/`cancelCurrentQuery` adapter 후보
- CockroachDB: real fixture가 pgjdbc CancelRequest와 server recovery를 증명할 때만
  PostgreSQL adapter와의 관계를 결정한다.

공통 `forceAbort=true` boolean, 임의 millisecond timeout, unsupported driver의
`close()` fallback은 거부한다.

## 검증 matrix와 실패 계약

| 검증 | 기대 | 미실행/실패 판정 |
| --- | --- | --- |
| synthetic generation race | stale abort 0건, latch terminal 유지 | race/lease 관찰 실패는 FAIL |
| PostgreSQL query cancel | cancel request 후 rollback·lease 0·next SELECT | Docker/driver unavailable은 PENDING; unsupported는 명시 |
| MySQL query cancel | statement cancel의 실제 effect와 connection recovery | timeout만으로 PASS 금지 |
| MariaDB cancel/abort | driver-specific capability를 별도 기록 | MySQL 결과 상속 금지 |
| CockroachDB | pgjdbc와 server cancel semantics를 real fixture로 확인 | 미실행은 N/A/PENDING, PostgreSQL PASS 대체 금지 |
| infinite interrupt-ignore driver | 강제 종료를 주장하지 않음 | 실제 종료까지 대기하는 기존 latch contract 유지 |

`Statement.cancel`이 예외를 던지거나 아무 효과가 없는 경우 원래 child failure와
`suppressed` abort failure를 보존한다. abort worker가 늦어도 transaction cleanup
barrier를 우회하지 않는다.

## 수용 기준 상태

- [~] PostgreSQL/MySQL/MariaDB/CockroachDB capability matrix에 exact version,
  operation, ownership, rollback/recovery, real/unsupported evidence를 기록했다.
  PostgreSQL은 runtime **PASS**, 나머지는 source-only 또는 미실행 **PENDING/N/A**다.
- [x] generic `forceAbort`/임의 timeout을 추가하지 않았고 production diff가 없으며,
  `JdbcParallelKeyEnumerationOptions` public data-class field도 변경하지 않았다.
- [~] generation-bound statement/connection handle과 stale pool lease 방지 규칙은
  후속 implementation contract로 고정했지만, 이 slot에서 synthetic race test나
  production handle은 구현하지 않았다.
- [x] 최소 한 실제 driver fixture와 미실행/PENDING backend row가 같은 evidence schema를
  사용한다.
- [x] `cancelQuery` acknowledgement와 `rollback()`/lease release/next-query recovery
  terminal 경계를 문서와 PostgreSQL test-only fixture에서 분리했다.
- [x] JDBC detekt, affected test/compile, `git diff --check`, terminology audit와 stable
  manual guard를 최종 실행했다. PostgreSQL `8/8`, MySQL `11/11`, H2
  `JdbcParallelKeyEnumerationTest` `10/10`이 모두 통과했고 `docs/manual/**`는 변경되지
  않았다.

## 설계 gate

- [x] SPW-01 — Issue/Epic/base, runtime source, driver class/method와 공식 API source를
  대조했다.
- [x] SPW-02 — capability matrix, lifecycle/ownership, alternatives, failure/N/A와
  acceptance를 고정했다.
- [x] SPW-03 — 한국어 technical register와 `Statement.cancel`, `Connection.abort`,
  `PENDING`, `UNSUPPORTED` token을 보존했다.
- [x] SPW-04 — #697 latch/permit 코드와 local driver bytecode/source를 대조하고
  unsupported claims를 분리했다.
- [x] SPW-05 — 표·상태 그림·checklist·링크를 read-back했다.

## 설계 판정

`PARTIAL / PENDING` — PostgreSQL query-cancel test-only 증거와 네 backend capability
ledger의 책임 경계를 추가했지만, MySQL/MariaDB/Cockroach runtime row와
generation-bound handle implementation은 남아 있다. public API/ABI·driver ownership을
확정하기 전까지 공통 `forceAbort` 구현은 P1 contract blocker이며, 이번 slot은 production
변경 없이 종료한다.
