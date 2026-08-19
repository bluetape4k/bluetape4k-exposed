# Issue #694 PostgreSQL JDBC 첫 slot 실행계획

> **실행 규칙:** 이 계획은 승인된 설계와 7-Tier review(`P0=0, P1=0`)를 그대로
> 실행한다. `$bluetape-workflow`, `$bluetape-kotlin-patterns`,
> `$test-driven-development`, `$bluetape-writer`를 적용한다. 이번 slot은 test-only
> PostgreSQL conformance이며 production/API/ABI/benchmark/chart는 변경하지 않는다.

## 목표와 결과물

PostgreSQL Testcontainers/JDBC에서 PR #695의 bounded Virtual Thread key enumeration
계약을 실제 driver와 Hikari pool로 증명한다.

최종 변경 파일은 다음 두 개로 제한한다.

| 책임 | 파일 | 변경 |
| --- | --- | --- |
| PostgreSQL conformance fixture와 assertion | `exposed/jdbc/src/test/kotlin/io/bluetape4k/exposed/jdbc/PostgreSQLJdbcParallelKeyEnumerationTest.kt` | 생성 |
| 설계 evidence | `docs/superpowers/specs/2026-08-19-issue-694-postgresql-driver-conformance-design.md`, `docs/superpowers/plans/2026-08-20-issue-694-postgresql-driver-conformance-plan.md`, `docs/review/2026-08-20-issue-694-postgresql-design-review.md` | 생성 |

`exposed/jdbc/src/main/**`, loader module, catalog/BOM, workflow, benchmark/chart,
`docs/manual/**`(`1.12.1`)는 변경하지 않는다. PostgreSQL evidence가 green이면 다음
stacked slot에서 동일 fixture 경계를 MySQL 8에 적용한다.

## Task 0 — 기준 상태와 실행 조건 고정

**Files:** canonical/worktree read-only, live Issue #694/#659

- [x] `develop` `ff85c999`에서 `test/issue-694-postgresql` worktree를 만들었다.
- [x] canonical의 사용자 파일 `TEST_APPLY_PATCH_TMP.txt`를 건드리지 않았다.
- [x] H2 baseline을 다음으로 실행해 `JdbcParallelKeyEnumerationTest` 8/8,
  failure/error 0, `BUILD SUCCESSFUL`을 확인했다.

```bash
./gradlew :bluetape4k-exposed-jdbc:test \
  --tests 'io.bluetape4k.exposed.jdbc.JdbcParallelKeyEnumerationTest' \
  --rerun-tasks --no-parallel --max-workers=1 --console=plain
```

- [x] Issue #694와 Epic #659의 milestone `1.13.0`, `debop`, `test`/
  `performance`/`stacked-pr` 및 선행 PR #695를 live read-back했다.
- [x] 구현 직전 PostgreSQL 조건을 고정한다: Docker/Testcontainers, `EXPOSED_TEST_DB=POSTGRESQL`,
  `TESTCONTAINERS_RYUK_DISABLED=true`. 조건이 없으면 테스트를 성공으로 추정하지
  않고 `N/A`로 기록한다. 로컬 Colima에서는 Testcontainers가 Docker context를
  자동 해석하지 않아 `DOCKER_HOST=unix:///Users/debop/.colima/default/docker.sock`을
  명시했다.

Rollback: 이 task는 read-only이며 canonical branch를 변경하지 않는다.

## Task 1 — conformance 테스트를 먼저 작성한다 (RED/shape)

**File:** `exposed/jdbc/src/test/kotlin/io/bluetape4k/exposed/jdbc/PostgreSQLJdbcParallelKeyEnumerationTest.kt`

### 1-A. 테스트 경계와 fixture

- [x] `AbstractExposedTest`를 상속하고 PostgreSQL이 활성화된 경우에만 실행한다.
  `TestDB.POSTGRESQL in TestDB.enabledDialects()`와 Testcontainers 설정을
  `Assumptions.assumeTrue`로 확인해 H2 PR CI에서는 skip한다.
- [x] `Containers.Postgres`와 기존 PostgreSQLServer driver/url/credentials를
  재사용한다. 새 image tag, container lifecycle, credential log를 추가하지 않는다.
- [x] test마다 고유한 `LongIdTable` 이름을 생성해 클래스가 병렬 실행되어도 schema가
  충돌하지 않게 한다. seed는 8행을 삽입한 뒤 2행과 6행을 삭제해 sparse 결과를 만든다.
- [x] pool/isolation fixture는 Hikari `HikariDataSource`를 감싼 test-only
  `DataSource` decorator로 만들고 `Database.connect(dataSource)`를 명시적으로
  사용한다. `TestDB.db`의 DriverManager connection은 pool peak 증거로 사용하지
  않는다.
- [x] decorator는 `getConnection()` lease 획득/`Connection.close()` 반환을
  `AtomicInteger`로 세고 peak를 기록한다. 같은 connection의 중복 close는 한 번만
  반환으로 계산하며, SQL/예외/metadata는 delegate에 그대로 위임한다.
- [x] fixture의 schema drop, datasource close, custom executor close는 `finally`에서
  수행하고 active lease가 0인지 확인한다. helper가 caller executor를 닫지 않는
  계약은 executor 상태 assertion으로 남긴다.

### 1-B. RED assertions

다음 테스트를 실제 PostgreSQL selector로 먼저 실행한다. 실패하면 production code가
아니라 fixture/계약 assertion의 failure인지 확인한 뒤 최소 수정한다.

```kotlin
@Test fun `PostgreSQL sparse IDs keep sequential and parallel ordering`()
@Test fun `PostgreSQL lease peak is bounded for pool smaller equal and larger than concurrency`()
@Test fun `PostgreSQL injected lease failure preserves JDBC cause and releases leases`()
@Test fun `PostgreSQL READ_COMMITTED observes a committed mutation between statements`()
@Test fun `PostgreSQL SERIALIZABLE keeps weak consistency without duplicate IDs`()
@Test fun `PostgreSQL empty ranges do not acquire a connection`()
```

구체 assertion:

- correctness: public `parallelJdbcKeyEnumeration` 결과와 같은 table의 순차
  `selectAll().orderBy(id)` 결과가 sparse ID·range 순서에서 일치하고, `distinct()`로
  오류를 숨기지 않는다.
- range: `[null, 5)`, `[5, null)` 두 구간에서 각 ID가 한 번만 나타나고,
  overlap/reverse 입력은 connection 획득 전에 `IllegalArgumentException`으로
  끝난다.
- pool: `poolSize = 1, 2, 4`, `maxConcurrency = 2`를 각각 실행한다. 빠른 query는
  세 조합 모두 완료하되 peak lease는 `min(poolSize, maxConcurrency)` 이하이어야
  한다.
- pool failure: Hikari를 감싼 test-only `DataSource`가 실제 query lease 요청마다
  `SQLTransientConnectionException`을 주입한다. Exposed top-level transaction의
  기본 retry가 모든 fault를 소진한 뒤에도 원인 chain을 보존하고 active lease가 0으로
  돌아오는지 확인한다. 이 테스트는 Hikari 자체 timeout 수치나 production pool
  권고를 증명하지 않으며, 실제 timeout은 환경 evidence가 있을 때만 `N/A`가 아니다.
- mutation: reader callback에서 첫 `selectAll()` 뒤 barrier를 열고 writer가 별도
  connection에서 insert/delete를 commit한 뒤 두 번째 `selectAll()`을 실행한다.
  `READ_COMMITTED`는 commit 후 statement 관찰을 허용하고, `SERIALIZABLE`은 삽입을
  보지 않거나 PostgreSQL serialization failure를 반환할 수 있음을 assertion으로
  고정한다. 어느 경우도 전체 결과를 하나의 읽기 기준이라고 주장하지 않는다.
- empty: `ranges = emptyList()`에서 custom tracking datasource lease가 0이고,
  executor가 새 task를 받지 않는지 확인한다.

### 1-C. RED 명령

```bash
EXPOSED_TEST_DB=POSTGRESQL \
TESTCONTAINERS_RYUK_DISABLED=true \
./gradlew :bluetape4k-exposed-jdbc:test \
  --tests 'io.bluetape4k.exposed.jdbc.PostgreSQLJdbcParallelKeyEnumerationTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache \
  --no-parallel --max-workers=1 --console=plain
```

Expected: Docker가 없으면 실행 조건 `N/A`; Docker가 있으면 새 테스트가 compile되고
driver 계약의 첫 실패가 보인다. H2 환경에서는 assumptions로 skip되며 이것을
PostgreSQL GREEN으로 해석하지 않는다.

## Task 2 — fixture를 최소 구현하고 GREEN으로 고정한다

**File:** 같은 PostgreSQL test file (production source 변경 없음)

- [x] `PostgreSQLFixture`를 구현한다: Hikari pool config, `Database.connect`, schema
  create/seed/drop, Hikari close를 한 곳에 둔다. `minimumIdle=0`, test pool name,
  bounded `connectionTimeout`을 명시해 idle lease와 active lease를 구분한다.
- [x] `TrackingDataSource`와 `TrackingConnection`을 구현한다. Java `DataSource`의
  delegate 메서드를 모두 전달하고, `close()`는 `AtomicBoolean`으로 idempotent하게
  peak counter를 감소시킨다. `unwrap`/`isWrapperFor` 결과를 변형하지 않는다.
- [x] pool test의 range reader는 실제 table query를 한 번 실행한 뒤 짧은 delay를
  사용한다. sleep은 connection lease overlap을 만들기 위한 test-only delay로
  한정하고 무한 대기·busy loop를 사용하지 않는다.
- [x] failure fixture는 실제 query 전에 모든 connection lease 요청에 transient fault를
  주입한다. 첫 fault만 주입하면 Exposed retry가 성공으로 복구할 수 있으므로,
  retry-aware assertion으로 최종 failure와 원인 보존을 검증한다.
- [x] mutation writer는 동일 Hikari datasource의 별도 connection에서 실행하고,
  barrier·latch·`AtomicReference<Throwable?>`로 writer commit/failure를 관찰한다.
  모든 latch에는 bounded timeout을 둔다.
- [x] `READ_COMMITTED`/`SERIALIZABLE` 결과와 serialization failure를 구분해 assertion
  한다. PostgreSQL SQLSTATE/예외 chain을 로그에 credential 없이 남긴다.
- [x] test class가 끝날 때 active lease 0, fixture datasource closed, caller executor
  closed를 확인한다. helper가 caller executor를 조기에 닫았으면 실패시킨다.

### GREEN targeted command

```bash
EXPOSED_TEST_DB=POSTGRESQL \
TESTCONTAINERS_RYUK_DISABLED=true \
./gradlew :bluetape4k-exposed-jdbc:test \
  --tests 'io.bluetape4k.exposed.jdbc.PostgreSQLJdbcParallelKeyEnumerationTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache \
  --no-parallel --max-workers=1 --console=plain
```

Expected: PostgreSQL test `6/6` pass, failure/error `0`; serialization failure branch는
허용된 결과를 assertion으로 통과시킨다. 실패하면 `$systematic-debugging` 순서로
container, pool lease, transaction isolation, fixture cleanup을 분리해 수정한다.

## Task 3 — regression·정적 검증

의존하는 Testcontainers/DB 검증은 repository guard에 따라 순차 실행한다.

1. H2 helper regression:

```bash
EXPOSED_TEST_DB=H2 \
./gradlew :bluetape4k-exposed-jdbc:test \
  --tests 'io.bluetape4k.exposed.jdbc.JdbcParallelKeyEnumerationTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache \
  --no-parallel --max-workers=1 --console=plain
```

Expected: 기존 `8/8`, failure/error `0`.

2. PostgreSQL module regression (after targeted GREEN):

```bash
EXPOSED_TEST_DB=POSTGRESQL \
TESTCONTAINERS_RYUK_DISABLED=true \
./gradlew :bluetape4k-exposed-jdbc:test :bluetape4k-exposed-jdbc-tests:test \
  --rerun-tasks --no-build-cache --no-configuration-cache \
  --no-parallel --max-workers=1 --console=plain
```

Expected: module and shared JDBC test source set pass; skipped tests are reported
separately from failure/error.

3. Static/document checks:

```bash
./gradlew :bluetape4k-exposed-jdbc:detekt --no-parallel --max-workers=1 --console=plain
git diff --check
node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs \
  docs/superpowers/specs/2026-08-19-issue-694-postgresql-driver-conformance-design.md \
  docs/superpowers/plans/2026-08-20-issue-694-postgresql-driver-conformance-plan.md \
  docs/review/2026-08-20-issue-694-postgresql-design-review.md
```

Expected: detekt success, diff check clean, terminology findings `0`. `git diff --name-only`
로 production source/API/ABI, catalog/BOM, workflow, benchmark/chart, `docs/manual/**`
변경이 없는지 별도로 확인한다.

### Fresh verification evidence (2026-08-20)

- 기준 head: `ff85c999ff976544358d87f519d565188e6380f2`; JDK `25.0.4`, Gradle
  `9.7.0`; PostgreSQL image `postgres:18.4-alpine`, JDBC driver `42.7.13`,
  Testcontainers PostgreSQL `2.0.5`는 기존 `Containers.Postgres`/catalog 해석을
  그대로 사용했다.
- targeted PostgreSQL: `6/6` pass, failure/error `0`, `BUILD SUCCESSFUL`.
- full PostgreSQL JDBC module/shared source set: `373` tests (`12` skipped)와
  `129` tests (`5` skipped) 모두 pass, failure/error `0`, `BUILD SUCCESSFUL`.
- H2 helper regression: `8/8` pass, failure/error `0`, `BUILD SUCCESSFUL`.
- Detekt, `git diff --check`, Korean terminology audit(4 files)가 모두 pass.
- PostgreSQL 실행은 로컬 Colima Docker endpoint를 명시했다. Docker가 없는 일반
  환경에서는 PostgreSQL evidence를 `N/A`로 분리하며 H2 skip을 PostgreSQL green으로
  해석하지 않는다.

## Task 4 — evidence와 stacked PR handoff

- [x] test report에서 exact commit, JDK, PostgreSQL/Testcontainers image/driver,
  pool/concurrency 조합, test counts, skip/failure/error를 요약한다.
- [x] pool 부족 또는 SERIALIZABLE 결과가 `N/A`이면 명령·환경·원인·후속 slot을
  Issue #694 comment에 한국어로 추가한다. 수치가 없는 항목을 성능 결론으로 쓰지 않는다.
- [x] Issue #659 stacked table에 `#694 / test/issue-694-postgresql / develop`을
  slot 6으로 추가하는 metadata diff를 PR 직전에 다시 확인한다. child PR base는
  `develop`이며 #695 merge SHA `ff85c999`를 선행 조건으로 명시한다.
- [x] PR body는 한국어로 작성하고 마지막 heading을 정확히 `## DoD Status`로 둔다.
  evidence 표의 `Required checks: X/Y; N/A: N; Blocked: N` 합계를 실제 결과와
  일치시키며, PostgreSQL Docker 미실행이면 `PENDING`으로 표시한다.
- [ ] merge는 별도 gate다. PR 생성 후 exact head, required CI, review/thread,
  mergeability, final DoD를 fresh read-back하고 사용자 승인 없이 merge/branch 삭제를
  실행하지 않는다.

## 롤백과 중단 조건

- test-only 변경은 단일 파일이므로 실패 시 해당 파일만 되돌리고 production/API에는
  손대지 않는다.
- PostgreSQL/Testcontainers가 세 번 연속 같은 외부 환경 조건으로 실패하면 이를
  code blocker가 아닌 `N/A` evidence로 분류하고 후속 CI/environment issue로 분리한다.
- flaky isolation timing, unbounded wait, credential leakage, active lease 누수,
  production source diff가 발견되면 PR을 만들지 않고 해당 원인을 먼저 고친다.
- canonical `develop`, 기존 unrelated worktree와 `TEST_APPLY_PATCH_TMP.txt`는 절대
  정리/삭제 대상이 아니다.

## 계획 DoD

- [x] worktree·설계·7-Tier review가 승인된 범위로 존재한다.
- [x] PostgreSQL test-only fixture가 실제 driver에서 correctness·pool·isolation을
  검증한다.
- [x] H2 regression·PostgreSQL module·detekt·문서 audit의 fresh evidence가 있다.
- [x] Issue/Epic/PR metadata와 N/A/후속 slot이 서로 일치한다.
- [x] PR/CI handoff가 `DONE` 또는 Docker evidence 부재 시 `PENDING`으로 보고된다.
  현재 PR #696은 hosted CI `9/9` pass, `N/A=27`, `Blocked=0`이며 merge gate만
  `PENDING`이다.
