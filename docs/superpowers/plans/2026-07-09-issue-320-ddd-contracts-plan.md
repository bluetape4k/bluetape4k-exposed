# Issue 320 DDD Contracts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Spring-neutral DDD aggregate/domain-event contracts to `bluetape4k-exposed-core` with tests, KDoc, and README locale documentation.

**Architecture:** Add a small `io.bluetape4k.exposed.core.ddd` package in the existing core module. The package contains only framework-neutral contracts and an in-memory event buffer base class. Publishing, durable outbox, repository adapters, Spring Modulith integration, JaVers integration, and Exposed DAO lifecycle hooks stay out of scope.

**Tech Stack:** Kotlin catalog version in this worktree, JDK `Instant`, `bluetape4k-assertions`, JUnit 5, Gradle module `:bluetape4k-exposed-core`.

---

## File Structure

- Create: `exposed/core/src/test/kotlin/io/bluetape4k/exposed/core/ddd/AbstractAggregateRootTest.kt`
  - TDD tests for event recording, snapshot, drain, ordering, mismatched IDs, and typed ID fixtures.
- Create: `exposed/core/src/main/kotlin/io/bluetape4k/exposed/core/ddd/DomainEvent.kt`
  - Spring-neutral domain event contract.
- Create: `exposed/core/src/main/kotlin/io/bluetape4k/exposed/core/ddd/AggregateRoot.kt`
  - Aggregate root contract.
- Create: `exposed/core/src/main/kotlin/io/bluetape4k/exposed/core/ddd/AbstractAggregateRoot.kt`
  - Minimal base implementation with lazy event buffer.
- Modify: `README.md`
  - English DDD contracts section.
- Modify: `README.ko.md`
  - Korean equivalent section.

## Task 1: Write RED Tests For DDD Contracts

complexity: medium  
applies: `$bluetape4k-code-patterns`, `test-driven-development`

**Files:**
- Create: `exposed/core/src/test/kotlin/io/bluetape4k/exposed/core/ddd/AbstractAggregateRootTest.kt`

- [ ] **Step 1: Create the failing test file**

```kotlin
package io.bluetape4k.exposed.core.ddd

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.assertFailsWith
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.time.Instant

class AbstractAggregateRootTest {

    @Test
    fun `domainEvents returns empty immutable snapshot before recording`() {
        val order = TestOrder(OrderId(1L))

        val events = order.domainEvents()

        events.shouldBeSameInstanceAs(emptyList<DomainEvent<OrderId>>())
    }

    @Test
    fun `recordDomainEvent stores event without clearing it`() {
        val order = TestOrder(OrderId(1L))
        val event = OrderPlaced(order.id)

        order.place(event)

        order.domainEvents() shouldBeEqualTo listOf(event)
        order.domainEvents() shouldBeEqualTo listOf(event)
    }

    @Test
    fun `domainEvents returns defensive snapshot`() {
        val order = TestOrder(OrderId(1L))
        val event = OrderPlaced(order.id)
        order.place(event)

        val first = order.domainEvents()
        order.place(OrderConfirmed(order.id))

        first shouldBeEqualTo listOf(event)
        order.domainEvents() shouldHaveSize 2
    }

    @Test
    fun `domainEvents preserves recording order without clearing buffer`() {
        val order = TestOrder(OrderId(1L))
        val placed = OrderPlaced(order.id)
        val confirmed = OrderConfirmed(order.id)

        order.place(placed)
        order.place(confirmed)

        order.domainEvents() shouldBeEqualTo listOf(placed, confirmed)
        order.domainEvents() shouldBeEqualTo listOf(placed, confirmed)
    }

    @Test
    fun `drainDomainEvents returns ordered events and clears buffer`() {
        val order = TestOrder(OrderId(1L))
        val placed = OrderPlaced(order.id)
        val confirmed = OrderConfirmed(order.id)
        order.place(placed)
        order.place(confirmed)

        val drained = order.drainDomainEvents()

        drained shouldBeEqualTo listOf(placed, confirmed)
        order.recordedDomainEventsBuffer().shouldBeNull()
        order.domainEvents().isEmpty().shouldBeTrue()
        order.drainDomainEvents().shouldBeSameInstanceAs(emptyList<DomainEvent<OrderId>>())
        order.domainEvents().shouldBeSameInstanceAs(emptyList<DomainEvent<OrderId>>())
    }

    @Test
    fun `clearDomainEvents discards pending events`() {
        val order = TestOrder(OrderId(1L))
        order.place(OrderPlaced(order.id))

        order.clearDomainEvents()

        order.recordedDomainEventsBuffer().shouldBeNull()
        order.domainEvents().isEmpty().shouldBeTrue()
        order.domainEvents().shouldBeSameInstanceAs(emptyList<DomainEvent<OrderId>>())
        order.drainDomainEvents().shouldBeSameInstanceAs(emptyList<DomainEvent<OrderId>>())
    }

    @Test
    fun `recordDomainEvent rejects events for another aggregate id`() {
        val order = TestOrder(OrderId(1L))

        val error = assertFailsWith<IllegalArgumentException> {
            order.place(OrderPlaced(OrderId(2L)))
        }

        error.message shouldBeEqualTo "Domain event aggregateId must match aggregate id"
    }

    @Test
    fun `aggregate and event ids stay type specific at compile time`() {
        val order = TestOrder(OrderId(1L))
        val event = OrderPlaced(order.id)
        val customerEvent = CustomerRegistered(CustomerId(1L))

        order.place(event)

        order.id shouldBeEqualTo OrderId(1L)
        event.aggregateId shouldBeEqualTo order.id
        customerEvent.aggregateId shouldBeEqualTo CustomerId(1L)
        // A DomainEvent<CustomerId> cannot be passed to TestOrder.place(DomainEvent<OrderId>).
    }

    @JvmInline
    value class OrderId(val value: Long): Serializable {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    @JvmInline
    value class CustomerId(val value: Long): Serializable {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    private class TestOrder(
        override val id: OrderId,
    ): AbstractAggregateRoot<OrderId>() {

        fun place(event: DomainEvent<OrderId>) {
            recordDomainEvent(event)
        }
    }

    private fun TestOrder.recordedDomainEventsBuffer(): Any? {
        val field = AbstractAggregateRoot::class.java.getDeclaredField("recordedDomainEvents")
        field.isAccessible = true
        return field.get(this)
    }

    private data class OrderPlaced(
        override val aggregateId: OrderId,
        override val occurredAt: Instant = Instant.parse("2026-07-09T00:00:00Z"),
    ): DomainEvent<OrderId>, Serializable {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    private data class OrderConfirmed(
        override val aggregateId: OrderId,
        override val occurredAt: Instant = Instant.parse("2026-07-09T00:01:00Z"),
    ): DomainEvent<OrderId>, Serializable {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    private data class CustomerRegistered(
        override val aggregateId: CustomerId,
        override val occurredAt: Instant = Instant.parse("2026-07-09T00:02:00Z"),
    ): DomainEvent<CustomerId>, Serializable {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }
}
```

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
repo-test-summary -- ./gradlew :bluetape4k-exposed-core:test --tests 'io.bluetape4k.exposed.core.ddd.AbstractAggregateRootTest' --no-configuration-cache --no-build-cache --no-parallel --console=plain
```

Expected: `compileTestKotlin` fails because `AggregateRoot`, `DomainEvent`, and `AbstractAggregateRoot` do not exist.

## Task 2: Implement Minimal Spring-Neutral Contracts

complexity: medium  
applies: `$bluetape4k-code-patterns`, `test-driven-development`

**Files:**
- Create: `exposed/core/src/main/kotlin/io/bluetape4k/exposed/core/ddd/DomainEvent.kt`
- Create: `exposed/core/src/main/kotlin/io/bluetape4k/exposed/core/ddd/AggregateRoot.kt`
- Create: `exposed/core/src/main/kotlin/io/bluetape4k/exposed/core/ddd/AbstractAggregateRoot.kt`

- [ ] **Step 1: Add `DomainEvent`**

```kotlin
package io.bluetape4k.exposed.core.ddd

import java.time.Instant

/**
 * Spring-neutral domain event emitted by an [AggregateRoot].
 *
 * ## Contract
 * Implementations should carry opaque, non-sensitive identifiers and minimal
 * business facts. Do not put secrets, credentials, tokens, natural keys, or
 * unnecessary personally identifiable information into event payloads. This
 * contract does not publish, persist, replay, or observe events.
 *
 * ```kotlin
 * @JvmInline
 * value class OrderId(val value: Long) : Serializable {
 *     companion object {
 *         private const val serialVersionUID: Long = 1L
 *     }
 * }
 *
 * data class OrderPlaced(
 *     override val aggregateId: OrderId,
 *     override val occurredAt: Instant = Instant.now(),
 * ) : DomainEvent<OrderId>, Serializable {
 *     companion object {
 *         private const val serialVersionUID: Long = 1L
 *     }
 * }
 * ```
 */
interface DomainEvent<ID: Any> {

    /**
     * Opaque, non-sensitive identifier of the aggregate that emitted this event.
     *
     * Avoid secrets, credentials, tokens, natural keys, and unnecessary
     * personally identifiable information.
     */
    val aggregateId: ID

    /**
     * Time when the event occurred.
     */
    val occurredAt: Instant
}
```

- [ ] **Step 2: Add `AggregateRoot`**

```kotlin
package io.bluetape4k.exposed.core.ddd

/**
 * Spring-neutral DDD aggregate root contract.
 *
 * ## Contract
 * The aggregate owns an in-memory event buffer only. The buffer is not a durable
 * outbox, publisher adapter, Exposed DAO lifecycle hook, Exposed DAO
 * `EntityCache`, in-memory queue, or Spring Modulith publication store.
 * Repository adapters should snapshot events, commit the aggregate state,
 * wait for an after-transaction-commit or equivalent durability boundary, hand
 * the snapshot to a durable or retryable publisher path, and only then clear or
 * drain the buffer. Existing repositories remain unaffected unless callers
 * explicitly adopt these contracts.
 */
interface AggregateRoot<ID: Any> {

    /**
     * Stable aggregate identifier.
     */
    val id: ID

    /**
     * Returns an immutable snapshot of currently recorded domain events.
     *
     * Calling this method does not clear the aggregate event buffer.
     */
    fun domainEvents(): List<DomainEvent<ID>>

    /**
     * Clears recorded domain events without returning them.
     *
     * Use this for caller-owned discard or rollback cleanup. Normal successful
     * persistence flows should not clear events before commit and durable or
     * retryable handoff acceptance.
     */
    fun clearDomainEvents()

    /**
     * Returns recorded domain events in recording order and clears the buffer.
     *
     * Use this only after the caller has moved events into a durable or
     * otherwise retryable handoff path. This method is a local buffer operation,
     * not a publish or persistence boundary.
     */
    fun drainDomainEvents(): List<DomainEvent<ID>>
}
```

- [ ] **Step 3: Add `AbstractAggregateRoot`**

```kotlin
package io.bluetape4k.exposed.core.ddd

/**
 * Minimal base implementation for [AggregateRoot] event recording.
 *
 * ## Contract
 * This class is intentionally not thread-safe. Call [recordDomainEvent],
 * [domainEvents], [clearDomainEvents], and [drainDomainEvents] from one
 * command/transaction boundary at a time. The class does not publish, persist,
 * observe, or replay events, and it does not treat Exposed DAO `EntityCache`,
 * a database flush that can still roll back, or in-memory queues as durable
 * event boundaries. Event payloads should follow the [DomainEvent] guidance
 * for opaque, non-sensitive identifiers and minimal business facts.
 */
abstract class AbstractAggregateRoot<ID: Any>: AggregateRoot<ID> {

    abstract override val id: ID

    private var recordedDomainEvents: MutableList<DomainEvent<ID>>? = null

    override fun domainEvents(): List<DomainEvent<ID>> {
        val events = recordedDomainEvents ?: return emptyList()
        if (events.isEmpty()) return emptyList()
        return events.toList()
    }

    override fun clearDomainEvents() {
        recordedDomainEvents = null
    }

    override fun drainDomainEvents(): List<DomainEvent<ID>> {
        val events = recordedDomainEvents ?: return emptyList()
        if (events.isEmpty()) {
            recordedDomainEvents = null
            return emptyList()
        }

        val snapshot = events.toList()
        recordedDomainEvents = null
        return snapshot
    }

    /**
     * Records [event] for this aggregate.
     *
     * The event aggregate id must match [id]. Mismatches are caller errors.
     */
    protected fun recordDomainEvent(event: DomainEvent<ID>) {
        require(event.aggregateId == id) {
            "Domain event aggregateId must match aggregate id"
        }
        val events = recordedDomainEvents ?: mutableListOf<DomainEvent<ID>>().also {
            recordedDomainEvents = it
        }
        events += event
    }
}
```

- [ ] **Step 4: Run tests and verify GREEN**

Run:

```bash
repo-test-summary -- ./gradlew :bluetape4k-exposed-core:test --tests 'io.bluetape4k.exposed.core.ddd.AbstractAggregateRootTest' --no-configuration-cache --no-build-cache --no-parallel --console=plain
```

Expected: focused test class passes.

## Task 3: Add README Locale Documentation

complexity: low  
applies: `$bluetape4k-code-patterns`

**Files:**
- Modify: `README.md`
- Modify: `README.ko.md`

- [ ] **Step 1: Add English README section**

Insert near the Spring Modulith/boundary documentation:

````markdown
### Spring-Neutral DDD Contracts

`bluetape4k-exposed-core` provides Spring-neutral `AggregateRoot`,
`DomainEvent`, and `AbstractAggregateRoot` contracts for aggregates that record
domain events before repository adapters publish or persist them.

These contracts are opt-in helpers. Existing repositories, cache decorators,
Spring Modulith integration, and JaVers integration are unaffected until an
application explicitly adopts the new aggregate base class or interfaces.
They do not trigger automatic publication or persistence.

```kotlin
class Order(
    override val id: OrderId,
) : AbstractAggregateRoot<OrderId>() {

    fun place() {
        recordDomainEvent(OrderPlaced(id))
    }
}

data class OrderPlaced(
    override val aggregateId: OrderId,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent<OrderId>, Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
```

The contracts keep only an in-memory event buffer. They do not provide a
durable outbox, publisher adapter, Exposed DAO lifecycle hook, Exposed DAO
`EntityCache` event registry, in-memory event queue, or Spring Modulith
publication store. A database flush that can still roll back is not a durable
event boundary.

Repository integrations should:

1. Snapshot events with `domainEvents()`.
2. Persist aggregate state and wait for after-transaction-commit or an
   equivalent durability boundary.
3. Hand the snapshot to a durable or retryable publisher/outbox path.
4. Clear or drain the aggregate buffer only after that handoff accepts
   responsibility for the events.

The Spring Modulith and JaVers modules remain separate adapters. These core
contracts do not encode Spring Modulith publication semantics or JaVers audit
commit semantics.

Event payloads should prefer opaque, non-sensitive identifiers and minimal
business facts. Do not put secrets, credentials, tokens, natural keys, or
unnecessary PII in domain events.
````

- [ ] **Step 2: Add Korean README section**

Add source-equivalent Korean text in `README.ko.md`:

````markdown
### Spring-neutral DDD Contracts

`bluetape4k-exposed-core`는 aggregate가 repository adapter에 이벤트를 넘기기
전에 domain event를 기록할 수 있도록 Spring-neutral `AggregateRoot`,
`DomainEvent`, `AbstractAggregateRoot` contract를 제공합니다.

이 contract는 opt-in helper입니다. Application이 새 aggregate base class나
interface를 명시적으로 채택하기 전까지 기존 repository, cache decorator, Spring
Modulith 통합, JaVers 통합의 동작은 바뀌지 않습니다.
Automatic publication이나 persistence를 실행하지도 않습니다.

```kotlin
class Order(
    override val id: OrderId,
) : AbstractAggregateRoot<OrderId>() {

    fun place() {
        recordDomainEvent(OrderPlaced(id))
    }
}

data class OrderPlaced(
    override val aggregateId: OrderId,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent<OrderId>, Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
```

이 contract는 in-memory event buffer만 다룹니다. durable outbox, publisher
adapter, Exposed DAO lifecycle hook, Exposed DAO `EntityCache` event registry,
in-memory event queue, Spring Modulith publication store를 제공하지 않습니다.
Rollback될 수 있는 database flush도 durable event boundary가 아닙니다.

Repository 통합은 다음 순서를 따라야 합니다.

1. `domainEvents()`로 event snapshot을 만듭니다.
2. Aggregate 상태를 persist하고 after-transaction-commit 또는 동등한 durability
   boundary를 기다립니다.
3. Snapshot을 durable 또는 retry 가능한 publisher/outbox 경로에 넘깁니다.
4. 해당 경로가 event 책임을 인수한 뒤에만 aggregate buffer를 clear/drain합니다.

Spring Modulith와 JaVers module은 별도 adapter로 유지됩니다. Core contract는
Spring Modulith publication semantic이나 JaVers audit commit semantic을 encode하지
않습니다.

Event payload는 opaque하고 민감하지 않은 identifier와 최소 business fact 위주로
유지하세요. secret, credential, token, natural key, 불필요한 PII를 domain event에
넣지 않습니다.
````

- [ ] **Step 3: Verify docs source consistency**

Run:

```bash
rg -n "Spring-Neutral DDD Contracts|Spring-neutral DDD Contracts|AggregateRoot|DomainEvent|EntityCache|after-transaction-commit|existing repositories|기존 repository|opt-in|automatic publication|lifecycle hook|durable outbox|JaVers|Spring Modulith" README.md README.ko.md
git diff --check
```

Expected: both locale files contain source-equivalent sections covering opt-in
adoption, no existing behavior change, no automatic publication, no lifecycle
hook/outbox, `EntityCache` not being a durable boundary, after-commit handoff,
and `git diff --check` passes.

## Task 4: Run Targeted Verification

complexity: low  
applies: `verification-before-completion`

**Files:**
- Verify all changed files.

- [ ] **Step 1: Run focused core test**

```bash
repo-test-summary -- ./gradlew :bluetape4k-exposed-core:test --tests 'io.bluetape4k.exposed.core.ddd.AbstractAggregateRootTest' --no-configuration-cache --no-build-cache --no-parallel --console=plain
```

Expected: focused tests pass.

- [ ] **Step 2: Run full affected module test**

```bash
repo-test-summary -- ./gradlew :bluetape4k-exposed-core:test --no-configuration-cache --no-build-cache --no-parallel --console=plain
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run static diff checks**

```bash
git diff --check
rg -n "TODO|TBD|!!|AssertJ|assertThrows|kotlin.test.assertFailsWith" exposed/core/src/main/kotlin/io/bluetape4k/exposed/core/ddd exposed/core/src/test/kotlin/io/bluetape4k/exposed/core/ddd README.md README.ko.md || true
rg -n "^import .*(spring|modulith|javers|exposed\\.dao|EntityCache|EventPublication|publisher|outbox|repository)" exposed/core/src/main/kotlin/io/bluetape4k/exposed/core/ddd || true
```

Expected: no whitespace errors, no forbidden assertion APIs, no `!!`, no
unresolved placeholders, and no framework/adapter imports or implementation
coupling in the new core `ddd` package. KDoc may mention adapter boundaries in
plain text but must not import or depend on those types.

## Task 5: Review, Lessons, Commit, PR

complexity: medium  
applies: `$bluetape4k-code-patterns`, `verification-before-completion`

**Files:**
- Create: `docs/review/2026-07-09-issue-320-ddd-contracts-review.md`
- Create: `docs/lessons/2026-07-09-issue-320-ddd-contracts.md`

- [ ] **Step 1: Run Step 6-R code review**

Review changed code/docs against:
- public API KDoc in English,
- framework-neutral dependency boundary,
- aggregate ID mismatch validation,
- after-commit/handoff documentation,
- opt-in adoption/no existing repository behavior change,
- `EntityCache`, database flush, and in-memory queues not being durable event boundaries,
- README locale parity,
- test coverage and assertion style.

- [ ] **Step 2: Write review artifact**

Write `docs/review/2026-07-09-issue-320-ddd-contracts-review.md` with P0/P1/P2/P3 summary and verification evidence.

- [ ] **Step 3: Write short lesson**

Write `docs/lessons/2026-07-09-issue-320-ddd-contracts.md` with:
- context,
- decision,
- outcome,
- verification evidence,
- future guard: do not drain events before commit/durable handoff acceptance.

- [ ] **Step 4: Commit**

Use Lore protocol:

Stage the changed files and commit with a multi-line Lore protocol message.
The commit body must include `Constraint`, `Rejected`, `Confidence`,
`Scope-risk`, `Directive`, `Tested`, and `Not-tested` trailers.

- [ ] **Step 5: Create PR**

Before creating the PR, read live issue metadata:

```bash
gh issue view 320 --json assignees,labels,milestone,title,state,url
```

Create PR against `develop`, link `Closes #320`, assign `debop`, copy the issue
milestone (`1.12.0`) and labels (`enhancement`, `feature`, `test`), and ensure
the final PR body section is `## DoD Status` with test, documentation, review,
and metadata evidence. Verify the PR base branch and metadata exactly match the
issue:

```bash
gh pr view <number> --json baseRefName,body,assignees,labels,milestone,closingIssuesReferences
```
