# Issue 320 DDD 계약 구현 계획

> **에이전트 작업자 안내:** 필수 하위 스킬로 superpowers:subagent-driven-development(권장) 또는 superpowers:executing-plans를 사용해 이 계획을 작업 단위로 구현한다. 진행 상태는 체크박스(`- [ ]`) 문법으로 추적한다.

**목표:** 테스트, KDoc, README 로케일 문서와 함께 Spring 중립적인 DDD 애그리거트/도메인 이벤트 계약을 `bluetape4k-exposed-core`에 추가한다.

**아키텍처:** 기존 core 모듈에 작은 `io.bluetape4k.exposed.core.ddd` 패키지를 추가한다. 이 패키지에는 프레임워크 중립 계약과 인메모리 이벤트 버퍼 기반 클래스만 둔다. 발행, 영속 아웃박스, 저장소 어댑터, Spring Modulith 연동, JaVers 연동, Exposed DAO 수명주기 훅은 범위에서 제외한다.

**기술 스택:** 이 worktree의 Kotlin 카탈로그 버전, JDK `Instant`, `bluetape4k-assertions`, JUnit 5, Gradle 모듈 `:bluetape4k-exposed-core`.

---

## 파일 구조

- 생성: `exposed/core/src/test/kotlin/io/bluetape4k/exposed/core/ddd/AbstractAggregateRootTest.kt`
  - 이벤트 기록, 스냅숏, 비우기, 순서, ID 불일치, 타입 지정 ID fixture를 검증하는 TDD 테스트.
- 생성: `exposed/core/src/main/kotlin/io/bluetape4k/exposed/core/ddd/DomainEvent.kt`
  - Spring 중립 도메인 이벤트 계약.
- 생성: `exposed/core/src/main/kotlin/io/bluetape4k/exposed/core/ddd/AggregateRoot.kt`
  - 애그리거트 루트 계약.
- 생성: `exposed/core/src/main/kotlin/io/bluetape4k/exposed/core/ddd/AbstractAggregateRoot.kt`
  - 지연 이벤트 버퍼를 사용하는 최소 기반 구현.
- 수정: `README.md`
  - 영어 DDD 계약 절.
- 수정: `README.ko.md`
  - 같은 내용을 담은 한국어 절.

## 작업 1: DDD 계약의 RED 테스트 작성

복잡도: 중간
적용 항목: `$bluetape4k-code-patterns`, `test-driven-development`

**파일:**
- 생성: `exposed/core/src/test/kotlin/io/bluetape4k/exposed/core/ddd/AbstractAggregateRootTest.kt`

- [ ] **단계 1: 실패하는 테스트 파일 생성**

```kotlin
package io.bluetape4k.exposed.core.ddd

import io.bluetape4k.assertions.shouldBeEqualTo
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

        events.isEmpty().shouldBeTrue()
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
    fun `drainDomainEvents hands off ordered events and clears after success`() {
        val order = TestOrder(OrderId(1L))
        val placed = OrderPlaced(order.id)
        val confirmed = OrderConfirmed(order.id)
        val handedOff = mutableListOf<List<DomainEvent<OrderId>>>()
        order.place(placed)
        order.place(confirmed)

        val drained = order.drainDomainEvents { events ->
            handedOff += events
        }

        drained shouldBeEqualTo listOf(placed, confirmed)
        handedOff shouldBeEqualTo listOf(listOf(placed, confirmed))
        order.domainEvents().isEmpty().shouldBeTrue()
        order.drainDomainEvents {
            error("Empty drain should not invoke handoff")
        }.isEmpty().shouldBeTrue()
        order.domainEvents().isEmpty().shouldBeTrue()
    }

    @Test
    fun `drainDomainEvents keeps events when handoff fails`() {
        val order = TestOrder(OrderId(1L))
        val event = OrderPlaced(order.id)
        order.place(event)

        assertFailsWith<IllegalStateException> {
            order.drainDomainEvents {
                throw IllegalStateException("handoff failed")
            }
        }

        order.domainEvents() shouldBeEqualTo listOf(event)
    }

    @Test
    fun `clearDomainEvents discards pending events`() {
        val order = TestOrder(OrderId(1L))
        order.place(OrderPlaced(order.id))

        order.clearDomainEvents()

        order.domainEvents().isEmpty().shouldBeTrue()
        order.drainDomainEvents {
            error("Empty drain should not invoke handoff")
        }.isEmpty().shouldBeTrue()
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

- [ ] **단계 2: 테스트를 실행해 RED 확인**

실행:

```bash
repo-test-summary -- ./gradlew :bluetape4k-exposed-core:test --tests 'io.bluetape4k.exposed.core.ddd.AbstractAggregateRootTest' --no-configuration-cache --no-build-cache --no-parallel --console=plain
```

예상 결과: `AggregateRoot`, `DomainEvent`, `AbstractAggregateRoot`가 없으므로 `compileTestKotlin`이 실패한다.

## 작업 2: 최소 Spring 중립 계약 구현

복잡도: 중간
적용 항목: `$bluetape4k-code-patterns`, `test-driven-development`

**파일:**
- 생성: `exposed/core/src/main/kotlin/io/bluetape4k/exposed/core/ddd/DomainEvent.kt`
- 생성: `exposed/core/src/main/kotlin/io/bluetape4k/exposed/core/ddd/AggregateRoot.kt`
- 생성: `exposed/core/src/main/kotlin/io/bluetape4k/exposed/core/ddd/AbstractAggregateRoot.kt`

- [ ] **단계 1: `DomainEvent` 추가**

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

- [ ] **단계 2: `AggregateRoot` 추가**

```kotlin
package io.bluetape4k.exposed.core.ddd

/**
 * Spring-neutral DDD aggregate root contract.
 *
 * ## Contract
 * The aggregate owns an in-memory event buffer only. The buffer is not a durable
 * outbox, publisher adapter, Exposed DAO lifecycle hook, Exposed DAO
 * `EntityCache`, in-memory queue, or Spring Modulith publication store.
 * Repository adapters should snapshot events, commit the aggregate state, wait
 * for an after-transaction-commit or equivalent durability boundary, hand the
 * snapshot to a durable owner such as an outbox, persisted retry queue, or
 * transactionally recorded handoff, and only then clear or drain the buffer.
 * Existing repositories remain unaffected unless callers explicitly adopt these
 * contracts.
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
     * persistence flows should not clear events before commit and durable
     * handoff acceptance.
     */
    fun clearDomainEvents()

    /**
     * Hands recorded domain events to [handoff] in recording order and clears
     * the buffer only after [handoff] returns successfully.
     *
     * Use this only after the caller is ready to move events into a durable
     * owner such as an outbox, persisted retry queue, or transactionally
     * recorded handoff. This method is a local buffer operation, not a publish
     * or persistence boundary. If [handoff] throws, the buffer remains intact.
     */
    fun drainDomainEvents(handoff: (List<DomainEvent<ID>>) -> Unit): List<DomainEvent<ID>>
}
```

- [ ] **단계 3: `AbstractAggregateRoot` 추가**

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

    override fun drainDomainEvents(handoff: (List<DomainEvent<ID>>) -> Unit): List<DomainEvent<ID>> {
        val events = recordedDomainEvents ?: return emptyList()
        if (events.isEmpty()) {
            recordedDomainEvents = null
            return emptyList()
        }

        val snapshot = events.toList()
        handoff(snapshot)
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

- [ ] **단계 4: 테스트를 실행해 GREEN 확인**

실행:

```bash
repo-test-summary -- ./gradlew :bluetape4k-exposed-core:test --tests 'io.bluetape4k.exposed.core.ddd.AbstractAggregateRootTest' --no-configuration-cache --no-build-cache --no-parallel --console=plain
```

예상 결과: 대상 테스트 클래스가 통과한다.

## 작업 3: README 로케일 문서 추가

복잡도: 낮음
적용 항목: `$bluetape4k-code-patterns`

**파일:**
- 수정: `README.md`
- 수정: `README.ko.md`

- [ ] **단계 1: 영어 README 절 추가**

Spring Modulith/경계 문서 근처에 삽입한다.

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
3. Hand the snapshot to a durable owner such as an outbox, persisted retry
   queue, or transactionally recorded handoff.
4. Clear or drain the aggregate buffer only after that durable owner accepts
   responsibility for the events.

The Spring Modulith and JaVers modules remain separate adapters. These core
contracts do not encode Spring Modulith publication semantics or JaVers audit
commit semantics.

Event payloads should prefer opaque, non-sensitive identifiers and minimal
business facts. Do not put secrets, credentials, tokens, natural keys, or
unnecessary PII in domain events.
````

- [ ] **단계 2: 한국어 README 절 추가**

`README.ko.md`에 원문과 같은 내용을 담은 한국어 텍스트를 추가한다.

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
3. Snapshot을 outbox, persisted retry queue, transactionally recorded handoff처럼
   durable owner가 있는 경로에 넘깁니다.
4. 해당 durable owner가 event 책임을 인수한 뒤에만 aggregate buffer를
   clear/drain합니다.

Spring Modulith와 JaVers module은 별도 adapter로 유지됩니다. Core contract는
Spring Modulith publication semantic이나 JaVers audit commit semantic을 encode하지
않습니다.

Event payload는 opaque하고 민감하지 않은 identifier와 최소 business fact 위주로
유지하세요. secret, credential, token, natural key, 불필요한 PII를 domain event에
넣지 않습니다.
````

- [ ] **단계 3: 문서 원본 일관성 검증**

실행:

```bash
rg -n "Spring-Neutral DDD Contracts|Spring-neutral DDD Contracts|AggregateRoot|DomainEvent|EntityCache|after-transaction-commit|existing repositories|기존 repository|opt-in|automatic publication|lifecycle hook|durable outbox|JaVers|Spring Modulith" README.md README.ko.md
git diff --check
```

예상 결과: 두 로케일 파일에 옵트인 도입, 기존 동작 불변, 자동 발행 없음,
수명주기 훅/아웃박스 없음, `EntityCache`가 영속 경계가 아니라는 점,
커밋 후 전달을 같은 의미로 설명하는 절이 있으며 `git diff --check`가 통과한다.

## 작업 4: 대상 범위 검증 실행

복잡도: 낮음
적용 항목: `verification-before-completion`

**파일:**
- 변경한 모든 파일을 검증한다.

- [ ] **단계 1: 대상 core 테스트 실행**

```bash
repo-test-summary -- ./gradlew :bluetape4k-exposed-core:test --tests 'io.bluetape4k.exposed.core.ddd.AbstractAggregateRootTest' --no-configuration-cache --no-build-cache --no-parallel --console=plain
```

예상 결과: 대상 테스트가 통과한다.

- [ ] **단계 2: 영향받는 모듈 전체 테스트 실행**

```bash
repo-test-summary -- ./gradlew :bluetape4k-exposed-core:test --no-configuration-cache --no-build-cache --no-parallel --console=plain
```

예상 결과: `BUILD SUCCESSFUL`.

- [ ] **단계 3: 정적 diff 검사 실행**

```bash
git diff --check
rg -n "TODO|TBD|!!|AssertJ|assertThrows|kotlin.test.assertFailsWith" exposed/core/src/main/kotlin/io/bluetape4k/exposed/core/ddd exposed/core/src/test/kotlin/io/bluetape4k/exposed/core/ddd README.md README.ko.md || true
rg -n "^import .*(spring|modulith|javers|exposed\\.dao|EntityCache|EventPublication|publisher|outbox|repository)" exposed/core/src/main/kotlin/io/bluetape4k/exposed/core/ddd || true
```

예상 결과: 공백 오류, 금지된 assertion API, `!!`, 미해결 placeholder가 없고
새 core `ddd` 패키지에 프레임워크/어댑터 import나 구현 결합이 없다.
KDoc은 일반 텍스트로 어댑터 경계를 언급할 수 있지만 해당 타입을 import하거나
의존해서는 안 된다.

## 작업 5: 리뷰, 교훈, 커밋, PR

복잡도: 중간
적용 항목: `$bluetape4k-code-patterns`, `verification-before-completion`

**파일:**
- 생성: `docs/review/2026-07-09-issue-320-ddd-contracts-review.md`
- 생성: `docs/lessons/2026-07-09-issue-320-ddd-contracts.md`

- [ ] **단계 1: 단계 6-R 코드 리뷰 실행**

변경한 코드/문서를 다음 기준으로 검토한다.
- 영어 public API KDoc,
- 프레임워크 중립 의존성 경계,
- 애그리거트 ID 불일치 검증,
- 커밋 후 전달 문서,
- 옵트인 도입과 기존 저장소 동작 불변,
- `EntityCache`, 데이터베이스 flush, 인메모리 큐가 영속 이벤트 경계가 아니라는 점,
- README 로케일 동등성,
- 테스트 커버리지와 assertion 스타일.

- [ ] **단계 2: 리뷰 산출물 작성**

P0/P1/P2/P3 요약과 검증 근거를 담아 `docs/review/2026-07-09-issue-320-ddd-contracts-review.md`를 작성한다.

- [ ] **단계 3: 짧은 교훈 작성**

다음 내용을 담아 `docs/lessons/2026-07-09-issue-320-ddd-contracts.md`를 작성한다.
- 맥락,
- 결정,
- 결과,
- 검증 근거,
- 향후 가드: 커밋/영속 전달 수락 전에 이벤트를 비우지 않는다.

- [ ] **단계 4: 커밋**

Lore 프로토콜을 사용한다.

변경 파일을 stage하고 여러 줄로 된 Lore 프로토콜 메시지로 커밋한다.
커밋 본문에는 `Constraint`, `Rejected`, `Confidence`, `Scope-risk`,
`Directive`, `Tested`, `Not-tested` trailer가 있어야 한다.

- [ ] **단계 5: PR 생성**

PR을 만들기 전에 라이브 이슈 메타데이터를 읽는다.

```bash
gh issue view 320 --json assignees,labels,milestone,title,state,url
```

`develop`을 대상으로 PR을 만들고 `Closes #320`을 연결하며 `debop`을 할당한다.
이슈 milestone(`1.12.0`)과 label(`enhancement`, `feature`, `test`)을 복사하고,
PR 본문의 마지막 절이 테스트, 문서, 리뷰, 메타데이터 근거를 담은
`## DoD Status`인지 확인한다. PR base branch와 메타데이터가 이슈와 정확히
일치하는지 검증한다.

```bash
gh pr view <number> --json baseRefName,body,assignees,labels,milestone,closingIssuesReferences
```
