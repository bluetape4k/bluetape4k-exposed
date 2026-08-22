# Issue #694 PostgreSQL/MySQL JDBC driver benchmark 설계

## 문서 상태

- 대상 이슈: [#694](https://github.com/bluetape4k/bluetape4k-exposed/issues/694)
- Epic: [#659](https://github.com/bluetape4k/bluetape4k-exposed/issues/659)
- 선행 slot: #690 / merged PR #695, PostgreSQL PR #696, MySQL PR #705, 관련 검증 PR #706/#703
- 기준 base: `develop` `5c7e7f351ba92709029353bbacd34730847f91af`
- 구현 branch: `perf/issue-694-driver-benchmark`
- worktree: `.worktrees/perf-issue-694-driver-benchmark`
- 범위: 기존 `benchmark-exposed-benchmark`에 실제 PostgreSQL/MySQL 8 driver benchmark와
  raw evidence·차트·EN/KO 분석 문서를 추가하는 test/benchmark-only slice
- 설계 상태: 사용자 승인 계획을 반영해 실행 중

## 문제 정의

PR #695와 후속 driver conformance PR은 Virtual Thread 기반 JDBC key enumeration의
정확성, pool lease 상한, isolation·failure cleanup을 실제 driver에서 검증했다. 그러나
현재 benchmark는 H2 in-memory fixture만 사용한다. H2 처리량을 PostgreSQL 또는 MySQL 8
driver의 query/connection 비용으로 일반화할 수 없고, pool 크기와 `maxConcurrency`의
관계도 실제 driver에서 측정되지 않았다.

이번 slice는 Issue #694의 남은 성능·증거 범위를 다룬다. production helper와 public
API/ABI는 변경하지 않고, 동일한 `ExposedEntityMapLoader` sequential path와 opt-in
parallel path를 PostgreSQL 및 MySQL 8 Testcontainers에서 같은 row count·pool matrix로
측정한다. benchmark는 우열을 보장하는 제품 계약이 아니라 재현 가능한 방향성 evidence다.

## 현재 근거와 경계

- `benchmark/exposed-benchmark/src/benchmark/kotlin/io/bluetape4k/exposed/benchmark/jdbc/JdbcKeyEnumerationBenchmark.kt`
  는 H2 전용 JMH benchmark이며 row count와 range 분할 로직을 이미 갖고 있다.
- `benchmark/exposed-benchmark/src/benchmark/kotlin/io/bluetape4k/exposed/benchmark/support/BenchmarkSupport.kt`
  의 `BenchmarkUsers`, `seedJdbcUsers`, Exposed table lifecycle을 재사용한다.
- `exposed/jdbc-tests/src/main/kotlin/io/bluetape4k/exposed/tests/Containers.kt`의
  `Containers.Postgres`와 `Containers.MySQL8`, `TestDB.POSTGRESQL`/`MYSQL_V8`의
  credential·JDBC option을 재사용한다. benchmark가 container를 직접 stop하지 않는다.
- driver 버전은 중앙 catalog의 `bt4k.postgresql`과 `bt4k.mysql.connector.j`로 고정하고,
  Testcontainers image/version과 immutable digest는 기존 shared launcher가 제공하는 값을
  기록한다. digest를 관찰할 수 없으면 raw evidence를 `PENDING`으로 판정한다.
- `exposed/jdbc/src/main/**`, `exposed/jdbc-tests/src/main/**`, `docs/manual/**`,
  BOM/catalog source와 workflow definition은 이번 변경 대상이 아니다.
- Testcontainers benchmark는 하나의 Gradle process에서 backend를 병렬 실행하지 않는다.
  PostgreSQL과 MySQL task, 각 세 번의 raw run은 별도 순차 command로 실행한다.

## 채택한 구조

### 1. benchmark matrix와 fixture

순수 matrix/range helper는 benchmark module의 main source
`JdbcDriverBenchmarkMatrix.kt`에 두고, Testcontainers/Hikari fixture는 benchmark source의
`DriverBenchmarkFixture.kt`에 둔다. 둘 다 benchmark module 내부의 `internal` 지원 타입이며
publishable production API가 아니다. 이 분리는 unit test가 Docker 없이 matrix를 검증하면서도
container 의존성이 main compilation classpath로 새지 않게 한다.

```kotlin
internal enum class JdbcBenchmarkDriver { POSTGRESQL, MYSQL_V8 }

internal data class JdbcDriverBenchmarkCase(
    val driver: JdbcBenchmarkDriver,
    val rowCount: Int,
    val poolSize: Int,
    val maxConcurrency: Int = 2,
)

internal data class JdbcDriverBenchmarkRange(val lowerInclusive: Long?, val upperExclusive: Long?)
internal fun jdbcDriverBenchmarkCases(): List<JdbcDriverBenchmarkCase>
internal fun buildDriverBenchmarkRanges(rowCount: Int, rangeCount: Int): List<JdbcDriverBenchmarkRange>
```

matrix는 `driver = POSTGRESQL, MYSQL_V8`, `rowCount = 1000, 10000`,
`poolSize = 1, 2, 4`, `maxConcurrency = 2`의 12개 case다. `poolSize <`, `=`, `>`
`maxConcurrency`를 모두 포함하되, pool 1은 압박 조건이지 운영 권고가 아니다.
matrix 함수는 양수 row/pool, 승인된 `maxConcurrency == 2`, `rangeCount <= rowCount`를 검증하고,
불완전한 range가 중복 ID를 숨기지 않도록 disjoint half-open range를 생성한다.

fixture는 다음 순서로 소유권을 고정한다.

1. `TestDBConfig.useTestcontainers == true`를 fail-closed로 확인하고 선택한 shared
   container에 접근해 startup/driver 오류를 숨기지 않는다.
2. `TestDB.POSTGRESQL`/`MYSQL_V8` URL이 선택 container의 `jdbcUrl` prefix와 일치하는지
   검증한 뒤 중앙 JDBC option과 container credential로 Hikari `DataSource`를 만든다.
   `minimumIdle=0`, bounded `connectionTimeout`, pool name을 명시한다.
3. test-only `TrackingDataSource`로 connection request/active/peak와 statement
   execution count를 관찰하고 `Database.connect(tracker)`로 Exposed database를 만든다.
4. `seedJdbcUsers`로 schema와 정해진 row count를 준비한 뒤 setup lease가 0인지 확인한다.
   sequential preflight와 parallel preflight가 실제 대상 database에서 expected row count,
   정렬, no-duplicate를 만족해야 measurement를 시작한다.
5. caller-owned Virtual Thread executor와 loader를 만들고 benchmark invocation 동안
   `parallelJdbcKeyEnumeration`을 사용한다. sequential 측정은
   `transaction(database) { loader.loadAllKeys().count() }`로 명시 database를 바인딩한다.
6. fixture open 중 어느 단계라도 실패하면 이미 만든 executor/datasource/schema를 즉시
   bounded cleanup하고 primary/suppressed exception을 보존한다. `@TearDown(Level.Trial)`
   에서도 active lease 0을 확인한 뒤 schema, datasource, executor를 순서대로 정리한다.
   shared container는 fixture가 닫지 않는다.

`TrackingDataSource`는 SQL 결과를 변형하지 않고 lease와 statement execution 계측만 한다.
JMH `@AuxCounters`에는 primitive snapshot으로 request count, statement execution count,
peak active lease, active-at-end를 기록하고, 각 iteration 종료 시 peak가
`min(poolSize, maxConcurrency)`를 넘지 않는 hard assertion을 둔다. helper가 executor를
닫지 않는 기존 계약은 fixture teardown 전후 `isShutdown` assertion으로 확인한다.

### 2. JMH benchmark와 Gradle task

새 `JdbcDriverKeyEnumerationBenchmark`는 기존 H2 benchmark와 별도 class/configuration으로
둔다. `sequentialKeysetPaging`과 `parallelKeyEnumeration`을 같은 fixture에서 호출하며,
JMH `@Param`이 row count와 pool matrix를 생성한다. `@Threads(1)`, `@Fork(1)`, warmup
1회, measurement 3회, iteration 1초, `thrpt`/`ops/s`를 명시한다. JMH method는
`ops/s`를 반환하고 분석 script가 `rows/s = ops/s × rowCount`를 별도로 계산한다.

Gradle에는 `jdbcDriverPostgreSQL`/`jdbcDriverMySQL` configuration과 각각의
`benchmarkJdbcDriverPostgreSQLBenchmark`/`benchmarkJdbcDriverMySQLBenchmark` task를 추가한다. 각 task는
driver parameter를 하나로 고정해 output overwrite와 backend 혼합을 막는다. 기본
`jdbcKeyEnumeration` H2 task의 매개변수나 결과를 변경하지 않는다. Docker/Testcontainers
benchmark의 활성 조건과 exact command를 문서에 기록한다.

### 3. 결과와 차트

각 backend를 같은 host에서 별도 task로 세 번 실행해
`postgresql-run-1.json`…`postgresql-run-3.json`과
`mysql-run-1.json`…`mysql-run-3.json`을 보존한다. 각 JSON은 JMH의
`primaryMetric.score`와 `secondaryMetrics`를 그대로 유지하고, 분석 script는 각 파일이
정확히 12개(`2 methods × 2 row counts × 3 pool sizes × 1 driver`)의 finite·non-negative
entry를 갖는지 검증한다. 각 primary/auxiliary rawData는 measurement 3개를 보존해야 하며,
실행 SHA·run ID·raw SHA-256·관찰된 driver artifact/image digest/catalog ref·host/runtime
provenance는 sanitized metadata로 함께 보존하고, run 번호와 source report의 대응 및
post-copy SHA를 검증한다. metadata append는 lock으로 직렬화한다.
signal/OOM으로 중단된 partial output은 폐기한 뒤 active lease/schema preflight를 통과한
다음 run만 수집한다. 분석 표는 driver·row count·pool size·method별 세 run 중앙값과
`rows/s`를 계산한다. `ops/s`는 완료된 benchmark operation/s, `rows/s`는
`ops/s × rowCount`, statement execution은 invocation당 실행된 JDBC statement 수,
peak active lease와 active-at-end는 setup/teardown을 제외한 iteration-local 보조 관측값이다.
이 값들은 round-trip latency나 pool capacity를 뜻하지 않는다. 중앙값은 측정 요약일 뿐이고
최소 speedup을 요구하지 않는다.

차트는 `docs/images/readme-charts/`에 EN/KO SVG·PNG 쌍으로 둔다. 차트의 source ledger는
benchmark source와 raw JSON 경로를 node/edge evidence로 명시하고, throughput과 peak
lease를 한 눈에 비교하되 축 단위를 섞지 않는다. CairoSVG로 `-s 2` 렌더링하고 full-size
PNG를 검사한다. 숫자가 없거나 run이 하나라도 빠지면 차트와 PASS 주장을 만들지 않는다.

## 실패·안정성 계약

| 실패 모드 | 방어와 판정 |
| --- | --- |
| Docker/Testcontainers startup 실패 | benchmark acceptance를 PASS로 바꾸지 않고 sanitized 오류와 환경을 PENDING/BLOCKED로 기록 |
| driver class 또는 중앙 버전 누락 | `benchmarkClasses`에서 실패시키고 새 버전/하드코딩으로 우회하지 않음 |
| pool lease가 `maxConcurrency` 초과 | aux counter와 fixture tracker로 실패, throughput 수치도 해석 보류 |
| active lease 또는 executor가 남음 | trial teardown과 `active == 0`, caller executor 생존 assertion으로 실패 |
| benchmark run 간 container 경쟁 | backend와 세 run을 `--no-parallel --max-workers=1`로 순차 실행 |
| 비정상 중단으로 남은 schema/lease | 해당 run을 `PENDING`으로 폐기하고 다음 run 전에 bounded active-lease/schema preflight를 통과시킨다. shared container는 중지하지 않는다 |
| JMH score 변동/신뢰구간 과대 | 중앙값과 raw score를 함께 기록하고 일반적인 성능 우열을 주장하지 않음 |
| URL/credential 또는 비정상 metric 노출 | raw artifact는 JMH metric payload만 보존하고 알려진 connection/credential token(`jdbc:`, `postgres://`, `mysql://`, `password`, `passwd`, `secret`, `DOCKER_HOST`)을 검사한다. parser가 누락·NaN·Infinity·음수·허용되지 않은 enum·rawData sample count를 거부하며, summary provenance/lifecycle guard와 EN/KO table-cell parity를 통과할 때만 chart를 생성한다 |
| cancellation/driver abort 미검증 | 별도 #707 범위로 명시하고 benchmark 결과가 이를 대체한다고 쓰지 않음 |

benchmark source에는 새 production `!!`, broad exception swallowing, blocking event-loop
호출을 추가하지 않는다. JMH setup/teardown의 blocking JDBC와 Testcontainers는 benchmark
전용 worker JVM 경계이며, caller application runtime 계약과 섞지 않는다.

## 검토한 대안

### A. 기존 H2 benchmark에 driver parameter만 추가

짧지만 H2 setup과 실제 container lifecycle, driver credential, pool tracker가 뒤섞여
실패 원인을 분리하기 어렵다. 기존 H2 결과의 재현성을 보존하면서 driver-only fixture를
별도 class로 두는 A를 채택한다.

### B. PostgreSQL/MySQL JUnit test의 fixture를 benchmark source에 복사

acceptance test와 benchmark가 각각 다른 schema/lease 계측을 유지하게 되어 drift가
생긴다. shared `Containers`/`TestDB`와 benchmark 전용 얇은 tracker를 재사용하고, 이미
검증된 conformance test를 benchmark correctness의 대체로 사용하지 않는 절충을 채택한다.

### C. JMH를 버리고 독립 stopwatch CLI를 추가

raw JSON과 custom metric은 쉬워도 repository의 `kotlinx-benchmark` task/format과 분리되고
기존 benchmark 운영법을 깨뜨린다. JMH configuration을 추가하고 aux counter로 필요한
lease evidence를 붙이는 A를 채택한다.

## 수용 기준과 명시적 비목표

- [ ] 12 matrix case가 중앙 driver dependency와 shared Testcontainers 조건으로 compile된다.
- [ ] RED test가 matrix/range validation을 먼저 고정하고, GREEN에서 모든 case와 half-open
  range invariant를 통과한다.
- [ ] PostgreSQL/MySQL 전용 task를 각각 세 번 순차 실행해
  `postgresql-run-{1,2,3}.json`과 `mysql-run-{1,2,3}.json` 6개 raw JSON을 저장하고,
  각 backend·case의 중앙값 표에 throughput·statement execution·lease metric을 채운다.
- [ ] EN/KO README, lesson, semantic ledger와 SVG/PNG chart pair가 raw source를 가리킨다.
- [ ] 기존 H2 benchmark configuration과 production API/ABI/기본 sequential path는 동일하다.
- [ ] targeted compile/test, driver Testcontainers test, detekt, ABI/diff check와 exact-head
  full nightly의 PostgreSQL/MySQL job이 fresh evidence로 검증된다. skipped job은 PASS가 아니다.
- [ ] PR 본문은 Korean이고 마지막 H2가 `## DoD Status`이며, merge는 이 실행에서 하지 않는다.

이번 slice는 driver별 throughput 방향, pool lease 상한, query/transaction cleanup의
benchmark evidence만 다룬다. 새로운 API, production optimization, 최소 speedup 보장,
driver abort/cancellation 설계(#707), release publication과 manual 안정 release pinning은
비목표다.

## SPW 검토 기록

- SPW-01 사실 고정: current `develop` head, Issue #694 acceptance, 기존 H2 benchmark,
  shared container/driver aliases와 nightly 조건을 source anchor로 고정했다.
- SPW-02 구조: 문제·근거·구조·실패·대안·수용 기준·비목표 순으로 구현 경계를 분리했다.
- SPW-03 내용: benchmark-only support와 production API, raw JSON과 해석, Testcontainers
  failure와 skipped coverage를 혼동하지 않도록 명시했다.
- SPW-04 독자 검토: 구현자가 exact matrix, task, fixture ownership, docs/chart path를
  바로 찾을 수 있도록 표와 code signature를 사용했다.
- SPW-05 Korean naturalness: Korean prose를 유지하고 code token, command, API name,
  URL, exact error는 원문 형태로 보존했다.
