# Issue #326 Ktor R2DBC Cache and DDD Demo Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a runnable, bilingual Ktor Order Confirmation example backed by PostgreSQL R2DBC, an R2DBC Caffeine repository, and Spring-neutral domain-event handoff, with verified lifecycle behavior and readable architecture/sequence diagrams.

**Architecture:** POST commands enter a thin Ktor route and execute through `OrderCommandService`; GET demonstrates repository read-through directly. The command service owns aggregate rehydration, write-failure cache compensation, a post-persistence cancellation gate, and synchronous non-durable event handoff. `KtorExposedDemoResources` owns H2 JDBC plus PostgreSQL R2DBC/cache resources and restores Exposed's process-wide R2DBC default before disposing the pool.

**Tech Stack:** Kotlin 2.3 language level, Ktor 3, kotlinx.serialization, JetBrains Exposed R2DBC, PostgreSQL 16, r2dbc-pool, Caffeine, Kotlin coroutines, JUnit 5, MockK, Testcontainers 2, Docker Compose, SVG, CairoSVG.

---

## Locked File Structure

### Production and runtime

- Modify `examples/ktor-exposed-demo/build.gradle.kts` — serialization plugin, direct cache/PostgreSQL dependencies, isolated `postgresIntegrationTest` task.
- Create `examples/ktor-exposed-demo/compose.yaml` — loopback PostgreSQL 16 service, health check, named volume.
- Create `examples/ktor-exposed-demo/src/main/kotlin/io/bluetape4k/examples/exposed/ktor/order/OrderDomain.kt` — status, aggregate, event, serializable record.
- Create `examples/ktor-exposed-demo/src/main/kotlin/io/bluetape4k/examples/exposed/ktor/order/OrderRepository.kt` — UUID table and R2DBC Caffeine mapping.
- Create `examples/ktor-exposed-demo/src/main/kotlin/io/bluetape4k/examples/exposed/ktor/order/OrderCommandService.kt` — publisher port, typed failures, compensation, cancellation gate.
- Create `examples/ktor-exposed-demo/src/main/kotlin/io/bluetape4k/examples/exposed/ktor/order/OrderRoutes.kt` — serializers, validation precedence, response/error mapping, sanitized diagnostics.
- Modify `examples/ktor-exposed-demo/src/main/kotlin/io/bluetape4k/examples/exposed/ktor/KtorExposedDemoResources.kt` — config, PostgreSQL pool/schema, default-database lifecycle, close report.
- Modify `examples/ktor-exposed-demo/src/main/kotlin/io/bluetape4k/examples/exposed/ktor/KtorExposedDemoApplication.kt` — Ktor composition, readiness contributor, routes, testable server runner, exit statuses.

### Tests

- Create `examples/ktor-exposed-demo/src/test/kotlin/io/bluetape4k/examples/exposed/ktor/order/OrderDomainTest.kt`.
- Create `examples/ktor-exposed-demo/src/test/kotlin/io/bluetape4k/examples/exposed/ktor/order/OrderCommandServiceTest.kt`.
- Create `examples/ktor-exposed-demo/src/test/kotlin/io/bluetape4k/examples/exposed/ktor/order/OrderRoutesTest.kt`.
- Create `examples/ktor-exposed-demo/src/test/kotlin/io/bluetape4k/examples/exposed/ktor/KtorExposedDemoLifecycleTest.kt`.
- Replace `examples/ktor-exposed-demo/src/test/kotlin/io/bluetape4k/examples/exposed/ktor/KtorExposedDemoApplicationTest.kt` with Docker-free composition tests only.
- Create `examples/ktor-exposed-demo/src/postgresIntegrationTest/kotlin/io/bluetape4k/examples/exposed/ktor/KtorExposedDemoPostgresIntegrationTest.kt` — full PostgreSQL/cache/readiness/lifecycle proof.

### Documentation and durable evidence

- Replace `examples/ktor-exposed-demo/README.md` and `examples/ktor-exposed-demo/README.ko.md` in semantic parity.
- Create `docs/images/readme-diagrams/examples-ktor-exposed-demo-architecture-01.svg` and rendered `.png`.
- Create `docs/images/readme-diagrams/examples-ktor-exposed-demo-sequence-01.svg` and rendered `.png`.
- Create `docs/lessons/2026-07-17-issue-326-ktor-r2dbc-write-through-event-handoff.md`.
- Create `docs/review/2026-07-17-issue-326-ktor-r2dbc-ddd-demo-review.md`.
- Update the issue checklist and this plan as evidence is completed.

## Triggered Risk Predictions

| Risk | Earliest signal | Prevention/proof | Rerun point |
|---|---|---|---|
| Caffeine contains an unpersisted value after PostgreSQL failure | `put` throws after cache mutation | `NonCancellable` local invalidation, cause preservation, service + real PostgreSQL tests | after Tasks 3 and 7 |
| Cancellation publishes an event after an ambiguous write | cancelled job returns from `put` | `ensureActive()` immediately after `put`; same cancellation instance; no publish | after Task 3 |
| Closed demo DB remains Exposed's process-wide default | second lifecycle reads through old pool | capture, unregister, restore; run two real lifecycles sequentially | after Tasks 6 and 7 |
| Persisted order loses non-durable event handoff | publisher throws after successful write | typed `OrderEventHandoffException`, retained request-local buffer, README outbox warning | after Tasks 3, 5, and 10 |
| Docker test enters normal `test` or runs in parallel | fast task starts a container or CI contention appears | separate source set/task, no task dependency, `--no-parallel`, same-thread JUnit | after Tasks 1 and 7 |
| Startup/shutdown hides failure or leaks resources | exit 0, stale pool/thread, raw throwable log | runner statuses 1/2, aggregated close report, allowlisted diagnostics, lifecycle doubles | after Task 6 |
| Diagram is visually attractive but unreadable | clipped text, oversized markers, ambiguous branches | fixed markers/fonts, true lifelines/activation/numbered pills, all audits + full-size inspection | after Task 9 |

## Plan Review Record

The final 2026-07-17 plan review converged after every finding was repaired:

| Lens | P0 | P1 | P2 | P3 | Result |
|---|---:|---:|---:|---:|---|
| Performance | 0 | 0 | 0 | 0 | READY |
| Stability/concurrency | 0 | 0 | 0 | 0 | READY |
| Security/privacy | 0 | 0 | 0 | 0 | READY |
| Ops/operator | 0 | 0 | 0 | 0 | READY |
| Developer/API | 0 | 0 | 0 | 0 | READY |
| User/caller, bilingual docs, diagrams | 0 | 0 | 0 | 0 | READY |
| Main-session integration | 0 | 0 | 0 | 0 | READY |

The repair rounds closed dependency timing, Java-time availability, lifecycle
acquisition and concurrent close seams, Ktor 3.5 shutdown observability limits,
pre/post-write cancellation, process-wide R2DBC-default ownership, diagnostic
formatting, hostile-origin behavior, bilingual caller contracts, failure-safe
Compose cleanup, per-asset diagram validation, and Testcontainers execution
cost. Main integration rechecked those decisions against the current repository
APIs and Ktor 3.5.1 sources, confirmed every code fence is balanced, found no
placeholder or trailing-whitespace defect, and mapped every predicted risk to
an implementation task plus rerun point. No implementation gap remains in the
approved scope.

### Task 0: Freeze Reviewed Design and Plan Evidence

**Files:**
- Modify: `docs/superpowers/specs/2026-07-17-issue-326-ktor-r2dbc-ddd-demo-design.md`
- Modify: `docs/superpowers/plans/2026-07-17-issue-326-ktor-r2dbc-ddd-demo-plan.md`
- Modify: `docs/superpowers/checklists/2026-07-17-issue-326-ktor-r2dbc-ddd-demo-checklist.md`

- [ ] **Step 1: Record final plan-review convergence and risk traceability**

Append the six plan-lens counts plus main integration result to this plan, check A-04/A-05 only after every P0/P1 is repaired, and map each risk-table row above to the implementation task and rerun command already named in this document.

- [ ] **Step 2: Validate the durable artifacts before code changes**

Run:

```bash
rg -n "P0|P1|P2|P3|READY|Triggered Risk Predictions" \
  docs/superpowers/specs/2026-07-17-issue-326-ktor-r2dbc-ddd-demo-design.md \
  docs/superpowers/plans/2026-07-17-issue-326-ktor-r2dbc-ddd-demo-plan.md
git diff --check
```

Expected: design and plan both show final P0=0/P1=0; no placeholder or whitespace failure remains.

- [ ] **Step 3: Commit the approved decision artifacts before implementation**

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

### Task 1: Lock Build and Test-Task Boundaries

**Files:**
- Modify: `examples/ktor-exposed-demo/build.gradle.kts`

- [ ] **Step 1: Capture the fast-task baseline**

Run:

```bash
./gradlew :examples-ktor-exposed-demo:test --no-daemon --console=plain
```

Expected: PASS without starting Docker/Testcontainers.

- [ ] **Step 2: Replace the module build file with the explicit runtime and test-suite contract**

Use this complete shape, preserving the existing application main class:

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

Retain `runtimeOnly(bt4k.r2dbc.h2)` in this first build-only commit because the existing application test still constructs the current H2 R2DBC resources. Remove that driver only in Task 6, in the same commit that replaces those resources and tests with PostgreSQL-aware production wiring plus Docker-free doubles.

- [ ] **Step 3: Prove Gradle sees the isolated task**

Run:

```bash
./gradlew :examples-ktor-exposed-demo:tasks --all --no-daemon --console=plain | rg "postgresIntegrationTest"
./gradlew :examples-ktor-exposed-demo:test --no-daemon --console=plain
```

Expected: the task is listed exactly once; normal `test` still passes and does not execute `postgresIntegrationTest`.

- [ ] **Step 4: Commit the build boundary**

```bash
git add examples/ktor-exposed-demo/build.gradle.kts
git commit -m "Isolate PostgreSQL proof from the fast Ktor example tests" \
  -m "Constraint: Existing CI must keep invoking a Docker-free test task." \
  -m "Rejected: Reusing H2 R2DBC | The approved scenario requires real PostgreSQL behavior." \
  -m "Confidence: high" -m "Scope-risk: narrow" \
  -m "Tested: module task listing and fast test baseline" \
  -m "Not-tested: PostgreSQL integration sources do not exist yet"
```

### Task 2: Implement the Aggregate and Event Contract Test-First

**Files:**
- Create: `examples/ktor-exposed-demo/src/main/kotlin/io/bluetape4k/examples/exposed/ktor/order/OrderDomain.kt`
- Create: `examples/ktor-exposed-demo/src/test/kotlin/io/bluetape4k/examples/exposed/ktor/order/OrderDomainTest.kt`

- [ ] **Step 1: Write the failing aggregate tests**

Create `OrderDomainTest.kt` with three exact cases:

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

- [ ] **Step 2: Run RED**

Run:

```bash
./gradlew :examples-ktor-exposed-demo:test \
  --tests "*OrderDomainTest" --no-daemon --console=plain
```

Expected: FAIL because `DemoOrder`, `OrderStatus`, `OrderConfirmed`, and `OrderRecord` do not exist.

- [ ] **Step 3: Implement the complete domain file**

Create `OrderDomain.kt`:

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

- [ ] **Step 4: Run GREEN and serialization proof**

Run:

```bash
./gradlew :examples-ktor-exposed-demo:test \
  --tests "*OrderDomainTest" --no-daemon --console=plain
```

Expected: PASS, with exactly three tests.

- [ ] **Step 5: Commit the aggregate boundary**

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

### Task 3: Implement Command Ordering, Compensation, and Cancellation

**Files:**
- Create: `examples/ktor-exposed-demo/src/main/kotlin/io/bluetape4k/examples/exposed/ktor/order/OrderCommandService.kt`
- Create: `examples/ktor-exposed-demo/src/test/kotlin/io/bluetape4k/examples/exposed/ktor/order/OrderCommandServiceTest.kt`

- [ ] **Step 1: Write service tests before the service**

The test class must use a `mockk<R2dbcCaffeineRepository<UUID, OrderRecord>>()`, a fixed `Clock`, and recording/failing publishers. Use the fixed ID `018f6f95-7f4a-7a20-8b52-70ad30c30f36` and instant `2026-07-17T00:01:00Z` in every case. Implement this fixture and success test exactly, then repeat the same explicit arrangement for the failure matrix below:

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

Failure matrix:

| Test name | Arrangement | Exact assertions |
|---|---|---|
| `sequential confirmed record skips write and publish` | `get` returns `OrderRecord(CONFIRMED)` | result `eventPublished=false`; `put` exactly 0; publisher exactly 0 |
| `write failure invalidates skips publisher and preserves cause and events` | internal aggregate seam; `put` throws `primary`; `invalidate` succeeds | `OrderPersistenceException.cause === primary`; invalidate once; publisher 0; aggregate retains `OrderConfirmed` |
| `invalidation failure is suppressed on original persistence cause` | `put` throws `primary`; `invalidate` throws `cleanup` | wrapper cause is `primary`; `primary.suppressed` equals `[cleanup]` |
| `publisher failure leaves persisted record and request local events` | `put` succeeds; publisher throws `primary` | `OrderEventHandoffException.cause === primary`; no invalidate; aggregate retains event |
| `repository cancellation invalidates noncancellably and rethrows the same instance` | `put` throws a named `CancellationException`; invalidate succeeds | same cancellation instance escapes; invalidate once under `NonCancellable`; publisher 0 |
| `repository cancellation keeps cleanup failure suppressed on the same instance` | `put` throws named cancellation; invalidate throws cleanup | same cancellation instance escapes; its suppressed list is `[cleanup]`; publisher 0 |
| `pre-write cancellation never calls put or publisher` | cancel child before the service's pre-write gate | `ensureActive()` throws the same cancellation; `put` 0; publisher 0 |
| `cancellation observed immediately after put invalidates and never publishes` | repository answer cancels the child job immediately before returning | post-return `ensureActive()` throws same cancellation; invalidate once under `NonCancellable`; publisher 0 |
| `simultaneous confirmation can publish twice by design` | a two-party barrier forces both reads of `PENDING` to complete before either write | assert exactly two `put` calls and two publications; the deterministic characterization documents unsupported concurrent idempotency |

- [ ] **Step 2: Run RED**

Run:

```bash
./gradlew :examples-ktor-exposed-demo:test \
  --tests "*OrderCommandServiceTest" --no-daemon --console=plain
```

Expected: FAIL because the service, publisher, result, and typed failures do not exist.

- [ ] **Step 3: Implement the service API and failure taxonomy**

Create `OrderCommandService.kt` with this complete behavior:

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

The public ID method owns repository lookup/rehydration, while the internal aggregate overload is a test seam used to prove event retention and clearing without widening the HTTP API. Run `currentCoroutineContext().ensureActive()` immediately before `repository.put`, again immediately after it returns, and route every `CancellationException` from `put` or either gate through the same `NonCancellable` invalidation helper before rethrowing the original cancellation object. Never catch `Error`.

- [ ] **Step 4: Run GREEN and the whole fast suite**

Run:

```bash
./gradlew :examples-ktor-exposed-demo:test \
  --tests "*OrderCommandServiceTest" --no-daemon --console=plain
./gradlew :examples-ktor-exposed-demo:test --no-daemon --console=plain
```

Expected: all service cases and the full Docker-free suite PASS.

- [ ] **Step 5: Commit the application boundary**

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

### Task 4: Add the UUID PostgreSQL/Caffeine Repository

**Files:**
- Create: `examples/ktor-exposed-demo/src/main/kotlin/io/bluetape4k/examples/exposed/ktor/order/OrderRepository.kt`
- Modify: `examples/ktor-exposed-demo/src/test/kotlin/io/bluetape4k/examples/exposed/ktor/order/OrderDomainTest.kt`

- [ ] **Step 1: Add a failing metadata/serialization test**

Extend `OrderDomainTest` with:

```kotlin
@Test
fun `record serialization id is stable and table uses client UUID`() {
    java.io.ObjectStreamClass.lookup(OrderRecord::class.java).serialVersionUID shouldBeEqualTo 1L
    val idColumn: org.jetbrains.exposed.v1.core.Column<org.jetbrains.exposed.v1.core.dao.id.EntityID<UUID>> = DemoOrders.id
    idColumn.name shouldBeEqualTo "id"
    DemoOrders.tableName shouldBeEqualTo "ktor_demo_orders"
}
```

- [ ] **Step 2: Run RED**

Run:

```bash
./gradlew :examples-ktor-exposed-demo:test \
  --tests "*OrderDomainTest" --no-daemon --console=plain
```

Expected: FAIL because `DemoOrders` does not exist.

- [ ] **Step 3: Implement the table and concrete repository**

Create `OrderRepository.kt`:

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

- [ ] **Step 4: Run GREEN and compile main**

Run:

```bash
./gradlew :examples-ktor-exposed-demo:test \
  --tests "*OrderDomainTest" --no-daemon --console=plain
./gradlew :examples-ktor-exposed-demo:compileKotlin --no-daemon --console=plain
```

Expected: PASS; no H2 R2DBC type is referenced by main sources.

- [ ] **Step 5: Commit the repository mapping**

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

### Task 5: Add Deterministic Order HTTP Contracts

**Files:**
- Create: `examples/ktor-exposed-demo/src/main/kotlin/io/bluetape4k/examples/exposed/ktor/order/OrderRoutes.kt`
- Create: `examples/ktor-exposed-demo/src/test/kotlin/io/bluetape4k/examples/exposed/ktor/order/OrderRoutesTest.kt`

- [ ] **Step 1: Write route tests with mocked service/repository**

Create `OrderRoutesTest.kt` using `testApplication`, `installBluetape4kKtorCore()`, a mocked `OrderCommandService`, a mocked `R2dbcCaffeineRepository<UUID, OrderRecord>`, and a recording `DemoDiagnosticSink`. The exact matrix is:

| Test name | Request/setup | Exact assertions |
|---|---|---|
| `missing or wrong header wins over invalid id with 403` | two POST requests to `/orders/not-a-uuid/confirm`, one without the header and one with a wrong value | both `403`, code `DEMO_COMMAND_REQUIRED`, service/repository/publisher calls 0 |
| `hostile origin preflight receives no permissive CORS grant` | OPTIONS preflight with hostile `Origin`, requested POST method, and requested `X-Demo-Command` header | no `Access-Control-Allow-Origin`/credentials grant, no successful mutation response, service/repository/publisher calls 0 |
| `valid header and invalid uppercase nil or oversized id return constant 400` | four POST requests with the valid header | every response `400 INVALID_ORDER_ID`; service calls 0; no input echoed |
| `confirmation returns serialized eventPublished result` | service returns confirmed result | `200 application/json`; exact four fields; ISO instant |
| `sequential confirmation returns eventPublished false` | service returns `eventPublished=false` | `200`; false encoded, not omitted |
| `get returns 404 for missing order and 200 for stored order` | repository returns null then a record | exact `404 ORDER_NOT_FOUND`, then exact three-field success |
| `typed command failures map to distinct sanitized 503 responses` | service throws each typed failure | exact code/message, UUID correlation ID, operation `confirm` |
| `get repository failure maps to ORDER_READ_FAILED and operation read` | repository throws ordinary exception | exact `503 ORDER_READ_FAILED`; diagnostic operation `read` |
| `secret bearing primary and suppressed messages never enter body or diagnostic` | failures contain URL/user/password/SQL and suppressed secret | response/diagnostic serialization contains none of those strings |

- [ ] **Step 2: Run RED**

Run:

```bash
./gradlew :examples-ktor-exposed-demo:test \
  --tests "*OrderRoutesTest" --no-daemon --console=plain
```

Expected: FAIL because route DTOs, validation, and installer do not exist.

- [ ] **Step 3: Implement DTOs, diagnostics, validation, and routes**

Create `OrderRoutes.kt` with these exact public contracts:

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

Implement `fun Route.orderRoutes(service, repository, diagnostics)` with this precedence and mapping:

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

Add the GET route and helpers exactly as follows; imports are the corresponding Ktor request/response/routing types, `CancellationException`, and `UUID`:

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

- [ ] **Step 4: Run GREEN and serializer compilation**

Run:

```bash
./gradlew :examples-ktor-exposed-demo:test \
  --tests "*OrderRoutesTest" --no-daemon --console=plain
./gradlew :examples-ktor-exposed-demo:compileKotlin --no-daemon --console=plain
```

Expected: all route cases PASS and generated serializers compile.

- [ ] **Step 5: Commit the HTTP boundary**

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

### Task 6: Replace H2 R2DBC with Owned PostgreSQL Resources and a Safe Runner

**Files:**
- Modify: `examples/ktor-exposed-demo/src/main/kotlin/io/bluetape4k/examples/exposed/ktor/KtorExposedDemoResources.kt`
- Modify: `examples/ktor-exposed-demo/src/main/kotlin/io/bluetape4k/examples/exposed/ktor/KtorExposedDemoApplication.kt`
- Modify: `examples/ktor-exposed-demo/build.gradle.kts`
- Create: `examples/ktor-exposed-demo/src/test/kotlin/io/bluetape4k/examples/exposed/ktor/KtorExposedDemoLifecycleTest.kt`
- Replace: `examples/ktor-exposed-demo/src/test/kotlin/io/bluetape4k/examples/exposed/ktor/KtorExposedDemoApplicationTest.kt`

- [ ] **Step 1: Write lifecycle and composition tests first**

`KtorExposedDemoLifecycleTest` must define named fake close actions and fake `DemoServer` instances. Use this assertion matrix:

| Test name | Exact proof |
|---|---|
| `acquisition failure closes completed resources in reverse order and keeps primary cause` | acquired `lease,jdbc,dispatcher,pool`; throw `primary`; close order `pool,dispatcher,jdbc,lease`; thrown object is `primary`; cleanup failures are suppressed; immediate next lifecycle acquisition succeeds |
| `engine creation or start failure closes resources and returns exit one` | server factory/start throws; resources close once; one `DEMO_STARTUP_FAILED`; status 1; no throwable text |
| `schema initialization failure unregisters restores prior default closes pool and releases lease` | injected schema step throws `primary`; exact order `closeAndUnregister,restore-default,pool,...,lease`; result retains `primary`; cleanup failures are suppressed; immediate next lifecycle acquisition succeeds |
| `engine create bind and start failures retain their original cause separately` | inject one named failure at each boundary | each `DemoRunResult.primaryFailure` is the exact injected object; status 1; resources close once; sanitized stderr contains none of its text |
| `actual loopback server uses configured shutdown and cleans after ApplicationStopped` | create the production `EmbeddedServer` on an ephemeral loopback port; assert engine config `1000/5000`; stop it from a helper thread; prove `ApplicationStopped`/`closeReport` completes before blocking `start(wait=true)` returns; status 0 |
| `resource cleanup failures aggregate once continue cleanup and return two` | repository/pool failures; later closers still run; one `DEMO_SHUTDOWN_FAILED`; status 2 |
| `repeated close returns the original report without closing twice` | invoke close twice; each action count 1; report object/value unchanged |
| `concurrent close returns one report and runs every closer once` | two threads enter through a barrier while the first closer is held by a latch; both complete within a bounded timeout; each closer count is 1; both receive the same report instance |
| `overlapping demo lifecycle is rejected and sequential reuse succeeds` | hold the first resource lease; second acquisition fails before changing the default; close first; third acquisition succeeds; prior default is preserved |
| `external non-null default is never overwritten during close` | install a different non-null default immediately before demo close | `closeAndUnregister(demo)` leaves that external default current; conditional restore skips the captured prior value; external DB is neither closed nor unregistered |
| `stderr diagnostic sink renders only the allowlisted key-value record` | capture a supplied `PrintStream`; assert one line, stable field order, UUID correlation ID where required, omitted nulls, and absence of URL/user/password/SQL/throwable text for runtime, startup, and shutdown records |

Replace `KtorExposedDemoApplicationTest` with a Docker-free composition test that installs Ktor core and `orderRoutes` with mocked service/repository dependencies, verifies route registration, and never constructs or mocks JDBC/R2DBC databases.

- [ ] **Step 2: Run RED**

Run:

```bash
./gradlew :examples-ktor-exposed-demo:test \
  --tests "*KtorExposedDemoLifecycleTest" \
  --tests "*KtorExposedDemoApplicationTest" \
  --no-daemon --console=plain
```

Expected: FAIL because config, cleanup report, server abstraction, and runner do not exist.

- [ ] **Step 3: Implement configuration and failure-atomic resource ownership**

In `KtorExposedDemoResources.kt`, define:

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

Keep `DemoItems` and H2 JDBC initialization. Replace H2 R2DBC creation with `ConnectionFactoryOptions.parse(config.r2dbcUrl).mutate()` plus typed `USER` and `PASSWORD` options, a `ConnectionPool` using `initialSize(1)`, `maxSize(2)`, and `maxAcquireTime(Duration.ofSeconds(5))`, and `R2dbcDatabase.connect`. Capture the R2DBC `TransactionManager.defaultDatabase`, set the demo DB as default, then run:

```kotlin
runBlocking {
    suspendTransaction(r2dbcDatabase) { org.jetbrains.exposed.v1.r2dbc.SchemaUtils.create(DemoOrders) }
}
```

Create `OrderR2dbcCaffeineRepository`, `InMemoryOrderEventPublisher`, and `OrderCommandService(orderRepository, eventPublisher, Clock.systemUTC())`; expose those same instances as consistently named resource properties.

Put production acquisition behind an internal `DemoResourceAcquirer` whose injected `DemoResourceSteps` cover a process-local atomic lifecycle lease, JDBC, dispatcher, pool, R2DBC database, default-database registration, schema initialization, repository, and publisher/service construction. Every acquired step supplies a named close action that is pushed onto one reverse-order stack. Production uses real factories; tests inject named doubles. Reject a second active demo lifecycle before changing the Exposed default; release the lease on normal close and every construction-failure path so sequential reuse remains valid. On any acquisition or schema failure, unwind completed steps, ensure the R2DBC path performs `closeAndUnregister(demo)` → restore the captured previous default only when the current default is null → dispose pool, add every cleanup failure as suppressed to the original failure, and rethrow that exact original object. Never overwrite a different non-null default installed by external code. This seam is internal and does not change the example's user-facing API.

Implement idempotent `closeReport()` under a private close lock with a volatile stored report so concurrent `ApplicationStopped` and runner fallback calls execute cleanup once. The exact semantic order is: repository close; R2DBC `TransactionManager.closeAndUnregister(demo)`; restore the captured previous default only if the current default is null; pool disposal with five-second bound; Hikari close; dispatcher close; release the demo lifecycle lease last. Continue after each `Exception`, retain failures without logging messages, and return the same stored report on repeated calls. Lease release must still run if any earlier closer fails. `close()` delegates to `closeReport()`.

- [ ] **Step 4: Implement application composition and runner statuses**

In `KtorExposedDemoApplication.kt`:

- call the two-argument `installBluetape4kExposedKtor(config, cacheReadiness)` overload, passing `ExposedKtorCacheReadinessConfig(listOf(ExposedKtorCacheContributor.r2dbcRepository("orders") { resources.orderRepository.validateConsistency() }))`;
- keep `/transactions/jdbc-count` and add `/transactions/r2dbc-count` through `call.exposedR2dbcTransaction(resources.r2dbcDatabase) { DemoOrders.selectAll().count() }`;
- install POST/GET order routes;
- bind production Netty to `127.0.0.1:8080`;
- configure the production Netty engine with `shutdownGracePeriod = 1_000` and `shutdownTimeout = 5_000`; Ktor's `EmbeddedServer.start(wait=true)` installs the actual JVM shutdown hook and its no-argument `stop()` consumes those configured values;
- retain `ApplicationStopped` calling idempotent `closeReport()`.

Define a testable server port:

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

The runner returns `DemoRunResult(status=1, primaryFailure=<exact original>, cleanupReport=...)` for resource/server create/bind/start failure and emits one `DEMO_STARTUP_FAILED` with `phase=startup`; only that failed-start fallback explicitly calls `stop(1_000, 5_000)` on an already-created server before closing acquired resources. When `DemoResourcesFactory.create()` fails before returning a resource object, the acquirer has already attached cleanup failures to the original throwable; the runner uses `DemoCleanupReport(primary.suppressed.toList())` as the explicit fallback report. On the successful path it calls `start(wait=true)`: Ktor's installed shutdown hook initiates the configured `1_000/5_000` stop, synchronously raises `ApplicationStopped`, and unblocks `start`. The runner then reads the idempotent `closeReport()`; it must not issue a second successful-path stop. It returns status `0` when cleanup is clean or status `2` after one aggregated application-resource `DEMO_SHUTDOWN_FAILED` with `phase=shutdown`. Direct tests assert the retained cause and suppressed cleanup chain; diagnostics still receive no throwable or message. Ktor 3.5 internally catches and framework-logs engine-stop exceptions, so the demo does not falsely claim that its runner can observe or reclassify that framework-owned failure; the production loopback test instead proves the real engine configuration and lifecycle ordering.

Implement `StderrDemoDiagnosticSink(output: PrintStream = System.err)` with a deterministic manual formatter that emits one space-delimited key-value line in this fixed order, omitting null fields: `code`, `correlationId`, `component`, `operation`, `phase`, `outcome`. The formatter accepts only the allowlisted DTO fields; it does not serialize arbitrary maps or throwable values. The sink accepts no throwable parameter and never receives exception messages, URLs, credentials, or SQL. Capture a supplied `PrintStream` in tests and prove the exact confirm/read/startup/shutdown records, correlation-ID shape, one-line framing, omitted nulls, and secret/throwable exclusion. `main` passes `StderrDemoDiagnosticSink()` to the production runner and calls `exitProcess(result.status)`; it never exposes `primaryFailure`.

At the end of Task 6 remove `runtimeOnly(bt4k.r2dbc.h2)` from `build.gradle.kts`, then prove the replaced Docker-free tests no longer construct H2 R2DBC resources.

- [ ] **Step 5: Run GREEN and prove fast tests remain Docker-free**

Run:

```bash
./gradlew :examples-ktor-exposed-demo:test --no-daemon --console=plain
./gradlew :examples-ktor-exposed-demo:compileKotlin --no-daemon --console=plain
```

Expected: PASS; no Testcontainers log appears; lifecycle order/status assertions pass.

- [ ] **Step 6: Commit resource ownership**

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

### Task 7: Prove the Full Scenario Against PostgreSQL Sequentially

**Files:**
- Create: `examples/ktor-exposed-demo/src/postgresIntegrationTest/kotlin/io/bluetape4k/examples/exposed/ktor/KtorExposedDemoPostgresIntegrationTest.kt`

- [ ] **Step 1: Write the full integration test before running the Docker task**

Create one `@Execution(ExecutionMode.SAME_THREAD)` test class using `PostgreSQLContainer("postgres:16-alpine")`. Reuse one suite-level container sequentially for the normal scenario, readiness, and default-restoration cases; each of those tests creates and closes fresh application resources. The outage case owns a second independent container that it may stop. Use `try/finally` so every resource closes before its owning container, including suite teardown. Build `KtorExposedDemoConfig` from `container.host`, `container.getMappedPort(5432)`, `container.databaseName`, `container.username`, and `container.password`.

Implement four cases named `order confirmation persists publishes reads through cache and stays sequentially idempotent`, `readiness exposes jdbc r2dbc and cache orders while health remains probe free`, `stopped PostgreSQL keeps liveness up and returns bounded sanitized readiness down`, and `closing restores previous default and a second lifecycle does not reuse the closed pool`.

The scenario case must:

1. assert JDBC count `2` and initial R2DBC count `0`;
2. POST a client-generated lowercase UUID with the required header and assert `eventPublished=true`;
3. read the row directly from PostgreSQL with explicit
   `suspendTransaction(resources.r2dbcDatabase)`, never the implicit default;
4. invalidate the repository key, assert the cache entry is absent, GET over HTTP, then assert the cache contains the returned record;
5. call repository GET again and assert referential identity with the cached record;
6. repeat POST and assert `eventPublished=false`, one DB row, and one runtime event snapshot.

The outage case must stop the container after application startup, assert `/healthz/exposed` is `200`, and use a ten-second client/test timeout to assert `/readyz/exposed` is sanitized `503` with no URL, user, password, SQL, or exception text.

- [ ] **Step 2: Run the first real PostgreSQL proof**

Run:

```bash
./gradlew :examples-ktor-exposed-demo:postgresIntegrationTest \
  --no-parallel --no-daemon --console=plain
```

Expected: PASS when the implementation is already correct. If the first real run exposes a mapping, lifecycle, or timeout defect, preserve the exact failure as evidence and make the smallest repair. Docker unavailability is a hard environmental failure, never a skip or H2 fallback; do not manufacture a RED result.

- [ ] **Step 3: Make only the smallest integration corrections**

Correct mapping, route composition, lifecycle, or timeouts only where the real PostgreSQL failure proves the current implementation wrong. Preserve these invariants while editing:

```text
ensureActive -> repository.put -> ensureActive -> publisher.publish -> clearDomainEvents
repository.close -> closeAndUnregister -> restore previous default -> pool.dispose
ordinary test !-> postgresIntegrationTest
```

- [ ] **Step 4: Run GREEN and rerun fast tests**

Run:

```bash
./gradlew :examples-ktor-exposed-demo:postgresIntegrationTest \
  --no-parallel --no-daemon --console=plain
./gradlew :examples-ktor-exposed-demo:test --no-daemon --console=plain
```

Expected: the PostgreSQL task PASS; its dedicated test performs two resource lifecycles sequentially to prove no closed default/pool contamination without a duplicate whole-suite container run; fast tests remain PASS.

- [ ] **Step 5: Commit real-database proof**

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

### Task 8: Add the Loopback Docker Compose Runtime

**Files:**
- Create: `examples/ktor-exposed-demo/compose.yaml`

- [ ] **Step 1: Create the exact local PostgreSQL contract**

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

- [ ] **Step 2: Validate and smoke the Compose lifecycle**

Run from repository root:

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

Expected: config validation succeeds; PostgreSQL becomes healthy within 60 seconds; `down` retains the named volume.

- [ ] **Step 3: Commit local runtime ownership**

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

### Task 9: Create and Audit Architecture and Sequence Diagrams

**Files:**
- Create: `docs/images/readme-diagrams/examples-ktor-exposed-demo-architecture-01.svg`
- Create: `docs/images/readme-diagrams/examples-ktor-exposed-demo-architecture-01.png`
- Create: `docs/images/readme-diagrams/examples-ktor-exposed-demo-sequence-01.svg`
- Create: `docs/images/readme-diagrams/examples-ktor-exposed-demo-sequence-01.png`

- [ ] **Step 1: Complete the architecture asset loop**

Use `docs/images/readme-diagrams/exposed-r2dbc-caffeine-diagram-01.svg` as the style reference. The new SVG must show the application ownership boundary; HTTP → routes → command service; aggregate; concrete repository; Caffeine before PostgreSQL; Spring-free publisher; readiness contributor; and repository-before-unregister/pool shutdown. Set all marker sizes with `markerUnits="userSpaceOnUse"`, use the approved font stack, and keep every label at readable full-size PNG scale.

Before touching the sequence asset, close this exact loop for architecture: edit SVG → `xmllint` → render PNG at scale 2 → connector/geometry/endpoint/mixed-corner audits → inspect PNG at original resolution → record connector/card/path/label counts, PNG dimensions, and visual notes. Run geometry audit with `--fail-diagonal`. If an audit reports `WEAK`, zero connectors/cards/paths/labels, or cannot classify the SVG, add and run a targeted invariant check instead of treating that result as PASS.

- [ ] **Step 2: Complete the true sequence asset loop**

Use `docs/images/readme-diagrams/exposed-r2dbc-caffeine-sequence-01.svg` as the style reference. Include labeled lifelines, activation bars, visible numbered pills, and transparent `alt` frames for cache hit/miss, persistence success/failure+invalidate, and publisher success/retain. Make the non-atomic Caffeine→PostgreSQL order explicit; no connector may cross a label or frame title.

Only after Step 1 evidence is complete, close the same loop for sequence: edit SVG → `xmllint` → render PNG at scale 2 → connector/geometry/endpoint/mixed-corner/sequence-style audits → inspect PNG at original resolution → record connector/card/path/label counts, PNG dimensions, and visual notes. Run geometry audit with `--fail-diagonal`. Apply the same `WEAK`/zero-count fallback rule.

- [ ] **Step 3: Run the exact per-asset commands and preserve evidence**

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

Expected: each asset is closed before the next begins; both PNGs are non-empty; XML and every audit PASS with zero connector/geometry/endpoint/style violations or an explicit targeted fallback invariant; the review evidence records all counts, dimensions, and original-resolution visual notes.

- [ ] **Step 4: Reinspect both final PNGs after the last SVG change**

Open both PNGs with the image viewer at original resolution. Expected: all text is readable; arrowheads are proportionate; no clipping/overlap; sequence branches and numbers remain visible without color dependence. If either image changes, rerender and rerun all audits before inspecting again.

- [ ] **Step 5: Commit the visual explanation**

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

### Task 10: Write the Bilingual User Walkthrough and Limitations

**Files:**
- Replace: `examples/ktor-exposed-demo/README.md`
- Replace: `examples/ktor-exposed-demo/README.ko.md`

- [ ] **Step 1: Write English and Korean READMEs in the locked section order**

Both files must contain, in order: Overview; Example Scenario; Architecture; Order Confirmation Sequence; Project Structure; Resource Ownership; Routes; Run with PostgreSQL; Testing; Behavior and Limitations; See Also. Keep mutual locale links at the top.

Embed the PNGs with natural locale-specific alt text, link each canonical SVG below its image, and add adjacent textual legends explaining line styles, branch frames, colors, non-atomic Caffeine→PostgreSQL order, and repository-before-pool shutdown.

In each `Routes` section, include the full approved route/media-type table, explicitly label POST confirmation as bodyless, and include the exact success JSON plus the complete error table: `400 INVALID_ORDER_ID`, `403 DEMO_COMMAND_REQUIRED`, `404 ORDER_NOT_FOUND`, and all four `503` codes/messages. Explain that only `503` responses carry a generated UUID `correlationId`, that it links the sanitized caller response to one allowlisted stderr diagnostic record, and that it is not a retry or event-republication token.

- [ ] **Step 2: Add the exact copy-paste run and recovery commands**

Include the repository-root Compose `up --wait`, terminal-one Gradle run, terminal-two `BASE_URL` and lowercase `uuidgen`, health/readiness/count requests, first POST, GET, repeated POST, normal `down`, destructive `down -v --remove-orphans`, port-conflict inspection, and focused/full test commands exactly as locked in the design spec.

State expected responses: readiness components `jdbc`, `r2dbc`, `cache.orders`; first POST `eventPublished=true`; repeat `false`; GET same ID/status/timestamp; R2DBC count increases.

- [ ] **Step 3: State all operational and semantic limitations plainly**

Both locales must explicitly say:

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

Link the service/publisher source and focused `OrderCommandServiceTest` command so readers can verify the non-HTTP event boundary.

- [ ] **Step 4: Validate links, command parity, and formatting**

Create a parity matrix in the issue review file with rows for section order, mutual links, route/media table, every Compose/Gradle/curl command, success/error responses, limitations, diagram PNG/SVG assets, and service/publisher/test source links. Then run:

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

Expected: command fences compare byte-for-byte; the completed matrix proves semantic parity beyond token presence; every linked local file exists; no whitespace error.

- [ ] **Step 5: Execute the documented walkthrough**

Use a task-owned Compose project and a shell trap/finally that stops the background Gradle process and runs normal `down` on every success, failure, interrupt, or termination path while retaining the named volume. Start Compose with the documented bounded wait, start the Gradle application in a separate terminal/session, run every documented curl, verify the stated results, and allow the trap to stop Gradle and Compose.

Then create a separate disposable project named `bt4k-issue-326-reset`, run its PostgreSQL service, execute the documented `docker compose -p bt4k-issue-326-reset -f examples/ktor-exposed-demo/compose.yaml down -v --remove-orphans`, and assert `docker volume inspect bt4k-issue-326-reset_ktor-exposed-demo-postgres` fails. Never point this destructive verification at the retained smoke/walkthrough project or a user-selected project name.

- [ ] **Step 6: Commit the reader-facing example**

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

### Task 11: Final Verification, Durable Lesson, and PR Evidence

**Files:**
- Create: `docs/lessons/2026-07-17-issue-326-ktor-r2dbc-write-through-event-handoff.md`
- Create: `docs/review/2026-07-17-issue-326-ktor-r2dbc-ddd-demo-review.md`
- Modify: `docs/superpowers/checklists/2026-07-17-issue-326-ktor-r2dbc-ddd-demo-checklist.md`

- [ ] **Step 0: Remove only task-owned visual scratch artifacts**

Stop the issue #326 visual-companion server, then remove the task-owned untracked `.playwright-cli/` files and `.superpowers/brainstorm/82929-1784217386/` directory from this worktree. Do not remove any tracked file or any artifact outside that exact session path. Run `repo-status` and verify no visual scratch path remains.

- [ ] **Step 1: Run focused and proportional repository verification**

Run sequentially:

```bash
./gradlew :examples-ktor-exposed-demo:test --no-daemon --console=plain
./gradlew :examples-ktor-exposed-demo:postgresIntegrationTest \
  --no-parallel --no-daemon --console=plain
./gradlew :examples-ktor-exposed-demo:detekt --no-daemon --console=plain
git diff --check
```

Expected: every command PASS. If the module has no `detekt` task, record the exact Gradle task-not-found output and run root `./gradlew detekt --no-daemon --console=plain` as the next-best static proof.

- [ ] **Step 2: Record the durable lesson**

The lesson must contain Context, Decision, Outcome, Proof, Misses, and Future Guard. Record that WRITE_THROUGH updates Caffeine before PostgreSQL, service compensation invalidates but cannot remove the transient reader window, and non-durable publication must use snapshot/publish/clear rather than `drainDomainEvents`.

- [ ] **Step 3: Run the final multi-lens review and repair every P0/P1**

Review the actual diff independently for performance, stability/concurrency, security/privacy, Ops, developer/API, and user/caller/docs/diagrams. Integrate the results in the review file with P0-P3 counts, repairs, exact commands, and final READY state. Stop PR creation until every lens and main integration are P0=0/P1=0.

- [ ] **Step 4: Commit lesson and review evidence**

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

- [ ] **Step 5: Rerun PostgreSQL proof on the exact final head**

Run only from a clean worktree after all implementation/docs/evidence commits:

```bash
git status --short
git rev-parse HEAD
./gradlew :examples-ktor-exposed-demo:postgresIntegrationTest \
  --no-parallel --no-daemon --console=plain
```

Expected: clean status, recorded commit SHA, PASS. Any later commit invalidates this proof and requires repeating the step.

- [ ] **Step 6: Push and open the authorized PR without merging**

Push `feat/issue-326-ktor-r2dbc-ddd-demo`, open a PR targeting `develop`, mirror issue #326 metadata, describe the Order Confirmation scenario and PostgreSQL replacement, include the exact-head local PostgreSQL proof, and end the body with final `## DoD Status`. Wait for required CI/reviews/threads on that exact head. Report the exact PR number, head SHA, checks, and review state; obtain fresh user approval before any merge.
