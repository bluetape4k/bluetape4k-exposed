# Issue #325 Ktor 캐시 상태 점검 및 메트릭 구현 계획

> **에이전트 작업자용:** 필수 하위 기술: 이 계획을 작업별로 구현하려면 superpowers:subagent-driven-development(권장) 또는 superpowers:executing-plans를 사용하십시오. 단계는 추적을 위해 체크박스(`- [ ]`) 구문을 사용합니다.

**목표:** 기존 Exposed Ktor readiness route에 정제되고 제한된 캐시 준비 상태 및 Micrometer 메트릭을 추가하는 동시에, 아직 릴리스되지 않은 Boolean 캐시 작업자 상태 필드를 명시적인 수명 주기 상태로 교체하고 기존 Ktor JVM descriptor를 모두 유지합니다.

**아키텍처:** JDBC/R2DBC Caffeine repository에서 repository 수명 주기 상태를 유지하고, backend-neutral Ktor contributor를 통해 이를 조정하며, 하나의 공유 단조 증가 캐시 단계 deadline 아래에서 contributor를 순차적으로 집계합니다. 경로별로 고정된 meter 집합을 한 번만 등록하고, atomic holder를 통해 변경 불가능한 sample을 발행하며, Ktor HTTP 세부 정보는 `cache.<component> -> UP|DOWN|timeout`으로 제한합니다. Spring 타입을 Ktor 모듈로 가져오지 않고 동일한 worker-state contract로 Spring Actuator를 조정합니다.

**기술 스택:** Kotlin 2.3+, JetBrains Exposed, kotlinx-coroutines, Ktor 3, Micrometer, Spring Boot 4 Actuator, Caffeine, JUnit 5, MockK, bluetape4k assertions, Gradle 9.6.

---

## 전달 계약

- 저장소: `bluetape4k-exposed`
- 이슈: `#325`
- 기본 브랜치: `develop`
- 작업 브랜치: `feat/issue-325-cache-health-metrics`
- 승인된 설계: `docs/superpowers/specs/2026-07-16-issue-325-ktor-cache-health-design.md`
- Pull request: 구현, 로컬 검증, 독립적인 code review pass 이후 생성합니다. `develop`을 대상으로 하고, `debop`을 assign하며, issue milestone/label을 그대로 반영하고 `Closes #325`를 포함합니다.
- Merge: 정확한 PR/head, green required CI, 현재 승인, 해결되지 않은 review thread가 0개임을 보고한 후 중지합니다. rebase merge 전에 새로운 사용자 승인을 받습니다.
- Dependency rule: 기존 repository 모듈 및 catalog/BOM으로 관리되는 library 외에는 production dependency를 추가하지 않습니다. public Ktor factory가 cache type을 노출하므로 `api(project(":bluetape4k-exposed-cache"))`를 추가합니다. Ktor authentication이 이미 test compile classpath에 없다면 `testImplementation("io.ktor:ktor-server-auth")`만 추가합니다. 해당 version은 기존 Ktor BOM이 계속 관리합니다.
- Version rule: dependency catalog를 수정하지 않으며, 이 branch에서 issue #322의 Exposed 1.3.1 upgrade를 수행하지 않습니다.
- Module rule: 새로운 Gradle module, artifact, workflow 또는 `settings.gradle.kts` 변경을 추가하지 않습니다.
- Manual rule: module README만 업데이트하며, 안정적인 `docs/manual/**` content는 변경하지 않습니다.
- Public documentation rule: 새로운 KDoc 및 PR/commit text는 English로 작성합니다. English와 Korean README를 함께 업데이트하고 `bluetape-writer` parity/naturalness gate를 실행합니다.
- Diagram rule: N/A. 승인된 설계에는 API, table, runbook prose가 필요하며, 생성된 diagram으로 더 명확해지는 새로운 relationship은 없습니다.

## Acceptance 매핑

| ID | 인수 조건 | 작업 | 검증 |
|---|---|---|---|
| AC-1 | 기존 config, installer, 8-parameter route 및 `$default` descriptor가 그대로 유지됨 | 0, 7, 9 | captured baseline 및 `javap` compatibility test |
| AC-2 | Cache worker lifecycle이 not-applicable, idle, running, draining, failed 및 stopped를 구분함 | 1-3 | cache/JDBC/R2DBC state 및 close-race tests |
| AC-3 | Spring JDBC/R2DBC Actuator mapping 및 detail이 finite worker state를 사용함 | 4 | state-matrix auto-configuration tests |
| AC-4 | Ktor contributor가 typed, sanitized, bounded, unique하며 cache-only가 가능함 | 5, 7 | validation, factory, installer 및 route tests |
| AC-5 | Cache readiness가 순차적이며 전체 JDBC/R2DBC/cache endpoint가 보수적인 지원 budget을 따름 | 6 | virtual-time orchestration 및 saturated-dispatcher/DataSource smoke test |
| AC-6 | Request cancellation이 하나의 cancelled outcome을 생성하고 rethrow되며, supplier-thrown cancellation은 sanitized됨 | 6 | structured request/supplier cancellation tests |
| AC-7 | HTTP body 및 metric tag가 secret 또는 임의 caller data를 노출하지 않음 | 5-7 | redaction 및 exact tag-set tests |
| AC-8 | Meter cardinality가 고정되고 collision-safe하며 concurrency-safe함 | 5, 6 | 128-meter-ID bound, atomic registration rollback, repeat/concurrency tests |
| AC-9 | Snapshot gauge가 read-only measurement이며 historical count가 readiness를 down 상태로 만들지 않음 | 5, 6 | snapshot sampling/recovery/concurrency tests |
| AC-10 | Bilingual operator guidance가 security, timing, metrics, runbook, migration 및 Actuator를 다룸 | 8 | parity, API-name, fenced-snippet 및 prose review |

## 고정 JVM 호환성 baseline

Branch는 구현 전에 `./gradlew :bluetape4k-exposed-ktor:classes --no-configuration-cache`로 compile되었습니다. 다음 descriptor를 정확히 보존합니다.

```text
Bluetape4kExposedKtorConfig.<init>:
(Lorg/jetbrains/exposed/v1/jdbc/Database;Lkotlinx/coroutines/CoroutineDispatcher;Lorg/jetbrains/exposed/v1/r2dbc/R2dbcDatabase;ZZLjava/lang/String;Ljava/lang/String;JJLio/micrometer/core/instrument/MeterRegistry;)V

Bluetape4kExposedKtorConfig.<init>$default-marker:
(Lorg/jetbrains/exposed/v1/jdbc/Database;Lkotlinx/coroutines/CoroutineDispatcher;Lorg/jetbrains/exposed/v1/r2dbc/R2dbcDatabase;ZZLjava/lang/String;Ljava/lang/String;JJLio/micrometer/core/instrument/MeterRegistry;ILkotlin/jvm/internal/DefaultConstructorMarker;)V

installBluetape4kExposedKtor:
(Lio/ktor/server/application/Application;Lio/bluetape4k/exposed/ktor/Bluetape4kExposedKtorConfig;)V

installBluetape4kExposedKtor$default:
(Lio/ktor/server/application/Application;Lio/bluetape4k/exposed/ktor/Bluetape4kExposedKtorConfig;ILjava/lang/Object;)V

bluetape4kExposedHealthRoutes-021xcDE:
(Lio/ktor/server/routing/Route;Lorg/jetbrains/exposed/v1/jdbc/Database;Lkotlinx/coroutines/CoroutineDispatcher;Lorg/jetbrains/exposed/v1/r2dbc/R2dbcDatabase;Ljava/lang/String;Ljava/lang/String;JJLio/micrometer/core/instrument/MeterRegistry;)V

bluetape4kExposedHealthRoutes-021xcDE$default:
(Lio/ktor/server/routing/Route;Lorg/jetbrains/exposed/v1/jdbc/Database;Lkotlinx/coroutines/CoroutineDispatcher;Lorg/jetbrains/exposed/v1/r2dbc/R2dbcDatabase;Ljava/lang/String;Ljava/lang/String;JJLio/micrometer/core/instrument/MeterRegistry;ILjava/lang/Object;)V
```

기존 declaration 어느 쪽에도 defaulted cache parameter를 추가하지 않습니다. 별도의 overload를 추가하고 기존 및 새로운 entry point 모두를 internal implementation으로 delegate합니다.

## Risk 예측 및 재실행 trigger

| 위험 | 예방 설계 | 재실행 조건 |
|---|---|---|
| close timeout 이후 늦게 완료된 worker가 `FAILED`를 덮어씀 | completion은 `DRAINING`에서 compare-and-set을 사용하고, timeout은 cancellation 전에 `FAILED`를 발행함 | lifecycle/completion-handler 변경 |
| 첫 write 이전에 lazy worker가 unhealthy로 보임 | write-behind를 `IDLE`로 초기화하고, 처음 승인된 queue write 이후에만 CAS로 `RUNNING`으로 전환하며 draining/terminal state를 절대 덮어쓰지 않음 | queue admission/start 변경 |
| R2DBC fast consumer가 queue depth를 손상시킴 | suspend admission 및 accounting을 linearize하여 accepted-entry increment보다 decrement가 먼저 수행되지 않도록 함 | R2DBC queue 또는 admission 변경 |
| 복구 가능한 flush error가 실수로 terminal이 됨 | `lastFlushError`를 유지하고, 성공적인 flush가 이를 clear하며, uncaught completion/failed drain만 lifecycle을 `FAILED`로 변경함 | flush exception handling 변경 |
| Parent cancellation이 cache timeout으로 잘못 분류됨 | `withTimeoutOrNull`을 local deadline boundary로만 사용하고, 그 외부에서는 sealed result를 반환하며 parent cancellation을 rethrow함 | coroutine timeout wrapper 변경 |
| Concurrent request가 stale gauge sample을 발행함 | invocation/synthetic timeout 시점에만 contributor별 generation을 claim하고, 여전히 최신인 경우에만 publish함 | readiness ordering/state-holder 변경 |
| Meter collision이 일부 등록되거나 route 간 cross-bind됨 | installation-only `ReentrantLock`으로 library preflight/registration을 serialize하고, 생성된 meter를 추적하며 현재 시도를 rollback함 | meter name/tag/registration 변경 |
| Blocking caller probe가 deadline을 초과함 | public contract가 blocking/backend I/O를 금지하고, tests가 unsupported behavior를 문서화하며, library가 isolation thread를 생성하지 않음 | contributor factory/dispatcher 변경 |
| Cardinality가 request 또는 failure에 따라 증가함 | contributor마다 4개 gauge와 4개의 finite timer outcome을 pre-register함 | tag, outcome vocabulary, contributor limit 변경 |
| Secret이 HTTP, exception, log 또는 metric boundary에 도달함 | factory/result boundary에서 sanitize하고, validation은 index/length/reason만 보고함 | validation, detail, logging, tag 변경 |
| 안정적인 manual이 릴리스되지 않은 API를 홍보함 | module README로 docs를 제한하고 stable-manual diff가 비어 있음을 assert함 | 모든 `docs/manual` diff |
| Testcontainers contention이 failure를 가림 | JDBC, R2DBC 및 Spring module gate를 `--no-parallel`로 순차 실행함 | database fixture 또는 CI topology 변경 |

## Repository 위험 점검

- CodeGraph가 이 worktree에 대해 빈 graph를 반환했으므로, 현재 근거 자료는 직접적인 `rg`, context-mode indexing, source inspection 및 compiled `javap` output입니다.
- 기존 Ktor baseline: design commit 이전에 8개 test가 통과했으며, `:bluetape4k-exposed-ktor:classes`도 design-only head에서 통과합니다.
- 기존 `CacheHealthReport`는 아직 릴리스되지 않았으며 production consumer는 JDBC Caffeine, R2DBC Caffeine, Spring JDBC 및 Spring R2DBC뿐입니다.
- 기존 Ktor route는 JDBC 다음에 R2DBC를 순차적으로 실행하고 allowlisted `HealthResponse` detail만 반환합니다. Cache aggregation은 database 이후 이 ordering을 확장합니다.
- 기존 snapshot failure buffer는 이미 제한된 local `size`, `droppedCount` 및 `observerFailureCount`를 노출하므로 새로운 snapshot-buffer API는 필요하지 않습니다.
- `ktor-server-auth`에는 기존 local alias가 없습니다. test-only coordinate는 이미 import된 Ktor BOM에 의존할 수 있으므로 catalog version을 추가하지 않습니다.

## Task 0: Baseline behavior 및 TDD seam 고정

**파일:**
- 생성: `ktor/exposed/src/test/kotlin/io/bluetape4k/exposed/ktor/ExposedKtorAbiCompatibilityTest.kt`
- 아직 수정하지 않음: 프로덕션 Kotlin 소스

- [ ] **Action:** `test-driven-development`, `kotlin-coroutines-skill` 및 적용 가능한 Kotlin testing reference를 불러옵니다. `Bluetape4kExposedKtorConfig::class.java.protectionDomain.codeSource.location`에서 production class directory/JAR을 도출하고 JDK `javap -s -p`를 호출하는 ABI test를 추가합니다. 고정된 위 baseline의 모든 descriptor와 두 `$default` method를 assert합니다. runtime이 `javap`을 실행할 수 없다면 Gradle의 worker-bootstrap `java.class.path`에 의존하지 않고 method/constructor에 대해 reflection 및 `MethodType.toMethodDescriptorString()`을 사용합니다.
  **Evidence:** `./gradlew :bluetape4k-exposed-ktor:test --tests '*ExposedKtorAbiCompatibilityTest' --no-configuration-cache`가 production 변경 전에 통과합니다.
  **Failure:** `javap`을 사용할 수 없거나 captured descriptor가 다르면 production 작업을 중지합니다. 먼저 baseline을 해결하거나 동등한 compiled-consumer test로 교체합니다.
- [ ] **Action:** 현재 database-only Ktor test count와 response body를 기록하고 기존 tests를 regression fixture로 변경하지 않고 유지합니다.
  **Evidence:** `./gradlew :bluetape4k-exposed-ktor:test --no-configuration-cache --rerun-tasks`가 baseline suite green을 보고합니다.
  **Failure:** baseline failure가 있으면 RED test를 추가하기 전에 진단합니다. 이를 issue #325의 원인으로 간주하지 않습니다.
- [ ] **Action:** Lore trailer를 포함하여 compatibility fixture를 commit합니다.
  **Evidence:** Commit에는 ABI fixture만 포함되며 `Tested:`에 passing baseline command가 기록됩니다.
  **Failure:** production 변경을 baseline commit에 섞지 않습니다.

```text
Lock the Ktor binary contract before adding cache readiness

Constraint: Existing database-only callers must retain exact JVM descriptors
Rejected: Appending a defaulted cache parameter | it changes generated default bridges and risks binary callers
Confidence: high
Scope-risk: narrow
Tested: Ktor baseline tests and javap descriptor assertions
```

## 작업 1: 아직 릴리스되지 않은 Boolean health 필드를 worker state로 교체

**파일:**
- 수정: `exposed/cache/src/main/kotlin/io/bluetape4k/exposed/cache/CacheHealthReport.kt`
- 생성: `exposed/cache/src/test/kotlin/io/bluetape4k/exposed/cache/CacheHealthReportTest.kt`

- [ ] **행동:** enum의 exhaustive/order와 `NOT_APPLICABLE`, `IDLE`, `RUNNING`, `DRAINING`, `FAILED`, `STOPPED`를 포함한 report 직렬화에 대한 RED 테스트를 작성한다. reflection을 통해 Boolean property가 더 이상 존재하지 않음을 검증한다. 첫 릴리스 전에 직렬화 가능한 형태가 의도적으로 호환되지 않으므로, `ObjectStreamClass`를 통해 새로운 고정 `serialVersionUID = -1428853048381429257L`를 검증하고 새로운 형태만 round-trip한다.
  **증거:** 대상 테스트가 처음에는 `CacheWorkerState`와 `workerState`가 존재하지 않아 실패한다.
  **실패:** 구현 전에 테스트가 통과하면 단순히 기존 코드를 컴파일하는 것이 아니라 새로운 public contract를 입증하도록 테스트를 강화한다.
- [ ] **행동:** English KDoc과 함께 public contract를 구현한다:

```kotlin
enum class CacheWorkerState {
    NOT_APPLICABLE,
    IDLE,
    RUNNING,
    DRAINING,
    FAILED,
    STOPPED,
}

data class CacheHealthReport(
    val mode: CacheWriteMode,
    val queueDepth: Int,
    val workerState: CacheWorkerState,
    val lastFlushError: Throwable?,
) : Serializable
```

  **증거:** `./gradlew :bluetape4k-exposed-cache:test --tests '*CacheHealthReportTest' --no-configuration-cache`가 통과하고 public KDoc이 각 state의 의미를 다룬다.
  **실패:** 기존 UID를 유지하거나 `isFlushJobRunning`에 대한 compatibility alias를 추가하지 않는다. 둘 다 오해를 유발하는 legacy-deserialization 또는 semantic compatibility를 만들기 때문이다.
- [ ] **행동:** Lore trailers와 함께 commit한다.
  **증거:** commit에 유한한 report contract, KDoc, 그리고 통과한 대상 테스트가 포함된다.
  **실패:** Boolean compatibility alias 또는 관련 없는 cache 변경을 commit하지 않는다.

```text
Make cache worker health explicit before its first release

Constraint: A lazy healthy worker must differ from a failed or closed worker
Rejected: Keeping isFlushJobRunning as an alias | it preserves the ambiguity this issue must remove
Confidence: high
Scope-risk: moderate
Tested: CacheHealthReport contract and serialization tests
```

## 작업 2: 유한 state를 통해 JDBC Caffeine lifecycle 구동

**파일:**
- 수정: `exposed/jdbc-caffeine/src/main/kotlin/io/bluetape4k/exposed/jdbc/caffeine/repository/AbstractJdbcCaffeineRepository.kt`
- 수정: `exposed/jdbc-caffeine/src/test/kotlin/io/bluetape4k/exposed/jdbc/caffeine/repository/JdbcCaffeineRepositoryExtraTest.kt`
- 수정: `exposed/jdbc-caffeine/src/test/kotlin/io/bluetape4k/exposed/jdbc/caffeine/repository/JdbcCaffeinePersistedHookTest.kt`

- [ ] **행동:** write-behind가 아닌 mode에서의 `NOT_APPLICABLE`; 새로운 `IDLE`; 처음 승인된 write의 `RUNNING`; close가 관측 가능한 `DRAINING`; 성공적인 drain의 `STOPPED`; `RUNNING` 중 복구 가능한 flush error; 이후 성공적인 flush에서 error 제거; 포착되지 않은 failure/cancellation의 `FAILED`; 실제 deadline-expired close의 `FAILED`; 별도로 중단된 close의 `FAILED`; late completion이 `FAILED`를 `STOPPED`로 바꿀 수 없음; latch로 제어되는 put-versus-close admission race에 대한 RED 테스트를 추가한다.
  **증거:** 대상 테스트가 기존 Boolean report와 누락된 transition에서 실패한다. public `close()`에서 사용하는 module-internal close-wait duration seam을 추가하되 production 값 30초는 변경하지 않고, 테스트에서는 짧고 결정적인 duration을 사용한다. interruption은 별도의 dedicated-close-thread 테스트로 유지한다.
  **실패:** production timeout을 줄이거나 테스트만을 위한 public timeout knob를 추가하지 않는다. raw thread 사용이 필요하다면 동기 `close()` interruption fixture에만 한정하고 coroutine test helper가 `InterruptedException`을 모델링할 수 없는 이유를 문서화한다.
- [ ] **행동:** write mode에서 초기화되는 단일 authoritative `AtomicReference<CacheWorkerState>`를 추가한다. queue admission이 성공한 뒤에는 `IDLE -> RUNNING` CAS만 사용한다. concurrent한 `DRAINING`, `FAILED`, 또는 `STOPPED` state가 승리하며 절대 덮어쓰지 않는다. channel을 닫기 전에 `IDLE|RUNNING -> DRAINING`을 CAS한다. job completion 시 현재 state가 `DRAINING`이고, completion cause가 null이며, `lastFlushError`가 null이고, queue depth가 0일 때만 `STOPPED`를 publish한다. 그 외에는 late overwrite를 허용하지 않고 `FAILED`를 publish한다. 복구 가능한 flush-error clearing을 유지한다.
  **증거:** state transition 테스트가 반복해서 통과하고 `validateConsistency()`가 계속 O(1), read-only이며 job start/close side effect가 없다.
  **실패:** lazy job에 접근하거나 worker를 시작하거나 I/O를 수행하거나 `Job.isActive`에서 state를 도출하는 probe는 contract를 위반한다.
- [ ] **행동:** 수정된 production 코드의 `terminalError!!`를 명시적인 non-null local/validation 경로로 교체하고 exception type은 안정적으로 유지한다.
  **증거:** 수정된 파일의 source scan에서 `!!`가 보고되지 않으며 repository failure 테스트가 현재 exception type을 유지한다.
  **실패:** cleanup을 관련 없는 refactoring으로 확대하지 않는다.
- [ ] **행동:** `./gradlew :bluetape4k-exposed-jdbc-caffeine:test --no-configuration-cache --no-parallel --rerun-tasks`를 실행한다.
  **증거:** normal close와 persisted-hook cancellation을 포함한 모든 JDBC Caffeine 테스트가 통과한다.
  **실패:** 첫 실행 실패/재시도 통과 lifecycle 동작을 원인 조사 없이 flaky하다고 표시하지 않는다.
- [ ] **행동:** Lore trailers와 함께 commit한다.
  **증거:** commit에는 JDBC lifecycle 변경/테스트만 포함되며 전체 module 결과가 기록된다.
  **실패:** deadline, interruption, admission-close, 또는 late-completion coverage가 red이면 commit하지 않는다.

```text
Expose the real JDBC write-behind lifecycle

Constraint: Readiness probes may only observe O(1) in-memory state
Rejected: Deriving health from Job.isActive | it conflates idle, draining, failed, and stopped states
Confidence: high
Scope-risk: moderate
Tested: JDBC Caffeine lifecycle, recovery, cancellation, close, and late-completion tests
```

## 작업 3: R2DBC Caffeine에서 lifecycle contract 미러링

**파일:**
- 수정: `exposed/r2dbc-caffeine/src/main/kotlin/io/bluetape4k/exposed/r2dbc/caffeine/repository/AbstractR2dbcCaffeineRepository.kt`
- 수정: `exposed/r2dbc-caffeine/src/test/kotlin/io/bluetape4k/exposed/r2dbc/caffeine/repository/WriteBehindCacheTest.kt`

- [ ] **행동:** `runTest`를 사용한 suspend 작업과 동기 `close()` interruption 경로에만 bounded raw thread를 사용하는 방식으로 JDBC와 동일한 RED state matrix, 실제 deadline-expiry seam, interruption case, put-versus-close race coverage를 추가한다. admission/drain interleaving을 반복하고 queue depth가 underflow하지 않으며 drain 후 zero에 도달하고 completion 후 terminal classification을 변경할 수 없음을 검증하는 deterministic fast-consumer 테스트를 추가한다.
  **증거:** 테스트가 Boolean report, 누락된 terminal state, 현재의 send-then-increment accounting race에 대해 실패한다.
  **실패:** production에서 `runBlocking`을 사용하거나 suspend probe를 `runCatching`으로 감싸지 않는다.
- [ ] **행동:** 동일한 CAS-only lifecycle 규칙을 구현한다. accepted send가 count되기 전에 consumer가 decrement할 수 없도록 queue-depth accounting과 suspend admission을 linearize한다. rejected/cancelled send는 phantom depth를 남기지 않으며 close/terminal completion은 late increment와 race할 수 없다. terminal completion을 명시적으로 추적하고 exceptional worker completion 시 queue를 닫아 consumer 없이 이후 send가 accepted되지 않도록 한다. `NonCancellable` final drain을 유지하고 failed final flush 또는 nonzero terminal queue depth를 `FAILED`로 분류한다.
  **증거:** terminal failure 후 이후 write가 거부되고, normal drain이 `STOPPED`가 되며, 실제 timeout/interruption이 `FAILED`가 되고, drain 후 depth가 non-negative/zero로 유지되며, late admission/completion이 terminal state를 덮어쓸 수 없음을 테스트가 입증한다.
  **실패:** `STOPPED` 이후의 예상된 scope cancellation은 state를 `FAILED`로 낮추지 않아야 한다.
- [ ] **행동:** `./gradlew :bluetape4k-exposed-r2dbc-caffeine:test --no-configuration-cache --no-parallel --rerun-tasks`를 실행한다.
  **증거:** lifecycle timing flake 없이 모든 R2DBC Caffeine 테스트가 통과한다.
  **실패:** Testcontainers/database-backed module 실행은 순차적으로 유지한다.
- [ ] **행동:** Lore trailers와 함께 commit한다.
  **증거:** commit에는 R2DBC lifecycle/accounting 변경/테스트만 포함되며 전체 module 결과가 기록된다.
  **실패:** queue-depth underflow, terminal admission, 또는 timing instability가 있으면 commit하지 않는다.

```text
Keep R2DBC write-behind health aligned with its lifecycle

Constraint: Suspend admission must not succeed after the consumer terminates
Rejected: Reporting only the coroutine active flag | it hides terminal and shutdown states
Confidence: high
Scope-risk: moderate
Tested: R2DBC Caffeine lifecycle, terminal admission, close, and race tests
```

## 작업 4: Spring Actuator status와 details 조정

**파일:**
- 수정: `spring-boot/jdbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/jdbc/config/ExposedJdbcCacheHealthAutoConfiguration.kt`
- 수정: `spring-boot/jdbc/src/test/kotlin/io/bluetape4k/spring/data/exposed/jdbc/config/ExposedJdbcCacheHealthAutoConfigurationTest.kt`
- 수정: `spring-boot/r2dbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/r2dbc/config/ExposedR2dbcCacheHealthAutoConfiguration.kt`
- 수정: `spring-boot/r2dbc/src/test/kotlin/io/bluetape4k/spring/data/exposed/r2dbc/config/ExposedR2dbcCacheHealthAutoConfigurationTest.kt`

- [ ] **행동:** 두 indicator에 대해 `NOT_APPLICABLE|IDLE|RUNNING -> UP`, `DRAINING|STOPPED -> OUT_OF_SERVICE`, `FAILED -> DOWN`, 그리고 null이 아닌 모든 `lastFlushError -> DOWN(error)`를 검증하는 RED table-driven 테스트를 추가한다. error가 없는 `FAILED`는 `Health.down()`을 사용하는지 검증한다.
  **증거:** 두 대상 테스트 class가 기존 stalled-queue heuristic에서 실패한다.
  **실패:** queue depth에서 status를 추론하지 않는다. queue depth는 measurement로 유지된다.
- [ ] **행동:** 두 module과 두 repository order 모두에서 mixed-repository precedence case를 추가한다: `lastFlushError > FAILED > DRAINING|STOPPED > UP`.
  **증거:** aggregate status와 선택된 throwable이 repository order와 무관하게 동일하며 모든 error가 global하게 우선한다.
  **실패:** 더 이른 `OUT_OF_SERVICE` report가 이후의 `DOWN` report를 가리지 않도록 한다.
- [ ] **행동:** `flushJobRunning` details를 `workerState`로 교체하고 `repositoryCount`, `mode`, `queueDepth`, optional `lastFlushError` message를 유지한다. JDBC와 R2DBC mapping을 source-equivalent하게 유지한다.
  **증거:** single 및 multiple repository의 detail-key와 status 테스트가 통과한다.
  **실패:** Ktor redaction rules가 Spring의 별도 management-endpoint detail policy로 약화되어서는 안 된다.
- [ ] **행동:** 다음을 순차적으로 실행한다:

```bash
./gradlew :bluetape4k-exposed-spring-boot-jdbc:test --no-configuration-cache --no-parallel --rerun-tasks
./gradlew :bluetape4k-exposed-spring-boot-r2dbc:test --no-configuration-cache --no-parallel --rerun-tasks
```

  **증거:** 두 Spring module suite가 통과한다.
  **실패:** 기존 conditional auto-configuration과 bean name을 모두 유지한다.
- [ ] **행동:** Lore trailers와 함께 commit한다.
  **증거:** commit에는 source-equivalent JDBC/R2DBC Actuator mapping과 통과한 module suite가 포함된다.
  **실패:** status/detail matrix 또는 기존 bean condition이 regression을 일으키면 commit하지 않는다.

```text
Align Actuator cache health with finite worker states

Constraint: Spring management details remain separate from Ktor response redaction
Rejected: Queue-depth stall inference | queue depth alone is not a failure state
Confidence: high
Scope-risk: narrow
Tested: JDBC and R2DBC Actuator state matrices and detail contracts
```

## 작업 5: 타입이 지정된 Ktor 기여자와 고정 미터 등록 추가

**파일:**
- 수정: `ktor/exposed/build.gradle.kts`
- 생성: `ktor/exposed/src/main/kotlin/io/bluetape4k/exposed/ktor/ExposedKtorCacheReadiness.kt`
- 생성: `ktor/exposed/src/main/kotlin/io/bluetape4k/exposed/ktor/ExposedKtorCacheMetrics.kt`
- 생성: `ktor/exposed/src/test/kotlin/io/bluetape4k/exposed/ktor/ExposedKtorCacheContributorTest.kt`
- 생성: `ktor/exposed/src/test/kotlin/io/bluetape4k/exposed/ktor/ExposedKtorCacheMetricsTest.kt`

- [ ] **작업:** `api(project(":bluetape4k-exposed-cache"))`를 추가한 다음, 승인된 네 가지 팩토리, 변경 불가능한 방어적 복사 설정, 비어 있지 않음/최대 16개/고유성 검증, 정확한 컴포넌트 정규식, 원시 이름 비식별화, 음이 아닌 샘플 검증, repository 상태 매핑, 읽기 전용 스냅샷 샘플링에 대한 RED 컴파일/런타임 테스트를 작성합니다.
  **증거:** 기여자/config/status 타입이 존재하지 않아 테스트가 실패합니다.
  **실패 조건:** 독립 실행형 팩토리 예외에는 입력 길이와 안정적인 reason code만 포함할 수 있습니다. 설정 생성 시에는 목록 인덱스와 중복 위치를 추가로 포함할 수 있습니다. 어느 쪽도 원시 입력, cause, 제어 문자, URL, key, namespace 또는 secret-bearing substring을 포함해서는 안 됩니다.
- [ ] **작업:** 다음 public signature를 English KDoc 및 private/internal constructor와 kind로 구현합니다.

```kotlin
enum class ExposedKtorCacheStatus { UP, DOWN }

class ExposedKtorCacheContributor private constructor(/* sanitized finite kind and probe */) {
    companion object {
        fun jdbcRepository(component: String, report: () -> CacheHealthReport): ExposedKtorCacheContributor
        fun r2dbcRepository(component: String, report: suspend () -> CacheHealthReport): ExposedKtorCacheContributor
        fun snapshot(component: String, buffer: SnapshotCacheFailureBuffer): ExposedKtorCacheContributor
        fun custom(component: String, probe: suspend () -> ExposedKtorCacheStatus): ExposedKtorCacheContributor
    }
}

class ExposedKtorCacheReadinessConfig(
    contributors: List<ExposedKtorCacheContributor>,
)
```

  **증거:** 팩토리 테스트는 repository supplier가 메모리 내 report만 매핑하고, snapshot이 각 public measurement를 한 번만 읽으며 drain/mutation을 수행하지 않고, custom probe가 status만 노출함을 입증합니다. KDoc source review는 config/factory 문서에 `[a-z][a-z0-9_-]{0,62}`를 명시하고 tenant/key/URL/namespace/data-bearing name을 금지하며, O(1) 메모리 내 side-effect-free supplier를 요구하고, suspend cancellation cooperation을 요구하며, blocking/backend I/O를 금지하고, library가 isolation thread/dispatcher/scope를 생성하지 않음을 명시하는지 확인합니다.
  **실패 조건:** 임의의 detail/tag map, throwable field, caller-selected kind 또는 serializable internal sample을 노출하지 않습니다.
- [ ] **작업:** contributor마다 정확한 이름, tag, base unit 및 design의 `NaN` semantics를 사용하는 네 개의 gauge와 사전 등록된 네 개의 timer outcome meter ID를 구현합니다. `AtomicReference`에 하나의 immutable sample을 보관하고 contributor마다 하나의 monotonic generation을 보관합니다. 설치된 route state에는 direct meter/holder reference와 immutable tag set을 유지하며, 요청마다 registry lookup, builder call, tag construction 또는 registration을 수행하지 않습니다.
  **증거:** Metrics 테스트는 정확한 meter ID/description/base unit을 검증하고, 해당되지 않는 field는 `NaN`이며, 반복 probe로 meter count가 증가하지 않고, 16 contributor가 정확히 128개의 Micrometer meter ID를 생성함을 검증합니다. 테스트/문서는 exported backend time-series count가 registry/distribution configuration에 의존함을 명시합니다.
  **실패 조건:** measurement, write mode, exception type/message, URL, key, namespace, tenant 또는 request data가 tag가 되어서는 안 됩니다.
- [ ] **작업:** library-owned preflight와 registration을 하나의 installation-only `ReentrantLock`으로 직렬화합니다. 이 lock은 operation 이후 registry reference를 저장하지 않으며 readiness request에서 절대 사용하지 않습니다. 동일한 `component`와 `kind`를 가진 기존 library meter name이 extra tag 또는 호환되지 않는 meter type을 포함하더라도 모두 거부합니다. 현재 시도에서 생성된 meter만 추적하고 이후 registration failure 시 이를 제거하며, 임의의 registry cause가 포함되지 않은 안정적이고 비식별화된 오류를 반환합니다.
  **증거:** 사전 입력된 collision은 ID를 추가하지 않고, N번째 registration 이후 주입된 failure는 residual current-attempt ID를 0개로 남기며, 두 개의 동시 동일 install은 정확히 하나의 winner와 하나의 sanitized loser, 128개의 ID를 생성하고, gauge는 winner에만 바인딩됩니다. 서로 다른 route identity는 `128 * route-count` meter ID를 따릅니다.
  **실패 조건:** 다른 route의 gauge state holder를 조용히 재사용하거나, lock map에 registry를 유지하거나, registry exception을 노출하거나, request-path lock/global registry를 사용하지 않습니다.
- [ ] **작업:** 두 개의 targeted Ktor test class를 실행하고 Lore trailer와 함께 commit합니다.
  **증거:** Contributor 및 metrics 테스트가 정확한 meter-ID, collision, rollback 및 concurrent-install assertion과 함께 통과합니다.
  **실패 조건:** 어떤 installation이라도 partial meter를 남기거나 다른 route의 holder에 바인딩될 수 있다면 commit하지 않습니다.

```text
Bound cache readiness inputs and metric identities at installation

Constraint: Caller-controlled data must not escape into HTTP details or unbounded tags
Rejected: Arbitrary detail and tag maps | they cannot enforce redaction or cardinality
Confidence: high
Scope-risk: moderate
Tested: contributor validation, snapshot sampling, fixed meters, and collision rollback
```

## 작업 6: 하나의 공유 deadline으로 cache readiness 집계

**파일:**
- 수정: `ktor/exposed/src/main/kotlin/io/bluetape4k/exposed/ktor/ExposedKtorHealthRoutes.kt`
- 생성: `ktor/exposed/src/test/kotlin/io/bluetape4k/exposed/ktor/ExposedKtorCacheHealthRoutesTest.kt`
- 생성: `ktor/exposed/src/test/kotlin/io/bluetape4k/exposed/ktor/ExposedKtorReadinessBudgetTest.kt`

- [ ] **작업:** JDBC/R2DBC 이후 installation-order execution, 일반적인 `DOWN`/exception continuation, shared monotonic cache budget, active timeout, skipped probe 미호출, deterministic details 및 aggregate 503 behavior를 입증하는 RED 테스트를 작성합니다. `/healthz/exposed`가 cache supplier를 절대 호출하지 않음도 검증합니다.
  **증거:** route에 cache overload 또는 phase가 없어 테스트가 실패합니다.
  **실패 조건:** contributor를 concurrent하게 실행하거나 각 contributor에 새로운 timeout을 부여하지 않습니다.
- [ ] **작업:** 필요한 final `cacheReadiness` parameter를 포함한 새 route overload를 추가하고 두 overload를 하나의 internal implementation으로 위임합니다. internal orchestrator에는 production default가 있는 injectable backend probe lambda와 `TimeSource`를 제공합니다. 테스트에서는 `TestCoroutineScheduler.timeSource`를 사용합니다. 하나의 cache-phase monotonic deadline과 sealed probe result를 감싼 `withTimeoutOrNull(remaining)`을 사용하고, metrics 및 HTTP details는 timed block 외부에서 정확히 한 번 처리합니다.
  **증거:** Virtual-time 테스트는 느린 active contributor가 `timeout`을 받고, 나머지 contributor는 호출되지 않은 synthetic timeout을 받으며, 지원되는 cache phase가 contributor 수와 무관하게 하나의 readiness timeout을 소비함을 입증합니다. 하나의 bounded real-time smoke assertion도 유지합니다.
  **실패 조건:** Parent/request cancellation을 HTTP timeout 또는 일반 failure로 변환해서는 안 됩니다.
- [ ] **작업:** 새 public route overload에 cache-only usage, caller-owned authentication/lifecycle/concurrency, shared cache deadline, unsupported blocking/backend-I/O probe 및 helper가 resource를 생성하거나 종료하지 않는다는 내용을 다루는 English KDoc를 추가합니다.
  **증거:** Public-Kdoc review에서 모든 ownership, timeout, security 및 unsupported-probe clause가 overload 자체에 포함되어 있음을 확인합니다.
  **실패 조건:** public API contract를 README text에만 의존하지 않습니다.
- [ ] **작업:** exception hierarchy를 명시적으로 구현하고 테스트합니다. Local `withTimeoutOrNull` expiry는 `timeout`이 됩니다. `CancellationException`을 catch할 때 현재 request context가 inactive인 경우에만 다시 throw하고 `cancelled`를 기록합니다. supplier가 이를 throw했지만 request가 active 상태라면 message/cause를 폐기하고, 하나의 sanitized `error`로 매핑한 뒤 계속 진행합니다. cancellation 처리 후에는 ordinary `Exception`만 catch하며, 임의의 `Throwable`을 catch하거나 보관하지 않고 fatal JVM `Error`를 `DOWN`으로 변환하지 않습니다.
  **증거:** Parent cancellation은 하나의 `cancelled`를 기록하고, active newest generation의 gauge만 `NaN`으로 초기화하며, HTTP detail을 방출하지 않고, job을 누출하지 않은 채 다시 throw합니다. Secret-bearing supplier cancellation은 하나의 `error`를 기록하고 아무것도 누출하지 않으며 이후 contributor가 실행됩니다. Fatal-error fixture는 전파됩니다.
  **실패 조건:** 이중 `timeout+cancelled`, swallowed request cancellation, context가 active인 동안 rethrown supplier cancellation 또는 caught fatal error가 있으면 작업을 진행할 수 없습니다.
- [ ] **작업:** concurrency/generation 테스트를 추가합니다. 늦게 완료된 older success가 newer data를 덮어쓰지 못하고, newer success 이후 older active cancellation이 newer sample을 `NaN`으로 교체하지 못하며, contributor B 이전에 취소된 newer request가 older in-flight B를 억제하지 않음을 검증합니다. 반복적인 concurrent request에서도 meter count가 일정하고 gauge read가 안전함을 검증합니다.
  **증거:** Controlled deferred/latch 테스트가 sleep 또는 probabilistic ordering 없이 핵심 interleaving을 bounded number만큼 반복합니다.
  **실패 조건:** cross-request mutex, queue, worker, scheduler, dispatcher 또는 scope를 추가하지 않습니다.
- [ ] **작업:** cache key, SQL, URL, namespace, control character 및 credential을 포함하는 exception message와 malicious value를 사용하는 security test를 추가합니다. response body, validation exception/cause, tag 및 library log 어디에도 이 값들이 포함되지 않음을 검증합니다.
  **증거:** HTTP details에는 `cache.<validated-component>`와 `UP|DOWN|timeout`만 나타나고, meter에는 정확한 finite tag만 나타납니다.
  **실패 조건:** Ktor layer에서 supplier exception을 log하지 않습니다. caller/repository telemetry가 diagnostic detail을 담당합니다.
- [ ] **작업:** 의도적으로 blocking/cancellation-insensitive한 probe가 coroutine deadline 이후에도 실행될 수 있으며 library가 compensating thread/scope를 생성하지 않는다는 unsupported-supplier contract test를 추가합니다. 명시적인 release latch로 bounded 상태를 유지합니다.
  **증거:** 테스트가 제한 사항을 문서화하고 fixture를 release한 후 deterministic하게 종료됩니다.
  **실패 조건:** Unsupported-behavior test가 suite를 hang시키거나 supported-path deadline assertion을 약화해서는 안 됩니다.
- [ ] **작업:** producer, drainer 및 readiness sampler가 동시에 동작하는 bounded snapshot contention test를 추가합니다. Sampling이 `poll`/`drainTo`를 호출하지 않고, block하거나 work를 누출하지 않으며, non-negative measurement만 publish하고, meter ID를 증가시키지 않음을 입증합니다.
  **증거:** Latch-controlled test가 constant meter count로 반복 완료되고 buffer가 caller-drainable 상태로 남습니다.
  **실패 조건:** Historical dropped/observer count만으로 readiness failure가 되어서는 안 됩니다.
- [ ] **작업:** JDBC, R2DBC 및 cache가 모두 활성화된 all-backend budget test를 추가합니다. Internal probe/time seam을 사용해 deterministic additive orchestration을 수행한 다음, `R`에 가깝게 시작하고 `J_effective`를 소비하는 constrained single-thread JDBC dispatcher와 controllable statement/DataSource fixture를 사용합니다. Executor/DataSource를 명시적으로 정리합니다.
  **증거:** Virtual-time assertion은 `I_jdbc*(R+J_effective)+I_r2dbc*R+I_cache*R`을 입증하고, bounded real-time smoke는 declared margin을 더한 formula 이내에 유지되며 executor/connection work를 남기지 않습니다.
  **실패 조건:** Documentation-only math, 제어 latch 없는 wall-clock sleep 또는 누출된 executor/DataSource resource는 이 gate를 충족하지 못합니다.
- [ ] **작업:** `./gradlew :bluetape4k-exposed-ktor:test --tests '*ExposedKtorCacheHealthRoutesTest' --tests '*ExposedKtorReadinessBudgetTest' --no-configuration-cache --rerun-tasks`를 실행하고 commit합니다.
  **증거:** 두 cache-route 및 all-backend budget suite가 deterministic repeated interleaving과 함께 통과합니다.
  **실패 조건:** 어떤 first-fail/retry-pass 결과라도 commit 전에 root-cause investigation이 필요합니다.

```text
Aggregate cache readiness within one deterministic budget

Constraint: Cache probes are caller-owned, non-blocking, and cancellation-cooperative
Rejected: Parallel probes or one timeout per contributor | both increase pressure and destroy the route bound
Confidence: high
Scope-risk: moderate
Tested: ordering, timeout, cancellation, generation, concurrency, and redaction tests
```

## 작업 7: 설치자/cache 전용/인증 경로를 추가하고 ABI 입증

**파일:**
- 수정: `ktor/exposed/src/main/kotlin/io/bluetape4k/exposed/ktor/Bluetape4kExposedKtorConfig.kt`
- 수정: `ktor/exposed/src/main/kotlin/io/bluetape4k/exposed/ktor/Bluetape4kExposedKtor.kt`
- 수정: `ktor/exposed/src/test/kotlin/io/bluetape4k/exposed/ktor/Bluetape4kExposedKtorTest.kt`
- 수정: `ktor/exposed/src/test/kotlin/io/bluetape4k/exposed/ktor/ExposedKtorAbiCompatibilityTest.kt`
- auth 테스트에 `testImplementation("io.ktor:ktor-server-auth")`가 필요한 경우에만 수정: `ktor/exposed/build.gradle.kts`

- [ ] **작업:** 새 설치자 오버로드, null database를 사용하는 cache 전용 설치, 기존 database 전용 동작, `installHealthRoutes=false`, database와 cache가 모두 없는 경우의 거부를 검증하는 RED 테스트를 작성한다.
  **증거:** 구현 전에는 새 오버로드 테스트가 컴파일에 실패하고 기존 테스트는 계속 통과한다.
  **실패 조건:** 기존 config 생성자에 cache 속성을 추가하거나 현재 기본값을 변경하지 않는다.
- [ ] **작업:** 명시적인 `hasCacheContributors` flag를 허용하도록 내부 검증만 변경하고, 정확한 config primary constructor를 유지하며 다음을 추가한다:

```kotlin
fun Application.installBluetape4kExposedKtor(
    config: Bluetape4kExposedKtorConfig,
    cacheReadiness: ExposedKtorCacheReadinessConfig,
)
```

  **증거:** 두 설치자가 공유된 내부 설치로 위임하며, 기본 설치자는 계속 no-op이고, cache 전용 route는 readiness를 반환한다.
  **실패 조건:** 새 오버로드는 기본값이 아닌 cache config를 요구해야 하며 오버로드 모호성을 만들어서는 안 된다.
- [ ] **작업:** 새 설치자 오버로드에 caller가 소유하는 security/resource lifecycle, cache 전용 동작, `installHealthRoutes`, 공유 deadline, 지원되지 않는 blocking/backend-I/O probe를 다루는 English KDoc을 추가한다.
  **증거:** Public-KDoc 검토에서 오버로드에 완전한 ownership 및 safety 계약이 확인된다.
  **실패 조건:** 설치자가 authentication, repositories, dispatchers, registries 또는 shutdown을 소유한다고 암시하지 않는다.
- [ ] **작업:** `installHealthRoutes=false`인 direct route overload 주위에 `authenticate("ops")`를 사용하는 Ktor authentication fixture를 추가한다. 인증되지 않은 접근이 구성된 401/403을 반환하고 readiness 세부 정보를 포함하지 않으며 contributor를 0회 호출하는지 확인한 다음, 인증된 접근이 contributor를 정확히 1회 호출하고 readiness를 반환하는지 확인한다. 컴파일에 필요한 경우에만 테스트 범위 auth coordinate를 추가한다.
  **증거:** Authentication assertions가 통과하고 fixture에 보호되지 않은 두 번째 route가 없다.
  **실패 조건:** helper가 authentication을 설치하거나 root-installed routes를 자체적으로 보호한다고 주장해서는 안 된다.
- [ ] **작업:** ABI 테스트를 다시 실행하고 `javap -s -p` 출력을 검사한다. 고정된 여섯 개 descriptor가 계속 유지되는지 assert하고 다음 추가 descriptor를 고정한다(Duration-mangled route method suffix는 발견해도 되지만, 각 descriptor와 일치하는 추가 method는 정확히 하나여야 한다):

```text
(Lio/ktor/server/application/Application;Lio/bluetape4k/exposed/ktor/Bluetape4kExposedKtorConfig;Lio/bluetape4k/exposed/ktor/ExposedKtorCacheReadinessConfig;)V
(Lio/ktor/server/routing/Route;Lorg/jetbrains/exposed/v1/jdbc/Database;Lkotlinx/coroutines/CoroutineDispatcher;Lorg/jetbrains/exposed/v1/r2dbc/R2dbcDatabase;Ljava/lang/String;Ljava/lang/String;JJLio/micrometer/core/instrument/MeterRegistry;Lio/bluetape4k/exposed/ktor/ExposedKtorCacheReadinessConfig;)V
(Lio/ktor/server/routing/Route;Lorg/jetbrains/exposed/v1/jdbc/Database;Lkotlinx/coroutines/CoroutineDispatcher;Lorg/jetbrains/exposed/v1/r2dbc/R2dbcDatabase;Ljava/lang/String;Ljava/lang/String;JJLio/micrometer/core/instrument/MeterRegistry;Lio/bluetape4k/exposed/ktor/ExposedKtorCacheReadinessConfig;ILjava/lang/Object;)V
```

  기존 positional/default 호출과 새로운 named `cacheReadiness` 호출을 위한 compile fixture를 추가한다.
  **증거:** `./gradlew :bluetape4k-exposed-ktor:test --no-configuration-cache --rerun-tasks`가 통과하고, 정확한 기존/새 descriptor 및 두 source-usage 형태가 assert된다.
  **실패 조건:** 변경된 기존 method name, descriptor, constructor 또는 `$default` bridge가 하나라도 있으면 진행을 중단한다.
- [ ] **작업:** Lore trailers와 함께 commit한다.
  **증거:** commit에 installer/auth/ABI 변경이 포함되고 전체 Ktor suite 및 descriptor proof가 기록된다.
  **실패 조건:** 기존 descriptor가 변경되었거나 인증되지 않은 접근이 contributor를 호출하면 commit하지 않는다.

```text
Add cache-only Ktor installation without breaking existing callers

Constraint: Existing config and route default bridges are binary contracts
Rejected: Extending the existing config constructor | it changes default-constructor ABI
Confidence: high
Scope-risk: moderate
Tested: installer, cache-only, authenticated route, database regression, and javap ABI tests
```

## 작업 8: 안전한 배포, Actuator 매핑 및 마이그레이션 문서화

**파일:**
- 수정: `exposed/cache/README.md`
- 수정: `exposed/cache/README.ko.md`
- 수정: `ktor/exposed/README.md`
- 수정: `ktor/exposed/README.ko.md`
- 수정: `spring-boot/jdbc/README.md`
- 수정: `spring-boot/jdbc/README.ko.md`
- 수정: `spring-boot/r2dbc/README.md`
- 수정: `spring-boot/r2dbc/README.ko.md`
- 생성: `ktor/exposed/src/test/kotlin/io/bluetape4k/exposed/ktor/ExposedKtorReadmeFixture.kt`
- 생성: `ktor/exposed/src/test/kotlin/io/bluetape4k/exposed/ktor/ExposedKtorReadmeParityTest.kt`
- 생성: `docs/lessons/2026-07-16-issue-325-cache-readiness.md`
- 수정하지 않음: `docs/manual/**`

- [ ] **작업:** 편집 전에 `bluetape-writer`를 로드한다. JDBC report supplier, R2DBC suspend supplier, snapshot buffer, custom status, cache-only installer, ingress/network-policy root route 및 manually authenticated direct route를 compile-checked examples와 함께 source-equivalent English/Korean Ktor sections로 추가한다. 표시된 canonical examples를 `ExposedKtorReadmeFixture.kt`에 배치한다. `ExposedKtorReadmeParityTest`가 대응하는 marked README fences를 추출하고, 정규화된 내용을 compiled fixture와 비교하며 두 locale에 필요한 headings/API names가 있는지 assert하도록 한다.
  **증거:** `./gradlew :bluetape4k-exposed-ktor:test --tests '*ExposedKtorReadmeParityTest' --no-configuration-cache`가 통과하고, 모든 public type/factory/meter name 및 canonical example이 두 locale에 모두 나타나며 compiled fixture source와 일치한다.
  **실패 조건:** Kotlin identifier를 문자 그대로 번역하거나 하나의 example에 protected 및 unprotected route installation을 함께 표시하지 않는다.
- [ ] **작업:** component regex/limit, 금지된 data, side-effect-free O(1) supplier rule, 지원되지 않는 blocking/cancellation-insensitive 동작, sequential order, shared deadline, response details, 정확한 meter names/tags/base units, `NaN`, collision behavior, 128-meter-ID bound, registry/configuration-dependent exported time-series, registry당 하나의 route 권장사항 및 caller-owned concurrency/rate limiting을 문서화한다.
  **증거:** README parity test에서 누락된 names, headings, canonical fences 또는 semantic sections가 없다고 보고한다.
  **실패 조건:** timeout이 blocking threads 또는 processes를 종료할 수 있다고 주장하지 않는다.
- [ ] **작업:** `T_endpoint = I_jdbc * (R + J_effective) + I_r2dbc * R + I_cache * R + overhead` planning formula를 추가하고, whole-second/minimum-one-second JDBC conversion을 설명하며, `timeoutSeconds`가 반올림된 budget과 margin보다 크고 `periodSeconds > timeoutSeconds`, `failureThreshold >= 3`인 orchestrator example을 제공한다.
  **증거:** English/Korean examples가 동일한 numeric assumptions를 사용하고 동일한 bound를 산출한다.
  **실패 조건:** saturated drivers 또는 지원되지 않는 probes에 대한 hard guarantee로 formula를 제시하지 않는다.
- [ ] **작업:** 다음 Ktor mapping/operations table을 추가한다: `/healthz/exposed`는 probe-free liveness이고 `/readyz/exposed`는 traffic readiness이다. flush error가 없는 repository `NOT_APPLICABLE|IDLE|RUNNING`은 `UP`으로, `DRAINING|FAILED|STOPPED` 또는 flush error는 `DOWN`으로 매핑한다. snapshot counters는 measurements일 뿐이다. expected shutdown을 worker failure 및 Spring Actuator `OUT_OF_SERVICE` mapping과 대조한다.
  **증거:** parity test에서 두 locale에 동일한 state/path/status coverage를 찾는다.
  **실패 조건:** liveness가 cache recovery를 소유하거나 Ktor가 `OUT_OF_SERVICE`를 emit한다고 암시하지 않는다.
- [ ] **작업:** repository `DOWN`, cache timeout, snapshot cumulative counters, `NaN` gauges, invalid configuration, unsupported custom probes 및 meter collision에 대한 runbook rows를 추가한다. 점으로 구분된 이름을 Micrometer meter IDs로 표시하고, 고정된 Prometheus/OTel suffix 주장을 피하며, query 전에 실제 exported series를 검사하도록 operator에게 요구한다. missing/omitted/`NaN`을 절대 zero로 취급하지 말고 readiness/timer outcome과 연관시킨다. cumulative dropped/observer counters에 대해 process-restart/reset awareness를 포함하여 rate/increase를 설명한다.
  **증거:** runbook headings, diagnostic actions, exporter caveats 및 reset semantics가 두 locale에서 source-equivalent임을 확인한다.
  **실패 조건:** Ktor guidance를 통해 exception messages를 노출하지 않는다.
- [ ] **작업:** collision/shutdown ownership을 문서화한다: meter identities는 registry lifetime 동안 유지된다. registry당 하나의 route 또는 fresh registry를 사용하고, 이전 route가 계속 service할 가능성이 있는 동안 colliding meters를 제거하지 않는다. Route probes는 observers이며, caller가 traffic을 withdraw하고 repositories를 drain/close하며 application을 중지하고 registry를 close한다. `DRAINING`/`STOPPED`는 의도적으로 readiness를 실패시킨다.
  **증거:** 두 locale runbook에 동일한 safe reinstall 및 shutdown sequence가 포함된다.
  **실패 조건:** helper가 repository, application 또는 registry shutdown을 소유하게 만들지 않는다.
- [ ] **작업:** Spring JDBC/R2DBC Actuator tables 및 migration notes를 업데이트한다: `flushJobRunning`을 `workerState`로 교체하고 정확한 UP/OUT_OF_SERVICE/DOWN mappings를 나열하며 automatic Actuator discovery와 explicit Ktor contributors를 대조한다.
  **증거:** 두 Spring README pairs가 implementation 및 tests와 일치한다.
  **실패 조건:** Actuator의 management-endpoint policy와 Ktor의 더 엄격한 response redaction을 구분한다.
- [ ] **작업:** `exposed/cache` English/Korean READMEs에 direct Kotlin migration을 업데이트한다: `isFlushJobRunning`은 stable release 전에 제거되고 `workerState`를 사용한다. fresh `IDLE`을 healthy로 취급하고 `RUNNING`, `DRAINING`, `FAILED`, `STOPPED` 및 `NOT_APPLICABLE`을 구분하며, 모호한 compatibility alias가 남지 않는 이유를 설명한다.
  **증거:** `ExposedKtorReadmeParityTest`가 두 cache locale files를 읽고 모든 state 및 direct migration expression을 찾는다.
  **실패 조건:** 변경을 Actuator detail-key rename으로만 설명하지 않는다.
- [ ] **작업:** lifecycle state, shared deadline, fixed meter registration 및 overload-based ABI preservation이 필요한 이유를 다루는 짧은 lesson을 추가한다.
  **증거:** lesson이 full spec을 중복하지 않고 context, decision, outcome, verification expectations 및 future-agent guidance를 기록한다.
  **실패 조건:** implementation evidence를 알기 전에 lesson을 추가하지 않으며, tests가 통과한 후에만 outcome을 작성한다.
- [ ] **작업:** `git diff -- docs/manual`이 비어 있는지 확인하고 `git diff --check`를 실행한 뒤 Lore trailers와 함께 commit한다.
  **증거:** commit에 paired module README updates 및 completed lesson이 포함되며 parity, compiled snippets 및 stable-manual checks가 통과한다.
  **실패 조건:** source-asymmetric docs, literal Korean translation artifacts 또는 stable-manual changes를 commit하지 않는다.

```text
Explain safe cache readiness at application and operator boundaries

Constraint: Ktor routes are caller-secured and stable manuals remain release-pinned
Rejected: A dedicated cache endpoint | operators need one deterministic readiness decision
Confidence: high
Scope-risk: narrow
Tested: bilingual API parity, compiled examples, runbook review, and stable-manual diff
```

## 작업 9: 진단, 성능 검사, 독립 리뷰 및 PR 전달 실행

**파일:**
- 검증된 발견 사항을 해결하는 데 필요한 파일만 수정한다.

- [ ] **작업:** 소스 변경 후 CodeGraph를 사용할 수 있으면 업데이트한다. 그렇지 않으면 비어 있거나 오래된 그래프의 공백을 기록하고 직접 영향 범위 스캔을 사용한다. 변경된 모든 `.kt` 파일에 대해 Kotlin 진단/IDE 검사를 실행하고, import를 최적화하며, 변경된 코드의 모든 deprecation을 해결한다.
  **증거:** 변경된 Kotlin 파일에 진단/오류/해결되지 않은 deprecation이 0개이다.
  **실패:** Kotlin 진단 백엔드를 사용할 수 없을 때 IDE 증거가 있다고 주장하지 않는다. compile/test/static 게이트를 사용하고 공백을 보고한다.
- [ ] **작업:** 영향을 받는 모듈에 대해 결정론적인 게이트를 순차적으로 실행한다:

```bash
./gradlew :bluetape4k-exposed-cache:test --no-configuration-cache --no-parallel --rerun-tasks
./gradlew :bluetape4k-exposed-jdbc-caffeine:test --no-configuration-cache --no-parallel --rerun-tasks
./gradlew :bluetape4k-exposed-r2dbc-caffeine:test --no-configuration-cache --no-parallel --rerun-tasks
./gradlew :bluetape4k-exposed-spring-boot-jdbc:test --no-configuration-cache --no-parallel --rerun-tasks
./gradlew :bluetape4k-exposed-spring-boot-r2dbc:test --no-configuration-cache --no-parallel --rerun-tasks
./gradlew :bluetape4k-exposed-ktor:test --no-configuration-cache --no-parallel --rerun-tasks
```

  **증거:** 테스트 수와 0건의 실패/오류를 기록하고, 재시도에 민감한 모든 결과를 조사한다.
  **실패:** Testcontainers/database 기반 실행을 병렬로 수행하지 않는다.
- [ ] **작업:** 정적 및 호환성 게이트를 실행한다:

```bash
./gradlew detekt --no-configuration-cache
./gradlew :bluetape4k-exposed-ktor:classes --no-configuration-cache
javap -classpath ktor/exposed/build/classes/kotlin/main -s -p \
  io.bluetape4k.exposed.ktor.Bluetape4kExposedKtorConfig \
  io.bluetape4k.exposed.ktor.Bluetape4kExposedKtorKt \
  io.bluetape4k.exposed.ktor.ExposedKtorHealthRoutesKt
git diff --check
git diff --exit-code origin/develop -- settings.gradle.kts gradle docs/manual
```

  **증거:** Detekt/compile/diff 검사가 통과하고, 이전 descriptor가 작업 0과 일치하며, 금지된 영역이 변경되지 않았다.
  **실패:** catalog, settings, stable-manual 또는 issue #322 변경은 범위를 벗어나므로 이 branch에서 제거해야 한다.
- [ ] **작업:** 최종 리뷰 전에 performance-review 게이트를 로드하고 실행한다. 요청별 `MeterRegistry.find`, meter builder, tag 구성, 등록, 무제한 collection, 추가 dispatcher/scope/thread 생성, blocking 호출, contributor별 새로운 deadline, tag 증가, 이차식 validation, 누수된 request state를 route 및 metrics 경로에서 검사한다. 설치된 route state가 직접적인 meter/holder reference를 유지하는지 확인한다.
  **증거:** 성능 verdict가 P0/P1이 없음을 보고하고 O(contributors), max 16, 고정된 128개의 Micrometer meter ID, registry에 종속된 exported time-series 및 하나의 공유 cache timeout을 확인한다.
  **실패:** 기능 테스트가 통과하더라도 구조적인 hot-path regression이 있으면 PR 생성을 차단한다.
- [ ] **작업:** performance, stability/concurrency, security, operator/Ops, developer/API 및 user/caller behavior에 대해 새롭고 독립적인 code review를 실행한다. 발견 사항을 main session에 통합한다. `P0=0` 및 `P1=0`을 요구하며, P2/P3은 해결하거나 정당한 후속 issue를 생성한다.
  **증거:** 최종 review table에 여섯 가지 관점과 main integration, 정확한 발견 사항, 수정 내용, 재실행 명령 및 최종 severity 수를 기록한다.
  **실패:** reviewer timeout 또는 불완전한 verdict는 통과로 간주하지 않는다. 범위를 제한한 새로운 reviewer를 다시 실행한다.
- [ ] **작업:** `verification-before-completion`을 로드하고, review 수정의 영향을 받은 모든 게이트를 다시 실행하며, lesson outcome을 업데이트하고, 의도한 commit을 제외하면 worktree가 깨끗한지 확인한다.
  **증거:** 필수 검사 요약이 완전하다: `Required checks: X/X; N/A: N; Blocked: 0`.
  **실패:** 누락되었거나 오래되었거나 `UNKNOWN`이거나 건너뛴 증거가 있으면 전달을 차단한다.
- [ ] **작업:** live issue #325 metadata를 검사하고, 정확한 head를 push하며, `develop`을 대상으로 하는 English PR을 생성하고, `debop`을 assign하며, issue milestone/labels를 반영하고, live PR metadata를 검증한다. design summary, ABI proof, test counts, metric bound, security boundary, operator timing 및 `Closes #325`를 포함한다.
  **증거:** `gh pr view`가 예상한 base/head/assignee/milestone/labels 및 정확히 push된 SHA를 보고한다.
  **실패:** assign되지 않았거나 milestone이 잘못된 PR을 조용히 생성하지 않는다.
- [ ] **작업:** 정확한 head에서 필수 CI를 모니터링한다. CI가 green으로 전환된 후 reviews 및 해결되지 않은 threads를 다시 읽는다.
  **증거:** merge-ready 상태로 정확한 PR number, head SHA, required checks, reviews 및 thread count를 보고한다.
  **실패:** merge 전에 중지하고 새로운 정확한 head approval을 사용자에게 받는다. auto-merge는 금지된다.

## 완료 체크리스트

- [ ] 기존 database-only Ktor source 및 JVM compatibility가 보존된다.
- [ ] 모든 cache worker state 및 close race에 대해 결정론적인 JDBC/R2DBC coverage가 제공된다.
- [ ] Spring JDBC/R2DBC status 및 detail mappings가 design과 일치한다.
- [ ] Ktor contributors가 sanitized finite status 및 measurement fields만 노출한다.
- [ ] Cache readiness가 순차적이며 하나의 공유 supported-path deadline으로 제한된다.
- [ ] Parent cancellation이 다시 throw되며 결코 이중 집계되지 않는다.
- [ ] Contributor별로 네 개의 gauge 및 네 개의 timer outcome이 한 번씩 등록된다.
- [ ] Registry collision이 부분적인 meter registration보다 먼저 실패한다.
- [ ] HTTP bodies, exceptions, logs 및 tags가 secret-redaction tests를 통과한다.
- [ ] Authenticated manual route installation이 검증되며, helper-owned auth는 non-goal로 유지된다.
- [ ] English/Korean READMEs 및 Spring Actuator docs가 source-equivalent하다.
- [ ] Stable manuals, catalogs, settings 및 issue #322가 변경되지 않은 상태로 유지된다.
- [ ] 모든 targeted tests, diagnostics, detekt, ABI, diff, performance 및 seven-lens review gates가 통과한다.
- [ ] PR이 `develop`을 대상으로 열려 있으며, merge는 새로운 정확한 head approval을 기다린다.
