# Issue #698 MySQL 8 JDBC driver·pool·isolation conformance 설계

## 문서 상태

- 대상 이슈: [#698](https://github.com/bluetape4k/bluetape4k-exposed/issues/698)
- 상위 이슈: [#694](https://github.com/bluetape4k/bluetape4k-exposed/issues/694)
- Epic: [#659](https://github.com/bluetape4k/bluetape4k-exposed/issues/659)
- Stacked train slot: #698 (PostgreSQL 선행 slot #696 merge 후)
- 기준 base: `develop` `ea19b9e0c6d5135d2447c9a95435c85c1127e3b3`
- 구현 branch: `test/issue-698-mysql`
- worktree: `.worktrees/issue-698-mysql`
- 분류: Type A — 실제 MySQL 8 Testcontainers/JDBC conformance와 다층 검증을 포함하는
  backend-specific test slice
- 설계 상태: 사용자 승인 완료

## 문제 정의와 목표

`parallelJdbcKeyEnumeration`은 호출자가 나눈 PK range를 Virtual Thread와 독립
JDBC transaction으로 열거하는 opt-in helper다. H2와 PostgreSQL fixture는 range
ordering, sparse ID, connection lease 상한, transaction isolation 경계를 검증하지만,
MySQL 8 Connector/J와 InnoDB에서 같은 계약이 성립하는지는 아직 증거가 없다.

이 slot은 MySQL 8 실제 driver·Hikari pool·InnoDB transaction으로 다음을 고정한다.

1. sparse ID와 인접한 disjoint range의 ordering·no-duplicate parity;
2. `poolSize = 1, 2, 4`, `maxConcurrency = 2`의 active lease 상한과 반환;
3. `READ_COMMITTED`의 committed mutation 관찰과 MySQL 기본
   `REPEATABLE_READ` 기준 데이터 경계;
4. connection lease fault와 transaction statement failure의 원인·rollback·cleanup;
5. caller-owned executor의 수명과 empty range의 무연결 실행.

이 결과는 MySQL 8 fixture에서 관찰한 conformance evidence다. production pool
설정, 전체 MySQL 호환성, cross-driver 성능 우열, 단일 읽기 일관성 기준을 공개 API
계약으로 확장하지 않는다.

## 현재 근거와 불변 경계

- `exposed/jdbc/src/main/kotlin/io/bluetape4k/exposed/jdbc/JdbcParallelKeyEnumeration.kt`
  는 production 구현과 public API의 기준이다. 이 slot에서는 수정하지 않는다.
- `exposed/jdbc/src/test/kotlin/io/bluetape4k/exposed/jdbc/JdbcParallelKeyEnumerationTest.kt`
  는 H2의 공통 range·concurrency·failure contract를 이미 검증한다.
- `exposed/jdbc/src/test/kotlin/io/bluetape4k/exposed/jdbc/PostgreSQLJdbcParallelKeyEnumerationTest.kt`
  는 #696에서 `Containers.Postgres`, Hikari, test-only `DataSource` decorator와
  barrier 기반 isolation fixture를 확립했다. MySQL fixture는 이 증거 schema를
  backend-specific 파일로 재사용한다.
- `exposed/jdbc-tests/src/main/kotlin/io/bluetape4k/exposed/tests/Containers.kt`의
  `Containers.MySQL8`과 `TestDB.MYSQL_V8`을 사용한다. 새 image tag, credential,
  container launcher, workflow job을 만들지 않는다.
- `TestDB.MYSQL_V8.connection()`은 기존 Connector/J 옵션
  (`allowPublicKeyRetrieval`, `useCursorFetch`, UTC, batch 옵션)를 소유한다.
  fixture는 이 URL·user·password·driver를 Hikari에 전달해 공용 설정과 별도 URL의
  drift를 막는다.
- canonical `develop`은 clean이고 H2 baseline `JdbcParallelKeyEnumerationTest`
  8/8이 통과했다. Docker Server `29.2.1`은 사용 가능하지만 MySQL targeted test는
  구현 전이라 아직 실행 증거가 아니다.
- `docs/manual/**`는 안정 release `1.12.1`의 source of truth이므로 수정하지 않는다.

## 외부 동작 근거

MySQL 8 InnoDB의 기본 격리 수준은 `REPEATABLE READ`이며, 같은 transaction의
plain `SELECT`는 첫 read가 만든 기준 데이터를 재사용한다. 따라서 이
slot은 `READ_COMMITTED`와 `REPEATABLE_READ`를 명시적으로 비교한다. `SERIALIZABLE`은
autocommit이 꺼진 plain `SELECT`가 locking read로 변환될 수 있어 writer barrier가
driver·server lock timing에 종속된다. 이 slot은 이를 보편 계약으로 주장하지 않고
`N/A — 별도 lock/cancellation 후속 범위`로 기록한다.

- [MySQL 8.0 Transaction Isolation Levels](https://dev.mysql.com/doc/refman/8.0/en/innodb-transaction-isolation-levels.html)
- [MySQL 8.0 SET TRANSACTION](https://dev.mysql.com/doc/refman/8.0/en/set-transaction.html)
- [Connector/J Configuration Properties](https://dev.mysql.com/doc/connector-j/en/connector-j-reference-configuration-properties.html)

## 채택한 접근

### MySQL 전용 test class

`MySQLJdbcParallelKeyEnumerationTest`를
`exposed/jdbc/src/test/kotlin/io/bluetape4k/exposed/jdbc/`에 추가한다.
PostgreSQL 테스트와 test helper를 억지로 parameterize하지 않고 driver별 fixture를
분리한다. backend 장애, 격리 차이, SQLState를 한 파일 안에서 구분할 수 있고,
PostgreSQL 선행 slot의 diff를 다시 열지 않아 stacked train 경계가 보존된다.

fixture 책임은 다음과 같다.

1. `Containers.MySQL8`이 시작된 뒤 Hikari `maximumPoolSize`, `minimumIdle = 0`,
   bounded `connectionTimeout`, test pool name을 설정한다.
2. Hikari를 test-only `TrackingDataSource`로 감싸 `getConnection()` 획득,
   `Connection.close()` 반환, active/peak lease를 계측한다.
3. 고유한 `LongIdTable` 이름으로 8행을 삽입하고 2행·6행을 삭제해 sparse seed를
   만든다. `use {}`와 `finally`에서 schema drop, datasource close, executor close를
   보장한다.
4. `Database.connect(trackingDataSource)`로 helper가 실제 Hikari lease를 사용하게
   하며 `TestDB.db`의 DriverManager 연결을 pool evidence에 섞지 않는다.
5. executor를 helper에 주입하고 호출 전·후 `isShutdown`을 확인한다. helper가 caller
   executor를 닫으면 실패시킨다.

### Test matrix

| 계약 | 실행 | 기대 결과 |
| --- | --- | --- |
| sparse ordering | `[null, 5)`, `[5, null)`와 순차 `ORDER BY id` 비교 | 같은 정렬 ID, 중복 없음 |
| range validation | overlap/reverse/empty | connection side effect 전 `IllegalArgumentException`, empty는 lease 0 |
| pool lease | pool `1/2/4`, `maxConcurrency=2`, range 4개 | peak `<= min(pool, 2)`, active 0 |
| `READ_COMMITTED` | 첫 SELECT 뒤 writer가 row 9 commit, 두 번째 SELECT | row 9 관찰 가능 |
| `REPEATABLE_READ` | 같은 barrier와 기준 데이터 | row 9 미관찰 |
| lease fault | 모든 lease 요청에 transient connection exception | 원인 chain 보존, active 0, executor caller-owned |
| statement/transaction fault | unique payload 충돌을 reader transaction 안에서 발생 | SQL integrity 원인 보존, transaction rollback, 후속 조회 정상 |

`distinct()`로 결과를 정규화하지 않는다. 중복 또는 순서 불일치는 직접 실패해야
한다. mutation assertion은 range 전체가 하나의 기준 데이터를 공유한다는 주장이 아니라,
명시한 isolation에서 두 statement가 어떻게 관찰되는지만 검증한다.

### Failure injection과 retry 경계

lease fault는 `TrackingDataSource.getConnection()`에서 `SQLTransientConnectionException`
을 발생시키는 test-only 경로다. Exposed transaction retry가 이 예외를 재시도한
뒤에도 최종 failure의 cause chain과 cleanup이 유지되는지 확인한다. 이 테스트는
Hikari의 실제 timeout 숫자나 운영 pool 권고를 측정하지 않는다.

transaction fault는 test table의 payload unique index에 이미 존재하는 값을 다시
삽입해 MySQL `SQLIntegrityConstraintViolationException`을 유도한다. 이 오류는
transient retry 성공으로 변환하지 않고 statement/transaction rollback 경계와
후속 read 가능성을 검증한다. network proxy, TCP reset, 실제 query cancellation은
Issue #697 또는 별도 network 후속 범위다.

## 고려한 대안

### A. MySQL 전용 테스트 파일 — 채택

기존 PostgreSQL 증거 schema를 복사하되 URL·driver·isolation expectation·SQLState만
MySQL에 맞춘다. 변경 파일이 한 backend에 집중되고, Testcontainers 실패와
production defect를 쉽게 분류할 수 있다.

### B. PostgreSQL/MySQL parameterized matrix로 기존 파일 리팩터링 — 거부

두 driver의 default isolation과 lock semantics가 달라 공통 assertion에 조건문이
늘어난다. 이미 merge된 PostgreSQL slot의 regression surface와 stacked diff가
불필요하게 커진다.

### C. 공통 test fixture 추출 — 거부

`TrackingDataSource`, proxy lifecycle, mutation barrier를 shared test module로
옮기면 재사용성은 높지만, 이번 slot은 production/API 변화 없이 하나의 backend를
닫는 것이 목적이다. 공통 추출은 #697 cancellation 또는 cross-driver benchmark에서
실제 중복이 확인될 때 별도 설계로 한다.

## 실패 모드와 방어

| 실패 모드 | 방어와 판정 |
| --- | --- |
| MySQL container가 시작되지 않음 | `Assumptions` skip과 Docker/CI unavailable을 구분하고 MySQL GREEN으로 해석하지 않음 |
| pool lease가 상한을 넘음 | test-only peak counter가 즉시 실패; production throttling을 추가하지 않음 |
| pool size 1에서 대기 hang | 모든 latch와 transaction barrier에 bounded timeout, thread dump 원인 기록 |
| connection fault가 retry에 삼켜짐 | 모든 lease 요청 fault와 최종 cause chain·active 0 assertion |
| unique statement fault가 commit됨 | 후속 transaction에서 row count/seed를 다시 읽어 rollback을 확인 |
| `REPEATABLE_READ`가 mutation을 잘못 관찰함 | 첫 read 전 writer를 허용하지 않는 barrier와 명시 isolation 설정을 사용 |
| executor/datasource/schema 누수 | `use`/`finally`, idempotent close proxy, post-test active 0 assertion |
| backend 특수 동작을 일반화함 | README와 lesson에 관찰 범위·`SERIALIZABLE`/network N/A를 명시 |

## 호환성·범위

- production source, public API, ABI baseline, BOM/catalog, dependency version,
  workflow, Kover configuration은 변경하지 않는다.
- 기본 sequential loader와 기존 H2/PostgreSQL 테스트는 변경하지 않는다.
- 모듈 README EN/KO에는 test evidence와 실행 명령만 추가한다. MySQL 운영 지원
  범위나 cross-driver 성능 우위를 주장하지 않는다.
- `docs/manual/**`와 안정 `1.12.1` manual ref는 유지한다.
- 후속 slot은 #697 실제 cancellation/child transaction/connection lifecycle이며,
  그 뒤 #690 cross-driver benchmark raw JSON·median·grouped chart다.

## 수용 기준과 DoD

- [ ] MySQL 8 Testcontainers/JDBC targeted test가 sparse ordering·no-duplicate parity를 통과한다.
- [ ] pool `1/2/4`와 `maxConcurrency=2`에서 peak·cleanup·caller executor 수명을 검증한다.
- [ ] `READ_COMMITTED`/`REPEATABLE_READ` committed mutation 경계를 deterministic assertion으로 고정한다.
- [ ] lease fault와 unique statement fault의 원인·rollback·cleanup을 검증한다.
- [ ] `exposed/jdbc/README.md`와 `README.ko.md`가 같은 evidence와 N/A 범위를 설명한다.
- [ ] H2 targeted regression, MySQL targeted test, affected JDBC regression, detekt, `git diff --check`가 fresh evidence로 남는다.
- [ ] production/API/ABI/catalog/workflow/`docs/manual/**` diff가 없다.
- [ ] Type A review의 P0=0/P1=0, lesson commit, PR DoD와 exact-head CI가 남는다.

## 검증 명령 초안

```bash
EXPOSED_TEST_DB=H2 \
./gradlew :bluetape4k-exposed-jdbc:test \
  --tests 'io.bluetape4k.exposed.jdbc.JdbcParallelKeyEnumerationTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache \
  --no-parallel --max-workers=1 --console=plain
```

```bash
EXPOSED_TEST_DB=MYSQL_V8 \
TESTCONTAINERS_RYUK_DISABLED=true \
./gradlew :bluetape4k-exposed-jdbc:test \
  --tests 'io.bluetape4k.exposed.jdbc.MySQLJdbcParallelKeyEnumerationTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache \
  --no-parallel --max-workers=1 --console=plain
```

```bash
EXPOSED_TEST_DB=MYSQL_V8 \
TESTCONTAINERS_RYUK_DISABLED=true \
./gradlew :bluetape4k-exposed-jdbc:test :bluetape4k-exposed-jdbc-tests:test \
  --rerun-tasks --no-build-cache --no-configuration-cache \
  --no-parallel --max-workers=1 --console=plain
```

실행 결과는 test count, failure/error, skipped, Docker/driver version, XML 경로를
함께 기록한다. MySQL unavailable은 N/A/PENDING으로 분류하며 H2 통과를 MySQL
통과로 대체하지 않는다.

## SPW-01~05 설계 gate

- [x] SPW-01 — Issue/Epic/선행 head, 구현자·reviewer audience, source path, MySQL
  공식 문서, production/manual 경계와 미검증 항목을 고정했다.
- [x] SPW-02 — 문제, 선택지, fixture ownership, matrix, failure modes, compatibility,
  acceptance, commands와 rollback 범위를 포함했다.
- [x] SPW-03 — 한국어 technical register를 적용하고 `READ_COMMITTED`,
  `REPEATABLE_READ`, `N/A`, `PENDING`, SQLState와 API token을 보존했다.
- [x] SPW-04 — 현재 `JdbcParallelKeyEnumeration`, #696 fixture, `TestDB.MYSQL_V8`,
  H2 8/8 evidence와 공식 MySQL 동작을 대조했다.
- [x] SPW-05 — Markdown read-back에서 heading/table/code fence/link와 범위 문장을
  확인했다. 설계 review와 plan review가 끝나기 전 구현을 시작하지 않는다.
