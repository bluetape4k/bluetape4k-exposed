# Issue #326 Ktor R2DBC 캐시 및 DDD 데모 구현 계획

> **에이전트 작업자용:** REQUIRED SUB-SKILL: 이 계획을 작업별로 구현하려면 superpowers:subagent-driven-development(권장) 또는 superpowers:executing-plans를 사용하세요. 단계는 추적을 위해 체크박스(`- [ ]`) 구문을 사용합니다.

**목표:** PostgreSQL R2DBC, R2DBC Caffeine 저장소, Spring과 무관한 도메인 이벤트 전달을 기반으로 실행 가능한 이중 언어 Ktor 주문 확인 예제를 제공하고, 검증된 생명주기 동작과 이해하기 쉬운 아키텍처/시퀀스 다이어그램을 구현합니다.

**아키텍처:** POST 명령은 얇은 Ktor 라우트로 진입하여 `OrderCommandService`를 통해 실행됩니다. GET은 저장소의 read-through를 직접 보여 줍니다. 명령 서비스는 애그리거트 재수화, 쓰기 실패 시 캐시 보상, persistence 이후 취소 게이트, 동기식 비내구적 이벤트 전달을 담당합니다. `KtorExposedDemoResources`는 H2 JDBC와 PostgreSQL R2DBC/캐시 리소스를 소유하고, 풀을 해제하기 전에 Exposed의 프로세스 전역 R2DBC 기본값을 복원합니다.

**기술 스택:** Kotlin 2.3 language level, Ktor 3, kotlinx.serialization, JetBrains Exposed R2DBC, PostgreSQL 16, r2dbc-pool, Caffeine, Kotlin coroutines, JUnit 5, MockK, Testcontainers 2, Docker Compose, SVG, CairoSVG.

---

## 고정된 파일 구조

### Production 및 runtime

- `examples/ktor-exposed-demo/build.gradle.kts` 수정 — serialization plugin, direct cache/PostgreSQL dependencies, isolated `postgresIntegrationTest` task.
- `examples/ktor-exposed-demo/compose.yaml` 생성 — loopback PostgreSQL 16 service, health check, named volume.
- `examples/ktor-exposed-demo/src/main/kotlin/io/bluetape4k/examples/exposed/ktor/order/OrderDomain.kt` 생성 — status, aggregate, event, serializable record.
- `examples/ktor-exposed-demo/src/main/kotlin/io/bluetape4k/examples/exposed/ktor/order/OrderRepository.kt` 생성 — UUID table and R2DBC Caffeine mapping.
- `examples/ktor-exposed-demo/src/main/kotlin/io/bluetape4k/examples/exposed/ktor/order/OrderCommandService.kt` 생성 — publisher port, typed failures, compensation, cancellation gate.
- `examples/ktor-exposed-demo/src/main/kotlin/io/bluetape4k/examples/exposed/ktor/order/OrderRoutes.kt` 생성 — serializers, validation precedence, response/error mapping, sanitized diagnostics.
- `examples/ktor-exposed-demo/src/main/kotlin/io/bluetape4k/examples/exposed/ktor/KtorExposedDemoResources.kt` 수정 — config, PostgreSQL pool/schema, default-database lifecycle, close report.
- `examples/ktor-exposed-demo/src/main/kotlin/io/bluetape4k/examples/exposed/ktor/KtorExposedDemoApplication.kt` 수정 — Ktor composition, readiness contributor, routes, testable server runner, exit statuses.

### 테스트

- `examples/ktor-exposed-demo/src/test/kotlin/io/bluetape4k/examples/exposed/ktor/order/OrderDomainTest.kt` 생성.
- `examples/ktor-exposed-demo/src/test/kotlin/io/bluetape4k/examples/exposed/ktor/order/OrderCommandServiceTest.kt` 생성.
- `examples/ktor-exposed-demo/src/test/kotlin/io/bluetape4k/examples/exposed/ktor/order/OrderRoutesTest.kt` 생성.
- `examples/ktor-exposed-demo/src/test/kotlin/io/bluetape4k/examples/exposed/ktor/KtorExposedDemoLifecycleTest.kt` 생성.
- `examples/ktor-exposed-demo/src/test/kotlin/io/bluetape4k/examples/exposed/ktor/KtorExposedDemoApplicationTest.kt`를 Docker-free composition tests only로 교체.
- `examples/ktor-exposed-demo/src/postgresIntegrationTest/kotlin/io/bluetape4k/examples/exposed/ktor/KtorExposedDemoPostgresIntegrationTest.kt` 생성 — full PostgreSQL/cache/readiness/lifecycle proof.

### 문서 및 영구 증거

- `examples/ktor-exposed-demo/README.md`와 `examples/ktor-exposed-demo/README.ko.md`를 의미적으로 동일하게 교체.
- `docs/images/readme-diagrams/examples-ktor-exposed-demo-architecture-01.svg` 및 렌더링된 `.png` 생성.
- `docs/images/readme-diagrams/examples-ktor-exposed-demo-sequence-01.svg` 및 렌더링된 `.png` 생성.
- `docs/lessons/2026-07-17-issue-326-ktor-r2dbc-write-through-event-handoff.md` 생성.
- `docs/review/2026-07-17-issue-326-ktor-r2dbc-ddd-demo-review.md` 생성.
- 증거가 완료되는 대로 issue checklist와 이 계획을 업데이트.

## 트리거된 위험 예측

| 위험 | 가장 이른 신호 | 예방/증명 | 재실행 지점 |
|---|---|---|---|
| PostgreSQL 실패 후 Caffeine에 persistence되지 않은 값이 남음 | 캐시 변경 후 `put` throws | `NonCancellable` local invalidation, cause preservation, service + real PostgreSQL tests | after Tasks 3 and 7 |
| 취소된 작업이 모호한 쓰기 이후 이벤트를 publish함 | cancelled job returns from `put` | `ensureActive()` immediately after `put`; same cancellation instance; no publish | after Task 3 |
| 종료된 demo DB가 Exposed의 프로세스 전역 기본값으로 남음 | second lifecycle reads through old pool | capture, unregister, restore; run two real lifecycles sequentially | after Tasks 6 and 7 |
| persistence된 order가 비내구적 이벤트 전달을 잃음 | publisher throws after successful write | typed `OrderEventHandoffException`, retained request-local buffer, README outbox warning | after Tasks 3, 5, and 10 |
| Docker test가 일반 `test`에 들어가거나 병렬 실행됨 | fast task starts a container or CI contention appears | separate source set/task, no task dependency, `--no-parallel`, same-thread JUnit | after Tasks 1 and 7 |
| Startup/shutdown이 실패를 숨기거나 리소스를 누수함 | exit 0, stale pool/thread, raw throwable log | runner statuses 1/2, aggregated close report, allowlisted diagnostics, lifecycle doubles | after Task 6 |
| 다이어그램이 시각적으로 매력적이지만 읽기 어려움 | clipped text, oversized markers, ambiguous branches | fixed markers/fonts, true lifelines/activation/numbered pills, all audits + full-size inspection | after Task 9 |

## 계획 검토 기록

최종 2026-07-17 plan review는 모든 finding이 수정된 후 수렴했습니다:

| 관점 | P0 | P1 | P2 | P3 | 결과 |
|---|---:|---:|---:|---:|---|
| 성능 | 0 | 0 | 0 | 0 | READY |
| 안정성/동시성 | 0 | 0 | 0 | 0 | READY |
| 보안/개인정보 보호 | 0 | 0 | 0 | 0 | READY |
| 운영/운영자 | 0 | 0 | 0 | 0 | READY |
| 개발자/API | 0 | 0 | 0 | 0 | READY |
| 사용자/caller, 이중 언어 문서, 다이어그램 | 0 | 0 | 0 | 0 | READY |
| 주 세션 통합 | 0 | 0 | 0 | 0 | READY |

수정 라운드에서는 dependency timing, Java-time availability, lifecycle
acquisition 및 concurrent close seams, Ktor 3.5 shutdown observability limits, pre/post-write cancellation, process-wide R2DBC-default ownership, diagnostic formatting, hostile-origin behavior, bilingual caller contracts, failure-safe Compose cleanup, per-asset diagram validation, Testcontainers execution cost를 마무리했습니다. Main integration에서는 현재 repository APIs와 Ktor 3.5.1 sources를 기준으로 해당 결정을 다시 확인하고, 모든 code fence의 균형을 검증했으며, placeholder 또는 trailing-whitespace defect가 없음을 확인했습니다. 또한 모든 predicted risk를 implementation task와 rerun point에 매핑했습니다. 승인된 범위에는 implementation gap이 남아 있지 않습니다.

### Task 0: 검토된 설계 및 계획 증거 고정

**파일:**
- 수정: `docs/superpowers/specs/2026-07-17-issue-326-ktor-r2dbc-ddd-demo-design.md`
- 수정: `docs/superpowers/plans/2026-07-17-issue-326-ktor-r2dbc-ddd-demo-plan.md`
- 수정: `docs/superpowers/checklists/2026-07-17-issue-326-ktor-r2dbc-ddd-demo-checklist.md`

- [ ] **Step 1: 최종 plan-review 수렴 및 risk traceability 기록**

여섯 plan-lens counts와 main integration result를 이 계획에 추가하고, 모든 P0/P1이 수정된 후에만 A-04/A-05를 check하며, 위의 risk-table 각 행을 이 문서에 이미 명시된 implementation task와 rerun command에 매핑합니다.

- [ ] **Step 2: 코드 변경 전에 durable artifacts 검증**

실행:

```bash
rg -n "P0|P1|P2|P3|READY|Triggered Risk Predictions" \
  docs/superpowers/specs/2026-07-17-issue-326-ktor-r2dbc-ddd-demo-design.md \
  docs/superpowers/plans/2026-07-17-issue-326-ktor-r2dbc-ddd-demo-plan.md
git diff --check
```

예상 결과: design과 plan 모두 최종 P0=0/P1=0을 표시하고, placeholder 또는 whitespace failure가 남아 있지 않습니다.

- [ ] **Step 3: 구현 전에 승인된 decision artifacts commit**

```bash
git add docs/superpowers/specs/2026-07-17-issue-326-ktor-r2dbc-ddd-demo-design.md \
  docs/superpowers/plans/2026-07-17-issue-326-ktor-r2dbc-ddd-demo-plan.md \
  docs/superpowers/checklists/2026-07-17-issue-326-ktor-r2dbc-ddd-demo-checklist.md
git commit -m "Lock the PostgreSQL order scenario before implementation" \
  -m "Constraint: Type A work requires reviewed design, executable plan, and predicted-risk evidence before code mutation." \
  -m "Rejected: Route-owned orchestration | Persistence, compensation, and event handoff need one testable command boundary." \
  -m "Confidence: high" -m "Scope-risk: moderate" \
  -m "Directive: Do not widen this issue into Spring, JaVers, publishing, catalog, or issue #322 work." \
  -m "Tested: seven design lenses, six plan lenses, main integration, placeholder scan, fence balance, and diff check" \
  -m "Not-tested: implementation does not exist yet"
```

### 작업 1: 빌드 및 테스트 태스크 경계 고정

**파일:**
- 수정: `examples/ktor-exposed-demo/build.gradle.kts`

- [ ] **단계 1: 빠른 태스크 기준선 캡처**

실행:

```bash
./gradlew :examples-ktor-exposed-demo:test --no-daemon --console=plain
```

예상 결과: Docker/Testcontainers를 시작하지 않고 PASS.

- [ ] **단계 2: 모듈 빌드 파일을 명시적인 런타임 및 테스트 스위트 계약으로 교체**

기존 애플리케이션 메인 클래스를 유지하면서 다음의 완전한 형태를 사용합니다:

```kotlin
plugins {
    application
    alias(bt4k.plugins.kotlin.serialization)
}

application {
    mainClass.set("io.bluetape4k.examples.exposed.ktor.KtorExposedDemoApplicationKt")
}

val postgresIntegrationTest by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += output + compileClasspath
}

kotlin.target.compilations.getByName(postgresIntegrationTest.name)
    .associateWith(kotlin.target.compilations.getByName("main"))

configurations.named(postgresIntegrationTest.implementationConfigurationName) {
    extendsFrom(configurations.implementation.get(), configurations.testImplementation.get())
}
configurations.named(postgresIntegrationTest.runtimeOnlyConfigurationName) {
    extendsFrom(configurations.runtimeOnly.get(), configurations.testRuntimeOnly.get())
}

dependencies {
    implementation(platform(bt4k.ktor.bom))
    implementation(platform(libs.exposed.bom))

    implementation(project(":bluetape4k-exposed-ktor"))
    implementation(project(":bluetape4k-exposed-r2dbc-caffeine"))
    implementation(bt4k.bluetape4k.ktor.core)
    implementation(bt4k.exposed.jdbc)
    implementation(bt4k.exposed.r2dbc)
    implementation(bt4k.exposed.java.time)
    implementation(bt4k.hikaricp)
    implementation(libs.r2dbc.pool)
    implementation(libs.kotlinx.coroutines.core)
    implementation("io.ktor:ktor-server-netty")

    runtimeOnly(libs.h2.v2)
    runtimeOnly(bt4k.r2dbc.h2)
    runtimeOnly(libs.r2dbc.postgresql)

    testImplementation(bt4k.bluetape4k.ktor.testing)
    testImplementation(bt4k.bluetape4k.assertions)
    testImplementation(bt4k.bluetape4k.junit5)

    add(postgresIntegrationTest.implementationConfigurationName, libs.testcontainers.postgresql)
}

tasks.register<Test>("postgresIntegrationTest") {
    description = "Runs the sequential PostgreSQL Ktor demo integration tests."
    group = "verification"
    testClassesDirs = postgresIntegrationTest.output.classesDirs
    classpath = postgresIntegrationTest.runtimeClasspath
    shouldRunAfter(tasks.test)
    maxParallelForks = 1
    systemProperty("junit.jupiter.execution.parallel.enabled", "false")
    useJUnitPlatform()
}
```

기존 애플리케이션 테스트가 현재 H2 R2DBC 리소스를 계속 생성하므로, 첫 번째 빌드 전용 커밋에서는 `runtimeOnly(bt4k.r2dbc.h2)`를 유지합니다. 해당 드라이버는 Task 6에서 PostgreSQL을 인식하는 프로덕션 wiring과 Docker가 필요 없는 double로 리소스와 테스트를 교체하는 동일한 커밋에서만 제거합니다.

- [ ] **단계 3: Gradle이 격리된 태스크를 인식하는지 입증**

실행:

```bash
./gradlew :examples-ktor-exposed-demo:tasks --all --no-daemon --console=plain | rg "postgresIntegrationTest"
./gradlew :examples-ktor-exposed-demo:test --no-daemon --console=plain
```

예상 결과: 해당 태스크가 정확히 한 번 나열되고, 일반 `test`는 계속 통과하며 `postgresIntegrationTest`를 실행하지 않습니다.

- [ ] **단계 4: 빌드 경계 커밋**

```bash
git add examples/ktor-exposed-demo/build.gradle.kts
git commit -m "Isolate PostgreSQL proof from the fast Ktor example tests" \
  -m "Constraint: Existing CI must keep invoking a Docker-free test task." \
  -m "Rejected: Reusing H2 R2DBC | The approved scenario requires real PostgreSQL behavior." \
  -m "Confidence: high" -m "Scope-risk: narrow" \
  -m "Tested: module task listing and fast test baseline" \
  -m "Not-tested: PostgreSQL integration sources do not exist yet"
```

### 작업 2: Aggregate 및 Event Contract를 테스트 우선으로 구현

**파일:**
- 생성: `examples/ktor-exposed-demo/src/main/kotlin/io/bluetape4k/examples/exposed/ktor/order/OrderDomain.kt`
- 생성: `examples/ktor-exposed-demo/src/test/kotlin/io/bluetape4k/examples/exposed/ktor/order/OrderDomainTest.kt`

- [ ] **1단계: 실패하는 aggregate 테스트 작성**

세 가지 정확한 케이스로 `OrderDomainTest.kt`를 생성합니다:

```kotlin
package io.bluetape4k.examples.exposed.ktor.order

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class OrderDomainTest {
    private val id = UUID.fromString("018f6f95-7f4a-7a20-8b52-70ad30c30f36")
    private val createdAt = Instant.parse("2026-07-17T00:00:00Z")
    private val confirmedAt = Instant.parse("2026-07-17T00:01:00Z")

    @Test
    fun `pending order confirms once and records one event`() {
        val order = DemoOrder.pending(id, createdAt)

        order.confirm(confirmedAt) shouldBeEqualTo true
        order.status shouldBeEqualTo OrderStatus.CONFIRMED
        order.updatedAt shouldBeEqualTo confirmedAt
        order.domainEvents() shouldBeEqualTo listOf(OrderConfirmed(id, confirmedAt))
        order.toRecord() shouldBeEqualTo OrderRecord(id, OrderStatus.CONFIRMED, confirmedAt)
    }

    @Test
    fun `sequential confirmation is idempotent`() {
        val order = DemoOrder.pending(id, createdAt)
        order.confirm(confirmedAt)
        order.clearDomainEvents()

        order.confirm(confirmedAt.plusSeconds(10)) shouldBeEqualTo false
        order.updatedAt shouldBeEqualTo confirmedAt
        order.domainEvents() shouldBeEqualTo emptyList()
    }

    @Test
    fun `rehydration does not recreate historical events`() {
        val record = OrderRecord(id, OrderStatus.CONFIRMED, confirmedAt)

        val order = DemoOrder.rehydrate(record)

        order.toRecord() shouldBeEqualTo record
        order.domainEvents() shouldBeEqualTo emptyList()
    }
}
```

- [ ] **2단계: RED 실행**

실행:

```bash
./gradlew :examples-ktor-exposed-demo:test \
  --tests "*OrderDomainTest" --no-daemon --console=plain
```

예상 결과: `DemoOrder`, `OrderStatus`, `OrderConfirmed`, `OrderRecord`가 존재하지 않으므로 FAIL.

- [ ] **3단계: 완전한 domain 파일 구현**

`OrderDomain.kt`를 생성합니다:

```kotlin
package io.bluetape4k.examples.exposed.ktor.order

import io.bluetape4k.exposed.core.ddd.AbstractAggregateRoot
import io.bluetape4k.exposed.core.ddd.DomainEvent
import java.io.Serializable
import java.time.Instant
import java.util.UUID

enum class OrderStatus { PENDING, CONFIRMED }

class DemoOrder private constructor(
    override val id: UUID,
    status: OrderStatus,
    updatedAt: Instant,
): AbstractAggregateRoot<UUID>() {
    var status: OrderStatus = status
        private set

    var updatedAt: Instant = updatedAt
        private set

    fun confirm(occurredAt: Instant): Boolean {
        if (status == OrderStatus.CONFIRMED) return false
        status = OrderStatus.CONFIRMED
        updatedAt = occurredAt
        recordDomainEvent(OrderConfirmed(id, occurredAt))
        return true
    }

    fun toRecord(): OrderRecord = OrderRecord(id, status, updatedAt)

    companion object {
        fun pending(id: UUID, createdAt: Instant): DemoOrder =
            DemoOrder(id, OrderStatus.PENDING, createdAt)

        fun rehydrate(record: OrderRecord): DemoOrder =
            DemoOrder(record.id, record.status, record.updatedAt)
    }
}

data class OrderConfirmed(
    override val aggregateId: UUID,
    override val occurredAt: Instant,
): DomainEvent<UUID>, Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

data class OrderRecord(
    val id: UUID,
    val status: OrderStatus,
    val updatedAt: Instant,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
```

- [ ] **4단계: GREEN 및 serialization 증명 실행**

실행:

```bash
./gradlew :examples-ktor-exposed-demo:test \
  --tests "*OrderDomainTest" --no-daemon --console=plain
```

예상 결과: 정확히 세 개의 테스트가 PASS.

- [ ] **5단계: aggregate 경계 커밋**

```bash
git add examples/ktor-exposed-demo/src/main/kotlin/io/bluetape4k/examples/exposed/ktor/order/OrderDomain.kt \
  examples/ktor-exposed-demo/src/test/kotlin/io/bluetape4k/examples/exposed/ktor/order/OrderDomainTest.kt
git commit -m "Keep order confirmation invariants inside the aggregate" \
  -m "Constraint: DDD types must remain Spring-neutral and JaVers-neutral." \
  -m "Rejected: Public mutable status | It can bypass OrderConfirmed recording." \
  -m "Confidence: high" -m "Scope-risk: narrow" \
  -m "Directive: Rehydration must never recreate historical domain events." \
  -m "Tested: focused aggregate transition and rehydration tests" \
  -m "Not-tested: persistence and HTTP mapping"
```

### 작업 3: 명령 순서, 보상 및 취소 구현

**파일:**
- 생성: `examples/ktor-exposed-demo/src/main/kotlin/io/bluetape4k/examples/exposed/ktor/order/OrderCommandService.kt`
- 생성: `examples/ktor-exposed-demo/src/test/kotlin/io/bluetape4k/examples/exposed/ktor/order/OrderCommandServiceTest.kt`

- [ ] **1단계: 서비스보다 먼저 서비스 테스트 작성**

테스트 클래스는 `mockk<R2dbcCaffeineRepository<UUID, OrderRecord>>()`, 고정된 `Clock`, 기록/실패 publisher를 사용해야 합니다. 모든 경우에 고정 ID `018f6f95-7f4a-7a20-8b52-70ad30c30f36`와 instant `2026-07-17T00:01:00Z`를 사용합니다. 다음 fixture와 성공 테스트를 정확히 구현한 뒤, 아래 실패 매트릭스에도 동일한 명시적 구성을 반복합니다:

```kotlin
private val id = UUID.fromString("018f6f95-7f4a-7a20-8b52-70ad30c30f36")
private val instant = Instant.parse("2026-07-17T00:01:00Z")
private val clock = Clock.fixed(instant, ZoneOffset.UTC)
private val repository = mockk<R2dbcCaffeineRepository<UUID, OrderRecord>>()

@Test
fun `confirmation writes before publishing and clears events`() = runTest {
    val calls = mutableListOf<String>()
    val published = mutableListOf<DomainEvent<UUID>>()
    coEvery { repository.get(id) } returns null
    coEvery { repository.put(id, any()) } coAnswers { calls += "put" }
    val publisher = OrderEventPublisher { events ->
        calls += "publish"
        published += events
    }
    val service = OrderCommandService(repository, publisher, clock)

    val aggregate = DemoOrder.pending(id, instant)
    val result = service.confirm(aggregate)

    calls shouldBeEqualTo listOf("put", "publish")
    result.eventPublished shouldBeEqualTo true
    result.record.updatedAt shouldBeEqualTo instant
    published shouldBeEqualTo listOf(OrderConfirmed(id, instant))
    aggregate.domainEvents() shouldBe emptyList()
}
```

실패 매트릭스:

| 테스트 이름 | 구성 | 정확한 단언 |
|---|---|---|
| `sequential confirmed record skips write and publish` | `get`이 `OrderRecord(CONFIRMED)` 반환 | 결과 `eventPublished=false`; `put` 정확히 0회; publisher 정확히 0회 |
| `write failure invalidates skips publisher and preserves cause and events` | 내부 aggregate seam; `put`이 `primary` throw; `invalidate` 성공 | `OrderPersistenceException.cause === primary`; invalidate 1회; publisher 0회; aggregate가 `OrderConfirmed` 유지 |
| `invalidation failure is suppressed on original persistence cause` | `put`이 `primary` throw; `invalidate`가 `cleanup` throw | wrapper cause는 `primary`; `primary.suppressed`가 `[cleanup]`과 동일 |
| `publisher failure leaves persisted record and request local events` | `put` 성공; publisher가 `primary` throw | `OrderEventHandoffException.cause === primary`; invalidate 없음; aggregate가 event 유지 |
| `repository cancellation invalidates noncancellably and rethrows the same instance` | `put`이 이름이 지정된 `CancellationException` throw; invalidate 성공 | 동일한 cancellation instance가 외부로 전달; `NonCancellable` 아래에서 invalidate 1회; publisher 0회 |
| `repository cancellation keeps cleanup failure suppressed on the same instance` | `put`이 이름이 지정된 cancellation throw; invalidate가 cleanup throw | 동일한 cancellation instance가 외부로 전달; 해당 suppressed 목록이 `[cleanup]`; publisher 0회 |
| `pre-write cancellation never calls put or publisher` | 서비스의 pre-write gate 전에 child를 취소 | `ensureActive()`가 동일한 cancellation throw; `put` 0회; publisher 0회 |
| `cancellation observed immediately after put invalidates and never publishes` | repository 응답이 반환 직전에 child job을 취소 | 반환 후 `ensureActive()`가 동일한 cancellation throw; `NonCancellable` 아래에서 invalidate 1회; publisher 0회 |
| `simultaneous confirmation can publish twice by design` | 두 write가 발생하기 전에 두 `PENDING` 읽기가 모두 완료되도록 two-party barrier가 강제 | `put` 호출 정확히 2회 및 publication 2회 단언; 결정론적 특성이 지원되지 않는 concurrent idempotency를 문서화 |

- [ ] **2단계: RED 실행**

실행:

```bash
./gradlew :examples-ktor-exposed-demo:test \
  --tests "*OrderCommandServiceTest" --no-daemon --console=plain
```

예상 결과: service, publisher, result 및 typed failure가 존재하지 않으므로 FAIL.

- [ ] **3단계: 서비스 API와 실패 분류 구현**

다음의 완전한 동작으로 `OrderCommandService.kt`를 생성합니다:

```kotlin
package io.bluetape4k.examples.exposed.ktor.order

import io.bluetape4k.exposed.core.ddd.DomainEvent
import io.bluetape4k.exposed.r2dbc.caffeine.repository.R2dbcCaffeineRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.time.Clock
import java.util.UUID

fun interface OrderEventPublisher {
    fun publish(events: List<DomainEvent<UUID>>)
}

class InMemoryOrderEventPublisher: OrderEventPublisher {
    @Volatile
    var latestEvents: List<DomainEvent<UUID>> = emptyList()
        private set

    override fun publish(events: List<DomainEvent<UUID>>) {
        latestEvents = events.toList()
    }
}

data class OrderConfirmationResult(
    val record: OrderRecord,
    val eventPublished: Boolean,
)

sealed class OrderCommandException(message: String, cause: Throwable): RuntimeException(message, cause)
class OrderPersistenceException(cause: Throwable): OrderCommandException("Order persistence failed.", cause)
class OrderEventHandoffException(cause: Throwable): OrderCommandException("Order event handoff failed.", cause)

class OrderCommandService(
    private val repository: R2dbcCaffeineRepository<UUID, OrderRecord>,
    private val publisher: OrderEventPublisher,
    private val clock: Clock,
) {
    suspend fun confirm(orderId: UUID): OrderConfirmationResult {
        val record = try {
            repository.get(orderId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw OrderPersistenceException(e)
        }
        val now = clock.instant()
        return confirm(record?.let(DemoOrder::rehydrate) ?: DemoOrder.pending(orderId, now), now)
    }

    internal suspend fun confirm(order: DemoOrder, occurredAt: java.time.Instant = clock.instant()): OrderConfirmationResult {
        if (!order.confirm(occurredAt)) return OrderConfirmationResult(order.toRecord(), false)
        val record = order.toRecord()
        try {
            currentCoroutineContext().ensureActive()
            repository.put(order.id, record)
            currentCoroutineContext().ensureActive()
        } catch (e: CancellationException) {
            invalidateAfterFailure(order.id, e)
            throw e
        } catch (e: Exception) {
            invalidateAfterFailure(order.id, e)
            throw OrderPersistenceException(e)
        }

        val events = order.domainEvents()
        try {
            publisher.publish(events)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw OrderEventHandoffException(e)
        }
        order.clearDomainEvents()
        return OrderConfirmationResult(record, events.isNotEmpty())
    }

    private suspend fun invalidateAfterFailure(id: UUID, original: Throwable) {
        try {
            withContext(NonCancellable) { repository.invalidate(id) }
        } catch (cleanup: Exception) {
            original.addSuppressed(cleanup)
        }
    }
}
```

공개 ID 메서드는 repository 조회/rehydration을 담당하며, 내부 aggregate overload는 HTTP API를 확장하지 않고 event 보존 및 정리를 검증하기 위한 테스트 seam입니다. `repository.put` 직전에 `currentCoroutineContext().ensureActive()`를 실행하고, 반환 직후 다시 실행합니다. `put` 또는 두 gate 중 하나에서 발생하는 모든 `CancellationException`은 동일한 `NonCancellable` invalidation helper를 거친 후 원래 cancellation object를 다시 throw하도록 처리합니다. 절대로 `Error`를 catch하지 않습니다.

- [ ] **4단계: GREEN 및 전체 빠른 테스트 실행**

실행:

```bash
./gradlew :examples-ktor-exposed-demo:test \
  --tests "*OrderCommandServiceTest" --no-daemon --console=plain
./gradlew :examples-ktor-exposed-demo:test --no-daemon --console=plain
```

예상 결과: 모든 서비스 케이스와 전체 Docker-free suite가 PASS.

- [ ] **5단계: 애플리케이션 경계 커밋**

```bash
git add examples/ktor-exposed-demo/src/main/kotlin/io/bluetape4k/examples/exposed/ktor/order/OrderCommandService.kt \
  examples/ktor-exposed-demo/src/test/kotlin/io/bluetape4k/examples/exposed/ktor/order/OrderCommandServiceTest.kt
git commit -m "Publish order events only after persistence returns" \
  -m "Constraint: WRITE_THROUGH mutates Caffeine before PostgreSQL and the publisher is intentionally non-durable." \
  -m "Rejected: drainDomainEvents | Durable ownership does not transfer in this example." \
  -m "Confidence: high" -m "Scope-risk: moderate" \
  -m "Directive: Preserve cache invalidation and ensureActive before any future event handoff changes." \
  -m "Tested: service ordering, compensation, cancellation, idempotency, and cause retention" \
  -m "Not-tested: real PostgreSQL and Ktor routes"
```

### 작업 4: UUID PostgreSQL/Caffeine 리포지토리 추가

**파일:**
- 생성: `examples/ktor-exposed-demo/src/main/kotlin/io/bluetape4k/examples/exposed/ktor/order/OrderRepository.kt`
- 수정: `examples/ktor-exposed-demo/src/test/kotlin/io/bluetape4k/examples/exposed/ktor/order/OrderDomainTest.kt`

- [ ] **1단계: 실패하는 메타데이터/직렬화 테스트 추가**

다음과 같이 `OrderDomainTest`를 확장합니다:

```kotlin
@Test
fun `record serialization id is stable and table uses client UUID`() {
    java.io.ObjectStreamClass.lookup(OrderRecord::class.java).serialVersionUID shouldBeEqualTo 1L
    val idColumn: org.jetbrains.exposed.v1.core.Column<org.jetbrains.exposed.v1.core.dao.id.EntityID<UUID>> = DemoOrders.id
    idColumn.name shouldBeEqualTo "id"
    DemoOrders.tableName shouldBeEqualTo "ktor_demo_orders"
}
```

- [ ] **2단계: RED 실행**

다음을 실행합니다:

```bash
./gradlew :examples-ktor-exposed-demo:test \
  --tests "*OrderDomainTest" --no-daemon --console=plain
```

예상 결과: `DemoOrders`가 존재하지 않으므로 FAIL.

- [ ] **3단계: 테이블 및 구체적인 리포지토리 구현**

`OrderRepository.kt`를 생성합니다:

```kotlin
package io.bluetape4k.examples.exposed.ktor.order

import io.bluetape4k.exposed.cache.CacheWriteMode
import io.bluetape4k.exposed.cache.LocalCacheConfig
import io.bluetape4k.exposed.core.dao.id.TimebasedUUIDTable
import io.bluetape4k.exposed.r2dbc.caffeine.repository.AbstractR2dbcCaffeineRepository
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.statements.BatchInsertStatement
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Duration
import java.util.UUID

object DemoOrders: TimebasedUUIDTable("ktor_demo_orders") {
    val status = enumerationByName("status", 16, OrderStatus::class)
    val updatedAt = timestamp("updated_at")
}

class OrderR2dbcCaffeineRepository(
    config: LocalCacheConfig = LocalCacheConfig(
        keyPrefix = "orders",
        maximumSize = 1_000,
        expireAfterWrite = Duration.ofMinutes(10),
        writeMode = CacheWriteMode.WRITE_THROUGH,
    ),
): AbstractR2dbcCaffeineRepository<UUID, OrderRecord>(config) {
    override val table: IdTable<UUID> = DemoOrders

    override suspend fun ResultRow.toEntity(): OrderRecord = OrderRecord(
        id = this[DemoOrders.id].value,
        status = this[DemoOrders.status],
        updatedAt = this[DemoOrders.updatedAt],
    )

    override fun UpdateStatement.updateEntity(entity: OrderRecord) {
        this[DemoOrders.status] = entity.status
        this[DemoOrders.updatedAt] = entity.updatedAt
    }

    override fun BatchInsertStatement.insertEntity(entity: OrderRecord) {
        this[DemoOrders.id] = entity.id
        this[DemoOrders.status] = entity.status
        this[DemoOrders.updatedAt] = entity.updatedAt
    }

    override fun extractId(entity: OrderRecord): UUID = entity.id
}
```

- [ ] **4단계: GREEN 실행 및 main 컴파일**

다음을 실행합니다:

```bash
./gradlew :examples-ktor-exposed-demo:test \
  --tests "*OrderDomainTest" --no-daemon --console=plain
./gradlew :examples-ktor-exposed-demo:compileKotlin --no-daemon --console=plain
```

예상 결과: PASS; main 소스에서 H2 R2DBC 타입을 참조하지 않습니다.

- [ ] **5단계: 리포지토리 매핑 커밋**

```bash
git add examples/ktor-exposed-demo/src/main/kotlin/io/bluetape4k/examples/exposed/ktor/order/OrderRepository.kt \
  examples/ktor-exposed-demo/src/test/kotlin/io/bluetape4k/examples/exposed/ktor/order/OrderDomainTest.kt
git commit -m "Use client UUIDs for deterministic write-through inserts" \
  -m "Constraint: Generic repository insertion needs a non-auto-increment identifier." \
  -m "Rejected: Demo-local upsert | It would hide the real repository behavior being taught." \
  -m "Confidence: high" -m "Scope-risk: narrow" \
  -m "Directive: Keep Caffeine and PostgreSQL explicitly non-atomic in documentation and tests." \
  -m "Tested: table metadata, serialization identity, and Kotlin compilation" \
  -m "Not-tested: database mapping round trip"
```

### 작업 5: 결정론적 순서 HTTP 계약 추가

**파일:**
- 생성: `examples/ktor-exposed-demo/src/main/kotlin/io/bluetape4k/examples/exposed/ktor/order/OrderRoutes.kt`
- 생성: `examples/ktor-exposed-demo/src/test/kotlin/io/bluetape4k/examples/exposed/ktor/order/OrderRoutesTest.kt`

- [ ] **1단계: 모의 서비스/리포지토리를 사용해 라우트 테스트 작성**

`testApplication`, `installBluetape4kKtorCore()`, 모의 `OrderCommandService`, 모의 `R2dbcCaffeineRepository<UUID, OrderRecord>`, 그리고 기록용 `DemoDiagnosticSink`를 사용해 `OrderRoutesTest.kt`를 작성합니다. 정확한 매트릭스는 다음과 같습니다.

| 테스트 이름 | 요청/설정 | 정확한 assertion |
|---|---|---|
| `missing or wrong header wins over invalid id with 403` | 헤더가 없는 요청과 잘못된 값이 있는 요청, `/orders/not-a-uuid/confirm`에 대한 두 번의 POST 요청 | 모두 `403`, code `DEMO_COMMAND_REQUIRED`, service/repository/publisher 호출 0회 |
| `hostile origin preflight receives no permissive CORS grant` | 악성 `Origin`, 요청된 POST 메서드, 요청된 `X-Demo-Command` 헤더가 포함된 OPTIONS preflight | `Access-Control-Allow-Origin`/credentials 허용 없음, 성공적인 mutation 응답 없음, service/repository/publisher 호출 0회 |
| `valid header and invalid uppercase nil or oversized id return constant 400` | 유효한 헤더를 사용한 네 번의 POST 요청 | 모든 응답이 `400 INVALID_ORDER_ID`; service 호출 0회; 입력값을 echo하지 않음 |
| `confirmation returns serialized eventPublished result` | 서비스가 confirmed result 반환 | `200 application/json`; 정확히 네 개의 필드; ISO instant |
| `sequential confirmation returns eventPublished false` | 서비스가 `eventPublished=false` 반환 | `200`; false가 인코딩되며 생략되지 않음 |
| `get returns 404 for missing order and 200 for stored order` | 리포지토리가 먼저 null을 반환한 뒤 record를 반환 | 정확한 `404 ORDER_NOT_FOUND`, 이어서 정확히 세 필드로 구성된 성공 응답 |
| `typed command failures map to distinct sanitized 503 responses` | 서비스가 각 typed failure를 throw | 정확한 code/message, UUID correlation ID, operation `confirm` |
| `get repository failure maps to ORDER_READ_FAILED and operation read` | 리포지토리가 일반 exception을 throw | 정확한 `503 ORDER_READ_FAILED`; diagnostic operation `read` |
| `secret bearing primary and suppressed messages never enter body or diagnostic` | URL/user/password/SQL 및 suppressed secret을 포함한 failure | response/diagnostic serialization에 해당 문자열이 하나도 포함되지 않음 |

- [ ] **2단계: RED 실행**

다음을 실행합니다.

```bash
./gradlew :examples-ktor-exposed-demo:test \
  --tests "*OrderRoutesTest" --no-daemon --console=plain
```

예상 결과: route DTO, validation, installer가 존재하지 않으므로 FAIL.

- [ ] **3단계: DTO, diagnostics, validation, routes 구현**

다음의 정확한 public contract로 `OrderRoutes.kt`를 생성합니다.

```kotlin
@Serializable data class OrderResponse(val orderId: String, val status: String, val updatedAt: String)
@Serializable data class OrderConfirmationResponse(val orderId: String, val status: String, val updatedAt: String, val eventPublished: Boolean)
@Serializable data class DemoErrorResponse(val code: String, val message: String, val correlationId: String? = null)

data class DemoDiagnostic(
    val code: String,
    val correlationId: String,
    val component: String,
    val operation: String? = null,
    val phase: String? = null,
    val outcome: String,
)

fun interface DemoDiagnosticSink { fun emit(diagnostic: DemoDiagnostic) }
```

다음 우선순위와 매핑으로 `fun Route.orderRoutes(service, repository, diagnostics)`를 구현합니다.

```kotlin
post("/orders/{orderId}/confirm") {
    if (call.request.headers["X-Demo-Command"] != "confirm-order") {
        call.respond(HttpStatusCode.Forbidden, DemoErrorResponse("DEMO_COMMAND_REQUIRED", "Required demo command header is missing or invalid."))
        return@post
    }
    val id = call.parameters["orderId"].toCanonicalOrderIdOrNull()
    if (id == null) {
        call.respond(HttpStatusCode.BadRequest, DemoErrorResponse("INVALID_ORDER_ID", "Order id must be a canonical non-nil UUID."))
        return@post
    }
    try {
        call.respond(service.confirm(id).toResponse())
    } catch (e: OrderPersistenceException) {
        call.respondServiceUnavailable("ORDER_PERSISTENCE_FAILED", "Order could not be stored.", "confirm", diagnostics)
    } catch (e: OrderEventHandoffException) {
        call.respondServiceUnavailable("ORDER_EVENT_HANDOFF_FAILED", "Order was stored but its event was not handed off.", "confirm", diagnostics)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        call.respondServiceUnavailable("ORDER_CONFIRMATION_FAILED", "Order confirmation failed.", "confirm", diagnostics)
    }
}
```

GET route와 helper를 다음과 정확히 같이 추가합니다. import는 해당 Ktor request/response/routing types, `CancellationException`, `UUID`입니다.

```kotlin
get("/orders/{orderId}") {
    val id = call.parameters["orderId"].toCanonicalOrderIdOrNull()
    if (id == null) {
        call.respond(HttpStatusCode.BadRequest, DemoErrorResponse("INVALID_ORDER_ID", "Order id must be a canonical non-nil UUID."))
        return@get
    }
    try {
        val record = repository.get(id)
        if (record == null) {
            call.respond(HttpStatusCode.NotFound, DemoErrorResponse("ORDER_NOT_FOUND", "Order was not found."))
        } else {
            call.respond(record.toResponse())
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        call.respondServiceUnavailable("ORDER_READ_FAILED", "Order could not be loaded.", "read", diagnostics)
    }
}

private fun String?.toCanonicalOrderIdOrNull(): UUID? {
    val raw = this ?: return null
    if (raw.length != 36) return null
    val parsed = runCatching { UUID.fromString(raw) }.getOrNull() ?: return null
    if (parsed == UUID(0L, 0L) || parsed.toString() != raw) return null
    return parsed
}

private fun OrderConfirmationResult.toResponse() = OrderConfirmationResponse(
    orderId = record.id.toString(),
    status = record.status.name,
    updatedAt = record.updatedAt.toString(),
    eventPublished = eventPublished,
)

private fun OrderRecord.toResponse() = OrderResponse(
    orderId = id.toString(),
    status = status.name,
    updatedAt = updatedAt.toString(),
)

private suspend fun ApplicationCall.respondServiceUnavailable(
    code: String,
    message: String,
    operation: String,
    diagnostics: DemoDiagnosticSink,
) {
    val correlationId = UUID.randomUUID().toString()
    diagnostics.emit(
        DemoDiagnostic(
            code = code,
            correlationId = correlationId,
            component = "order-command",
            operation = operation,
            outcome = "failed",
        )
    )
    respond(
        HttpStatusCode.ServiceUnavailable,
        DemoErrorResponse(code, message, correlationId),
    )
}
```

- [ ] **4단계: GREEN 및 serializer compilation 실행**

다음을 실행합니다.

```bash
./gradlew :examples-ktor-exposed-demo:test \
  --tests "*OrderRoutesTest" --no-daemon --console=plain
./gradlew :examples-ktor-exposed-demo:compileKotlin --no-daemon --console=plain
```

예상 결과: 모든 route case가 PASS하고 생성된 serializer가 compile됨.

- [ ] **5단계: HTTP boundary 커밋**

```bash
git add examples/ktor-exposed-demo/src/main/kotlin/io/bluetape4k/examples/exposed/ktor/order/OrderRoutes.kt \
  examples/ktor-exposed-demo/src/test/kotlin/io/bluetape4k/examples/exposed/ktor/order/OrderRoutesTest.kt
git commit -m "Make order confirmation failures stable for callers" \
  -m "Constraint: The loopback demo needs deterministic responses without exposing SQL, credentials, or throwable text." \
  -m "Rejected: One generic 503 | Persistence and post-write event failure require different recovery guidance." \
  -m "Confidence: high" -m "Scope-risk: moderate" \
  -m "Directive: Keep header validation ahead of order-id parsing." \
  -m "Tested: route precedence, serialization, media types, failure taxonomy, and redaction" \
  -m "Not-tested: full application resources"
```

### 작업 6: H2 R2DBC를 소유한 PostgreSQL 리소스 및 안전한 러너로 교체

**파일:**
- 수정: `examples/ktor-exposed-demo/src/main/kotlin/io/bluetape4k/examples/exposed/ktor/KtorExposedDemoResources.kt`
- 수정: `examples/ktor-exposed-demo/src/main/kotlin/io/bluetape4k/examples/exposed/ktor/KtorExposedDemoApplication.kt`
- 수정: `examples/ktor-exposed-demo/build.gradle.kts`
- 생성: `examples/ktor-exposed-demo/src/test/kotlin/io/bluetape4k/examples/exposed/ktor/KtorExposedDemoLifecycleTest.kt`
- 교체: `examples/ktor-exposed-demo/src/test/kotlin/io/bluetape4k/examples/exposed/ktor/KtorExposedDemoApplicationTest.kt`

- [ ] **1단계: 라이프사이클 및 컴포지션 테스트를 먼저 작성**

`KtorExposedDemoLifecycleTest`는 이름이 지정된 가짜 close action과 가짜 `DemoServer` 인스턴스를 정의해야 합니다. 다음 assertion matrix를 사용합니다:

| 테스트 이름 | 정확한 검증 |
|---|---|
| `acquisition failure closes completed resources in reverse order and keeps primary cause` | `lease,jdbc,dispatcher,pool`을 획득하고 `primary`를 throw; close 순서는 `pool,dispatcher,jdbc,lease`; throw된 객체는 `primary`; cleanup failures는 suppressed 처리; 즉시 다음 lifecycle acquisition이 성공 |
| `engine creation or start failure closes resources and returns exit one` | server factory/start가 throw; resources가 한 번만 close; 하나의 `DEMO_STARTUP_FAILED`; status 1; throwable text 없음 |
| `schema initialization failure unregisters restores prior default closes pool and releases lease` | 주입된 schema step이 `primary`를 throw; 정확한 순서는 `closeAndUnregister,restore-default,pool,...,lease`; result가 `primary`를 유지; cleanup failures는 suppressed 처리; 즉시 다음 lifecycle acquisition이 성공 |
| `engine create bind and start failures retain their original cause separately` | 각 boundary에 하나의 이름 있는 failure를 주입 | 각 `DemoRunResult.primaryFailure`가 정확히 주입된 객체; status 1; resources가 한 번만 close; 정제된 stderr에 해당 text가 포함되지 않음 |
| `actual loopback server uses configured shutdown and cleans after ApplicationStopped` | ephemeral loopback port에서 production `EmbeddedServer`를 생성; engine config가 `1000/5000`인지 확인; helper thread에서 stop; blocking `start(wait=true)`가 반환되기 전에 `ApplicationStopped`/`closeReport`가 완료됨을 검증; status 0 |
| `resource cleanup failures aggregate once continue cleanup and return two` | repository/pool failures; 이후 closer들도 계속 실행; 하나의 `DEMO_SHUTDOWN_FAILED`; status 2 |
| `repeated close returns the original report without closing twice` | close를 두 번 호출; 각 action count가 1; report object/value가 변경되지 않음 |
| `concurrent close returns one report and runs every closer once` | 첫 번째 closer가 latch에 의해 대기 중인 동안 두 thread가 barrier를 통해 진입; 둘 다 제한 시간 내 완료; 각 closer count가 1; 둘 다 동일한 report instance를 받음 |
| `overlapping demo lifecycle is rejected and sequential reuse succeeds` | 첫 번째 resource lease를 유지; 두 번째 acquisition은 default를 변경하기 전에 실패; 첫 번째를 close; 세 번째 acquisition은 성공; 이전 default가 보존됨 |
| `external non-null default is never overwritten during close` | demo close 직전에 다른 non-null default를 설치 | `closeAndUnregister(demo)`가 해당 external default를 현재 값으로 유지; conditional restore가 캡처된 이전 값을 건너뜀; external DB가 close 또는 unregister되지 않음 |
| `stderr diagnostic sink renders only the allowlisted key-value record` | 제공된 `PrintStream`을 캡처; 한 줄, 고정된 field order, 필요한 경우 UUID correlation ID, null 생략, runtime/startup/shutdown record에서 URL/user/password/SQL/throwable text가 없음을 검증 |

`KtorExposedDemoApplicationTest`를 Docker-free composition test로 교체합니다. 이 테스트는 mock service/repository dependencies를 사용해 Ktor core와 `orderRoutes`를 설치하고, route registration을 검증하며, JDBC/R2DBC databases를 생성하거나 mock하지 않습니다.

- [ ] **2단계: RED 실행**

다음을 실행합니다:

```bash
./gradlew :examples-ktor-exposed-demo:test \
  --tests "*KtorExposedDemoLifecycleTest" \
  --tests "*KtorExposedDemoApplicationTest" \
  --no-daemon --console=plain
```

예상 결과: config, cleanup report, server abstraction, runner가 아직 존재하지 않으므로 FAIL.

- [ ] **3단계: configuration 및 failure-atomic resource ownership 구현**

`KtorExposedDemoResources.kt`에서 다음을 정의합니다:

```kotlin
data class KtorExposedDemoConfig(
    val r2dbcUrl: String = System.getenv("DEMO_POSTGRES_R2DBC_URL") ?: "r2dbc:postgresql://localhost:5432/ktor_exposed_demo",
    val user: String = System.getenv("DEMO_POSTGRES_USER") ?: "demo",
    val password: String = System.getenv("DEMO_POSTGRES_PASSWORD") ?: "demo",
)

data class DemoCleanupReport(val failures: List<Throwable>) {
    val isClean: Boolean get() = failures.isEmpty()
}
```

`DemoItems`와 H2 JDBC 초기화는 유지합니다. H2 R2DBC 생성을 `ConnectionFactoryOptions.parse(config.r2dbcUrl).mutate()`와 typed `USER`, `PASSWORD` options를 사용하는 방식으로 교체하고, `initialSize(1)`, `maxSize(2)`, `maxAcquireTime(Duration.ofSeconds(5))`를 사용하는 `ConnectionPool` 및 `R2dbcDatabase.connect`를 사용합니다. R2DBC `TransactionManager.defaultDatabase`를 캡처하고 demo DB를 default로 설정한 다음 다음을 실행합니다:

```kotlin
runBlocking {
    suspendTransaction(r2dbcDatabase) { org.jetbrains.exposed.v1.r2dbc.SchemaUtils.create(DemoOrders) }
}
```

`OrderR2dbcCaffeineRepository`, `InMemoryOrderEventPublisher`, `OrderCommandService(orderRepository, eventPublisher, Clock.systemUTC())`를 생성하고, 동일한 인스턴스를 일관되게 이름이 지정된 resource properties로 노출합니다.

production acquisition은 주입 가능한 `DemoResourceSteps`가 process-local atomic lifecycle lease, JDBC, dispatcher, pool, R2DBC database, default-database registration, schema initialization, repository, publisher/service construction을 담당하는 내부 `DemoResourceAcquirer` 뒤에 둡니다. 획득된 각 step은 하나의 reverse-order stack에 push되는 이름 있는 close action을 제공합니다. Production은 실제 factory를 사용하고, 테스트는 이름 있는 double을 주입합니다. Exposed default를 변경하기 전에 두 번째 active demo lifecycle을 거부합니다. 정상 close 및 모든 construction-failure 경로에서 lease를 release하여 sequential reuse가 유효하게 유지되도록 합니다. acquisition 또는 schema failure가 발생하면 완료된 step을 unwind하고, R2DBC 경로가 `closeAndUnregister(demo)` → current default가 null인 경우에만 캡처된 이전 default 복원 → pool dispose를 수행하도록 보장하며, 모든 cleanup failure를 원래 failure에 suppressed로 추가한 뒤 정확히 동일한 원본 객체를 rethrow합니다. 외부 코드가 설치한 다른 non-null default를 절대 덮어쓰지 않습니다. 이 seam은 내부용이며 example의 user-facing API를 변경하지 않습니다.

private close lock과 volatile stored report를 사용해 idempotent `closeReport()`를 구현하여, concurrent `ApplicationStopped` 및 runner fallback 호출이 cleanup을 한 번만 실행하도록 합니다. 정확한 semantic order는 다음과 같습니다: repository close; R2DBC `TransactionManager.closeAndUnregister(demo)`; current default가 null인 경우에만 캡처된 이전 default 복원; five-second bound를 적용한 pool disposal; Hikari close; dispatcher close; 마지막으로 demo lifecycle lease release. 각 `Exception` 이후에도 계속 진행하고, logging messages 없이 failure를 보존하며, 반복 호출 시 동일한 stored report를 반환합니다. 이전 closer가 실패하더라도 lease release는 반드시 실행되어야 합니다. `close()`는 `closeReport()`에 위임합니다.

- [ ] **4단계: application composition 및 runner status 구현**

`KtorExposedDemoApplication.kt`에서 다음을 수행합니다:

- 두 인자 overload인 `installBluetape4kExposedKtor(config, cacheReadiness)`를 호출하고, `ExposedKtorCacheReadinessConfig(listOf(ExposedKtorCacheContributor.r2dbcRepository("orders") { resources.orderRepository.validateConsistency() }))`를 전달합니다.
- `/transactions/jdbc-count`를 유지하고, `call.exposedR2dbcTransaction(resources.r2dbcDatabase) { DemoOrders.selectAll().count() }`를 통해 `/transactions/r2dbc-count`를 추가합니다.
- POST/GET order routes를 설치합니다.
- production Netty를 `127.0.0.1:8080`에 bind합니다.
- production Netty engine을 `shutdownGracePeriod = 1_000`, `shutdownTimeout = 5_000`으로 구성합니다. Ktor의 `EmbeddedServer.start(wait=true)`는 실제 JVM shutdown hook을 설치하고, 인자가 없는 `stop()`은 구성된 값을 사용합니다.
- idempotent `closeReport()`를 호출하는 `ApplicationStopped`를 유지합니다.

테스트 가능한 server port를 정의합니다:

```kotlin
internal interface DemoServer {
    fun start(wait: Boolean)
    fun stop(gracePeriodMillis: Long, timeoutMillis: Long)
}

internal fun interface DemoResourcesFactory {
    fun create(): KtorExposedDemoResources
}

internal fun runKtorExposedDemo(
    resourcesFactory: DemoResourcesFactory,
    serverFactory: (KtorExposedDemoResources) -> DemoServer,
    diagnosticSink: DemoDiagnosticSink,
): DemoRunResult

internal data class DemoRunResult(
    val status: Int,
    val primaryFailure: Throwable? = null,
    val cleanupReport: DemoCleanupReport,
)
```

resource/server create/bind/start failure가 발생하면 runner는 `DemoRunResult(status=1, primaryFailure=<exact original>, cleanupReport=...)`를 반환하고 `phase=startup`인 하나의 `DEMO_STARTUP_FAILED`를 출력합니다. 이미 생성된 server에 대해서만 failed-start fallback이 `stop(1_000, 5_000)`을 명시적으로 호출한 뒤 획득한 resources를 close합니다. `DemoResourcesFactory.create()`가 resource object를 반환하기 전에 실패하는 경우, acquirer는 이미 cleanup failures를 원래 throwable에 연결한 상태입니다. runner는 명시적인 fallback report로 `DemoCleanupReport(primary.suppressed.toList())`를 사용합니다. 성공 경로에서는 `start(wait=true)`를 호출합니다. Ktor가 설치한 shutdown hook은 구성된 `1_000/5_000` stop을 시작하고, `ApplicationStopped`를 동기적으로 발생시키며, `start`를 unblock합니다. 그런 다음 runner는 idempotent `closeReport()`를 읽습니다. 두 번째 successful-path stop을 실행해서는 안 됩니다. cleanup이 clean이면 status `0`을 반환하고, `phase=shutdown`인 하나의 aggregated application-resource `DEMO_SHUTDOWN_FAILED` 이후에는 status `2`를 반환합니다. Direct tests는 retained cause와 suppressed cleanup chain을 검증합니다. diagnostics에는 여전히 throwable 또는 message가 전달되지 않습니다. Ktor 3.5는 engine-stop exceptions를 내부적으로 catch하고 framework-log하므로, demo는 runner가 해당 framework-owned failure를 observe하거나 reclassify할 수 있다고 잘못 주장하지 않습니다. 대신 production loopback test가 실제 engine configuration과 lifecycle ordering을 검증합니다.

`StderrDemoDiagnosticSink(output: PrintStream = System.err)`를 구현합니다. deterministic manual formatter는 null field를 생략하고, 다음 고정 순서로 하나의 space-delimited key-value line을 출력합니다: `code`, `correlationId`, `component`, `operation`, `phase`, `outcome`. Formatter는 allowlisted DTO fields만 받으며, arbitrary maps 또는 throwable values를 serialize하지 않습니다. Sink는 throwable parameter를 받지 않으며 exception messages, URLs, credentials 또는 SQL을 절대 전달받지 않습니다. 테스트에서는 제공된 `PrintStream`을 캡처하고, 정확한 confirm/read/startup/shutdown records, correlation-ID shape, one-line framing, 생략된 null, secret/throwable exclusion을 검증합니다. `main`은 production runner에 `StderrDemoDiagnosticSink()`를 전달하고 `exitProcess(result.status)`를 호출하며, `primaryFailure`를 절대 노출하지 않습니다.

Task 6이 끝나면 `build.gradle.kts`에서 `runtimeOnly(bt4k.r2dbc.h2)`를 제거하고, 교체된 Docker-free tests가 더 이상 H2 R2DBC resources를 생성하지 않음을 검증합니다.

- [ ] **5단계: GREEN 실행 및 fast tests가 Docker-free임을 검증**

다음을 실행합니다:

```bash
./gradlew :examples-ktor-exposed-demo:test --no-daemon --console=plain
./gradlew :examples-ktor-exposed-demo:compileKotlin --no-daemon --console=plain
```

예상 결과: PASS; Testcontainers log가 나타나지 않으며 lifecycle order/status assertions가 통과합니다.

- [ ] **6단계: resource ownership 커밋**

```bash
git add examples/ktor-exposed-demo/src/main/kotlin/io/bluetape4k/examples/exposed/ktor/KtorExposedDemoResources.kt \
  examples/ktor-exposed-demo/src/main/kotlin/io/bluetape4k/examples/exposed/ktor/KtorExposedDemoApplication.kt \
  examples/ktor-exposed-demo/build.gradle.kts \
  examples/ktor-exposed-demo/src/test/kotlin/io/bluetape4k/examples/exposed/ktor/KtorExposedDemoLifecycleTest.kt \
  examples/ktor-exposed-demo/src/test/kotlin/io/bluetape4k/examples/exposed/ktor/KtorExposedDemoApplicationTest.kt
git commit -m "Restore Exposed global state before releasing PostgreSQL" \
  -m "Constraint: Parameterless repository transactions resolve the process-wide R2DBC default." \
  -m "Rejected: Pool-only cleanup | It leaves a closed database registered for later lifecycles." \
  -m "Confidence: high" -m "Scope-risk: broad" \
  -m "Directive: Preserve repository-unregister-restore-pool shutdown order and non-zero failure exits." \
  -m "Tested: Docker-free lifecycle, startup, shutdown, diagnostics, and application composition" \
  -m "Not-tested: real PostgreSQL connectivity"
```

### 작업 7: PostgreSQL을 대상으로 전체 시나리오를 순차적으로 입증

**파일:**
- 생성: `examples/ktor-exposed-demo/src/postgresIntegrationTest/kotlin/io/bluetape4k/examples/exposed/ktor/KtorExposedDemoPostgresIntegrationTest.kt`

- [ ] **1단계: Docker 작업을 실행하기 전에 전체 통합 테스트 작성**

`PostgreSQLContainer("postgres:16-alpine")`을 사용하는 `@Execution(ExecutionMode.SAME_THREAD)` 테스트 클래스 하나를 생성합니다. 일반 시나리오, readiness, 기본값 복원 케이스에는 suite 수준의 컨테이너 하나를 순차적으로 재사용하며, 각 테스트는 애플리케이션 리소스를 새로 생성하고 닫습니다. 장애 케이스는 중지할 수 있는 독립적인 두 번째 컨테이너를 소유합니다. suite teardown을 포함하여 모든 리소스가 자신이 속한 컨테이너보다 먼저 닫히도록 `try/finally`를 사용합니다. `container.host`, `container.getMappedPort(5432)`, `container.databaseName`, `container.username`, `container.password`에서 `KtorExposedDemoConfig`를 구성합니다.

다음 네 가지 이름의 케이스를 구현합니다: `order confirmation persists publishes reads through cache and stays sequentially idempotent`, `readiness exposes jdbc r2dbc and cache orders while health remains probe free`, `stopped PostgreSQL keeps liveness up and returns bounded sanitized readiness down`, `closing restores previous default and a second lifecycle does not reuse the closed pool`.

시나리오 케이스는 다음을 수행해야 합니다.

1. JDBC count `2`와 초기 R2DBC count `0`을 검증합니다.
2. 클라이언트가 생성한 소문자 UUID를 필수 헤더와 함께 POST하고 `eventPublished=true`임을 검증합니다.
3. 명시적인 `suspendTransaction(resources.r2dbcDatabase)`를 사용하여 PostgreSQL에서 행을 직접 읽습니다. 암시적 기본값은 절대 사용하지 않습니다.
4. repository key를 무효화하고 캐시 항목이 없음을 검증한 다음, HTTP를 통해 GET하고 반환된 레코드가 캐시에 포함되어 있음을 검증합니다.
5. repository GET을 다시 호출하고 캐시된 레코드와 참조 동일성임을 검증합니다.
6. POST를 반복하고 `eventPublished=false`, DB 행 하나, runtime event snapshot 하나임을 검증합니다.

장애 케이스는 애플리케이션이 시작된 후 컨테이너를 중지하고 `/healthz/exposed`가 `200`임을 검증해야 합니다. 또한 10초 클라이언트/테스트 timeout을 사용하여 `/readyz/exposed`가 URL, 사용자, 비밀번호, SQL 또는 exception 텍스트 없이 정제된 `503`임을 검증해야 합니다.

- [ ] **2단계: 최초의 실제 PostgreSQL 검증 실행**

다음을 실행합니다.

```bash
./gradlew :examples-ktor-exposed-demo:postgresIntegrationTest \
  --no-parallel --no-daemon --console=plain
```

예상 결과: 구현이 이미 올바르면 PASS입니다. 최초의 실제 실행에서 매핑, lifecycle 또는 timeout 결함이 드러나면 정확한 실패를 증거로 보존하고 가장 작은 수정만 적용합니다. Docker를 사용할 수 없는 경우는 엄격한 환경 실패이며, skip이나 H2 fallback으로 처리하지 않습니다. RED 결과를 조작하지 않습니다.

- [ ] **3단계: 가장 작은 통합 수정만 적용**

실제 PostgreSQL 실패가 현재 구현의 잘못을 입증하는 경우에만 매핑, route composition, lifecycle 또는 timeout을 수정합니다. 편집하는 동안 다음 불변 조건을 유지합니다.

```text
ensureActive -> repository.put -> ensureActive -> publisher.publish -> clearDomainEvents
repository.close -> closeAndUnregister -> restore previous default -> pool.dispose
ordinary test !-> postgresIntegrationTest
```

- [ ] **4단계: GREEN 실행 및 빠른 테스트 재실행**

다음을 실행합니다.

```bash
./gradlew :examples-ktor-exposed-demo:postgresIntegrationTest \
  --no-parallel --no-daemon --console=plain
./gradlew :examples-ktor-exposed-demo:test --no-daemon --console=plain
```

예상 결과: PostgreSQL task는 PASS해야 합니다. 전용 테스트는 전체 suite의 중복 컨테이너 실행 없이 두 개의 리소스 lifecycle을 순차적으로 수행하여 닫힌 기본값/풀 오염이 없음을 입증해야 하며, 빠른 테스트도 계속 PASS해야 합니다.

- [ ] **5단계: 실제 데이터베이스 검증 커밋**

```bash
git add examples/ktor-exposed-demo/src/postgresIntegrationTest/kotlin/io/bluetape4k/examples/exposed/ktor/KtorExposedDemoPostgresIntegrationTest.kt \
  examples/ktor-exposed-demo/src/main/kotlin/io/bluetape4k/examples/exposed/ktor \
  examples/ktor-exposed-demo/src/test/kotlin/io/bluetape4k/examples/exposed/ktor
git commit -m "Prove the Ktor cache scenario against PostgreSQL" \
  -m "Constraint: Testcontainers-backed Exposed verification must run sequentially." \
  -m "Rejected: Mock-only repository proof | It cannot validate PostgreSQL mapping, readiness, or global default cleanup." \
  -m "Confidence: high" -m "Scope-risk: moderate" \
  -m "Directive: Rerun this task on the exact final PR head after any later commit." \
  -m "Tested: two sequential PostgreSQL integration runs and the fast module suite" \
  -m "Not-tested: Docker Compose walkthrough"
```

### 작업 8: Loopback Docker Compose 런타임 추가

**파일:**
- 생성: `examples/ktor-exposed-demo/compose.yaml`

- [ ] **1단계: 정확한 로컬 PostgreSQL 계약 생성**

```yaml
services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: ktor_exposed_demo
      POSTGRES_USER: demo
      POSTGRES_PASSWORD: demo
    ports:
      - "127.0.0.1:${DEMO_POSTGRES_PORT:-5432}:5432"
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U demo -d ktor_exposed_demo"]
      interval: 2s
      timeout: 3s
      retries: 20
      start_period: 5s
    volumes:
      - ktor-exposed-demo-postgres:/var/lib/postgresql/data

volumes:
  ktor-exposed-demo-postgres:
```

- [ ] **2단계: Compose 수명 주기 검증 및 스모크 테스트**

저장소 루트에서 실행:

```bash
COMPOSE_PROJECT_NAME=bt4k-issue-326-smoke
COMPOSE_FILE=examples/ktor-exposed-demo/compose.yaml
cleanup_compose() {
  docker compose -p "$COMPOSE_PROJECT_NAME" -f "$COMPOSE_FILE" down
}
trap cleanup_compose EXIT INT TERM
docker compose -p "$COMPOSE_PROJECT_NAME" -f "$COMPOSE_FILE" config --quiet
docker compose -p "$COMPOSE_PROJECT_NAME" -f "$COMPOSE_FILE" \
  up -d --wait --wait-timeout 60 postgres
docker compose -p "$COMPOSE_PROJECT_NAME" -f "$COMPOSE_FILE" ps
cleanup_compose
trap - EXIT INT TERM
```

예상 결과: 구성 검증이 성공하고, PostgreSQL이 60초 이내에 정상 상태가 되며, `down` 실행 후에도 이름이 지정된 볼륨이 유지된다.

- [ ] **3단계: 로컬 런타임 소유권 커밋**

```bash
git add examples/ktor-exposed-demo/compose.yaml
git commit -m "Make the PostgreSQL example runnable without hidden infrastructure" \
  -m "Constraint: Both Ktor and PostgreSQL must bind to loopback by default." \
  -m "Rejected: Runtime Testcontainers | Applications must not depend on test libraries." \
  -m "Confidence: high" -m "Scope-risk: narrow" \
  -m "Directive: Keep normal down and destructive volume reset distinct in documentation." \
  -m "Tested: compose config, health wait, ps, and normal down" \
  -m "Not-tested: README curl walkthrough"
```

### 작업 9: 아키텍처 및 시퀀스 다이어그램 생성과 감사

**파일:**
- 생성: `docs/images/readme-diagrams/examples-ktor-exposed-demo-architecture-01.svg`
- 생성: `docs/images/readme-diagrams/examples-ktor-exposed-demo-architecture-01.png`
- 생성: `docs/images/readme-diagrams/examples-ktor-exposed-demo-sequence-01.svg`
- 생성: `docs/images/readme-diagrams/examples-ktor-exposed-demo-sequence-01.png`

- [ ] **1단계: 아키텍처 에셋 루프 완료**

스타일 참고 자료로 `docs/images/readme-diagrams/exposed-r2dbc-caffeine-diagram-01.svg`를 사용한다. 새 SVG에는 애플리케이션 소유권 경계, HTTP → routes → command service, aggregate, concrete repository, PostgreSQL 앞의 Caffeine, Spring-free publisher, readiness contributor, repository-before-unregister/pool shutdown을 표시해야 한다. 모든 마커 크기는 `markerUnits="userSpaceOnUse"`로 설정하고, 승인된 글꼴 스택을 사용하며, 모든 레이블이 전체 크기 PNG에서도 읽기 쉬워야 한다.

시퀀스 에셋을 수정하기 전에 아키텍처에 대한 다음의 정확한 루프를 완료한다: SVG 편집 → `xmllint` → 배율 2로 PNG 렌더링 → 커넥터/기하/엔드포인트/혼합 모서리 감사 → 원본 해상도로 PNG 검사 → 커넥터/카드/경로/레이블 수, PNG 크기 및 시각적 메모 기록. `--fail-diagonal`을 사용하여 기하 감사를 실행한다. 감사에서 `WEAK`, 커넥터/카드/경로/레이블 수 0, 또는 SVG 분류 불가가 보고되면 해당 결과를 PASS로 간주하지 말고 대상 불변식 검사를 추가하여 실행한다.

- [ ] **2단계: 실제 시퀀스 에셋 루프 완료**

스타일 참고 자료로 `docs/images/readme-diagrams/exposed-r2dbc-caffeine-sequence-01.svg`를 사용한다. 레이블이 있는 라이프라인, 활성화 바, 표시되는 번호 pill, 그리고 cache hit/miss, persistence success/failure+invalidate, publisher success/retain을 위한 투명한 `alt` 프레임을 포함한다. 비원자적인 Caffeine→PostgreSQL 순서를 명확히 표시해야 하며, 어떤 커넥터도 레이블이나 프레임 제목을 가로질러서는 안 된다.

1단계의 증거가 완료된 후에만 시퀀스에 대해 동일한 루프를 완료한다: SVG 편집 → `xmllint` → 배율 2로 PNG 렌더링 → 커넥터/기하/엔드포인트/혼합 모서리/시퀀스 스타일 감사 → 원본 해상도로 PNG 검사 → 커넥터/카드/경로/레이블 수, PNG 크기 및 시각적 메모 기록. `--fail-diagonal`을 사용하여 기하 감사를 실행한다. 동일한 `WEAK`/수량 0 대체 규칙을 적용한다.

- [ ] **3단계: 에셋별 정확한 명령을 실행하고 증거 보존**

```bash
xmllint --noout docs/images/readme-diagrams/examples-ktor-exposed-demo-architecture-01.svg
cairosvg -s 2 -o docs/images/readme-diagrams/examples-ktor-exposed-demo-architecture-01.png \
  docs/images/readme-diagrams/examples-ktor-exposed-demo-architecture-01.svg
python ~/.codex/skills/bluetape-diagram/scripts/diagram-connector-audit.py docs/images/readme-diagrams/examples-ktor-exposed-demo-architecture-01.svg
python ~/.codex/skills/bluetape-diagram/scripts/diagram-geometry-audit.py --fail-diagonal docs/images/readme-diagrams/examples-ktor-exposed-demo-architecture-01.svg
python ~/.codex/skills/bluetape-diagram/scripts/diagram-endpoint-audit.py docs/images/readme-diagrams/examples-ktor-exposed-demo-architecture-01.svg
python ~/.codex/skills/bluetape-diagram/scripts/diagram-mixed-corner-audit.py docs/images/readme-diagrams/examples-ktor-exposed-demo-architecture-01.svg

xmllint --noout docs/images/readme-diagrams/examples-ktor-exposed-demo-sequence-01.svg
cairosvg -s 2 -o docs/images/readme-diagrams/examples-ktor-exposed-demo-sequence-01.png \
  docs/images/readme-diagrams/examples-ktor-exposed-demo-sequence-01.svg
python ~/.codex/skills/bluetape-diagram/scripts/diagram-connector-audit.py docs/images/readme-diagrams/examples-ktor-exposed-demo-sequence-01.svg
python ~/.codex/skills/bluetape-diagram/scripts/diagram-geometry-audit.py --fail-diagonal docs/images/readme-diagrams/examples-ktor-exposed-demo-sequence-01.svg
python ~/.codex/skills/bluetape-diagram/scripts/diagram-endpoint-audit.py docs/images/readme-diagrams/examples-ktor-exposed-demo-sequence-01.svg
python ~/.codex/skills/bluetape-diagram/scripts/diagram-mixed-corner-audit.py docs/images/readme-diagrams/examples-ktor-exposed-demo-sequence-01.svg
python ~/.codex/skills/bluetape-diagram/scripts/diagram-sequence-style-audit.py docs/images/readme-diagrams/examples-ktor-exposed-demo-sequence-01.svg
```

예상 결과: 각 에셋은 다음 에셋으로 넘어가기 전에 완료되어야 하며, 두 PNG는 비어 있지 않아야 한다. XML 및 모든 감사가 커넥터/기하/엔드포인트/스타일 위반 0건 또는 명시적인 대상 대체 불변식과 함께 PASS해야 한다. 검토 증거에는 모든 수량, 크기 및 원본 해상도 시각적 메모가 기록되어야 한다.

- [ ] **4단계: 마지막 SVG 변경 후 최종 PNG 두 개 재검사**

이미지 뷰어에서 두 PNG를 원본 해상도로 연다. 예상 결과: 모든 텍스트가 읽기 쉽고, 화살촉의 비율이 적절하며, 잘림/겹침이 없어야 한다. 시퀀스 분기와 번호는 색상에 의존하지 않고 계속 표시되어야 한다. 어느 이미지라도 변경되면 다시 렌더링하고 모든 감사를 다시 실행한 후 다시 검사한다.

- [ ] **5단계: 시각적 설명 커밋**

```bash
git add docs/images/readme-diagrams/examples-ktor-exposed-demo-architecture-01.svg \
  docs/images/readme-diagrams/examples-ktor-exposed-demo-architecture-01.png \
  docs/images/readme-diagrams/examples-ktor-exposed-demo-sequence-01.svg \
  docs/images/readme-diagrams/examples-ktor-exposed-demo-sequence-01.png
git commit -m "Explain the Ktor order flow without implying cache atomicity" \
  -m "Constraint: README diagrams require canonical SVG and PNG pairs in the approved dark family." \
  -m "Rejected: Generic flowchart sequence | It hides timing and failure branches." \
  -m "Confidence: high" -m "Scope-risk: narrow" \
  -m "Directive: Rerun all audits and full-size inspection after any SVG edit." \
  -m "Tested: XML, render, connector, geometry, endpoint, mixed-corner, sequence-style, and visual inspection" \
  -m "Not-tested: README embedding"
```

### 작업 10: 이중 언어 사용자 워크스루 및 제한 사항 작성

**파일:**
- 교체: `examples/ktor-exposed-demo/README.md`
- 교체: `examples/ktor-exposed-demo/README.ko.md`

- [ ] **1단계: 고정된 섹션 순서로 영어 및 한국어 README 작성**

두 파일은 다음 순서를 따라야 합니다: 개요; 예제 시나리오; 아키텍처; 주문 확인 시퀀스; 프로젝트 구조; 리소스 소유권; 라우트; PostgreSQL로 실행; 테스트; 동작 및 제한 사항; 참고 자료. 상단에는 상호 로케일 링크를 유지합니다.

PNG를 자연스러운 로케일별 대체 텍스트와 함께 삽입하고, 각 이미지 아래에 해당 canonical SVG를 링크하며, 선 스타일, 분기 프레임, 색상, 비원자적인 Caffeine→PostgreSQL 순서, repository-before-pool 종료를 설명하는 인접 텍스트 범례를 추가합니다.

각 `Routes` 섹션에는 승인된 전체 route/media-type 표를 포함하고, POST 확인이 bodyless임을 명시하며, 정확한 성공 JSON과 전체 오류 표를 포함합니다: `400 INVALID_ORDER_ID`, `403 DEMO_COMMAND_REQUIRED`, `404 ORDER_NOT_FOUND`, 그리고 네 가지 `503` 코드/메시지. `503` 응답에만 생성된 UUID `correlationId`가 포함되며, 이 값은 정제된 호출자 응답을 하나의 allowlisted stderr 진단 레코드에 연결하고, 재시도 또는 이벤트 재게시 토큰이 아님을 설명합니다.

- [ ] **2단계: 정확한 복사-붙여넣기 실행 및 복구 명령 추가**

저장소 루트의 Compose `up --wait`, 터미널 1의 Gradle 실행, 터미널 2의 `BASE_URL` 및 소문자 `uuidgen`, health/readiness/count 요청, 첫 번째 POST, GET, 반복 POST, 일반 `down`, 삭제 작업인 `down -v --remove-orphans`, 포트 충돌 검사, 집중/전체 테스트 명령을 설계 사양에 고정된 그대로 포함합니다.

예상 응답을 명시합니다: readiness components `jdbc`, `r2dbc`, `cache.orders`; 첫 번째 POST `eventPublished=true`; 반복 요청 `false`; GET은 동일한 ID/status/timestamp를 반환; R2DBC count가 증가합니다.

- [ ] **3단계: 모든 운영 및 의미상의 제한 사항을 명확하게 기술**

두 로케일 모두 다음을 명시적으로 포함해야 합니다:

```text
- loopback and X-Demo-Command are teaching guards, not production auth;
- the application installs no permissive CORS policy; the demo header is only a browser-origin teaching guard;
- Compose credentials demo/demo are local-only and must never be reused as deployment secrets;
- any external binding requires application-owned authentication, authorization, TLS, secret management, and network policy;
- startup DDL requires DDL permission and is not a migration system;
- Caffeine/PostgreSQL write-through is not atomic and has a transient dirty-read window;
- confirmation is idempotent only for sequential calls;
- cancellation can leave an ambiguous PostgreSQL commit;
- event handoff is request-local and non-durable;
- after any confirmation-command 503, GET reconciles stored state, but repeating POST does not recover an event; `ORDER_READ_FAILED` means that reconciliation endpoint is itself temporarily unavailable;
- ORDER_EVENT_HANDOFF_FAILED requires an outbox or other durable production boundary;
- the missing-order path may perform SELECT, UPDATE, INSERT;
- the two-connection pool is demonstration sizing only;
- the five-second acquire bound covers waiting for a pooled connection, not active SQL, DDL, or PostgreSQL lock time; production must configure statement/lock timeouts and migrations;
- only one demo resources lifecycle may own Exposed's process-wide default R2DBC database at a time; external code must not replace that default while the demo runs;
- the demo's stderr diagnostic sink is synchronous and may block under output backpressure; production should use bounded structured logging;
- Ktor owns internal engine-stop exception logging; this demo's status 2 covers application-resource cleanup failures, while production owns engine-level log policy and shutdown observability;
- the demo exposes no readiness drain and production owns traffic withdrawal.
```

서비스/퍼블리셔 소스와 집중 `OrderCommandServiceTest` 명령을 링크하여 독자가 비HTTP 이벤트 경계를 확인할 수 있도록 합니다.

- [ ] **4단계: 링크, 명령어 동등성 및 형식 검증**

이슈 리뷰 파일에 섹션 순서, 상호 링크, route/media 표, 모든 Compose/Gradle/curl 명령, 성공/오류 응답, 제한 사항, 다이어그램 PNG/SVG 자산, 서비스/퍼블리셔/테스트 소스 링크를 행으로 포함하는 동등성 매트릭스를 작성합니다. 그런 다음 실행합니다:

```bash
awk '/^```bash$/{copy=1; next} /^```$/{if(copy){copy=0; print "---"}; next} copy' \
  examples/ktor-exposed-demo/README.md > /tmp/issue-326-readme-en.commands
awk '/^```bash$/{copy=1; next} /^```$/{if(copy){copy=0; print "---"}; next} copy' \
  examples/ktor-exposed-demo/README.ko.md > /tmp/issue-326-readme-ko.commands
cmp /tmp/issue-326-readme-en.commands /tmp/issue-326-readme-ko.commands
for path in \
  docs/images/readme-diagrams/examples-ktor-exposed-demo-architecture-01.png \
  docs/images/readme-diagrams/examples-ktor-exposed-demo-architecture-01.svg \
  docs/images/readme-diagrams/examples-ktor-exposed-demo-sequence-01.png \
  docs/images/readme-diagrams/examples-ktor-exposed-demo-sequence-01.svg \
  examples/ktor-exposed-demo/src/main/kotlin/io/bluetape4k/examples/exposed/ktor/order/OrderCommandService.kt \
  examples/ktor-exposed-demo/src/test/kotlin/io/bluetape4k/examples/exposed/ktor/order/OrderCommandServiceTest.kt; do
  test -e "$path"
done
rg -n "fun interface OrderEventPublisher" \
  examples/ktor-exposed-demo/src/main/kotlin/io/bluetape4k/examples/exposed/ktor/order/OrderCommandService.kt
rg -n "examples-ktor-exposed-demo-(architecture|sequence)-01\.(png|svg)|DEMO_POSTGRES_|X-Demo-Command|postgresIntegrationTest|INVALID_ORDER_ID|DEMO_COMMAND_REQUIRED|ORDER_NOT_FOUND|ORDER_PERSISTENCE_FAILED|ORDER_EVENT_HANDOFF_FAILED|ORDER_CONFIRMATION_FAILED|ORDER_READ_FAILED|correlationId" \
  examples/ktor-exposed-demo/README.md examples/ktor-exposed-demo/README.ko.md
git diff --check
```

예상 결과: 명령어 펜스가 바이트 단위로 동일하게 비교되고, 완성된 매트릭스가 토큰 존재 여부를 넘어 의미상의 동등성을 입증하며, 링크된 모든 로컬 파일이 존재하고, 공백 오류가 없습니다.

- [ ] **5단계: 문서화된 워크스루 실행**

작업 전용 Compose 프로젝트와, 모든 성공·실패·인터럽트·종료 경로에서 백그라운드 Gradle 프로세스를 중지하고 일반 `down`을 실행하면서 명명된 볼륨은 유지하는 shell trap/finally를 사용합니다. 문서화된 제한 시간 내 대기로 Compose를 시작하고, 별도의 터미널/세션에서 Gradle 애플리케이션을 시작하며, 문서화된 모든 curl을 실행하고, 명시된 결과를 검증한 다음, trap이 Gradle과 Compose를 중지하도록 합니다.

그런 다음 `bt4k-issue-326-reset`이라는 별도의 일회용 프로젝트를 생성하고 PostgreSQL 서비스를 실행한 뒤, 문서화된 `docker compose -p bt4k-issue-326-reset -f examples/ktor-exposed-demo/compose.yaml down -v --remove-orphans`를 실행하고 `docker volume inspect bt4k-issue-326-reset_ktor-exposed-demo-postgres`가 실패하는지 확인합니다. 이 삭제 검증의 대상을 보존된 smoke/walkthrough 프로젝트나 사용자가 선택한 프로젝트 이름으로 지정하지 않습니다.

- [ ] **6단계: 독자 대상 예제 커밋**

```bash
git add examples/ktor-exposed-demo/README.md examples/ktor-exposed-demo/README.ko.md
git commit -m "Teach the PostgreSQL order scenario from request to shutdown" \
  -m "Constraint: English and Korean examples must remain semantically and technically equivalent." \
  -m "Rejected: API inventory prose | The user asked for a scenario, architecture, and sequence that are easy to follow." \
  -m "Confidence: high" -m "Scope-risk: narrow" \
  -m "Directive: Keep recovery guidance beside every non-durable event limitation." \
  -m "Tested: link/path/command parity, documented curl walkthrough, and diff check" \
  -m "Not-tested: external deployment beyond loopback"
```

### 작업 11: 최종 검증, 지속 가능한 교훈, PR 증거

**파일:**
- 생성: `docs/lessons/2026-07-17-issue-326-ktor-r2dbc-write-through-event-handoff.md`
- 생성: `docs/review/2026-07-17-issue-326-ktor-r2dbc-ddd-demo-review.md`
- 수정: `docs/superpowers/checklists/2026-07-17-issue-326-ktor-r2dbc-ddd-demo-checklist.md`

- [ ] **단계 0: 작업에 속한 시각적 임시 산출물만 제거**

issue #326 시각적 동반 서버를 중지한 다음, 이 worktree에서 작업에 속한 추적되지 않은 `.playwright-cli/` 파일과 `.superpowers/brainstorm/82929-1784217386/` 디렉터리를 제거한다. 추적된 파일이나 정확히 지정된 세션 경로 외부의 산출물은 제거하지 않는다. `repo-status`를 실행하고 시각적 임시 경로가 남아 있지 않은지 확인한다.

- [ ] **단계 1: 집중적이고 비례적인 저장소 검증 실행**

다음 명령을 순서대로 실행한다:

```bash
./gradlew :examples-ktor-exposed-demo:test --no-daemon --console=plain
./gradlew :examples-ktor-exposed-demo:postgresIntegrationTest \
  --no-parallel --no-daemon --console=plain
./gradlew :examples-ktor-exposed-demo:detekt --no-daemon --console=plain
git diff --check
```

예상 결과: 모든 명령이 PASS. 모듈에 `detekt` task가 없다면 정확한 Gradle task-not-found 출력을 기록하고, 다음으로 root `./gradlew detekt --no-daemon --console=plain`을 차선의 정적 증거로 실행한다.

- [ ] **단계 2: 지속 가능한 교훈 기록**

교훈에는 Context, Decision, Outcome, Proof, Misses, Future Guard가 포함되어야 한다. WRITE_THROUGH가 PostgreSQL보다 먼저 Caffeine을 업데이트하고, 서비스 보상은 무효화하지만 일시적인 reader window를 제거할 수 없으며, 비내구성 publication에는 `drainDomainEvents`가 아니라 snapshot/publish/clear를 사용해야 한다는 내용을 기록한다.

- [ ] **단계 3: 최종 다중 관점 리뷰를 실행하고 모든 P0/P1 수정**

실제 diff를 성능, 안정성/동시성, 보안/개인정보, Ops, 개발자/API, 사용자/caller/docs/diagrams 관점에서 독립적으로 검토한다. 결과를 리뷰 파일에 P0-P3 개수, 수정 사항, 정확한 명령, 최종 READY 상태와 함께 통합한다. 모든 관점과 main integration에서 P0=0/P1=0이 될 때까지 PR 생성을 중지한다.

- [ ] **단계 4: 교훈 및 리뷰 증거 커밋**

```bash
git add docs/lessons/2026-07-17-issue-326-ktor-r2dbc-write-through-event-handoff.md \
  docs/review/2026-07-17-issue-326-ktor-r2dbc-ddd-demo-review.md \
  docs/superpowers/checklists/2026-07-17-issue-326-ktor-r2dbc-ddd-demo-checklist.md \
  docs/superpowers/plans/2026-07-17-issue-326-ktor-r2dbc-ddd-demo-plan.md \
  docs/superpowers/specs/2026-07-17-issue-326-ktor-r2dbc-ddd-demo-design.md
git commit -m "Preserve the cache compensation and event handoff boundary" \
  -m "Constraint: Type A delivery requires durable learning and final review evidence before PR creation." \
  -m "Rejected: Treating readiness as cache-data equality | The contributor reports only in-memory worker consistency." \
  -m "Confidence: high" -m "Scope-risk: narrow" \
  -m "Directive: Future event reliability work belongs in a durable outbox design, not this demo publisher." \
  -m "Tested: final fast, PostgreSQL, static, diagram, documentation, and review gates" \
  -m "Not-tested: production authentication, migrations, and concurrent idempotency"
```

- [ ] **단계 5: 정확한 최종 head에서 PostgreSQL 증명 재실행**

모든 구현/docs/evidence 커밋 이후 깨끗한 worktree에서만 다음을 실행한다:

```bash
git status --short
git rev-parse HEAD
./gradlew :examples-ktor-exposed-demo:postgresIntegrationTest \
  --no-parallel --no-daemon --console=plain
```

예상 결과: clean status, 기록된 commit SHA, PASS. 이후 커밋이 하나라도 추가되면 이 증명이 무효화되므로 해당 단계를 반복해야 한다.

- [ ] **단계 6: 승인된 PR을 push하고 merge 없이 생성**

`feat/issue-326-ktor-r2dbc-ddd-demo`를 push하고, `develop`을 대상으로 PR을 생성한다. issue #326 메타데이터를 반영하고, Order Confirmation 시나리오와 PostgreSQL 대체를 설명하며, 정확한 head에 대한 로컬 PostgreSQL 증명을 포함하고, 본문을 최종 `## DoD Status`로 끝낸다. 해당 정확한 head에 대한 필수 CI/reviews/threads를 기다린다. 정확한 PR 번호, head SHA, checks, review state를 보고하고, merge 전에 사용자의 새로운 승인을 받는다.
