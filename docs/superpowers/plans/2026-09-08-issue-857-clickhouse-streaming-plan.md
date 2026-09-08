# #857 ClickHouse 수집·스트리밍 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use `executing-plans` for inline execution with checkpoints; use `subagent-driven-development` only if bounded native lanes are available. Steps use checkbox syntax.

**Goal:** 기존 `queryFlow` 호환성을 유지하며 `queryList`와 점진적 `queryFlow(Query, mapper)`를 추가하고 실제 ClickHouse에서 수명·메모리 계약을 검증한다.

**Architecture:** 기존 함수는 변경하지 않는다. 새 Flow는 별도 suspend 트랜잭션의 직접 ResultSet 순회와 rendezvous 채널로 생산자·소비자를 연결한다. 자원 정리와 예외 우선순위는 [승인 명세](../specs/2026-09-08-issue-857-clickhouse-streaming-design.md)를 따른다.

**Tech Stack:** Kotlin/JDK/Gradle은 현 저장소 설정, Exposed 1.5.0, ClickHouse JDBC 0.9.9, coroutines, JUnit 5, bluetape4k assertions·Testcontainers, 기존 kotlinx.benchmark 모듈.

## 승인·실행 경계

- 사용자 명세 승인: `eb719216`에 대한 현재 대화의 `승인`.
- 기준 구현: `d5fb9602491ad64811bffcda227e3b2deecf9eea`. 작업 경로는 `.worktrees/feat/issue-857-clickhouse-streaming`.
- 실행은 계획 리뷰를 먼저 마친 후 테스트 우선으로 진행한다. 독립 리뷰가 실행되지 않으면 원인을 보존하고 inline fallback으로 검토한다. production 구현자는 메인 하나다.
- 새 런타임 의존성, 기존 API 제거, PR 생성·push·머지·배포·Full Nightly는 실행하지 않는다. 후속 승인으로 `testImplementation(bt4k.hikaricp)` 한 줄만 허용했다.
- 메모리 실측은 기존 `benchmark/exposed-benchmark`에 둔다. 이 범위와 루트 ABI 파일을 helper 쓰기 범위에 등록한 뒤 수정한다. 중앙 매뉴얼과 wiki 게시에 필요한 별도 저장소 범위는 T8에서 확인하며, 승인 없이 외부 상태를 변경하지 않는다.

## 현재 검증과 선행 조건

2026-09-08에 `:bluetape4k-exposed-clickhouse:tasks`와 `:benchmark-exposed-benchmark:tasks --all`이 성공했다. 실제 작업은 `checkKotlinAbi`, `updateKotlinAbi`, `compileBenchmarkKotlin`, `benchmarkBenchmarkJar`다. 처음 시도한 `:exposed-benchmark`는 존재하지 않아 실패했으며 아래 명령은 확인된 이름만 사용한다.

기존 `ClickHouseExtensionsTest`는 테스트 실행 프로세스에 `DOCKER_HOST`가 없어 초기화에 실패했다. 정상 Colima를 확인하고 해당 프로세스에만 `DOCKER_HOST=unix:///Users/debop/.colima/default/docker.sock`, `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`을 전달한 fresh 실행에서 3/3 통과했다. 환경을 이미 상속하는 로그인 shell에서는 반복 설정하지 않는다. Docker 재시작·전역 설정 변경은 하지 않는다.

`dependencyInsight --dependency HikariCP --configuration testRuntimeClasspath` 결과 ClickHouse 테스트 모듈에는 HikariCP가 없다. 기존 benchmark 모듈에는 있지만 `ClickHouseConnectionWrapper`가 internal이고 공개 Database 팩터리에 DataSource 인자가 없어, 검증을 단순 이동해서 같은 연결 경로를 재사용할 수 없다. 후속 사용자 `승인`으로 **ClickHouse 모듈에 `testImplementation(bt4k.hikaricp)` 한 줄을 추가**한다. 기존 catalog 버전을 사용하고 공개·runtime 의존성은 바꾸지 않는다. 테스트만을 위해 래퍼를 public으로 바꾸거나 별도 커넥션 풀을 직접 구현하지 않는다.

## 파일과 책임

아래 경로는 저장소 루트 기준이다. `P`는 `exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse`, `T`는 `exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse`를 뜻한다.

| 파일 | 작업·책임 |
|---|---|
| `P/ClickHouseExtensions.kt` | queryList와 신규 overload의 공개 진입점·KDoc, 기존 함수 유지 |
| `P/ClickHouseQueryStreaming.kt` | 내부 커서 실행, 생산자 정리·예외 전달; 공개 클래스 추가 없음 |
| `T/ClickHouseExtensionsTest.kt` | 기존 materialization과 queryList 계약 |
| `T/ClickHouseQueryFlowTest.kt` | 실서버 cold·순서·첫 행·역압력·타입·동시 수집 |
| `T/ClickHouseQueryFlowLifecycleTest.kt` | 자원 관측·오류 주입·취소·재시도·외부 트랜잭션 분리 |
| `T/support/TrackingClickHouseConnection.kt` | 테스트 전용 JDBC decorator, next/close/lease 계수 및 오류 주입 |
| `api/bluetape4k-exposed-clickhouse.api` | 대상 모듈의 생성된 ABI 추가분만 반영 |
| `exposed/clickhouse/README.md`, `README.ko.md` | 수집/스트리밍 선택·마이그레이션·호출자 책임 |
| `benchmark/exposed-benchmark/build.gradle.kts` | 기존 harness에 ClickHouse 대상과 전용 설정 추가, smoke에서 제외 |
| `benchmark/exposed-benchmark/src/benchmark/kotlin/io/bluetape4k/exposed/benchmark/clickhouse/ClickHouseQueryBenchmark.kt` | 동일 SQL의 List/Flow 메모리·최초 행·처리량 측정 |
| `docs/review/2026-09-08-issue-857-plan-review.md` | 계획 리뷰·위험·수용 기준 추적 |
| `docs/review/2026-09-08-issue-857-validation.md` | 명령·exit code·테스트 집계·실측·최종 리뷰 근거 |
| `docs/lessons/2026-09-08-issue-857-clickhouse-streaming.md` | V1/V2·iterator·취소·도구 환경 등 재발 방지 |

재사용: 기존 `AbstractClickHouseTest`와 `ClickHouseServer.Launcher`를 사용한다. JDBC 테스트의 `MariaDBJdbcDriverCancellationTest.TrackingDataSource`는 private이므로 직접 의존하지 않고 관측 방식만 참고한다. 새 JDBC decorator는 실제 driver 호출/정리 관측 대상이므로 테스트에만 둔다. JDK Proxy를 쓰면 `InvocationTargetException.targetException`을 풀어 원래 예외를 보존한다. 다른 모듈의 private fixture를 public으로 확장하지 않는다.

## T1 — 전체 수집과 호환성 고정 (낮음, AC-01/02)

- [ ] **T1.1 — RED 테스트 추가**
  - Action: `T/ClickHouseExtensionsTest.kt`에 아래 두 테스트를 추가하고 명시적인 empty·SQLException 시도 횟수(1/2)·일반 예외·실제 Job 취소 테스트를 같은 클래스에 둔다. touched assertion은 bluetape4k assertions만 사용한다.
  - Evidence: 신규 queryList 미정의로 컴파일 실패. 기존 materialization 테스트는 기존 구현에서 성공해야 한다.
  - Failure: 인프라 실패는 RED로 인정하지 않고 환경을 복구한다.

```kotlin
@Test
fun `queryList는 반환 전에 모든 항목을 수집한다`() = runSuspendIO {
    val visited = mutableListOf<Int>()
    val rows = queryList(db) {
        Iterable { (1..3).asSequence().onEach(visited::add).iterator() }
    }
    visited shouldBeEqualTo listOf(1, 2, 3)
    rows shouldBeEqualTo listOf(1, 2, 3)
}

@Test
fun `기존 queryFlow는 첫 방출 전에 전체 수집한다`() = runSuspendIO {
    val visited = mutableListOf<Int>()
    queryFlow(db) {
        Iterable { (1..3).asSequence().onEach(visited::add).iterator() }
    }.take(1).collect {
        visited shouldBeEqualTo listOf(1, 2, 3)
    }
}
```

- [ ] **T1.2 — 최소 queryList 구현**
  - Action: 공개 함수에 한국어 KDoc와 아래 구현을 추가한다. 기존 queryFlow를 이 함수로 재작성하지 않는다.
  - Evidence: 대상 테스트 성공, 기존 JVM 메서드 미변경.
  - Failure: 동작 차이가 있으면 공유 추상화를 만들지 않고 되돌려 원인을 조사한다.

```kotlin
suspend fun <T> queryList(
    db: Database,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    block: JdbcTransaction.() -> Iterable<T>,
): List<T> = suspendTransaction(db, dispatcher) { block().toList() }
```

- [ ] **T1.3 — GREEN과 로컬 커밋**
  - Action: `./gradlew :bluetape4k-exposed-clickhouse:cleanTest :bluetape4k-exposed-clickhouse:test --tests '*ClickHouseExtensionsTest' --no-build-cache --console=plain` 실행 후 XML의 tests/failures/errors를 읽고 scoped Lore 커밋한다.
  - Evidence: 실제 테스트 실행·0 failures/errors, 기존 API 코드 diff 없음.
  - Failure: 테스트가 실행되지 않았거나 assertion이 누락되면 다음 단계 중단.

## T2 — 직접 커서 기반 신규 Flow (높음, AC-03/04/07)

- [ ] **T2.1 — cold·take RED 테스트**
  - Action: `T/ClickHouseQueryFlowTest.kt`에 아래 테스트를 작성한다. `Numbers`는 별도 DDL 없이 실제 서버의 `system.numbers`를 조회한다.
  - Evidence: overload 부재로 실패; 기존 API로 치환하면 미리 전체 변환하여 읽기 상한 검사가 실패해야 한다.
  - Failure: fixture에서 List를 미리 만들어 테스트하지 않는다.

```kotlin
private object Numbers : Table("system.numbers") {
    val number = long("number")
}

@Test
fun `신규 Flow는 cold이며 일부 행만 매핑한다`() = runSuspendIO {
    val queries = AtomicInteger()
    val mapped = AtomicInteger()
    val result = queryFlow(db, query = {
        queries.incrementAndGet()
        Numbers.selectAll().limit(100_000)
    }, mapper = { row ->
        mapped.incrementAndGet()
        row[Numbers.number]
    })
    queries.get() shouldBeEqualTo 0
    result.take(1).toList() shouldBeEqualTo listOf(0L)
    queries.get() shouldBeEqualTo 1
    (mapped.get() <= 2).shouldBeTrue()
}
```

- [ ] **T2.2 — 커서·채널 구현**
  - Action: 새 진입점은 `clickHouseQueryFlow` 내부 함수에 위임한다. 내부는 `flow { supervisorScope { ... } }`에 `Channel<T>(Channel.RENDEZVOUS)`와 주입 dispatcher의 생산자 하나를 둔다. 생산자가 `inTopLevelSuspendTransaction(db, outerTransaction = null)`을 열어 `maxAttempts = 1`로 설정한다. Query의 distinct fields를 복사·정규화한 뒤 `execQuery(query) { it }`를 얻고 아래 루프를 실행한다. null ResultSet은 내부 불변식 위반으로 실패한다.
  - Evidence: 첫 행 전에 전체 List/iterator 생성 없음, 쿼리·매퍼가 IO dispatcher에서 실행.
  - Failure: public 변환 API로 구현되지 않으면 reflection이나 private API로 우회하지 않는다.

```kotlin
val fields = selected.set.realFields.toSet()
    .mapIndexed { index, expression -> expression to index }.toMap()
val resultSet = checkNotNull(execQuery(selected) { it })
resultSet.use { cursor ->
    while (true) {
        currentCoroutineContext().ensureActive()
        if (!cursor.next()) break
        currentCoroutineContext().ensureActive()
        val item = mapper(ResultRow.create(JdbcResult(cursor), fields))
        currentCoroutineContext().ensureActive()
        channel.send(item)
    }
}
```

생산자 실패는 종료 결과와 `channel.close(cause)`로 전달한다. CancellationException은 별도로 기록 후 재전파한다. 비취소 실패는 supervisor 자식의 uncaught 예외로 던져 collector 원인을 선점하지 않고 채널 수신자가 받는다. 소비자는 `for (item in channel) emit(item)`을 실행하고 최초 원인을 저장한다. finally에서 channel 취소·producer 취소 후 `withContext(NonCancellable) { producer.join() }`으로 완료를 기다린다. 생산자 종료 결과는 join 후 읽고 원래 consumer/취소 원인이 있으면 직접 close 실패만 suppressed로 추가한다. 정상 경로에서는 query/mapper/close 실패를 주 원인으로 전달한다. 취소를 기록할 때 broad catch 앞에 CancellationException 분기를 둔다.

- [ ] **T2.3 — 컨텍스트·순서·역압력 검증**
  - Action: 동일 Flow 2회 수집=팩터리 2회, 빈 결과, 0..99 순서, consumer gate를 `CompletableDeferred<Unit>`로 닫은 동안 mapper count≤received+1을 확인한다. query/mapper dispatcher는 단일 스레드 executor의 이름으로 관측하고 테스트가 소유한 dispatcher만 finally에서 닫는다.
  - Evidence: `./gradlew :bluetape4k-exposed-clickhouse:test --tests '*ClickHouseQueryFlowTest' --no-build-cache --console=plain`과 XML.
  - Failure: 임의 sleep 후 우연히 성립하는 count 검사는 허용하지 않는다. gate와 `untilSuspending`으로 상태를 기다린다.

## T3 — 오류·취소·정리 계측 (높음, AC-05/06)

- [ ] **T3.1 — JDBC 관측 fixture와 RED**
  - Action: `T/support/TrackingClickHouseConnection.kt`는 Connection→PreparedStatement→ResultSet 순서로 decorate한다. get/prepare/execute/next/close 카운터, 활성 자원 수, 실제 delegate에 close를 먼저 전달한 뒤 지정한 예외를 던지는 injection을 제공한다. close 이중 호출은 시도 횟수와 실제 최초 정리를 분리 기록한다. Fixture 자체에서 위임과 예외 원형 보존을 MockK로 검증한다.
  - Evidence: 정상/empty/take(1)/query factory/execute/mapper/collector/외부 cancel 각각에 활성 자원 0, SELECT 재실행 성공을 요구하는 테스트. 이를 cleanup을 누락한 후보에 적용하면 실패해야 한다.
  - Failure: fixture가 예외를 삼키거나 실제 close를 대신하면 관측 근거 무효.

- [ ] **T3.2 — 종료 원인 표를 테스트로 고정**
  - Action: 명세의 기존 원인 3행×정리 실패 2열을 테스트한다. `assertFailsWith` 반환 객체의 identity, cause/suppressed를 검사한다. mapper SQLException을 세 번째 행에서 주입하고 execute count=1을 확인한다. collector 실패와 producer close 실패가 경합해도 collector 원인이 유지돼야 한다.
  - Evidence: 다음 대표 검증과 각 종료 케이스 XML 이름.
  - Failure: `take`의 내부 취소를 일반 실패로 바꾸거나 producer 정리가 join 뒤에도 계속되면 수정한다.

```kotlin
// ClickHouseQueryFlowLifecycleTest 내부에도 독립적으로 선언한다.
private object Numbers : Table("system.numbers") {
    val number = long("number")
}

val original = IllegalStateException("mapper failure")
val observed = assertFailsWith<IllegalStateException> {
    queryFlow(db, query = { Numbers.selectAll().limit(10) }, mapper = {
        throw original
    }).collect()
}
observed.shouldBeSameInstanceAs(original)
```

- [ ] **T3.3 — 실제 취소와 종료 후 재사용**
  - Action: query 전, next 대기 중, next 반환 직후, mapper 진입 후, send 대기 중에 각각 gate로 위치를 고정하고 Job.cancel을 호출한다. next/mapper gate는 테스트 finally에서 해제하여 무한 테스트를 막는다. 취소 후 미전달·정리 완료·새 collect 성공을 검증한다. EOF, 응답 읽기 SQLException(절단된 응답 모델), double terminal, 0행, 1행도 포함한다.
  - Evidence: `./gradlew :bluetape4k-exposed-clickhouse:test --tests '*ClickHouseQueryFlowLifecycleTest' --no-build-cache --console=plain` 성공.
  - Failure: 수동 continuation/가짜 CancellationException 발생만으로 실제 취소를 대체하지 않는다.

## T4 — 독립 연결·타입·서버 한도 (중간, AC-03/05/07)

- [ ] **T4.1 — 독립성·타입 회귀**
  - Action: 동시 collect 2개의 트랜잭션·연결·ResultSet 식별자가 다름을 기록하고 결과가 섞이지 않는지 검사한다. 외부 트랜잭션 안에서 collect해도 외부 연결을 반환하지 않는지 확인한다. duplicate select 표현식, alias, nullable 날짜·정수·문자열, 바인딩된 악성 문자열을 실제 Query로 조회한다. 기존 Events 공유 스키마를 파괴하지 않고 읽기 표현식/고유 fixture를 사용한다.
  - Evidence: `ClickHouseQueryFlowTest`·`LifecycleTest`의 새 케이스 실행, dispatcher 및 현재 트랜잭션 식별자 확인.
  - Failure: SELECT 순서와 ResultRow field index 불일치는 P1.

- [ ] **T4.2 — pool·timeout 복구**
  - Action: 승인된 `testImplementation(bt4k.hikaricp)`를 `exposed/clickhouse/build.gradle.kts`에 추가한다. 테스트 소유 pool 크기 2, 대여 timeout 500ms, 유한 driver query/socket timeout을 설정하고 두 느린 collect가 점유한 상태의 대여 timeout, 취소 후 active=0, 후속 SELECT 성공을 검증한다. 서버 결과 행 한도 초과와 지연 응답 timeout도 각각 검증한다.
  - Evidence: 실제 JDBC V2 timeout 설정을 jar/source에서 확인한 값, 종료 latency, pool 반환 카운터.
  - Failure: 단순 코루틴 timeout 성공을 JDBC read 중단의 증거로 사용하지 않는다.

## T5 — API·문서·모듈 검증 (중간, AC-02/09)

- [ ] **T5.1 — API baseline과 소비자 호출**
  - Action: `./gradlew :bluetape4k-exposed-clickhouse:updateKotlinAbi :bluetape4k-exposed-clickhouse:checkKotlinAbi --console=plain` 실행. 기존 ABI 줄 삭제 0개, 추가 overload/queryList만 있는지 원본과 비교한다. 기존 trailing lambda와 신규 named query/mapper 호출을 테스트 컴파일한다.
  - Evidence: 루트 대상 api 파일 하나의 추가 diff, checkKotlinAbi 성공.
  - Failure: 전체 `updateProductionAbiBaseline`을 실행하지 않는다.

- [ ] **T5.2 — 공개 문서 갱신**
  - Action: KDoc와 README 두 언어에 아래 사용 예제 및 API 계약 표를 넣는다. 새 Flow가 트랜잭션·연결을 collect 동안 점유함, maxAttempts=1, 매퍼 제약, caller-owned timeout/권한, 드라이버 버퍼링 실측 결과를 명시한다. 기존 queryFlow를 deprecated/삭제하거나 이미 스트리밍인 것처럼 설명하지 않는다.
  - Evidence: writer SPW-01~05 및 locale parity. 중앙 매뉴얼은 T8과 구분.
  - Failure: 실측 전 종단 간 스트리밍 보장 문구 금지.

```kotlin
val rows = queryList(db) { Events.selectAll().limit(100) }
val firstEvents = queryFlow(db, query = { Events.selectAll() }, mapper = { row ->
    row[Events.eventId] to row[Events.eventName]
}).take(100).toList()
```

`firstEvents` 예제는 호출자가 최대 100개를 보관하는 경우다. 전체 결과를 보관하지 않는 처리 예제는 T6의 `fold`와 함께 설명한다. 라이브러리 의존성 예제는 기존 BOM을 사용하고 개별 버전을 넣지 않는다.

- [ ] **T5.3 — 전체 대상 모듈 검증**
  - Action: IDE 진단/참조 검색이 가능하면 실행하고, 불가능하면 컴파일·detekt로 대체 근거를 남긴다. `./gradlew :bluetape4k-exposed-clickhouse:cleanTest :bluetape4k-exposed-clickhouse:test :bluetape4k-exposed-clickhouse:detekt :bluetape4k-exposed-clickhouse:checkKotlinAbi --no-build-cache --console=plain`을 순차 실행한다.
  - Evidence: 테스트 XML 집계, 실패 0, 진단·deprecation 결과, `git diff --check`.
  - Failure: 원래 있던 실패도 현재 완료 증거에서 제외하지 말고 원인을 분리한다.

## T6 — 실제 대량 결과 실측 (높음, AC-08)

- [ ] **T6.1 — 기존 benchmark 대상 확장**
  - Action: 기존 kotlinx.benchmark 모듈의 `benchmarkImplementation`에 현재 저장소 ClickHouse project만 연결한다. 새 외부 라이브러리는 추가하지 않는다. `ClickHouseQueryBenchmark`는 `@Param("100000", "1000000")`과 동일 Query/매퍼를 사용하고 list/flow 각각 count·checksum만 결과로 소비한다. 서버는 기존 Launcher, 호출자는 별도 fork JVM이다. 새 클래스는 기존 smoke의 전체 패턴에서 명시적으로 제외한다.
  - Evidence: `./gradlew :benchmark-exposed-benchmark:compileBenchmarkKotlin :benchmark-exposed-benchmark:tasks --all --console=plain` 성공, 새 generated task 이름을 읽은 후에만 실행 명령 고정.
  - Failure: production/test 모듈 안에 독립 benchmark harness를 만들지 않는다.

```kotlin
@Benchmark
fun materialized(): Long = runBlocking {
    queryList(db) { Numbers.selectAll().limit(rowCount).map { it[Numbers.number] } }.sum()
}

@Benchmark
fun streamed(): Long = runBlocking {
    queryFlow(db, query = { Numbers.selectAll().limit(rowCount) }, mapper = {
        it[Numbers.number]
    }).fold(0L, Long::plus)
}
```

`runBlocking`은 JVM benchmark 진입점에만 사용하고 production에는 넣지 않는다. benchmark의 `Numbers`는 T2와 동일한 system.numbers 정의를 해당 benchmark 파일에 private으로 둔다. `db`는 Trial setup에서 Launcher endpoint로 생성하고 `rowCount`는 public @Param Int 프로퍼티다.

- [ ] **T6.2 — 실측·판정 기록**
  - Action: 명세대로 warmup 2회·측정 3회·실행 순서 교대·동일 heap으로 두 API와 두 행 수를 비교한다. fresh JVM의 JFR, peak live heap, RSS, direct buffer, GC, 최초 항목 시간, 전체 시간을 `docs/review/2026-09-08-issue-857-validation.md`에 요약하고 원본 로그/JSON/JFR 경로를 기록한다. profiling은 benchmark JVM PID에 적용하며 Gradle daemon 수치를 섞지 않는다.
  - Evidence: 10배 행 증가 대비 streaming live heap 중앙값 증가율≤3, 전체 결과 보관 구조 없음, 첫 항목은 전체 매핑 이전. 수치가 작은 기준선 차감으로 불안정하면 PASS 대신 PENDING.
  - Failure: 드라이버 전체 응답 버퍼링 또는 누수를 발견하면 명세 재검토로 돌아가고 새 driver를 자동 채택하지 않는다.

## T7 — 최종 리뷰와 교훈 (중간)

- [ ] **T7.1 — 검증·리뷰 통합**
  - Action: 최신 diff의 성능·안정성·보안·운영·API·호출자 6개 관점과 메인 통합 리뷰. 모든 AC를 아래 추적표와 XML/실측에 대조하고 Kotlin checklist를 완료한다. 독립 실행 불가만 inline fallback 대상이며 부정적 결과를 폐기하지 않는다.
  - Evidence: P0=0/P1=0, KT-01~05·KT-TEST-01~05 및 Type A 검증 기록.
  - Failure: 미입증 AC를 green 테스트로 대체하지 않는다.
- [ ] **T7.2 — 교훈·scoped 커밋**
  - Action: 설계/계획/구현에서 수정한 가정과 예방 검사를 교훈 파일에 작성, writer gate·diff 검사 후 의도한 파일만 Lore 커밋한다.
  - Evidence: tracked 교훈과 exact HEAD, clean worktree, rollback은 신규 API 사용 중단/기능 커밋 revert이며 기존 API 유지.
  - Failure: 다른 세션의 변경을 stage하거나 broad reset을 하지 않는다.

## T8 — 중앙 문서·연구 보존·종료 경계

- [ ] **T8.1 — 중앙 매뉴얼 범위 확정**
  - Action: `bluetape4k.github.io`의 현재 guidance와 새 버전 문서 배치 규칙을 확인하고 신규 API를 기존 2.0.0 고정 매뉴얼의 기능처럼 덮어쓰지 않는다. 해당 저장소 작업 범위가 승인되지 않았으면 exact 대상 경로·검증 명령을 제시하고 그 문서 단계에서만 승인을 요청한다.
  - Evidence: 중앙 문서 locale pair 변경/검증 또는 승인 대기의 정확한 미완료 항목.
  - Failure: 로컬 `docs/manual` 복제나 임의 release ref 생성 금지.
- [ ] **T8.2 — 연구 검색·최종 DoD**
  - Action: 기존 wiki 연구 기록을 최신 증거와 일치시키고 `gno update`, 해당 collection embed/search, 필요 시 context-mode fallback을 검증한다. wiki 게시 승인 범위를 확인하고 미게시를 명시한다. PR 없는 로컬 완료 범위와 전체 이슈 완료를 구분한다.
  - Evidence: 검색 결과·artifact·exact HEAD, 남은 AC·권한 경계. PR/merge/Full Nightly 미실행.
  - Failure: 문서·실측 미완료 상태에서 #857 완료/닫힘을 주장하지 않는다.

## 추적·위험·검증 기준

| 수용 기준 | 구현/검증 작업 | 주요 실패 신호와 복구 |
|---|---|---|
| AC-01 | T1 | retry/호출 시점 변화 → 기존 suspendTransaction 정책으로 복구 |
| AC-02 | T1, T5 | ABI 삭제·과부하 해석 변경 → 기존 메서드 보존 |
| AC-03 | T2, T4 | 같은 연결/Query 공유 → collect 내부 생성·최상위 tx 검사 |
| AC-04 | T2, T6 | 첫 emit 전에 10만 행 매핑 → iterator/List 경로 차단 |
| AC-05 | T3, T4 | active 자원 잔류·pool 고갈 → 해당 종료 케이스 RED/GREEN |
| AC-06 | T3 | 예외 교체·중복 행 → 원인 우선순위·maxAttempts 검사 |
| AC-07 | T2, T4 | 잘못된 스레드/field index → source 확인·실서버 매핑 회귀 |
| AC-08 | T6 | 결과 규모 비례 retained heap/RSS → 드라이버 분석 후 재설계 |
| AC-09 | T5, T7, T8 | locale/source/ref 불일치 → 문서 범위 재검토 |

A-01/02/03의 명세 승인까지 완료. A-04 계획 리뷰·커밋, A-05 위험 예측(위 표), A-06~09 구현·검증·교훈은 순서대로 증거를 기록한다. A-10/12 및 CG-11~18은 이번 로컬 요청에서 PR·머지가 제외돼 N/A이며, 이후 PR 요청 시 새로 연다. 모듈 신설·catalog 변경·Spring 자동 설정은 N/A. benchmark 확장·JDBC HTTP/컨테이너·공개 ABI·한국어 문서 gate는 적용한다.

현 계획은 실행 전이다. 체크된 구현 항목이나 신규 테스트 성공 주장은 없다. 실행 중 범위나 설계가 바뀌면 이 문서와 해당 리뷰를 먼저 갱신한다.

## 리뷰 보완 — 실행 경계와 핵심 코드

이 절은 R1~R6에 대한 수정이다. 아래 코드는 계획이며 TDD의 RED 확인 전에 production 파일로 옮기지 않는다. 테스트 전용 HikariCP 승인 외 공개 계약은 변경하지 않는다.

### T2.2 종료 상태와 직접 정리

`failure`는 생산자만 기록하며 소비자는 `join()` 후 읽는다. `closeFailure`는 ResultSet 직접 close에서만 기록한다. 소비자가 이미 받은 동일 객체를 다시 suppressed에 넣지 않는다. `Throwable` 포착은 모든 생산자 종료를 채널에 전달하기 위한 경계이며 취소를 먼저 다시 던진다. 구조적 범위를 벗어난 실행은 없다.

```kotlin
internal fun <T> clickHouseQueryFlow(
    db: Database,
    dispatcher: CoroutineDispatcher,
    query: JdbcTransaction.() -> Query,
    mapper: (ResultRow) -> T,
): Flow<T> = flow {
    supervisorScope {
        val channel = Channel<T>(Channel.RENDEZVOUS)
        var failure: Throwable? = null
        var closeFailure: Throwable? = null
        val producer = launch(dispatcher) {
            try {
                currentCoroutineContext().ensureActive()
                inTopLevelSuspendTransaction(db, outerTransaction = null) {
                    maxAttempts = 1
                    currentCoroutineContext().ensureActive()
                    val original = query()
                    val distinct = original.set.fields.distinct()
                    val selected = if (distinct.size < original.set.fields.size) {
                        original.copy().adjustSelect { select(distinct) }
                    } else original
                    val fields = selected.set.realFields.toSet()
                        .mapIndexed { index, expression -> expression to index }.toMap()
                    currentCoroutineContext().ensureActive()
                    val cursor = checkNotNull(execQuery(selected) { it })
                    var readingFailure: Throwable? = null
                    try {
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            if (!cursor.next()) break
                            currentCoroutineContext().ensureActive()
                            val item = mapper(ResultRow.create(JdbcResult(cursor), fields))
                            currentCoroutineContext().ensureActive()
                            channel.send(item)
                        }
                    } catch (cancelled: CancellationException) {
                        readingFailure = cancelled
                        throw cancelled
                    } catch (caught: Throwable) {
                        readingFailure = caught
                        throw caught
                    } finally {
                        try {
                            cursor.close()
                        } catch (caught: Throwable) {
                            closeFailure = caught
                            val primary = readingFailure
                            if (primary == null) throw caught
                            if (caught !== primary) primary.addSuppressed(caught)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                failure = cancelled
                throw cancelled
            } catch (caught: Throwable) {
                failure = caught
            } finally {
                channel.close(failure)
            }
        }
        var consumerFailure: Throwable? = null
        try {
            for (item in channel) emit(item)
        } catch (cancelled: CancellationException) {
            consumerFailure = cancelled
            throw cancelled
        } catch (caught: Throwable) {
            consumerFailure = caught
            throw caught
        } finally {
            channel.cancel()
            producer.cancel()
            withContext(NonCancellable) { producer.join() }
            val primary = consumerFailure
            val cleanup = closeFailure
            if (primary != null && cleanup != null && cleanup !== primary &&
                primary.suppressed.none { it === cleanup }) {
                primary.addSuppressed(cleanup)
            }
        }
    }
}
```

운영 로그는 내부 `KLoggingChannel` logger에 시작·정리 완료·실패 종류만 기록한다. SQL, row, binding, 사용자 exception message/stack은 이 logger에 전달하지 않는다. Exposed와 driver가 별도 logger에 남기는 메시지까지 정제한다고 설명하지 않는다.

### T3.1 fixture 구성과 관측

테스트 패키지에서 `ClickHouseConnectionWrapper`를 그대로 사용한다. `TrackingClickHouseConnection`은 `Connection by delegate`로 구현하고 준비된 Statement와 ResultSet에만 JDK Proxy를 사용한다. 메서드 위임은 다음 원형 보존 함수를 사용한다.

```kotlin
private fun invokeJdbc(target: Any, method: java.lang.reflect.Method, args: Array<out Any?>?): Any? =
    try {
        method.invoke(target, *(args ?: emptyArray()))
    } catch (caught: java.lang.reflect.InvocationTargetException) {
        throw caught.targetException
    }
```

각 자원의 최초 close는 AtomicBoolean CAS로 기록하고, 실제 close 후 오류를 주입한다. Statement는 `executeQuery`의 ResultSet을 decorate한다. Connection `prepareStatement`의 모든 overload는 래퍼가 `prepareStatement(String)`으로 정규화하므로 그 지점에서 관측한다. Test fixture의 `opened`, `closed`, `next`, `executed`는 AtomicInteger이고 각 테스트마다 새 인스턴스를 만든다. close 계수만 증가시키고 delegate 호출을 생략하는 fixture는 금지한다.

```kotlin
val pool = HikariDataSource(HikariConfig().apply {
    jdbcUrl = "jdbc:clickhouse://${clickhouse.host}:${clickhouse.port}/default"
    username = clickhouse.username
    password = clickhouse.password
    maximumPoolSize = 2
    minimumIdle = 0
    connectionTimeout = 500
    isAutoCommit = true
})
// ClickHouseDatabase.DRIVER 접근으로 기존 dialect 등록을 먼저 실행한다.
val driver = ClickHouseDatabase.DRIVER
val trackedDb = Database.connect(getNewConnection = {
    TrackingClickHouseConnection(ClickHouseConnectionWrapper(pool.connection))
})
```

fixture별 주입점은 query 생성, execute, next, mapper, ResultSet close, Statement close, connection close다. 직접 close는 원래 Throwable identity/suppressed를 검사하고, Exposed가 삼키는 두 close 실패는 test logback ListAppender로 `Statements close failed`/`Transaction close failed` 기록과 실제 정리 완료를 확인한다. 테스트는 pool을 finally에서 닫으며 라이브러리 호출 이후에도 `pool.isClosed`가 false인지 먼저 확인한다.

fixture의 핵심 위임 코드는 다음과 같다. `JdbcObservation`은 테스트마다 생성해 연결들이 공유하며, hook은 실행 전에 설정하고 수집 도중 변경하지 않는다. 생성자/execute 실패는 획득하지 않은 자원을 close 계수에 넣지 않는다. 준비된 Statement 생성 실패 시 연결은 Exposed가 정리한다.

```kotlin
internal class JdbcObservation {
    val connections = AtomicInteger()
    val statements = AtomicInteger()
    val results = AtomicInteger()
    val executed = AtomicInteger()
    val next = AtomicInteger()
    var beforeNext: () -> Unit = {}
    var afterNext: () -> Unit = {}
    var resultCloseFailure: Throwable? = null
    var statementCloseFailure: Throwable? = null
    var connectionCloseFailure: Throwable? = null
}

internal class TrackingClickHouseConnection(
    private val delegate: Connection,
    private val observed: JdbcObservation = JdbcObservation(),
): Connection by delegate {
    private val closed = AtomicBoolean()
    private val markers = mutableMapOf<String, String>()

    init { observed.connections.incrementAndGet() }

    override fun setClientInfo(name: String, value: String?) {
        if (value == null) markers.remove(name) else markers[name] = value
    }
    override fun getClientInfo(name: String): String? = markers[name]

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { delegate.close() } finally { observed.connections.decrementAndGet() }
        observed.connectionCloseFailure?.let { throw it }
    }

    override fun prepareStatement(sql: String): PreparedStatement =
        trackStatement(delegate.prepareStatement(sql))

    override fun prepareStatement(sql: String, autoGeneratedKeys: Int): PreparedStatement =
        prepareStatement(sql)

    private fun trackStatement(statement: PreparedStatement): PreparedStatement {
        observed.statements.incrementAndGet()
        val closed = AtomicBoolean()
        return Proxy.newProxyInstance(
            PreparedStatement::class.java.classLoader, arrayOf(PreparedStatement::class.java),
        ) { _, method, args ->
            when (method.name) {
                "executeQuery" -> {
                    observed.executed.incrementAndGet()
                    trackResult(invokeJdbc(statement, method, args) as ResultSet)
                }
                "close" -> {
                    if (closed.compareAndSet(false, true)) {
                        try { invokeJdbc(statement, method, args) }
                        finally { observed.statements.decrementAndGet() }
                        observed.statementCloseFailure?.let { throw it }
                    }
                    null
                }
                else -> invokeJdbc(statement, method, args)
            }
        } as PreparedStatement
    }

    private fun trackResult(result: ResultSet): ResultSet {
        observed.results.incrementAndGet()
        val closed = AtomicBoolean()
        return Proxy.newProxyInstance(
            ResultSet::class.java.classLoader, arrayOf(ResultSet::class.java),
        ) { _, method, args ->
            when (method.name) {
                "next" -> {
                    observed.beforeNext()
                    val value = invokeJdbc(result, method, args)
                    observed.next.incrementAndGet()
                    observed.afterNext()
                    value
                }
                "close" -> {
                    if (closed.compareAndSet(false, true)) {
                        try { invokeJdbc(result, method, args) }
                        finally { observed.results.decrementAndGet() }
                        observed.resultCloseFailure?.let { throw it }
                    }
                    null
                }
                else -> invokeJdbc(result, method, args)
            }
        } as ResultSet
    }
}
```

이 fixture의 `prepareStatement(String, IntArray)`와 `prepareStatement(String, Array<String>)`도 동일한 `prepareStatement(sql)`로 위임하여 테스트 관측을 빠뜨리지 않는다. 필요한 imports는 `java.sql.*`, `java.lang.reflect.Proxy`, `java.util.concurrent.atomic.AtomicBoolean`, `AtomicInteger`이며 실제 파일에서는 명시적 import만 남긴다. marker는 해당 연결 생산자에서만 읽고 쓰며 공유 tenant 저장소가 아니다.

### T3.2~3 유한 gate와 예외 표

블로킹 위치 고정에는 테스트 내부의 `CountDownLatch(1)` 두 개만 사용한다. 테스트는 `runSuspendIO(timeout = 30.seconds)`에서 실행하고 모든 blocking wait는 5초로 제한한다. suspend 상태 관측은 `untilSuspending`을 사용한다. 단일 취소 시점 증명은 동시성 stress tester의 역할과 달라 이 fixture에만 latch를 둔다.

```kotlin
val entered = CountDownLatch(1)
val release = CountDownLatch(1)
val job = launch {
    queryFlow(trackedDb, query = { Numbers.selectAll().limit(10) }, mapper = {
        entered.countDown()
        check(release.await(5, TimeUnit.SECONDS)) { "test gate timed out" }
        it[Numbers.number]
    }).collect { error("cancelled result must not be delivered") }
}
try {
    org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5))
        .untilSuspending { entered.count == 0L }
    job.cancel()
} finally {
    release.countDown()
    job.cancelAndJoin()
}
job.isCancelled.shouldBeTrue()
pool.hikariPoolMXBean.activeConnections shouldBeEqualTo 0
```

같은 gate를 next 전·후와 mapper에 각각 배치한다. send 대기는 소비자의 CompletableDeferred gate와 `mapped == received + 1` 관측으로 고정한다. 모든 finally는 gate를 먼저 풀고 join하여 deadlock을 피한다. 외부 취소 후 새 collect는 성공해야 한다.

| 주 원인 | close 주입 없음 | ResultSet close 오류 주입 |
|---|---|---|
| 없음 | 전체 count/checksum, 자원 0 | close 오류와 동일한 예외 |
| mapper 오류 | 동일한 mapper 예외 | 동일한 mapper 예외 + close 오류 suppressed |
| collector 오류 | 동일한 collector 예외 | 동일한 collector 예외 + close 오류 suppressed |
| 외부 Job 취소 | Job cancelled, 방출 증가 없음 | 취소 원인 보존 + close 오류 suppressed |
| take(1) | `[0L]`, 정상 완료 | `[0L]`, 정상 완료; 내부 종료 원인에만 close 오류 보존 |

각 행에 Statement·connection close 주입을 별도로 반복하되 Exposed의 로그 후 진행 정책을 기대한다. mapper에서 세 번째 행에 SQLException을 던지는 별도 케이스는 재실행 0회, execute=1과 `[0L, 1L]`만 전달됐는지 확인한다.

### T4.1 보안 경계의 부정 테스트

테스트 fixture는 연결마다 `clientInfo` marker를 보관하는 decorator를 제공한다. 외부 tx에서 marker=`outer-only`를 설정하고 새 Flow query에서 null을 관측한다. 이 검사는 연결 지역 상태의 비상속만 증명하며 실제 서버 인증/tenant 보안을 증명한다고 표시하지 않는다. 두 tenant의 읽기 값은 고유 임시 fixture 또는 읽기 전용 상수 식으로 준비하고, Exposed `eq` bound predicate에 `"tenant-a' OR 1=1 --"`를 전달하여 빈 결과 및 execute=1을 검사한다. 동적 식별자는 테스트 입력으로 쓰지 않는다. 실제 tenant ID 입력에서는 해당 tenant만 반환되어야 한다.

확장 함수 logger만 ListAppender로 수집하고 synthetic binding/mapper message 문자열이 없는지 검사한다. Exposed/driver logger의 기존 로그까지 차단하는 테스트는 만들지 않는다. Kotlin imports는 `org.jetbrains.exposed.v1.core.eq`, assertions는 `io.bluetape4k.assertions`를 사용한다.

### T6.2 계측 실행 절차

benchmark 기존 source set에 계측 진입점 `ClickHouseStreamingProfile.kt`를 추가하고 기존 benchmark API 호출을 공유한다. 별도 새 모듈은 만들지 않는다. `JavaExec("profileClickHouseStreaming")`는 benchmark runtimeClasspath와 이 진입점을 사용하고 `jvmArgs("-Xms256m", "-Xmx256m", "-XX:NativeMemoryTracking=summary")`를 고정한다. `-PprofileApi=list|flow`, `-PprofileRows=100000|1000000`, `-PprofileRun=1|2|3`을 args로 받는다. 진입점은 같은 SQL을 2회 warmup한 뒤 한 번 측정하고 종료한다. 각 invocation은 새 JVM이다.

```bash
./gradlew :benchmark-exposed-benchmark:profileClickHouseStreaming -PprofileApi=flow -PprofileRows=100000 -PprofileRun=1 --console=plain
```

이 명령의 api/rows/run 조합 12개를 순차 실행하며 홀수 run은 list→flow, 짝수 run은 flow→list다. 처리량 JMH 실행과 live-memory 프로파일 실행은 분리한다. `System.nanoTime`으로 query 시작·최초 소비·마지막 소비 시간을 기록하고 count/checksum을 검증한다. profile에서만 0/25/50/75/100% 지점의 소비를 잠깐 멈춰 `System.gc()` 후 heap 사용량을 기록한다. 동시에 10ms 주기로 used heap, BufferPoolMXBean.direct/mapped, 외부 `ps -o rss= -p PID`를 수집한다. JVM 기준선은 서버 준비·warmup 후 측정한다.

순서 교대는 한 JVM 내부가 아니라 API별 독립 JVM 두 개의 실행 순서를 뜻한다. 비교 단위는 동일 rows/run의 두 프로세스이며 다음 순서로 고정한다.

```bash
for profile_rows in 100000 1000000; do
  for profile_run in 1 2 3; do
    profile_apis=(list flow)
    if [ "$profile_run" = 2 ]; then profile_apis=(flow list); fi
    for profile_api in "${profile_apis[@]}"; do
      ./gradlew :benchmark-exposed-benchmark:profileClickHouseStreaming \
        -PprofileApi="$profile_api" -PprofileRows="$profile_rows" \
        -PprofileRun="$profile_run" --console=plain
    done
  done
done
```

List 측정은 `val rows = queryList(...)` 반환 직후 소비 전에 checkpoint를 실행한다. rows를 지역 변수에 유지한 상태로 GC/heap/direct 샘플을 기록하고, 소비 종료 후 `Reference.reachabilityFence(rows)`를 호출하여 JIT가 측정 전에 List를 죽은 객체로 취급하지 못하게 한다. materialization 중 raw peak 샘플도 별도로 기록한다. Flow는 미리 List로 만들지 않는다. 두 API 모두 `count == rowCount.toLong()`와 `checksum == rowCount.toLong() * (rowCount - 1L) / 2`를 검사한다. 입력은 10만/100만으로 제한하므로 Long overflow가 없으며 예제 JMH `sum`은 이 프로파일 검사를 대신하지 않는다.

JFR은 측정 JVM의 `jdk.jfr.Recording`으로 `jdk.ObjectAllocationSample`, `jdk.OldObjectSample`, `jdk.GCHeapSummary`를 켜고 `build/reports/clickhouse-profile/{api}-{rows}-{run}.jfr`에 저장한다. JSON/CSV에는 JVM PID·버전·driver·서버 이미지·행 폭·timeout·원시 샘플과 기준선을 함께 기록한다. 강제 GC 프로파일의 TTFI/throughput을 지연 SLO로 해석하지 않으며 별도 무계측 JMH 결과와 구분한다.

allocation/old-object event에는 `withStackTrace()`를 설정한다. Recording은 측정 쿼리 직전에 시작해 소비와 정리 후 끝내고, warmup 할당과 분리한다. `jfr print --events jdk.ObjectAllocationSample,jdk.OldObjectSample,jdk.GCHeapSummary <file>`에서 List/배열의 allocation stack과 available object age/size를 확인한다. sample 부재는 retaining path 부재의 증거가 아니다. 결과 규모에 비례하는 driver 배열이 의심되면 소비 checkpoint에서 live heap dump를 얻어 root 경로를 확인하거나 AC-08을 PENDING으로 남긴다. 원시 .jfr/샘플/필요한 .hprof 경로를 validation에 기록하고 단순 JFR 생성 성공으로 통과시키지 않는다.

기준선 차감 live heap은 각 측정 지점의 최대값이다. 3회 중앙값으로 100만/10만 비율을 계산한다. 기준선 차이가 0 이하이거나 노이즈가 차이보다 크면 PENDING이다. heap 비율≤3만으로 통과시키지 않고 RSS/direct 증가와 JFR의 전체 결과 보관 경로도 검사한다. JDBC 전체 버퍼가 첫 소비 전에 할당·해제되어 live 샘플에 잡히지 않는 경우 raw peak/RSS/JFR 증거로 설계를 재검토한다. 결과 파일 생성 성공과 성능 수용 기준 통과는 별도 판정한다.
