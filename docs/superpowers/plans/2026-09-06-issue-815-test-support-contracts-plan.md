# #815 test-support 제공자 구현 계획

> 실행 담당자는 `$executing-plans`를 사용해 이 계획을 작업별로 실행한다. 현재 세션에서 순차 구현하며 리뷰만 독립 실행을 시도한다. 리뷰 실행 불가 시 이유를 기록하고 inline fallback을 적용한다.

**목표:** 기존 enum API의 바이너리 호환성을 유지하면서 caller-owned DB를 위한 JDBC/R2DBC fixture와 검증 가능한 lifecycle을 제공한다.

**구조:** 기존 두 artifact 안에 backend별 fixture와 실행 경계를 둔다. 기존 함수는 descriptor를 유지한 채 같은 실행 경계에 위임한다. 연결 공급원, 테스트 데이터, pool/container의 소유권은 호출자에게 남긴다.

**기술:** Kotlin, Exposed 1.5.0, kotlinx.coroutines, JUnit, H2, PostgreSQL, Gradle ABI dump, Ruby publication 검증.

## 기준과 범위

- 승인 명세: `docs/superpowers/specs/2026-09-06-issue-815-test-support-contracts-design.md`.
- 명세 커밋: `b54ea75b9ea87f53ac822d46e2c09fb24a0732f1`; 명세 SHA256: `c454d89e464800db5cbcb2c374c57dd44dd68ee1fea5dd5e0b7a460e02de4ebc`.
- 구현 기준: `03597729429fa0fe2ae74f2548640fdf683e082a`.
- 작업 위치: `.worktrees/feat/issue-815-test-support-contracts`; base `develop`.
- 제공자 구현·검증·PR 생성은 승인 범위다. 작성 명세도 승인되었다. 이 계획의 승인 이후 소스 구현을 시작한다.
- downstream 변경, catalog 승격, 배포, workflow dispatch, 머지, 브랜치 삭제는 실행하지 않는다. PR은 `Refs #815`를 사용하며 이슈 전체를 자동 종료하지 않는다.
- 새 dependency/module, 전역 custom-key registry, public close/reset API를 만들지 않는다.

## 파일 책임

아래 경로 별칭은 이 문서에서만 사용한다. 실제 편집·커밋에는 완전한 경로를 지정한다.

| 별칭 | 정확한 디렉터리 |
|---|---|
| J | `exposed/jdbc-tests/src/main/kotlin/io/bluetape4k/exposed/tests` |
| R | `exposed/r2dbc-tests/src/main/kotlin/io/bluetape4k/exposed/r2dbc/tests` |
| JT | `exposed/jdbc-tests/src/test/kotlin/io/bluetape4k/exposed/tests` |
| RT | `exposed/r2dbc-tests/src/test/kotlin/io/bluetape4k/exposed/r2dbc/tests` |

- 신규 `J/JdbcTestDbFixture.kt`, `R/R2dbcTestDbFixture.kt`: key, 기본 wrapper, 생성 callback, 종료 등록과 초기화 원자성.
- 신규 `J/JdbcFixtureExecution.kt`, `R/R2dbcFixtureExecution.kt`: permit, 활성 진입 토큰, 트랜잭션 컨텍스트, 임시 wrapper 정리. 공개 API는 fixture 파일과 기존 함수 파일에 한정한다.
- 기존 `J/WithDB.kt`, `J/WithDBSuspending.kt`, `R/withDb.kt`: 기존 선언 유지 및 additive overload.
- 기존 `J/WithTables.kt`, `J/WithTablesSuspending.kt`, `J/WithSchemas.kt`, `J/WithSchemasSuspending.kt`, `R/withTables.kt`, `R/withSchemas.kt`: 같은 backend 내부 실행 경계 재사용, DDL 실패 우선순위.
- 기존 양쪽 `TestDB.kt`: enum 항목·공개 connect/db 계약 유지. enum은 새 interface를 구현하지 않는다.
- 신규 `JT/JdbcTestDbFixtureTest.kt`, `JT/JdbcFixtureConcurrencyTest.kt`, `JT/JdbcFixtureCleanupTest.kt` 및 `RT/R2dbcTestDbFixtureTest.kt`, `RT/R2dbcFixtureConcurrencyTest.kt`, `RT/R2dbcFixtureCleanupTest.kt`: 신규 계약 검증.
- 신규 `exposed/jdbc-tests/src/test/kotlin/consumer/JdbcFixtureConsumerTest.kt`, `exposed/r2dbc-tests/src/test/kotlin/consumer/R2dbcFixtureConsumerTest.kt`: provider package 밖의 custom key 소비자.
- 신규 `scripts/publication/validate_test_support_consumer.rb`, `scripts/publication/test_validate_test_support_consumer.rb`: 두 artifact에 한정한 로컬 published-coordinate 소비자·구버전 linkage 검증.
- 기존 `exposed/jdbc-tests/build.gradle.kts`, 양쪽 `README.md`/`README.ko.md`, `api/bluetape4k-exposed-jdbc-tests.api`, `api/bluetape4k-exposed-r2dbc-tests.api`: 의존성·문서·ABI.

## 공통 실행 규칙

각 테스트 묶음은 테스트 추가 → RED 출력 확인 → 해당 최소 구현 → 같은 명령 GREEN 확인 → diff 검토 → 한국어 Lore 커밋 순서다. 테스트가 예상과 다른 이유로 실패하면 구현하지 않고 원인을 먼저 분리한다. 아래 신규 테스트 이름은 구현할 파일과 클래스 이름이다.

실제 DB 테스트는 기존 `runSuspendIO`와 해당 모듈의 TestDB 연결 설정을 재사용한다. 가상 시간으로 JDBC I/O를 검증하지 않는다. 동시성 순서는 `CompletableDeferred`/latch로 제어하며 sleep으로 추측하지 않는다. timeout은 deadlock 감지에만 사용한다. backend 명령은 각각 별도 Gradle 호출로 순차 실행한다.

## 작업 1 — 변경 전 호환성 소비자를 고정한다 (AC-01, AC-08)

- [ ] 기존 공개 선언과 두 ABI 파일을 기준 커밋에서 보존한다. 신규 소스 변경 전에 이전 JAR로 소비자를 컴파일한다.
- [ ] 로컬 검증 스크립트는 `--baseline-ref`, `--candidate-root`, `--output-dir`를 필수 입력으로 받는다. 기준 ref를 commit으로 해석하고, 기존 디렉터리를 덮어쓰지 않는다. 임시 기준 소스는 `git archive`로 추출하며 사용자 worktree를 전환하지 않는다.
- [ ] 검증용 Gradle init script에서만 `build/issue815/m2-baseline`과 `build/issue815/m2-candidate` Maven 저장소를 등록한다. `publishToMavenLocal`, 외부 repository publish, 전체 `publishPublicationValidation`은 사용하지 않는다. 두 모듈과 필요한 project dependency만 검증 저장소에 발행한다.
- [ ] 소비자는 기존 `withDb`, `withDbSuspending`, deprecated `withSuspendedDb`, table/schema 함수의 기본 인자 호출을 포함한다. 기존 JAR로 컴파일된 `.class`를 그대로 유지하고 실행 classpath의 provider JAR만 후보로 교체한다. 후보에 맞춰 재컴파일한 결과는 linkage 증거가 아니다.
- [ ] 같은 output-dir 재실행은 baseline class와 checksum을 검증해 재사용하고 candidate 하위 출력만 Gradle로 재생성한다. baseline-ref가 다르거나 manifest가 없는 기존 디렉터리는 실패 처리한다. `--output-dir` 아래에 두 Maven 저장소와 manifest를 두며 앞서 설명한 `build/issue815/m2-*`는 저장소 역할명이지 별도 고정 출력 경로가 아니다.
- [ ] 스크립트 단위 테스트는 누락 JAR, 기준 빌드 실패, 후보 실행 실패, main runtime의 test-support 유출에서 비영 종료를 검증한다. 프로세스 호출은 argv 배열로 수행한다.

```ruby
require 'minitest/autorun'
require 'open3'

class ValidateTestSupportConsumerTest < Minitest::Test
  def test_missing_arguments_fail_closed
    _out, _err, status = Open3.capture3(
      'ruby', File.join(__dir__, 'validate_test_support_consumer.rb'))
    refute status.success?
  end
end
```

```bash
ruby scripts/publication/test_validate_test_support_consumer.rb
ruby scripts/publication/validate_test_support_consumer.rb --baseline-ref 03597729429fa0fe2ae74f2548640fdf683e082a --candidate-root "$PWD" --output-dir "$PWD/build/issue815/consumer"
```

첫 RED는 검증기 파일 부재가 아니라 잘못된 입력을 받아들이는 구현이나 의도적으로 호환성을 깨뜨린 fixture로 확인한다. GREEN 증거에는 기준 JAR끼리의 linkage 성공, 부정 사례의 비영 종료, 보존한 class checksum을 포함한다.

## 작업 2 — JDBC fixture 초기화와 구성 경계를 만든다 (AC-02, AC-05, AC-06)

- [ ] `JdbcTestDbFixtureTest`에 초기 database=null, 기본 생성 1회, 첫 configure 생성 2회, 후속 기본 wrapper 재사용 테스트를 작성한다.
- [ ] factory의 공개 인자 이름은 `key`, `createDatabase`, `onShutdown`으로 고정한다. 생성 callback의 인자는 nullable이 아닌 `DatabaseConfig.Builder.() -> Unit`이다. 기본 호출에는 빈 callback을 전달한다.

```kotlin
val key = "fixture-key"
var creates = 0
val fixture = jdbcTestDbFixture(
    key = key,
    createDatabase = { configure ->
        creates++
        org.jetbrains.exposed.v1.jdbc.Database.connect(
            url = "jdbc:h2:mem:fixture-contract;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
            databaseConfig = org.jetbrains.exposed.v1.core.DatabaseConfig {
                configure()
            },
        )
    },
    onShutdown = {},
)
check(fixture.database == null)
withDb(fixture, configure = { defaultFetchSize = 17 }) { check(it == key) }
check(creates == 2)
val baseline = fixture.database
withDb(fixture) { check(currentJdbcTestDbFixture === fixture) }
check(fixture.database === baseline)
check(creates == 2)
```

- [ ] 내부 hook registrar는 생성자 주입이 가능하게 하고 공개 factory는 JVM registrar를 사용한다. 생성 실패·등록 실패·등록 후 cleanup 실패를 주입하여 캐시 미공개, 예외 동일성, 후속 재시도를 검증한다.
- [ ] baseline과 temporary가 같은 인스턴스이면 거부하고 baseline은 unregister하지 않는다. 다른 temporary는 transaction 종료 후에만 unregister한다. 외부 pool close 횟수는 0이어야 한다.
- [ ] legacy bridge는 `TestDB.connect`의 db 변경을 permit 안에서 복원하고 hook 등록 성공 후에만 baseline을 공개한다. JDBC `beforeConnection`을 바깥에서 중복 호출하지 않는다.

```bash
./gradlew :bluetape4k-exposed-jdbc-tests:test --tests '*JdbcTestDbFixtureTest' --no-parallel --console=plain
```

RED: fixture API 미정의 또는 첫 configure가 기본 구성으로 남음. GREEN: 전체 실행, 실패·신규 skipped 0.

## 작업 3 — JDBC의 blocking/suspend 실행을 공유한다 (AC-01, AC-03, AC-04, AC-05)

- [ ] `JdbcFixtureConcurrencyTest`에 mixed FIFO, 다른 fixture 독립 진행, 대기 취소, 획득 직후 dispatcher 반환 취소, 후속 호출 2개의 상호 배제를 추가한다.
- [ ] 같은 fixture의 활성 토큰을 상속한 중첩만 거부한다. blocking → blocking, suspend → blocking, suspend → dispatcher 전환 → suspend, 자식 coroutine을 검증한다. 바깥 실행 종료 후 만료 토큰은 재진입을 막지 않는다.
- [ ] fair JDK semaphore는 fixture당 하나다. suspend acquire는 interruptible blocking 경계 안에서 수행하고 permit 소유 표시와 release 책임은 dispatcher 반환보다 먼저 확립한다. `try/finally` 밖에서 acquire한 결과를 반환하지 않는다.

```kotlin
withContext(Dispatchers.IO) {
    var acquired = false
    try {
        runInterruptible {
            semaphore.acquire()
            acquired = true
        }
        currentCoroutineContext().ensureActive()
        executeTransaction()
    } finally {
        if (acquired) semaphore.release()
    }
}
```

위 코드는 `JdbcFixtureExecution.kt` 내부 permit 경계다. `semaphore`는 해당 fixture의 `Semaphore(1, true)`이고 `executeTransaction`은 같은 실행 메서드에 전달되는 suspend callback이다. 토큰 등록·무효화는 callback을 감싼 별도 finally로 관리한다.

- [ ] 기존 함수 선언·파일명을 유지한다. 기존 context 인자는 바깥 `withContext(context)`에서 적용하고 내부 트랜잭션은 `org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction`을 사용한다. 이 Exposed 1.5 API에는 context 인자가 없다. `newSuspendedTransaction`을 신규 내부 구현에 사용하지 않는다.
- [ ] 기존 `currentTestDB`, 신규 fixture 식별자를 commit interceptor에 보존한다. custom key는 TestDB로 cast하지 않는다. 본문 성공·예외·Job 취소와 commit 후 관찰값 및 종료 후 이전 transaction context를 검사한다.

```bash
./gradlew :bluetape4k-exposed-jdbc-tests:test --tests '*JdbcFixtureConcurrencyTest' --no-parallel --console=plain
./gradlew :bluetape4k-exposed-jdbc-tests:test --tests '*WithDbTest*' --no-parallel --console=plain
```

RED: 취소 후 후속 호출 timeout 또는 중첩 fail-fast 부재. GREEN: 명시한 모든 interleaving과 기존 호출 통과.

## 작업 4 — R2DBC fixture와 suspend lifecycle을 만든다 (AC-02–AC-06)

- [ ] `R2dbcTestDbFixtureTest`에 작업 2와 같은 생성·구성·등록 실패 행렬을 독립 테스트로 작성한다. callback은 suspend 함수이며 입력 구성은 기존 withDb의 `DatabaseConfig.Builder` 계약을 유지한다. 내부에서 `R2dbcDatabaseConfig` 구성으로 전달하는 기존 `TestDB.connect` 방식을 재사용한다.
- [ ] `R2dbcFixtureConcurrencyTest`에 FIFO, 서로 다른 fixture, 대기 취소, 획득 취소, 활성/만료 토큰, dispatcher 전환, 자식 coroutine을 각각 추가한다.
- [ ] `R2dbcFixtureExecution.kt`는 coroutine `Semaphore(1).withPermit`으로 acquire/release 책임을 묶는다. JDBC blocking semaphore와 공용 superclass를 만들지 않는다.

```kotlin
val firstEntered = CompletableDeferred<Unit>()
val releaseFirst = CompletableDeferred<Unit>()
val first = launch {
    withDb(fixture) {
        firstEntered.complete(Unit)
        releaseFirst.await()
    }
}
firstEntered.await()
val waiting = launch(start = CoroutineStart.UNDISPATCHED) {
    withDb(fixture) { error("취소한 대기자의 본문이 실행됨") }
}
waiting.cancelAndJoin()
releaseFirst.complete(Unit)
first.join()
withTimeout(5_000) { withDb(fixture) { check(it == fixture.key) } }
```

`fixture`는 테스트 클래스가 생성하는 H2 `R2dbcTestDbFixture`이고, 코드는 `runSuspendIO` 안의 `coroutineScope`에서 실행한다. 정상 호출 두 개의 최대 동시 실행 수 1도 별도로 확인해 over-release를 탐지한다.

- [ ] 첫 configure는 baseline과 temporary를 각각 생성한다. R2DBC `beforeConnection`은 wrapper 생성마다 한 번 호출한다. legacy `TestDB.db`, currentTestDB와 신규 fixture context를 복원한다.
- [ ] `NonCancellable`은 취소 후 필요한 suspend cleanup에만 적용한다. acquire, create callback, 본문을 감싸지 않는다. shutdown callback 하나의 실패가 다른 fixture callback 실행을 막지 않는 테스트를 추가한다.

```bash
./gradlew :bluetape4k-exposed-r2dbc-tests:test --tests '*R2dbcTestDbFixtureTest' --tests '*R2dbcFixtureConcurrencyTest' --no-parallel --console=plain
./gradlew :bluetape4k-exposed-r2dbc-tests:test --tests '*WithDbTest*' --no-parallel --console=plain
```

RED: 신규 API 미정의 또는 기존 acquire 인계 취소 결함. GREEN: 신규 skipped 없이 모든 테스트 실행.

## 작업 5 — table/schema overload와 실패 우선순위를 고정한다 (AC-02, AC-07)

- [ ] 양쪽 `FixtureCleanupTest`에 성공, 본문 실패, 실제 Job 취소, drop 실패, recovery 실패, cleanup 자체 취소, 같은 예외 인스턴스, 부분 생성 실패를 parameterized case로 작성한다. 기존 R2DBC `TestSupportsTest`의 dropStatement 실패 주입 방식을 재사용한다.
- [ ] JDBC 네 함수, R2DBC 두 함수에 fixture overload를 추가하고 기존 enum overload와 같은 내부 DDL 경로로 위임한다. receiver를 명시하여 nested transaction의 `this` 혼동을 피한다.
- [ ] 일반 예외 병합은 다음 규칙으로 최소 backend-local helper를 사용한다. 새 공용 module은 만들지 않는다.

```kotlin
internal fun retainFailure(primary: Throwable?, cleanup: Throwable): Throwable {
    if (primary == null) return cleanup
    if (primary !== cleanup) primary.addSuppressed(cleanup)
    return primary
}
```

- [ ] R2DBC withTables의 원래 본문이 CancellationException이면 위 helper 호출을 생략하여 기존 suppressed-empty 계약을 유지한다. 다른 취소 경로는 원래 취소에 직접 받은 cleanup 실패를 보존한다. upstream 내부에서 삼킨 close 예외는 #817 한계로 구분한다.
- [ ] 요청 table/schema만 정리한다. unrelated sentinel table 보존, `dropTables=false` 정상/부분 생성 실패 보존, schema 미지원 시 본문 미실행을 검증한다. H2와 PostgreSQL에서 정상 cleanup 후 실제 metadata로 부재를 확인한다. 실패 주입 잔여물은 테스트별 finally에서 회수한다.

```bash
EXPOSED_TEST_DB=H2 ./gradlew :bluetape4k-exposed-jdbc-tests:test --tests '*JdbcFixtureCleanupTest' --no-parallel --console=plain
EXPOSED_TEST_DB=H2 ./gradlew :bluetape4k-exposed-r2dbc-tests:test --tests '*R2dbcFixtureCleanupTest' --no-parallel --console=plain
EXPOSED_TEST_DB=POSTGRESQL ./gradlew :bluetape4k-exposed-jdbc-tests:test --tests '*JdbcFixtureCleanupTest' --rerun-tasks --no-parallel --console=plain
EXPOSED_TEST_DB=POSTGRESQL ./gradlew :bluetape4k-exposed-r2dbc-tests:test --tests '*R2dbcFixtureCleanupTest' --rerun-tasks --no-parallel --console=plain
```

신규 테스트는 `EXPOSED_TEST_DB`를 명시적으로 읽어 H2/POSTGRESQL case를 선택한다. 미지원 값은 실패 처리한다. 실제 사용한 dialect와 실행 건수를 결과에 남긴다. 환경 변수만 설정하고 PostgreSQL 검증으로 주장하지 않는다.

## 작업 6 — 소비자·의존성·로그·문서를 완성한다 (AC-01, AC-02, AC-08, AC-09)

- [ ] 두 `consumer/*FixtureConsumerTest.kt`는 자체 enum을 key로 사용하고 provider enum을 상속하거나 변경하지 않는다. seed와 FK 역순 삭제는 wrapper에 남긴다. 신규 API의 기본 인자 호출과 table/schema overload를 컴파일·실행한다.
- [ ] key.toString, URL, 설정, callback 예외 메시지에 각각 다른 sentinel을 넣는다. 기존 테스트 로깅 backend의 appender로 provider logger만 수집하고 모든 sentinel 부재를 확인한다. 원래 예외는 호출자에게 같은 객체로 반환되어야 한다.
- [ ] JDBC main source의 Spring 사용 재검색 후 불필요한 `implementation(bt4k.exposed.spring.boot4.starter)`를 제거한다. 빌드나 소비자가 필요성을 증명하면 제거를 강행하지 않고 직접 필요한 기존 의존성을 식별한다. 새 외부 의존성 추가는 범위 변경이다.
- [ ] 작업 1 소비자는 `testImplementation`에 두 artifact를 넣고 main runtime에는 test-support/해당 starter가 없음을 검사한다. test runtime의 테스트 라이브러리나 BOM 이름만으로 실패 처리하지 않는다. 기준 컴파일 class checksum이 그대로인지 재확인한다.

```bash
./gradlew :bluetape4k-exposed-jdbc-tests:generatePomFileForBluetapeExposedPublication :bluetape4k-exposed-r2dbc-tests:generatePomFileForBluetapeExposedPublication :bluetape4k-exposed-jdbc-tests:generateMetadataFileForBluetapeExposedPublication :bluetape4k-exposed-r2dbc-tests:generateMetadataFileForBluetapeExposedPublication --no-parallel --console=plain
ruby scripts/publication/validate_poms.rb exposed/jdbc-tests/build/publications/BluetapeExposed/pom-default.xml exposed/r2dbc-tests/build/publications/BluetapeExposed/pom-default.xml
ruby scripts/publication/validate_module_metadata.rb exposed/jdbc-tests/build/publications/BluetapeExposed/module.json exposed/r2dbc-tests/build/publications/BluetapeExposed/module.json
./gradlew :bluetape4k-exposed-jdbc-tests:updateKotlinAbi :bluetape4k-exposed-r2dbc-tests:updateKotlinAbi --no-parallel --console=plain
./gradlew :bluetape4k-exposed-jdbc-tests:checkKotlinAbi :bluetape4k-exposed-r2dbc-tests:checkKotlinAbi --no-parallel --console=plain
```

- [ ] ABI 변경 전에 workflow helper로 `api/` 전용 write scope를 등록한다. 전체 module의 ABI baseline은 갱신하지 않는다. 기존 descriptor/default bridge 소실은 blocker이며 dump 갱신만으로 수용하지 않는다.
- [ ] 양쪽 README.md/README.ko.md와 한국어 KDoc에 같은 fixture 공유, callback 소유권, 첫 configure, 중첩 거부, cleanup 예외, testImplementation을 설명한다. 영어 README는 영어를 유지하며 중앙 manual tree는 만들지 않는다.
- [ ] sibling `bluetape4k-dependencies/scripts/verify-publication-poms.py`는 이번에 실행하지 않는다. 위 scoped POM/metadata/consumer 검증을 증거로 사용하며 ecosystem 전체 감사나 실제 배포의 증거로 주장하지 않는다.

## 작업 7 — 최종 검증·리뷰·승인된 PR (AC-01–AC-09)

- [ ] 신규 테스트를 포함한 두 모듈 전체 테스트를 각각 실행한다. 기존 assumption skipped와 신규 skipped를 구분하고 JUnit XML에서 tests/failures/errors/skipped를 집계한다.

```bash
./gradlew :bluetape4k-exposed-jdbc-tests:test --rerun-tasks --no-parallel --console=plain
./gradlew :bluetape4k-exposed-r2dbc-tests:test --rerun-tasks --no-parallel --console=plain
./gradlew :bluetape4k-exposed-jdbc-tests:detekt :bluetape4k-exposed-r2dbc-tests:detekt --no-parallel --console=plain
ruby scripts/publication/test_validate_test_support_consumer.rb
git diff --check
```

- [ ] 작업 5 PostgreSQL 검증을 최종 소스에서 재실행하고 작업 1/6의 ABI linkage·POM·metadata 검증을 최종 JAR로 재실행한다. Docker 실패나 skipped는 PASS로 대체하지 않는다. 건강한 Colima를 재시작하지 않는다.
- [ ] 성능·안정성·보안·운영·API·호출자 관점 exact-diff 리뷰와 Type A 필수 검증을 수행한다. 독립 lane 불가 시 원인과 inline provenance를 기록한다. P0/P1 미해결 상태에서 PR 준비 완료라고 보고하지 않는다.
- [ ] `docs/review/2026-09-06-issue-815-implementation-review.md`와 `docs/lessons/2026-09-06-issue-815-test-support-contracts.md`에 정확한 검증 SHA, 명령·결과, #817 한계와 복구 방법을 남긴다. README locale/KDoc 및 새 문서 용어 audit를 실행한다.
- [ ] CI와 Nightly는 기존 두 module을 이미 포함한다. 새 module 등록은 N/A다. exact-head CI에서 신규 테스트가 실제 실행되는지 job/log를 확인한다. 전체 Nightly를 실행하지 않았다면 해당 범위는 미검증으로 표기하며 dispatch하지 않는다.
- [ ] 승인된 head를 push하고 `develop` 대상 한국어 PR을 생성한다. live #815의 label/milestone을 확인해 반영하고 assignee는 debop으로 설정한다. 본문은 `## DoD Status`로 끝내며 provider 완료와 downstream 미완료를 구분한다.
- [ ] PR exact-head CI와 review/thread/mergeability를 읽고 보고한다. 1인 유지보수자의 별도 사람 리뷰는 N/A지만 기술 리뷰·CI는 생략하지 않는다. fresh exact-head 머지 승인을 받기 전 머지·auto-merge·정리는 실행하지 않는다.

## 요구사항 추적과 복구

### 복잡도·위험 예측

| 작업 | 복잡도/선행 | 위험 신호 | 대응·재검증 |
|---|---|---|---|
| 1 | 높음/없음 | 같은 버전의 baseline/candidate가 Gradle cache에서 혼용됨 | 저장소별 별도 Gradle user home 또는 checksum 기반 resolution 검증; JAR SHA 기록, 기준 class 불변 검사 |
| 2 | 높음/1 | hook 실패 후 db가 공개되거나 baseline이 unregister됨 | 실패 주입으로 cache=null·manager 등록·외부 close=0 확인, 해당 lifecycle 커밋 수정 |
| 3 | 높음/2 | 취소 뒤 timeout, 독립 호출을 중첩으로 오판 | acquire 경계 제어 및 활성 토큰 수명 테스트 재실행; NonCancellable 확대 금지 |
| 4 | 높음/1 | suspend 취소 무시, wrapper 생성 hook 중복 | callback 횟수·후속 상호 배제 검증, backend-local 구현 유지 |
| 5 | 높음/3·4 | 원래 실패 대체, 무관한 table 삭제 | 예외 identity와 sentinel metadata 검증, H2/PG 순차 재실행 |
| 6 | 중간/1–5 | default bridge 삭제, main runtime 오염 | descriptor 소실 차단, 이전 compiled class + 후보 JAR 재실행 |
| 7 | 중간/1–6 | skipped/old SHA를 통과로 오인 | XML 건수·실제 dialect·head SHA 기록, 미실행 범위 PENDING 유지 |

작업 1의 publication 검증은 Gradle configuration이 사용하는 `.git` 의존 여부를 먼저 확인한다. archive 빌드가 불가능하면 임시 디렉터리에 baseline commit을 읽기 전용 source로 갖는 local clone을 생성한다. root 브랜치 전환이나 사용자 worktree 삭제로 우회하지 않는다.

공개 동작 문서 외에 AGENTS 변경, 새 module 등록, Spring auto-configuration 변경, 시각 자료 제작은 N/A다. CHANGELOG가 제공자 변경을 기록하는 기존 운영 방식이면 해당 unreleased 구역에 한국어 항목을 추가하고, 그렇지 않으면 PR과 lesson에 기록한다. 구체적인 CHANGELOG 파일 존재와 운영 방식은 구현 직전 확인한다.

| 명세 | 작업 | 완료 증거 |
|---|---|---|
| AC-01 | 1, 3, 6, 7 | 기준 compiled class + 후보 JAR 실행, additive ABI |
| AC-02 | 2, 4, 5, 6 | legacy/custom key 외부 package 소비자 |
| AC-03, AC-04 | 3, 4 | FIFO·독립 진행·혼합 중첩·취소 후 상호 배제 |
| AC-05, AC-06 | 2, 3, 4 | 최초 configure·실패 원자성·context/permit 복원 |
| AC-07 | 5, 7 | H2/PG 실제 DDL·예외 동일성·suppressed 순서 |
| AC-08 | 1, 6, 7 | scoped POM/metadata 및 test/main runtime |
| AC-09 | 6, 7 | locale/KDoc·sentinel·정확한 리뷰 provenance |

호환성·자원 소유권·검증에서 문제가 생기면 해당 작업의 작은 커밋만 수정하거나 명시적 revert 커밋으로 복구한다. root worktree, unrelated worktree, downstream을 되돌리지 않는다. 배포 전 제공자 작업이므로 외부 데이터 migration은 없다. 삭제가 필요한 임시 자료도 정확한 생성 경로를 확인하고 사용자 소유 자료와 분리한다.

## 계획 DoD

- [x] 승인 명세의 AC-01–AC-09를 작업과 검증에 연결
- [x] 변경 파일·순서·소유권·호환성·복구 경계 명시
- [x] 계획 리뷰 및 지적사항 해소 — native 실행 불가로 inline fallback, 리뷰 문서에 provenance 기록
- [x] 작성 계획 사용자 승인 — 현재 스레드의 “승인”에 따라 구현 시작
- [ ] RED/GREEN 구현과 최종 검증
- [ ] 승인된 PR 생성 및 exact-head CI 확인

계획 문서의 명령과 코드는 실행 지침이며 아직 구현·빌드 성공의 증거가 아니다.

## 구현 실행 기록 — 2026-09-06

위 본문은 승인 시점의 실행 계획이다. 완료 판단에는 아래 기록과
[구현 리뷰](../../review/2026-09-06-issue-815-implementation-review.md)의 실제 검증 범위를 적용한다.
미실행 실패 주입 행렬까지 일괄 체크하지 않는다.

| 작업 | 현재 상태 | 근거 |
|---|---|---|
| 1 소비자 기준 | PASS | 이전 class 불변·후보 JAR checksum·main runtime 분리. 검증 runner 단위 테스트 5건 |
| 2 JDBC fixture | 구현·핵심 검증 PASS, 세부 행렬 PENDING | 최초 configure·생성/등록 실패·기본 연결 복원. unregister 자체 실패 주입은 미실행 |
| 3 JDBC 실행 | PASS | 혼합 FIFO·활성/만료 토큰·명시 dispatcher·대기/획득/본문 취소·permit 수 |
| 4 R2DBC fixture | 구현·핵심 검증 PASS, 세부 행렬 PENDING | FIFO·취소·임시 등록 해제·hook. legacy beforeConnection 직접 계측은 미실행 |
| 5 DDL | PASS | H2/PG cleanup, 부분 생성·opt-out·schema 미지원·예외 우선순위 |
| 6 소비자·문서 | PASS, 실행 행렬 한계 기록 | 외부 enum·pool 소비자, 로그 sentinel, ABI 삭제 0, POM/metadata, README 네 파일·KDoc·CHANGELOG |
| 7 리뷰·PR | BLOCKED | 코드 inline fallback 완료, 독립 아키텍처 검토 thread limit. PR/CI 미실행 |

전체 테스트는 JDBC 213건·기존 skipped 16건, R2DBC 194건·기존 skipped 14건이며 실패·오류 0건이다.
최종 PostgreSQL cleanup은 각 8건 통과했다. 신규 fixture 테스트 skipped는 없다.
구현은 `4125687f`, 최종 테스트·CHANGELOG는 `99fea3e9`다.

문서 검증: SPW-01–SPW-04 및 KO-01–KO-06은 실행 로그·SHA·미완료 행을 대조했다.
SPW-05 최종 read-back과 용어 audit를 완료했다.
다음 단계는 남은 실패 주입 행렬과 필수 아키텍처 검토를 마친 뒤 승인된 PR 생성이다.
