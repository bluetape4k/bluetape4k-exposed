# Issue #323 Transaction-Aware Domain Event Publisher Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an explicit Spring Boot JDBC bridge that hands aggregate domain events to Spring inside the command transaction, clears them only after committed completion, and demonstrates the contract in the DDD Spring Modulith example.

**Architecture:** `spring-boot/jdbc` owns one public `ExposedAggregateEventPublisher` and one public auto-configuration class. Publisher state lives only in a private transaction synchronization discovered from Spring's current synchronization list; aggregate identity is reserved before publication, commit is poisoned after any lifecycle violation, and completion cleanup is isolated per aggregate. `exposed/core` remains Spring-neutral, while the example replaces its manual publisher loop with the new API.

**Tech Stack:** Kotlin, Spring Framework 7 transaction synchronization, Spring Boot 4 auto-configuration, JetBrains Exposed 1.3.1, Spring Modulith 2.0.6, H2, JUnit 5, bluetape4k assertions/logging, Logback test capture, CairoSVG.

---

## Approved Basis

- Issue: `#323`, milestone `1.12.0`, assignee `debop`.
- Design: `docs/superpowers/specs/2026-07-10-issue-323-domain-event-publisher-design.md`.
- Spec review: `docs/review/2026-07-10-issue-323-domain-event-publisher-spec-review.md`, final `P0 = 0`, `P1 = 0`.
- Baseline command already passed before implementation:
  `./gradlew :bluetape4k-exposed-spring-boot-jdbc:test :bluetape4k-exposed-spring-modulith:test :examples-ddd-spring-modulith-demo:test --no-configuration-cache --no-daemon --console=plain`.
- CodeGraph is unavailable in this worktree (`0` files/nodes). Exact source paths below were verified with direct repository inspection.

## File And Ownership Map

| Task | Write scope | Responsibility |
|---|---|---|
| 0 | Read-only workflow inspection; approved spec/plan/review artifacts | Fail fast on CI/Nightly coverage gaps and freeze the approved implementation basis. |
| 1 | `exposed/core/src/main/.../ddd/**`, `exposed/core/src/test/.../ddd/AbstractAggregateRootTest.kt` | Lock and document Spring-neutral event immutability/reference contracts. |
| 2-4 | `spring-boot/jdbc/src/main/.../ddd/ExposedAggregateEventPublisher.kt`, `spring-boot/jdbc/src/test/.../ddd/ExposedAggregateEventPublisherTest.kt` | Implement and test transaction lifecycle, poison semantics, correlation, and completion logging. |
| 5 | JDBC repository configuration extension/base implementation, aggregate publisher auto-configuration/imports, matching tests | Make the existing `transactionManagerRef` contract executable and add guarded default publisher registration. |
| 6 | `examples/ddd-spring-modulith-demo/**` listed in Task 6 | Replace manual publication and extend integration/serializer tests. |
| 7 | README locale pairs, `CHANGELOG.md`, lifecycle SVG/PNG | Document public behavior, migration, operations, and timing. |
| 8-9 | Review/lesson artifacts only | Run final verification and capture evidence. |
| 10 | GitHub PR/CI evidence after explicit external-side-effect approval | Prove live metadata, checks, coverage artifacts, and review-thread closure before merge. |

Task 0 is the pre-implementation gate. Tasks 2-4 share one implementation file and must run sequentially. Task 5 depends on the public class from Task 3. Task 6 depends on Tasks 3 and 5. Documentation starts only after the API and example compile. No task depends on an artifact produced by a later task.

## Task 0: Verify Workflow Coverage And Freeze The Approved Basis

complexity: low
depends_on: approved design and Step 3-R plan review
applies: `bluetape4k-full-feature`, `verification-before-completion`

**Files:**
- Inspect: `.github/workflows/ci.yml`
- Inspect: `.github/workflows/nightly-tests.yml`
- Verify: committed approved spec
- Commit: implementation plan and plan-review artifact only

- [ ] **Step 1: Record the approved execution gate and checklist applicability**

Quote the user's explicit implementation-plan approval in the execution log. Instantiate the Full Feature checklist and record `WF` (workflow), `CL` (change lifecycle), `CG` (Common Gates `CG-01..17`), `A` (Full Feature `A-01..11`), and `KT` (Kotlin) as applicable or unavailable with evidence. Mark the triggered `KT-TEST` and `KT-SPR` checklists applicable as well. Record CodeGraph availability separately as tool evidence; it is not the `CG` checklist. For every task below, record before execution: `Action`, `Expected DoD`, and `Failure/return point`; record `Step DoD` with command/file evidence before advancing. A missing approval or unchecked applicable gate stops before mutation.

- [ ] **Step 2: Fail fast on workflow coverage gaps**

Before any source edit, inspect both workflows and record evidence that CI changes under `exposed/core/**`, `spring-boot/**` (covering JDBC and Spring Modulith), the narrower `spring-boot/jdbc/**`/`spring-boot/spring-modulith/**` dependent-job filters, and `examples/**` route to jobs that run the corresponding `test` and `koverXmlReport` tasks. Nightly has no path filter: prove the core, Spring Boot, and Spring Modulith jobs run in the applicable Nightly scopes, while `test-examples` runs in weekly/full scope. Verify those jobs are included in status/coverage `needs` and their Kover XML is uploaded. Preserve the repository's report-only Kover policy; this task verifies visibility/routing rather than introducing a hard coverage threshold.

```bash
actionlint .github/workflows/ci.yml .github/workflows/nightly-tests.yml
ruby -ryaml -e '
  ARGV.each do |path|
    jobs = YAML.load_file(path).fetch("jobs")
    %w[test-core test-spring-boot test-spring-modulith test-examples coverage-report].each { |job| abort("#{path}: missing #{job}") unless jobs.key?(job) }
    status = path.end_with?("ci.yml") ? "ci-status" : "nightly-status"
    abort("#{path}: missing #{status}") unless jobs.key?(status)
  end
' .github/workflows/ci.yml .github/workflows/nightly-tests.yml
rg -n 'exposed/core/\*\*|spring-boot/\*\*|spring-boot/jdbc/\*\*|spring-boot/spring-modulith/\*\*|examples/\*\*|bluetape4k-exposed-core:(test|koverXmlReport)|bluetape4k-exposed-spring-boot-jdbc:(test|koverXmlReport)|bluetape4k-exposed-spring-modulith:(test|koverXmlReport)|examples-ddd-spring-modulith-demo:(test|koverXmlReport)|needs:|kover.*xml|coverage-' \
  .github/workflows/ci.yml .github/workflows/nightly-tests.yml
```

Expected: CI path routing, Nightly full-scope job inclusion, all four module test/Kover registrations, status/coverage dependencies, and coverage upload paths are evidenced. If any route is absent, stop before Task 1, add the workflow file to the ownership map, and insert a RED workflow-validation step plus the minimal workflow edit into the owning implementation task.

- [ ] **Step 3: Freeze the reviewed implementation basis**

Confirm the plan review records final `P0 = 0`, `P1 = 0`, all P2/P3 resolutions, and the exact reviewed plan/spec blob IDs. Verify the approved spec is already pinned in branch history. Because the plan/review files are new, run scoped checks that include untracked content, commit them in one Lore commit, and verify the committed blob IDs before Task 1:

```text
docs/superpowers/plans/2026-07-11-issue-323-domain-event-publisher-plan.md
docs/review/2026-07-11-issue-323-domain-event-publisher-plan-review.md
```

```bash
git hash-object docs/superpowers/specs/2026-07-10-issue-323-domain-event-publisher-design.md
git hash-object docs/superpowers/plans/2026-07-11-issue-323-domain-event-publisher-plan.md
check_new_file() {
  set +e
  output="$(git diff --no-index --check /dev/null "$1" 2>&1)"
  diff_status=$?
  set -e
  test "$diff_status" -eq 1 || { printf '%s\n' "unexpected diff status $diff_status: $1" "$output"; return 1; }
  test -z "$output" || { printf '%s\n' "$output"; return 1; }
}
set -e
check_new_file docs/superpowers/plans/2026-07-11-issue-323-domain-event-publisher-plan.md
check_new_file docs/review/2026-07-11-issue-323-domain-event-publisher-plan-review.md
git add -- \
  docs/superpowers/plans/2026-07-11-issue-323-domain-event-publisher-plan.md \
  docs/review/2026-07-11-issue-323-domain-event-publisher-plan-review.md
git diff --cached --check
git commit \
  -m 'docs: make transaction-safe event handoff executable' \
  -m $'Constraint: Transactional listeners require in-transaction publication with committed completion cleanup.\nRejected: Publish from afterCommit | transactional listener registration is already closed\nRejected: Make Kover workflow fail-closed | workspace policy keeps coverage report-only; issue evidence verifies non-empty XML locally and in PR artifacts\nConfidence: high\nScope-risk: broad\nDirective: Execute the reviewed plan in dependency order and rerun affected review lenses after any contract change.\nTested: Plan self-review, 7-Tier plan review, actionlint, workflow routing inspection, spec traceability, git diff --check\nNot-tested: Implementation and post-change module tests'
test "$(git rev-parse HEAD:docs/superpowers/specs/2026-07-10-issue-323-domain-event-publisher-design.md)" = \
  "$(git hash-object docs/superpowers/specs/2026-07-10-issue-323-domain-event-publisher-design.md)"
test "$(git rev-parse HEAD:docs/superpowers/plans/2026-07-11-issue-323-domain-event-publisher-plan.md)" = \
  "$(git hash-object docs/superpowers/plans/2026-07-11-issue-323-domain-event-publisher-plan.md)"
test "$(git rev-parse HEAD:docs/review/2026-07-11-issue-323-domain-event-publisher-plan-review.md)" = \
  "$(git hash-object docs/review/2026-07-11-issue-323-domain-event-publisher-plan-review.md)"
git rev-parse HEAD:docs/superpowers/plans/2026-07-11-issue-323-domain-event-publisher-plan.md
git rev-parse HEAD:docs/review/2026-07-11-issue-323-domain-event-publisher-plan-review.md
git status --short
```

Record the resulting baseline commit SHA in the execution log; the review artifact records the pre-commit spec/plan blobs because a commit cannot contain its own SHA. The pinned spec commit plus this plan/review commit form the immutable implementation baseline. Any later plan change reruns affected Step 3-R lenses and creates a new reviewed baseline rather than being folded into source work silently.

## Task 1: Lock The Spring-Neutral Aggregate Contract

complexity: low
depends_on: Task 0 PASS and committed reviewed basis
applies: `test-driven-development`, `bluetape4k-code-patterns`, `ecc-kotlin-testing`

**Files:**
- Modify: `exposed/core/src/main/kotlin/io/bluetape4k/exposed/core/ddd/AggregateRoot.kt`
- Modify: `exposed/core/src/main/kotlin/io/bluetape4k/exposed/core/ddd/AbstractAggregateRoot.kt`
- Modify: `exposed/core/src/main/kotlin/io/bluetape4k/exposed/core/ddd/DomainEvent.kt`
- Modify: `exposed/core/src/test/kotlin/io/bluetape4k/exposed/core/ddd/AbstractAggregateRootTest.kt`

- [ ] **Step 1: Add a characterization test for stable event references**

Add this test to `AbstractAggregateRootTest`:

```kotlin
@Test
fun `domainEvents preserves event object references until clear`() {
    val order = TestOrder(OrderId(1L))
    val placed = OrderPlaced(order.id)
    val confirmed = OrderConfirmed(order.id)
    order.place(placed)
    order.place(confirmed)

    val first = order.domainEvents()
    val second = order.domainEvents()

    (first !== second).shouldBeTrue()
    (first[0] === placed).shouldBeTrue()
    (first[1] === confirmed).shouldBeTrue()
    (second[0] === placed).shouldBeTrue()
    (second[1] === confirmed).shouldBeTrue()
    first shouldBeEqualTo second

    @Suppress("UNCHECKED_CAST")
    (first as MutableList<DomainEvent<OrderId>>).clear()
    val afterMisuse = order.domainEvents()
    afterMisuse shouldHaveSize 2
    (afterMisuse[0] === placed).shouldBeTrue()
    (afterMisuse[1] === confirmed).shouldBeTrue()
}
```

This is a characterization test and is expected to pass before KDoc edits; no production behavior change is required.

- [ ] **Step 2: Run the focused core test**

Run:

```bash
./gradlew :bluetape4k-exposed-core:test \
  --tests 'io.bluetape4k.exposed.core.ddd.AbstractAggregateRootTest' \
  --no-configuration-cache --no-daemon --console=plain
```

Expected: PASS, proving that snapshots are separate caller-visible `List` instances containing the same event object references and isolated from caller mutation.

- [ ] **Step 3: Update English KDoc without adding Spring dependencies**

Update the three public contracts so they state:

```text
AggregateRoot.domainEvents(): side-effect-free immutable snapshot, recording order and event object references stable until clear.
AggregateRoot.clearDomainEvents(): forbidden between ExposedAggregateEventPublisher registration and transaction completion.
AggregateRoot.drainDomainEvents(...): incompatible with that bridge because it clears before completion.
AbstractAggregateRoot: single command/transaction owner, no concurrent or overlapping REQUIRES_NEW reuse.
DomainEvent: payload must be deeply immutable after recording/registration.
```

Do not import or link Spring types from `exposed/core`; name the bridge as plain code text in KDoc.

- [ ] **Step 4: Verify the dependency boundary and commit**

Run:

```bash
! rg -n 'import org\.springframework|import org\.springframework\.modulith|org\.javers' \
  exposed/core/src/main exposed/core/build.gradle.kts
./gradlew :bluetape4k-exposed-core:test --no-configuration-cache --no-daemon --console=plain
git diff --check
```

Expected: the `rg` command returns no matches; core remains Spring- and JaVers-neutral, and core tests and diff check pass. Audit history, snapshot persistence, and JaVers commit semantics are explicitly outside this publisher contract.

Commit only Task 1 files with a Lore message beginning:

```text
docs: define aggregate event handoff invariants
```

Rollback point: this commit contains only KDoc plus a passing characterization test and can be reverted independently.

## Task 2: Write RED Transaction Lifecycle Tests

complexity: high
depends_on: Task 1
applies: `test-driven-development`, `bluetape4k-code-patterns`, `ecc-kotlin-exposed`, `ecc-kotlin-testing`

**Files:**
- Create: `spring-boot/jdbc/src/test/kotlin/io/bluetape4k/spring/data/exposed/jdbc/ddd/ExposedAggregateEventPublisherTest.kt`

- [ ] **Step 1: Add deterministic H2 transaction fixtures**

Create the test class with these reusable fixtures:

```kotlin
package io.bluetape4k.spring.data.exposed.jdbc.ddd

import io.bluetape4k.exposed.core.ddd.AbstractAggregateRoot
import io.bluetape4k.exposed.core.ddd.DomainEvent
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.MDC
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.io.Serializable
import java.time.Instant

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExposedAggregateEventPublisherTest {

    private val dataSource: EmbeddedDatabase = EmbeddedDatabaseBuilder()
        .generateUniqueName(true)
        .setType(EmbeddedDatabaseType.H2)
        .build()
    private val transactionManager: PlatformTransactionManager =
        DataSourceTransactionManager(dataSource)
    private val transactionTemplate = TransactionTemplate(transactionManager)
    private val jdbcTemplate = JdbcTemplate(dataSource).also {
        it.execute("CREATE TABLE DOMAIN_EVENT_TEST (ID BIGINT PRIMARY KEY)")
    }

    @AfterEach
    fun cleanup() {
        val synchronizationActive = TransactionSynchronizationManager.isSynchronizationActive()
        try {
            synchronizationActive.shouldBeFalse()
        } finally {
            TransactionSynchronizationManager.clear()
            MDC.clear()
            jdbcTemplate.update("DELETE FROM DOMAIN_EVENT_TEST")
        }
    }

    @AfterAll
    fun shutdownDatabase() {
        dataSource.shutdown()
    }

    @JvmInline
    value class TestId(val value: Long) : Serializable {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    data class TestEvent(
        override val aggregateId: TestId,
        val sequence: Int,
        override val occurredAt: Instant = Instant.parse("2026-07-11T00:00:00Z"),
    ) : DomainEvent<TestId>, Serializable {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    open class TestAggregate(
        override val id: TestId,
    ) : AbstractAggregateRoot<TestId>() {
        fun record(sequence: Int): TestEvent =
            TestEvent(id, sequence).also(::recordDomainEvent)
    }
}
```

Every additional `AnnotationConfigApplicationContext`, `EmbeddedDatabase`, and Logback appender introduced by later tests must be lifecycle-safe: wrap contexts in `use`, shut databases down in `finally`, call `appender.start()` before capture, and detach plus `appender.stop()` in `finally`. No test may leave MDC or transaction synchronization active for the next case.

- [ ] **Step 2: Add RED tests for commit, rollback, ordering, and empty no-op**

Add tests with these exact assertions:

```kotlin
@Test
fun `empty aggregate is a no-op outside a transaction`() {
    val publisher = ExposedAggregateEventPublisher(ApplicationEventPublisher { error("must not publish") })

    publisher.publishAfterSave(TestAggregate(TestId(1L)))
}

@Test
fun `commit publishes in order and clears after completion`() {
    val published = mutableListOf<TestEvent>()
    val publisher = ExposedAggregateEventPublisher(ApplicationEventPublisher { published += it as TestEvent })
    val aggregate = TestAggregate(TestId(1L)).apply { record(1); record(2) }

    transactionTemplate.executeWithoutResult {
        jdbcTemplate.update("INSERT INTO DOMAIN_EVENT_TEST(ID) VALUES (?)", aggregate.id.value)
        publisher.publishAfterSave(aggregate)
        published.map(TestEvent::sequence) shouldBeEqualTo listOf(1, 2)
        aggregate.domainEvents() shouldHaveSize 2
    }

    jdbcTemplate.queryForObject("SELECT COUNT(*) FROM DOMAIN_EVENT_TEST", Long::class.java) shouldBeEqualTo 1L
    aggregate.domainEvents().isEmpty().shouldBeTrue()
}

@Test
fun `rollback preserves events and rolls back persistence`() {
    val published = mutableListOf<TestEvent>()
    val publisher = ExposedAggregateEventPublisher(ApplicationEventPublisher { published += it as TestEvent })
    val aggregate = TestAggregate(TestId(2L)).apply { record(1) }

    transactionTemplate.executeWithoutResult { status ->
        jdbcTemplate.update("INSERT INTO DOMAIN_EVENT_TEST(ID) VALUES (?)", aggregate.id.value)
        publisher.publishAfterSave(aggregate)
        status.setRollbackOnly()
    }

    published shouldHaveSize 1
    jdbcTemplate.queryForObject("SELECT COUNT(*) FROM DOMAIN_EVENT_TEST", Long::class.java) shouldBeEqualTo 0L
    aggregate.domainEvents() shouldHaveSize 1
}

@Test
fun `event-bearing aggregate requires synchronization and an actual transaction`() {
    val publisher = ExposedAggregateEventPublisher(ApplicationEventPublisher {})
    val aggregate = TestAggregate(TestId(3L)).apply { record(1) }

    assertFailsWith<IllegalStateException> {
        publisher.publishAfterSave(aggregate)
    }

    aggregate.domainEvents() shouldHaveSize 1
}

@Test
fun `synchronization without an actual transaction is rejected`() {
    val publisher = ExposedAggregateEventPublisher(ApplicationEventPublisher {})
    val aggregate = TestAggregate(TestId(4L)).apply { record(1) }

    TransactionSynchronizationManager.initSynchronization()
    try {
        assertFailsWith<IllegalStateException> {
            publisher.publishAfterSave(aggregate)
        }
    } finally {
        TransactionSynchronizationManager.clearSynchronization()
    }

    aggregate.domainEvents() shouldHaveSize 1
}
```

The rollback test intentionally observes the immediate Spring handoff through the recording publisher.

- [ ] **Step 3: Add the real `AFTER_COMMIT` listener test before implementation**

Add a nested `@Configuration(proxyBeanMethods = false)` plus `@EnableTransactionManagement` that defines a unique H2 `EmbeddedDatabase`, `DataSourceTransactionManager`, `TransactionTemplate`, and an `AfterCommitListener` bean with `@TransactionalEventListener`. Use a refreshed context explicitly:

```kotlin
AnnotationConfigApplicationContext(ListenerTestConfiguration::class.java).use { context ->
    val transactionTemplate = context.getBean(TransactionTemplate::class.java)
    val listener = context.getBean(AfterCommitListener::class.java)
    val publisher = ExposedAggregateEventPublisher(context)
    // Commit case: listener size is 0 inside and 1 after return; buffer clears.
    // Rollback case in a fresh context: listener remains 0; buffer remains.
}
```

Keep commit and rollback in independent tests with fresh contexts/databases. The context itself is the `ApplicationEventPublisher`; do not call `getBean(ApplicationEventPublisher::class.java)`. Every context is closed by `use`.

- [ ] **Step 4: Run the RED test**

Run:

```bash
./gradlew :bluetape4k-exposed-spring-boot-jdbc:test \
  --tests 'io.bluetape4k.spring.data.exposed.jdbc.ddd.ExposedAggregateEventPublisherTest' \
  --no-configuration-cache --no-daemon --console=plain
```

Expected: FAIL at Kotlin compilation because `ExposedAggregateEventPublisher` does not exist, including the real `AFTER_COMMIT` tests.

Do not commit RED-only state.

## Task 3: Implement The Minimal Transaction-Safe Publisher

complexity: high
depends_on: Task 2 RED evidence
applies: `test-driven-development`, `bluetape4k-code-patterns`, `ecc-kotlin-exposed`

**Files:**
- Create: `spring-boot/jdbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/jdbc/ddd/ExposedAggregateEventPublisher.kt`
- Modify: `spring-boot/jdbc/src/test/kotlin/io/bluetape4k/spring/data/exposed/jdbc/ddd/ExposedAggregateEventPublisherTest.kt`

- [ ] **Step 1: Add only the minimal public publisher and per-call completion synchronization**

Implement this shape in one focused file:

```kotlin
class ExposedAggregateEventPublisher(
    private val applicationEventPublisher: ApplicationEventPublisher,
) {
    fun <ID : Any> publishAfterSave(aggregate: AggregateRoot<ID>) {
        val events = aggregate.domainEvents()
        if (events.isEmpty()) return

        check(TransactionSynchronizationManager.isSynchronizationActive()) {
            "Domain event handoff requires active transaction synchronization"
        }
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "Domain event handoff requires an actual active transaction"
        }

        TransactionSynchronizationManager.registerSynchronization(
            MinimalAggregateCompletionSynchronization(aggregate)
        )
        events.forEach(applicationEventPublisher::publishEvent)
    }
}
```

The internal implementation must use:

```kotlin
private class MinimalAggregateCompletionSynchronization(
    private val aggregate: AggregateRoot<*>,
) : TransactionSynchronization {
    override fun afterCompletion(status: Int) {
        if (status == TransactionSynchronization.STATUS_COMMITTED) {
            aggregate.clearDomainEvents()
        }
    }
}
```

This Task 3 implementation intentionally supports only the RED cases from Task 2: empty no-op, active-transaction checks, immediate ordered handoff, commit clear, rollback preserve, and real default `AFTER_COMMIT` timing. Do not add identity reservation, synchronization reuse, mutation verification, poison state, completion logging, correlation capture, or final lifecycle KDoc yet; those changes belong to Task 4 after its RED evidence.

Add only a one-line English summary KDoc in Task 3. Task 4 replaces it with the final contract and executable example.

```kotlin
/** Hands aggregate domain events to Spring inside the current command transaction. */
```

- [ ] **Step 2: Run GREEN verification**

Run the same focused test command from Task 2.

Expected: PASS for empty, commit, rollback, ordering, active-transaction, and real `AFTER_COMMIT` listener tests.

- [ ] **Step 3: Commit the minimal lifecycle implementation**

Run `git diff --check`, then commit only the publisher and its test with a Lore message beginning:

```text
feat: hand aggregate events to Spring transactions
```

Rollback point: reverting this commit removes the new API without affecting auto-configuration or the example.

## Task 4: Harden Identity, Poison, Completion, And Logging Semantics

complexity: high
depends_on: Task 3
applies: `test-driven-development`, `bluetape4k-code-patterns`, `ecc-kotlin-testing`

**Files:**
- Modify: `spring-boot/jdbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/jdbc/ddd/ExposedAggregateEventPublisher.kt`
- Modify: `spring-boot/jdbc/src/test/kotlin/io/bluetape4k/spring/data/exposed/jdbc/ddd/ExposedAggregateEventPublisherTest.kt`

- [ ] **Step 1: Add RED lifecycle and fail-closed tests**

Add tests for each approved case:

```text
same aggregate registered twice -> second call throws, publishes no second snapshot, beforeCommit rejects commit
manual clear or drain after registration followed by second call -> reserved identity rejects before empty no-op
new event recorded after registration -> beforeCommit rejects by size/reference mismatch and buffer remains
ApplicationEventPublisher throws -> original exception propagates and caught exception still cannot allow commit
ApplicationEventPublisher throws AssertionError -> rethrown object is the original by ===; caller catches it, beforeCommit still rejects commit, persistence rolls back, buffer remains
ApplicationEventPublisher throws on the second event -> the first synchronous handoff is observable, commit still fails, buffer remains
synchronous listener re-enters with same aggregate -> identity was already reserved, no recursion, commit poisoned
throwing default AFTER_COMMIT listener -> command callback count 1, listener count 1, transaction call returns without retry, row count 1, buffer empty
three pre-existing ordered sentinel synchronizations plus multiple aggregates -> exactly one publisher synchronization, reused for all aggregates
snapshot instrumentation -> internal same-module accessor returns the exact domainEvents result object by identity; no publisher-side toList/map/filter/sorted copy
normal counting aggregate -> domainEvents call count is 1 after registration and 2 after beforeCommit
duplicate call -> domainEvents call count remains 1 and no second publication occurs
two aggregates in sentinel test -> each domainEvents count follows 1 after registration to 2 after beforeCommit
commit then same-thread next transaction -> new synchronization, no duplicate reservation, new event commits
rollback then same-thread next transaction -> retained aggregate registers with a new synchronization and commits
inner REQUIRES_NEW commit plus outer rollback -> inner row commits/buffer clears; outer row rolls back/buffer remains
inner REQUIRES_NEW rollback plus outer commit -> inner row absent/buffer remains; outer row commits/buffer clears
both REQUIRES_NEW cases -> inner synchronization differs from suspended outer synchronization and the original outer synchronization is restored
same aggregate instance across overlapping REQUIRES_NEW -> documented unsupported; no supporting test path is added
```

Use counting/throwing aggregate subclasses in the same test file. Poison and mutation tests use `assertFailsWith<IllegalStateException>` around `TransactionTemplate.executeWithoutResult`; Spring propagates the synchronization's stable `IllegalStateException` from `beforeCommit`.

Keep `Registration` and a read-only `internal fun retainedSnapshotForTest(aggregate: AggregateRoot<*>): List<*>?` accessor internal to the JDBC module. The snapshot-retention test compares the accessor result to the aggregate's instrumented snapshot with `===`; no public testing hook is added.

- [ ] **Step 2: Add RED completion and structured-log tests**

Attach a Logback `ListAppender<ILoggingEvent>` to the publisher implementation logger and cover:

```text
STATUS_COMMITTED + clear failure -> other aggregates still clear, one aggregate-event-cleanup-failed error row
STATUS_UNKNOWN -> no aggregate clears, one aggregate-event-completion-unknown row per registration
rollback -> no clear and no completion anomaly row
registration-time traceId/spanId/requestId retained even if caller MDC changes before completion
empty value rejected; exactly 128 allowed characters accepted; 129 rejected
newline, tab, control, Unicode, whitespace, and disallowed punctuation rejected
traceId, spanId, and requestId each accepted independently; every non-allowlisted key excluded
log MDC contains only category, aggregateType, eventType, eventCount, and valid correlation keys even when completion-time MDC contains arbitrary values
captured ILoggingEvent has null throwableProxy, absent arguments and markers, and only approved key/value fields
message, formatted message, MDC, arguments, key/value fields, and throwable proxy exclude payload, aggregate ID, throwable message, raw headers, arbitrary MDC, secret markers, and PII markers
caller MDC is restored after each anomaly log
```

Define `eventType` as the recording-order, de-duplicated, comma-separated set of fully qualified event class names for one aggregate registration. Build it lazily with a `LinkedHashSet` in one O(E) pass only when an anomaly row is emitted; do not sort it. `eventCount` is the retained snapshot size and `aggregateType` is the aggregate's fully qualified class name. Normal publication, commit, and rollback must not allocate or traverse event-type metadata.

- [ ] **Step 3: Run the RED lifecycle and logging tests**

Run the focused Task 4 test class before changing production code:

```bash
./gradlew :bluetape4k-exposed-spring-boot-jdbc:test \
  --tests 'io.bluetape4k.spring.data.exposed.jdbc.ddd.ExposedAggregateEventPublisherTest' \
  --no-configuration-cache --no-daemon --console=plain
```

Expected: targeted failures prove duplicate/mutation checks, caught `Exception` and caught `AssertionError` poisoning/identity, exact `1 -> 2` snapshot counts, `REQUIRES_NEW` isolation, completion logging, correlation validation, sentinel synchronization reuse, and snapshot retention are not yet implemented. Record the failing test names; do not accept a compile-only or already-green result without explaining which earlier task supplied the behavior.

- [ ] **Step 4: Implement identity reservation and poison behavior**

Use this exact operation order:

```text
if synchronization active -> find current owner synchronization -> reject reserved identity
obtain domainEvents snapshot -> return if empty
require synchronization active -> require actual transaction active
find or register owner synchronization -> reject reserved identity again
capture allowlisted correlation -> reserve identity -> publish events in order
publication Throwable -> poison -> rethrow the same instance
beforeCommit -> throw stored poison first -> verify every snapshot by size and element identity
```

`rejectReserved` stores a stable poison reason before throwing. No repeated call may invoke `domainEvents()` again. The synchronization lookup scans only `TransactionSynchronizationManager.getSynchronizations()` and compares publisher owners with `===`; do not call `bindResource` or keep a bean-global map.

Replace Task 3's minimal synchronization with one `internal AggregateEventTransactionSynchronization` per publisher/current transaction. It owns an `IdentityHashMap<AggregateRoot<*>, Registration>`, poison state, and completion cleanup. `Registration` retains the exact immutable snapshot object, aggregate reference/class, verification/clear lambdas, and registration-time correlation without a publisher copy. Keep a read-only internal snapshot accessor only for same-module identity tests.

`currentSynchronization()` must call `getSynchronizations()` zero times when synchronization is inactive and exactly once when active; cache that returned list and scan it outside all event/registration loops. The sentinel test proves this bounded scan while source inspection confirms the cached operation order.

Replace the temporary KDoc with the final English contract. It must include same-transaction save/handoff, empty no-op, active-transaction checks, immediate synchronous versus default `AFTER_COMMIT` timing, commit clear/rollback preserve, poison and one-final-call rules, immutable event references, unsupported NESTED/savepoint and same-instance overlapping `REQUIRES_NEW`, listener write `REQUIRES_NEW`, and `@throws IllegalStateException`. Include this executable usage block:

```kotlin
/**
 * Publishes an aggregate's immutable domain-event snapshot inside the current command transaction.
 *
 * Save the aggregate and call this method exactly once in the same active transaction:
 * ```kotlin
 * transactionTemplate.executeWithoutResult {
 *     orderRepository.save(order)
 *     aggregateEventPublisher.publishAfterSave(order)
 * }
 * ```
 * Empty aggregates are a no-op. Synchronous listeners run immediately; default `AFTER_COMMIT` listeners run
 * only after commit. Committed completion clears the registered buffer, while rollback or unknown completion
 * preserves it. Publication, duplicate registration, or snapshot mutation poisons the transaction even when
 * caller code catches the immediate failure. Event instances and payloads must remain deeply immutable.
 *
 * `PROPAGATION_NESTED`/savepoint rollback and same-instance reuse across overlapping `REQUIRES_NEW` transactions
 * are unsupported. Listener database writes after commit require a new transaction.
 *
 * @throws IllegalStateException when the transaction, identity, or snapshot lifecycle contract is violated.
 */
```

The executable block inside that KDoc is:

```kotlin
transactionTemplate.executeWithoutResult {
    orderRepository.save(order)
    aggregateEventPublisher.publishAfterSave(order)
}
```

KDoc must distinguish failures: a synchronous publication failure is rethrown immediately as the same `Throwable`; if caller code catches a lifecycle/publication failure, the stored poison causes a stable `IllegalStateException` from `beforeCommit` so commit still fails.

- [ ] **Step 5: Implement bounded correlation and completion logging**

Capture only these keys at registration:

```kotlin
private val correlationKeys = listOf("traceId", "spanId", "requestId")
private val safeCorrelation = Regex("[A-Za-z0-9._:-]{1,128}")
```

Use `MDC.get(key)?.takeIf(safeCorrelation::matches)`. Do not use `errorMdc` for anomaly rows because it preserves unrelated completion-time MDC. Implement one private logging helper with this isolation contract:

```kotlin
private inline fun withSanitizedMdc(
    fields: Map<String, String>,
    block: () -> Unit,
) {
    val previous = MDC.getCopyOfContextMap()
    try {
        MDC.clear()
        fields.forEach(MDC::put)
        block()
    } finally {
        MDC.clear()
        if (previous != null) {
            MDC.setContextMap(previous)
        }
    }
}
```

Inside that scope, call plain `logger.error(...)` with a stable category-only message. Do not pass the caught clear exception or its message/cause to the logger. Tests must prove arbitrary completion-time MDC is absent from the captured row and restored after logging. Start each `ListAppender` before use, then detach and stop it in `finally`; inspect the complete `ILoggingEvent`, not only its rendered message. Event metadata derivation must be reachable only from the two anomaly branches.

`afterCompletion` must:

```text
COMMITTED -> attempt every clear independently; log each failure; always discard registry
UNKNOWN -> log every registration; preserve buffers; always discard registry
ROLLED_BACK or other known non-commit -> preserve buffers; always discard registry
```

- [ ] **Step 6: Run focused and module tests**

Run:

```bash
./gradlew :bluetape4k-exposed-spring-boot-jdbc:test \
  --tests 'io.bluetape4k.spring.data.exposed.jdbc.ddd.ExposedAggregateEventPublisherTest' \
  --no-configuration-cache --no-daemon --console=plain --rerun-tasks
./gradlew :bluetape4k-exposed-spring-boot-jdbc:test \
  --no-configuration-cache --no-daemon --console=plain
```

Expected: both commands PASS, including original-Throwable identity and exact normal/duplicate/sentinel snapshot counts, with no sleeps, ad hoc threads, Testcontainers, or shared aggregate instances across concurrent commands.

- [ ] **Step 7: Commit the hardened lifecycle**

Run `git diff --check`, then commit the two Task 4 files with a Lore message beginning:

```text
test: harden aggregate event transaction lifecycle
```

Rollback point: revert Task 4 and Task 3 together if the synchronization contract proves incompatible; do not retain a partial publisher that clears early or permits caught publication failures to commit.

## Task 5: Repair Manager Selection And Add Guarded Auto-Configuration

complexity: medium
depends_on: Task 4
applies: `test-driven-development`, `bluetape4k-code-patterns`, `ecc-springboot-kotlin`, `ecc-kotlin-testing`

**Files:**
- Create: `spring-boot/jdbc/src/test/kotlin/io/bluetape4k/spring/data/exposed/jdbc/config/ExposedAggregateEventPublisherAutoConfigurationTest.kt`
- Create: `spring-boot/jdbc/src/test/kotlin/io/bluetape4k/spring/data/exposed/jdbc/ddd/MultiManagerDocumentationExample.kt`
- Create: `spring-boot/jdbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/jdbc/config/ExposedAggregateEventPublisherAutoConfiguration.kt`
- Modify: `spring-boot/jdbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/jdbc/repository/config/ExposedJdbcRepositoryConfigurationExtension.kt`
- Modify: `spring-boot/jdbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/jdbc/repository/support/SimpleExposedJdbcRepository.kt`
- Modify: `spring-boot/jdbc/src/test/kotlin/io/bluetape4k/spring/data/exposed/jdbc/config/ExposedSpringDataAutoConfigurationTest.kt`
- Modify: `spring-boot/jdbc/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

- [ ] **Step 1: Write RED manager-selection tests against existing production code**

Create a self-contained `MultiManagerDocumentationExample.kt` without referencing the not-yet-created publisher auto-configuration. It defines `Orders : LongIdTable`, `OrderEntity : LongEntity`, `OrderRepository : ExposedJdbcRepository<OrderEntity, Long>`, the aggregate/event, two distinguishable H2 stores, two Exposed `SpringTransactionManager` beans, and an explicit `ExposedAggregateEventPublisher` because the two managers intentionally have no single autowire candidate.

Add two independent RED proofs before production edits:

1. In `ExposedSpringDataAutoConfigurationTest`, inspect the generated repository factory bean definition and assert its `transactionManager` property is `secondTransactionManager` when `@EnableExposedJdbcRepositories(transactionManagerRef = "secondTransactionManager")` is used.
2. In `MultiManagerDocumentationExampleTest`, seed different row counts in the first and second stores, call `repository.count()` outside an explicit transaction, and assert the result matches only the second store. Then call `repository.deleteAll()` through the proxy and assert only the second store changed. `save()` is not manager-selection evidence because `OrderEntity.from(...)` executes before the proxy and `save()` returns the existing DAO entity.

The fixture also contains the production-shaped command example:

```kotlin
@Configuration(proxyBeanMethods = false)
@EnableExposedJdbcRepositories(
    basePackageClasses = [OrderRepository::class],
    transactionManagerRef = "secondTransactionManager",
)
class SecondOrderStoreConfiguration {
    @Bean("secondTransactionManager")
    fun secondTransactionManager(
        @Qualifier("secondDataSource") dataSource: DataSource,
    ): PlatformTransactionManager = SpringTransactionManager(dataSource, DatabaseConfig {}, false)

    @Bean("secondTransactionTemplate")
    fun secondTransactionTemplate(
        @Qualifier("secondTransactionManager") manager: PlatformTransactionManager,
    ): TransactionTemplate = TransactionTemplate(manager)
}

class OrderCommandService(
    private val repository: OrderRepository,
    private val aggregateEventPublisher: ExposedAggregateEventPublisher,
    @Qualifier("secondTransactionTemplate") private val transactionTemplate: TransactionTemplate,
) {
    fun save(aggregate: OrderAggregate, rollback: Boolean = false) {
        transactionTemplate.executeWithoutResult { status ->
            OrderEntity.from(aggregate)
            aggregateEventPublisher.publishAfterSave(aggregate)
            if (rollback) status.setRollbackOnly()
        }
    }
}
```

The successful command case proves only the second store commits and the buffer clears. The rollback case proves the second write disappears and the buffer remains. Delimit the production-shaped configuration/service region with `// issue-323-multi-manager:start/end`; Task 7 copies and compares that exact region. Close contexts and databases in `finally`.

- [ ] **Step 2: Run the behavioral manager-selection RED alone**

```bash
./gradlew :bluetape4k-exposed-spring-boot-jdbc:test \
  --tests 'io.bluetape4k.spring.data.exposed.jdbc.config.ExposedSpringDataAutoConfigurationTest' \
  --tests 'io.bluetape4k.spring.data.exposed.jdbc.ddd.MultiManagerDocumentationExampleTest' \
  --no-configuration-cache --no-daemon --console=plain
```

Expected: Kotlin compilation succeeds, then the bean-definition property assertion and/or proxy `count()`/`deleteAll()` store-selection assertion fails because the existing annotation value is not forwarded and the base repository hard-codes `springTransactionManager`. Record the exact failing assertion; a compile failure is not acceptable RED evidence for this step.

- [ ] **Step 3: Make only the manager-selection tests GREEN**

- Override the annotation-source post-processing hook in `ExposedJdbcRepositoryConfigurationExtension` and forward `transactionManagerRef` to the `transactionManager` property of `ExposedJdbcRepositoryFactoryBean`, following the Spring Data JDBC/JPA extension pattern.
- Remove explicit `transactionManager = EXPOSED_TRANSACTION_MANAGER` qualifiers from `SimpleExposedJdbcRepository` transaction annotations so the factory-selected manager governs proxy operations; retain read-only/write semantics and the annotation default `springTransactionManager`. Remove or relocate the constant if it otherwise becomes unused.
- Update English KDoc for the touched public configuration surface to state that `transactionManagerRef` controls the repository proxy.

Run the exact Step 2 command again. Expected: PASS, with `count()` and `deleteAll()` proving the second manager against distinguishable stores. This repairs existing declared behavior required by the approved spec; if it needs a broader repository redesign, stop and return to the approved design.

- [ ] **Step 4: Write RED `ApplicationContextRunner` auto-configuration coverage**

Only after manager-selection GREEN, create `ExposedAggregateEventPublisherAutoConfigurationTest` using `ApplicationContextRunner` plus `AutoConfigurations.of(ExposedAggregateEventPublisherAutoConfiguration::class.java)` for this matrix:

```text
no PlatformTransactionManager -> no publisher bean
one manager -> one publisher bean
two managers without @Primary -> no publisher bean
two managers with one @Primary -> one publisher bean
application-provided publisher -> auto-configuration backs off
FilteredClassLoader("org.springframework.modulith") -> publisher still created with one manager
FilteredClassLoader(AggregateRoot::class.java) -> context starts and no publisher bean exists
FilteredClassLoader(TransactionSynchronizationManager::class.java) -> context starts and no publisher bean exists
ApplicationEventPublisher class condition -> verify ConditionalOnClass annotation metadata contains the exact class
```

Use H2 `DataSourceTransactionManager` beans only as condition candidates; repository operations continue to use Exposed `SpringTransactionManager`. Add an ordering test that loads `ExposedSpringDataAutoConfiguration` before the new auto-configuration and asserts the default manager and publisher beans exist.

- [ ] **Step 5: Run the isolated auto-configuration RED**

```bash
./gradlew :bluetape4k-exposed-spring-boot-jdbc:test \
  --tests 'io.bluetape4k.spring.data.exposed.jdbc.config.ExposedAggregateEventPublisherAutoConfigurationTest' \
  --no-configuration-cache --no-daemon --console=plain
```

Expected: FAIL at Kotlin compilation only because `ExposedAggregateEventPublisherAutoConfiguration` does not exist. The manager-selection suite is already GREEN and is not hidden behind this compile failure.

- [ ] **Step 6: Implement and register the publisher auto-configuration**

Create the class with `@AutoConfiguration(after = [ExposedSpringDataAutoConfiguration::class])`, class conditions for `AggregateRoot`, `ApplicationEventPublisher`, and `TransactionSynchronizationManager`, `@ConditionalOnSingleCandidate(PlatformTransactionManager::class)`, and a missing-bean guarded `ExposedAggregateEventPublisher` bean. The bean injects only `ApplicationEventPublisher`; it never identifies a manager. Register it immediately after `ExposedSpringDataAutoConfiguration` in `AutoConfiguration.imports`.

Add English KDoc explaining single autowire-candidate semantics, including multiple managers with one `@Primary`, and caller responsibility for matching repository and command boundaries to the active transaction.

- [ ] **Step 7: Run combined GREEN tests and commit**

```bash
./gradlew :bluetape4k-exposed-spring-boot-jdbc:test \
  --tests 'io.bluetape4k.spring.data.exposed.jdbc.config.ExposedAggregateEventPublisherAutoConfigurationTest' \
  --tests 'io.bluetape4k.spring.data.exposed.jdbc.config.ExposedSpringDataAutoConfigurationTest' \
  --tests 'io.bluetape4k.spring.data.exposed.jdbc.ddd.MultiManagerDocumentationExampleTest' \
  --tests 'io.bluetape4k.spring.data.exposed.jdbc.ddd.ExposedAggregateEventPublisherTest' \
  --no-configuration-cache --no-daemon --console=plain
git diff --check
```

Expected: PASS. Commit Task 5 files with a Lore message beginning:

```text
feat: auto-configure aggregate event publisher safely
```

Rollback point: the publisher auto-configuration can revert while manual construction remains supported, but the `transactionManagerRef` repair and regression tests move or revert together so the public annotation never claims unimplemented behavior.

## Task 6: Migrate The DDD Spring Modulith Example

complexity: medium
depends_on: Task 5
applies: `test-driven-development`, `bluetape4k-code-patterns`, `ecc-kotlin-exposed`, `ecc-springboot-kotlin`, `ecc-kotlin-testing`

**Files:**
- Modify: `examples/ddd-spring-modulith-demo/src/main/kotlin/io/bluetape4k/exposed/examples/modulith/orders/OrderApplicationService.kt`
- Modify: `examples/ddd-spring-modulith-demo/src/main/kotlin/io/bluetape4k/exposed/examples/modulith/orders/OrderDomain.kt`
- Modify: `examples/ddd-spring-modulith-demo/src/test/kotlin/io/bluetape4k/exposed/examples/modulith/DddSpringModulithDemoApplicationTest.kt`

- [ ] **Step 1: Update tests first**

Change the existing successful-command test to assert:

```kotlin
accepted.domainEvents().isEmpty().shouldBeTrue()
```

Keep the existing persistence, completed publication, and reservation assertions. Update the failed-handoff fixture to construct:

```kotlin
aggregateEventPublisher = ExposedAggregateEventPublisher(
    ApplicationEventPublisher {
        throw IllegalStateException("Synthetic publication handoff failure")
    }
)
```

Add a serializer trust-boundary integration test that exercises actual Spring/Modulith publication, not only a direct serializer call:

```kotlin
@Test
fun `serializer rejects unsupported event type without exposing payload`() {
    withApplicationContext() { context ->
        val secret = "UNSUPPORTED-SECRET-MUST-NOT-LEAK"
        val logCapture = attachRootAndNonAdditiveLoggers()

        try {
            val error = assertFailsWith<IllegalArgumentException> {
                transactionTemplate.executeWithoutResult {
                    context.publishEvent(UnsupportedEvent(secret))
                }
            }
            error.assertNoSecretInMessageOrCauseChain(secret)
            publicationRepository.count() shouldBeEqualTo 0L
            logCapture.allEvents().forEach { event ->
                event.assertNoSecretInAnyField(secret)
            }
        } finally {
            logCapture.detachStopAndRestoreLoggerState()
        }
    }
}

private data class UnsupportedEvent(val secret: String) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
```

Attach a started `ListAppender` to the Logback root logger for the test duration. Discover any non-additive logger in the active context, attach there too, and restore its original additivity/appender state in `finally`; do not assume the publication repository has its own logger. Use concrete helpers to inspect every captured `ILoggingEvent` field: message, formatted message, MDC, argument array, key/value pairs, marker list, and the full throwable/cause chain. Inspect the caught exception's message/cause chain as well. Require zero secret occurrences and zero publication rows. Keep a direct serializer rejection assertion only as supplemental unit coverage.

The existing rollback, sensitive-payload, restart replay, module-boundary, and idempotency tests remain mandatory.

- [ ] **Step 2: Run RED example tests**

Run:

```bash
./gradlew :examples-ddd-spring-modulith-demo:test \
  --no-configuration-cache --no-daemon --console=plain
```

Expected: Kotlin compilation fails at the updated failed-handoff fixture/service call site because the existing `OrderApplicationService` constructor still expects `ApplicationEventPublisher` rather than `ExposedAggregateEventPublisher`. Do not claim the post-success empty-buffer assertion as RED evidence: the legacy manual clear already satisfies that assertion. Lifecycle behavior becomes GREEN evidence only after the manual path is removed.

- [ ] **Step 3: Replace the manual loop with the public bridge**

Change the service constructor and transaction body to:

```kotlin
class OrderApplicationService(
    private val orderRepository: OrderRepository,
    private val aggregateEventPublisher: ExposedAggregateEventPublisher,
    private val transactionTemplate: TransactionTemplate,
) {
    fun accept(command: AcceptOrderCommand, failAfterPublish: Boolean = false): Order {
        val order = Order.accept(command)
        return try {
            transactionTemplate.execute {
                orderRepository.save(order)
                aggregateEventPublisher.publishAfterSave(order)
                if (failAfterPublish) {
                    throw IllegalStateException("Failing after event publication for rollback verification")
                }
                order
            } ?: error("Order transaction returned no aggregate")
        } catch (e: Exception) {
            if (order.domainEvents().isNotEmpty()) {
                throw OrderHandoffFailedException(order, e)
            }
            throw e
        }
    }
}
```

Remove both the manual `domainEvents().forEach` publication loop and the post-transaction `clearDomainEvents()` call.

Change `OrderHandoffFailedException` to use the stable message `order-event-handoff-failed`; retain the aggregate property and cause for direct caller inspection, but never include aggregate ID or nested exception text in its message.

- [ ] **Step 4: Run GREEN example and cross-module tests**

Run:

```bash
./gradlew \
  :bluetape4k-exposed-spring-boot-jdbc:test \
  :bluetape4k-exposed-spring-modulith:test \
  :examples-ddd-spring-modulith-demo:test \
  --no-configuration-cache --no-daemon --console=plain
! rg -n 'domainEvents\(\)\.forEach|saved\.clearDomainEvents\(\)|eventPublisher\.publishEvent' \
  examples/ddd-spring-modulith-demo/src/main
```

Expected: Gradle PASS; the source search is run as `! rg` and returns no matches. A rollback retains the aggregate buffer while leaving no order, listener side effect, or publication row.

- [ ] **Step 5: Commit the example migration**

Run `git diff --check`, then commit the three example files with a Lore message beginning:

```text
refactor: adopt transaction-aware event handoff in DDD demo
```

Rollback point: rollback must restore the complete old manual loop and manual clear together; never run old and new paths in one application instance.

## Task 7: Update README Locales, Changelog, And Lifecycle Diagram

complexity: medium
depends_on: Task 6
applies: `bluetape4k-maintenance`, `bluetape4k-blog`, `bluetape4k-diagram`

**Files:**
- Modify: `spring-boot/jdbc/README.md`
- Modify: `spring-boot/jdbc/README.ko.md`
- Modify: `spring-boot/spring-modulith/README.md`
- Modify: `spring-boot/spring-modulith/README.ko.md`
- Modify: `examples/ddd-spring-modulith-demo/README.md`
- Modify: `examples/ddd-spring-modulith-demo/README.ko.md`
- Modify: `CHANGELOG.md`
- Create: `docs/images/readme-diagrams/spring-boot-exposed-jdbc-domain-event-sequence-01.svg`
- Create: `docs/images/readme-diagrams/spring-boot-exposed-jdbc-domain-event-sequence-01.png`

- [ ] **Step 1: Add source-equivalent English/Korean JDBC documentation**

Wrap every issue-owned README section in `<!-- issue-323-section:start -->` / `<!-- issue-323-section:end -->`. Add `<a id="transaction-aware-domain-events"></a>` immediately before the JDBC locale headings `Transaction-Aware Domain Events` / `트랜잭션 인식 도메인 이벤트` so cross-locale links use one stable anchor. Cover:

```text
dependency and auto-configuration conditions
save -> publishAfterSave inside the same command transaction
empty no-op versus event-bearing active-transaction failure
immediate Spring handoff versus default AFTER_COMMIT listener execution
commit clear, rollback preserve, poison, duplicate, mutation, and synchronous listener behavior
plain Spring Boot versus optional Spring Modulith
deeply immutable events and stable object references
single final call, unsupported NESTED/savepoint, unsupported same-instance overlapping REQUIRES_NEW
multi-manager @Primary semantics plus an executable @Qualifier/transactionManagerRef example
listener database writes requiring REQUIRES_NEW
serializer/publication-store trust boundary, idempotent consumers, and no outbox/exactly-once claim
outcome/retry table, anomaly categories, allowlisted correlation, and four-state reconciliation
replacement-only migration and binary rollback after evidence preservation/repair
R2DBC exclusion
JaVers audit/history boundary
```

Wrap the outcome table in `<!-- issue-323-outcome-table:start -->` / `<!-- issue-323-outcome-table:end -->`. The JDBC locale pair must reproduce these five outcome rows with the same persistence/buffer/retry decisions:

| Outcome | Persistence | Buffer | Command retry |
|---|---|---|---|
| No active transaction or same-transaction precondition violation | Indeterminate | Preserved | No automatic retry; reconcile first |
| Full rollback or poisoned handoff | Rolled back | Preserved | Allowed only in a fresh transaction; synchronous side effects may need deduplication |
| Committed listener failure | Committed | Cleared | Never retry command; use listener retry/replay |
| Committed cleanup failure | Committed | May remain | Never retry; discard aggregate instance |
| `STATUS_UNKNOWN` | Indeterminate | Preserved | No automatic retry; reconcile first |

Wrap reconciliation in `<!-- issue-323-reconciliation:start -->` / `<!-- issue-323-reconciliation:end -->`. Prefix each translated bullet with the matching stable semantic marker shown below so state/action mapping and order can be checked independently from prose. It must reproduce these four states and actions without changing retry semantics:

```text
<!-- issue-323-reconciliation:state=present-present;action=listener-recovery;command-retry=false -->
persistence present + publication present -> do not replay command; use Modulith replay or listener recovery
<!-- issue-323-reconciliation:state=present-absent;action=idempotent-repair;command-retry=false -->
persistence present + publication absent -> do not replay command; run application-owned idempotent repair from persisted state
<!-- issue-323-reconciliation:state=absent-absent;action=fresh-command-after-side-effect-check;command-retry=conditional -->
persistence absent + publication absent -> retry only as a new command after ruling out irreversible synchronous side effects
<!-- issue-323-reconciliation:state=absent-present;action=quarantine-and-compensate;command-retry=false -->
persistence absent + publication present -> quarantine invariant breach and compensate manually; replay neither path
```

Add a `Production rollout checklist` / `프로덕션 롤아웃 체크리스트` naming the application owner and requiring, before canary: alerts for both anomaly categories, at least one propagated allowlisted correlation field, audit/trace-to-persistence-key lookup, database and publication-table read access, and a canary proving one persisted aggregate, one durable publication, one listener side effect, and zero anomaly rows. Prefix the four failure steps with `<!-- issue-323-rollout:01-stop -->`, `<!-- issue-323-rollout:02-preserve -->`, `<!-- issue-323-rollout:03-reconcile-repair -->`, and `<!-- issue-323-rollout:04-binary-rollback-version-defect-only -->`. The order is exact: `stop rollout -> preserve logs/records -> reconcile and repair canary -> full binary rollback only for a version defect`.

Add the no-correlation recovery rule verbatim in meaning: if no allowlisted correlation field is present, quarantine the affected time window, use application audit records, and forbid automatic repair.

Both JDBC README locales and both example README locales must state the publication-store controls explicitly: least-privilege database access, encryption at rest and in transit as application infrastructure permits, integrity protection, retention/deletion policy, payload minimization, and exposure of stored event class names. State that audit history, snapshot persistence, and JaVers commit semantics are forbidden dependencies of the new publisher.

Embed the new PNG from both locale files.

- [ ] **Step 2: Update example and Modulith README locale pairs**

Replace manual publication/clear instructions in the example README pair with `ExposedAggregateEventPublisher`. Copy the compiled multi-manager Kotlin example from Task 5 into both JDBC locale files without changing the code block, wrapped by `<!-- issue-323-multi-manager:start -->` / `<!-- issue-323-multi-manager:end -->`. Preserve restart replay, publication-table protection, idempotency, and serializer guidance. Add a short cross-link from the Spring Modulith README pair to the JDBC publisher section and example; do not duplicate the full lifecycle contract.

- [ ] **Step 3: Add the changelog entry**

Under `CHANGELOG.md` `Unreleased -> Added`, record issue #323 and the public JDBC publisher, guarded auto-configuration, committed cleanup, and example adoption. Do not edit stale release versions or `WIP.md` as part of this feature.

- [ ] **Step 4: Create and render one lifecycle sequence asset**

Open these full-size reference PNGs first:

```text
/Users/debop/work/bluetape4k/bluetape4k-wiki/docs/diagrams/best-practices/assets/sequence-workflow-sample.png
docs/images/readme-diagrams/spring-boot-exposed-spring-modulith-sequence-01.png
```

The new diagram must show aggregate, repository, publisher, Spring transaction, default transactional listener, optional Modulith, commit, rollback, and committed cleanup with visible numbered labels and transparent `alt` branches.

Run the one-asset loop:

```bash
xmllint --noout docs/images/readme-diagrams/spring-boot-exposed-jdbc-domain-event-sequence-01.svg
/Users/debop/.local/bin/cairosvg \
  docs/images/readme-diagrams/spring-boot-exposed-jdbc-domain-event-sequence-01.svg \
  -o docs/images/readme-diagrams/spring-boot-exposed-jdbc-domain-event-sequence-01.png -s 2
python3 /Users/debop/.codex/skills/bluetape4k-diagram/scripts/diagram-sequence-style-audit.py \
  docs/images/readme-diagrams/spring-boot-exposed-jdbc-domain-event-sequence-01.svg
python3 /Users/debop/.codex/skills/bluetape4k-diagram/scripts/diagram-connector-audit.py \
  docs/images/readme-diagrams/spring-boot-exposed-jdbc-domain-event-sequence-01.svg
python3 /Users/debop/.codex/skills/bluetape4k-diagram/scripts/diagram-geometry-audit.py --fail-diagonal \
  docs/images/readme-diagrams/spring-boot-exposed-jdbc-domain-event-sequence-01.svg
python3 /Users/debop/.codex/skills/bluetape4k-diagram/scripts/diagram-endpoint-audit.py \
  docs/images/readme-diagrams/spring-boot-exposed-jdbc-domain-event-sequence-01.svg
python3 /Users/debop/.codex/skills/bluetape4k-diagram/scripts/diagram-mixed-corner-audit.py \
  docs/images/readme-diagrams/spring-boot-exposed-jdbc-domain-event-sequence-01.svg
```

Inspect the PNG at full size after the final coordinate change and record dimensions, visible label count, branch transparency, marker-color parity, connector counts, and zero audit failures.

- [ ] **Step 5: Verify locale/source parity and commit**

Run:

```bash
for file in \
  spring-boot/jdbc/README.md spring-boot/jdbc/README.ko.md \
  spring-boot/spring-modulith/README.md spring-boot/spring-modulith/README.ko.md \
  examples/ddd-spring-modulith-demo/README.md examples/ddd-spring-modulith-demo/README.ko.md; do
  rg -q 'issue-323-section:start' "$file" || exit 1
  rg -q 'ExposedAggregateEventPublisher' "$file" || exit 1
done
for file in spring-boot/jdbc/README.md spring-boot/jdbc/README.ko.md; do
  for token in publishAfterSave AFTER_COMMIT STATUS_UNKNOWN REQUIRES_NEW PROPAGATION_NESTED JaVers; do
    rg -q "$token" "$file" || exit 1
  done
done
for file in examples/ddd-spring-modulith-demo/README.md examples/ddd-spring-modulith-demo/README.ko.md; do
  for token in publishAfterSave AFTER_COMMIT; do
    rg -q "$token" "$file" || exit 1
  done
done

sed -n '/\/\/ issue-323-multi-manager:start/,/\/\/ issue-323-multi-manager:end/p' \
  spring-boot/jdbc/src/test/kotlin/io/bluetape4k/spring/data/exposed/jdbc/ddd/MultiManagerDocumentationExample.kt \
  | sed '/\/\/ issue-323-multi-manager:/d' > /tmp/issue-323-multi-manager.source
for locale in README.md README.ko.md; do
  sed -n '/issue-323-multi-manager:start/,/issue-323-multi-manager:end/p' \
    "spring-boot/jdbc/$locale" \
    | sed '/issue-323-multi-manager:/d; /^```/d' > "/tmp/$locale.multi-manager"
  diff -u /tmp/issue-323-multi-manager.source "/tmp/$locale.multi-manager"
done
diff -u /tmp/README.md.multi-manager /tmp/README.ko.md.multi-manager

printf '%s\n' \
  'spring-boot/jdbc/README.md|spring-boot/jdbc/README.ko.md' \
  'spring-boot/spring-modulith/README.md|spring-boot/spring-modulith/README.ko.md' \
  'examples/ddd-spring-modulith-demo/README.md|examples/ddd-spring-modulith-demo/README.ko.md' \
  | while IFS='|' read -r english korean; do
  for file in "$english" "$korean"; do
    sed -n '/issue-323-section:start/,/issue-323-section:end/p' "$file" \
      | ruby -ne '$_.scan(/!?\[[^\]]*\]\(([^)]+)\)/) { |m| puts m[0].sub("README.ko.md", "README.md") }' \
      | sort > "/tmp/$(echo "$file" | tr / _).links"
  done
  diff -u "/tmp/$(echo "$english" | tr / _).links" "/tmp/$(echo "$korean" | tr / _).links"
done

ruby -e '
  files = ARGV
  files.each do |file|
    section = File.read(file)[/<!-- issue-323-section:start -->(.*?)<!-- issue-323-section:end -->/m, 1] or abort("missing issue section: #{file}")
    section.scan(/!?\[[^\]]*\]\(([^)]+)\)/).flatten.each do |link|
      next if link.match?(/\A(?:https?:|mailto:|#)/)
      path, anchor = link.split("#", 2)
      target = File.expand_path(path, File.dirname(file))
      abort("missing link target: #{file} -> #{link}") unless File.file?(target)
      if anchor
        body = File.read(target)
        abort("missing anchor: #{file} -> #{link}") unless body.include?(%Q{id="#{anchor}"})
      end
    end
  end
' spring-boot/jdbc/README.md spring-boot/jdbc/README.ko.md \
  spring-boot/spring-modulith/README.md spring-boot/spring-modulith/README.ko.md \
  examples/ddd-spring-modulith-demo/README.md examples/ddd-spring-modulith-demo/README.ko.md

test -s docs/images/readme-diagrams/spring-boot-exposed-jdbc-domain-event-sequence-01.svg
test -s docs/images/readme-diagrams/spring-boot-exposed-jdbc-domain-event-sequence-01.png
for file in spring-boot/jdbc/README.md spring-boot/jdbc/README.ko.md; do
  rg -Fq 'spring-boot-exposed-jdbc-domain-event-sequence-01.png' "$file" || exit 1
done

for file in spring-boot/jdbc/README.md spring-boot/jdbc/README.ko.md; do
  test "$(sed -n '/issue-323-outcome-table:start/,/issue-323-outcome-table:end/p' "$file" | rg -c '^\|' )" -eq 7
  test "$(sed -n '/issue-323-reconciliation:start/,/issue-323-reconciliation:end/p' "$file" | rg -c '^- ' )" -eq 4
  rg -q 'aggregate-event-cleanup-failed' "$file" || exit 1
  rg -q 'aggregate-event-completion-unknown' "$file" || exit 1
  rg -qi 'stop rollout|rollout.*stop|롤아웃.*중지' "$file" || exit 1
  rg -qi 'preserve|보존' "$file" || exit 1
  rg -qi 'reconcile|대조' "$file" || exit 1
  rg -qi 'repair|복구' "$file" || exit 1
  rg -qi 'binary rollback|바이너리 롤백' "$file" || exit 1
  rg -qi 'no allowlisted correlation|허용.*correlation.*없|허용.*상관.*없' "$file" || exit 1
  rg -qi 'quarantine|격리' "$file" || exit 1
  rg -qi 'audit|감사' "$file" || exit 1
  rg -qi 'automatic repair.*forbid|자동 복구.*금지' "$file" || exit 1
  rg -qi 'application owner|애플리케이션 소유자' "$file" || exit 1
  rg -qi 'database.*read access|데이터베이스.*읽기 권한' "$file" || exit 1
  rg -qi 'publication.*read access|publication.*읽기 권한|발행.*읽기 권한' "$file" || exit 1
  rg -qi 'one persisted aggregate|persisted aggregate.*1|영속.*aggregate.*1|영속.*애그리거트.*1' "$file" || exit 1
  rg -qi 'one durable publication|durable publication.*1|내구.*발행.*1' "$file" || exit 1
  rg -qi 'one listener side effect|listener side effect.*1|리스너 부수 효과.*1' "$file" || exit 1
  rg -qi 'zero anomaly|anomaly.*0|이상.*0' "$file" || exit 1
done

ruby -e '
  expected_reconciliation = [
    "<!-- issue-323-reconciliation:state=present-present;action=listener-recovery;command-retry=false -->",
    "<!-- issue-323-reconciliation:state=present-absent;action=idempotent-repair;command-retry=false -->",
    "<!-- issue-323-reconciliation:state=absent-absent;action=fresh-command-after-side-effect-check;command-retry=conditional -->",
    "<!-- issue-323-reconciliation:state=absent-present;action=quarantine-and-compensate;command-retry=false -->",
  ]
  expected_rollout = [
    "<!-- issue-323-rollout:01-stop -->",
    "<!-- issue-323-rollout:02-preserve -->",
    "<!-- issue-323-rollout:03-reconcile-repair -->",
    "<!-- issue-323-rollout:04-binary-rollback-version-defect-only -->",
  ]
  ARGV.each do |file|
    body = File.read(file)
    reconciliation = body.scan(/<!-- issue-323-reconciliation:(?!start|end)[^>]+-->/)
    rollout = body.scan(/<!-- issue-323-rollout:[^>]+-->/)
    abort("reconciliation order/mapping mismatch: #{file}") unless reconciliation == expected_reconciliation
    abort("rollout order mismatch: #{file}") unless rollout == expected_rollout
  end
' spring-boot/jdbc/README.md spring-boot/jdbc/README.ko.md

for file in \
  spring-boot/jdbc/README.md spring-boot/jdbc/README.ko.md \
  examples/ddd-spring-modulith-demo/README.md examples/ddd-spring-modulith-demo/README.ko.md; do
  rg -qi 'access control|least[- ]privilege|접근 제어|최소 권한' "$file" || exit 1
  rg -qi 'encryption at rest|encrypt.*at rest|저장.*암호화' "$file" || exit 1
  rg -qi 'encryption in transit|encrypt.*in transit|전송.*암호화' "$file" || exit 1
  rg -qi 'integrity|무결성' "$file" || exit 1
  rg -qi 'retention|deletion|보존|삭제' "$file" || exit 1
  rg -qi 'payload minimization|페이로드 최소화' "$file" || exit 1
  rg -qi 'event class name|stored class name|이벤트 클래스 이름' "$file" || exit 1
done
git diff --check
```

Expected: Kotlin blocks, link/image targets, five outcome rows, four reconciliation state/action mappings, rollout order, and every named publication-store control are source-equivalent across each locale pair; example and cross-links use the new API; diff check passes. Manually compare every translated issue-owned section, including table cells and prose adjacent to semantic markers, against the approved English decisions because token and marker checks cannot prove translation meaning.

Commit Task 7 files with a Lore message beginning:

```text
docs: explain aggregate event transaction lifecycle
```

Rollback point: SVG and PNG are one asset pair and must be reverted or retained together with the README embeds.

## Task 8: Run Final Implementation Verification And Risk Scan

complexity: high
depends_on: Tasks 1-7
applies: `verification-before-completion`, `bluetape4k-full-feature`, `bluetape4k-code-patterns`

**Files:**
- No source edits unless verification returns to the owning task.

- [ ] **Step 1: Inspect Kotlin impact and diagnostics**

Before final compilation, inspect references for every touched Kotlin public symbol with available IDE/LSP tools. CodeGraph was empty during planning; if still empty, record that gap and use exact `rg` import/call-site checks. Run IDE diagnostics when available and leave no unresolved deprecation warning in touched files.

- [ ] **Step 2: Run targeted compile and test commands sequentially**

Run:

```bash
./gradlew \
  :bluetape4k-exposed-core:test \
  :bluetape4k-exposed-spring-boot-jdbc:test \
  :bluetape4k-exposed-spring-modulith:test \
  :examples-ddd-spring-modulith-demo:test \
  --no-configuration-cache --no-daemon --console=plain

./gradlew \
  :bluetape4k-exposed-core:koverXmlReport \
  :bluetape4k-exposed-spring-boot-jdbc:koverXmlReport \
  :bluetape4k-exposed-spring-modulith:koverXmlReport \
  :examples-ddd-spring-modulith-demo:koverXmlReport \
  --no-configuration-cache --no-daemon --console=plain

test -s exposed/core/build/reports/kover/report.xml
test -s spring-boot/jdbc/build/reports/kover/report.xml
test -s spring-boot/spring-modulith/build/reports/kover/report.xml
test -s examples/ddd-spring-modulith-demo/build/reports/kover/report.xml
```

Expected: PASS with four non-empty Kover XML reports. These H2-backed checks run in one Gradle invocation; no Testcontainers jobs run in parallel. Existing CI `continue-on-error` report-only behavior is repository governance and is not changed by #323; missing local or PR coverage artifacts still block this issue's evidence gate.

- [ ] **Step 3: Run static boundary and workflow checks**

Run:

```bash
! rg -n 'import org\.springframework|import org\.springframework\.modulith|org\.javers' \
  exposed/core/src/main exposed/core/build.gradle.kts
! rg -n 'org\.javers|org\.springframework\.modulith' \
  spring-boot/jdbc/src/main spring-boot/jdbc/build.gradle.kts
./gradlew :bluetape4k-exposed-spring-boot-jdbc:dependencies --configuration compileClasspath \
  --no-configuration-cache --no-daemon --console=plain | tee /tmp/issue-323-jdbc-compile-classpath
! rg -n 'org\.javers|spring-modulith' /tmp/issue-323-jdbc-compile-classpath
! rg -n 'domainEvents\(\)\.forEach|saved\.clearDomainEvents\(\)|eventPublisher\.publishEvent' \
  examples/ddd-spring-modulith-demo/src/main
rg -n 'exposed/core/\*\*|spring-boot/\*\*|spring-boot/jdbc/\*\*|spring-boot/spring-modulith/\*\*|examples/\*\*|bluetape4k-exposed-core:(test|koverXmlReport)|examples-ddd-spring-modulith-demo:(test|koverXmlReport)|bluetape4k-exposed-spring-boot-jdbc:(test|koverXmlReport)|bluetape4k-exposed-spring-modulith:(test|koverXmlReport)|needs:|kover.*xml|coverage-' \
  .github/workflows/ci.yml .github/workflows/nightly-tests.yml
git diff --check
```

Expected: all forbidden-dependency and legacy-path searches return no matches. The JDBC compile classpath contains neither JaVers nor Spring Modulith. Exact workflow blocks prove CI path routes, Nightly full-scope jobs, corresponding test/Kover tasks, status/coverage `needs`, and coverage uploads; do not claim workflow coverage from a task-name-only search.

- [ ] **Step 4: Run the performance/stability scan**

Prove with source and tests:

```text
one immutable snapshot object retained per event-bearing aggregate; sentinel/snapshot-retention tests pass and no publisher copy exists
one beforeCommit snapshot and identity comparison per aggregate
getSynchronizations() zero times when synchronization is inactive and exactly once when active; cache and scan outside event/registration loops; three sentinel synchronizations plus multiple aggregates still produce one reused publisher synchronization
IdentityHashMap average O(1) reservation; no bean-global lock or mutable registry
publisher call cost O(E + S log S) and O(E + S) temporary/reference storage for E events and S current synchronizations
source path contains no publisher-side toList/map/filter/sorted, lock, or global mutable registry
event-type aggregation is reachable only from cleanup-failure/STATUS_UNKNOWN anomaly logging, never normal publication/commit/rollback
no blocking call, polling, sleep, thread, container, or retry loop added
completion always discards registry state
clear failures are isolated; rollback and unknown completion preserve buffers
REQUIRES_NEW uses Spring synchronization suspension with distinct aggregate instances
```

Record file/line evidence for each item and map operations explicitly: event publication and identity verification are `E`; one synchronization-list scan plus Spring's synchronization ordering is `S log S`; retained snapshots plus current synchronization references are `E + S`. Re-run the sentinel, snapshot-retention, anomaly-log, and exact call-count tests; prove normal and each sentinel aggregate transitions `1 -> 2`, while duplicate rejection remains `1`.

If a P0/P1 issue appears, return to the owning task, add a RED regression test, fix, rerun Task 8 from Step 1, and rerun only affected review lenses.

- [ ] **Step 5: Review the complete diff**

Use raw `git diff origin/develop...HEAD` plus uncommitted diff inspection. Confirm only issue #323 files are present, public KDoc is English, README pairs are source-equivalent, the SVG/PNG pair matches, and no generated build output is tracked.

## Task 9: Capture Review, Lesson, And Pre-PR Evidence

complexity: medium
depends_on: Task 8 PASS
applies: `bluetape4k-full-feature`, `requesting-code-review`, `verification-before-completion`

**Files:**
- Create: `docs/review/2026-07-11-issue-323-domain-event-publisher-implementation-review.md`
- Create: `docs/lessons/2026-07-11-issue-323-domain-event-publisher.md`

- [ ] **Step 1: Run the six implementation review lenses plus main integration**

Review the exact branch diff in dependency order: core contract -> publisher -> auto-configuration -> example -> docs/diagram. Record P0/P1/P2/P3, resolved edits, rerun lanes, performance/stability evidence, and final `P0 = 0`, `P1 = 0`. P2/P3 must be fixed, deferred with rationale, or filed as follow-up.

- [ ] **Step 2: Run the spec/plan verifier**

Map every design acceptance criterion to an implementation file, test name, documentation section, and command result. A missing criterion returns to the owning implementation task; it does not become a prose-only exception.

- [ ] **Step 3: Write the durable lesson**

Record context, chosen in-transaction handoff, why `afterCommit` publication was rejected, identity/poison insight, completion uncertainty, test evidence, review misses, and the future rule: never clear aggregate events before committed completion and never claim manager identity from Spring thread-local synchronization.

- [ ] **Step 4: Commit review and lesson artifacts**

Run `git diff --check`, then commit both artifacts with a Lore message beginning:

```text
docs: capture aggregate event handoff evidence
```

- [ ] **Step 5: Prepare the external-side-effect authority gate**

At this point implementation is ready for PR preparation. Re-read issue #323 metadata and prepare the exact branch/PR payload, then stop until explicit push/PR authority is present. Merge, workflow dispatch beyond PR-triggered CI, branch deletion, and worktree cleanup remain separate authority boundaries.

## Task 10: Prove Live PR, CI, Coverage, And Review Gates

complexity: medium
depends_on: Task 9 PASS and explicit push/PR approval
applies: `bluetape4k-full-feature`, `verification-before-completion`

**Files:**
- No source edits unless live evidence returns to the owning task.

- [ ] **Step 1: Push and create the issue-linked PR only after authority is present**

Push the reviewed branch and create the PR against `develop`. Copy issue #323 assignee `debop`, milestone `1.12.0`, and relevant labels. The PR body links/closes #323, includes test and coverage evidence, and ends with the final Markdown section `## DoD Status`. Verify live metadata and body with `gh pr view --json` rather than relying on create-command output.

- [ ] **Step 2: Watch CI and inspect raw retry evidence**

Use `ci-status --watch` or `gh pr checks --watch`, then inspect the raw job logs for core, Spring Boot JDBC, Spring Modulith, and examples. If any log contains `Attempt N failed`, stop even when the final conclusion is success, investigate lifecycle/container/timing causes, return to the affected task, and rerun the relevant local suite before a fresh CI run.

```bash
gh run view <run-id> --json status,conclusion,jobs,url
gh run view <run-id> --log | tee /tmp/issue-323-ci.log
! rg -n 'Attempt [1-5] failed' /tmp/issue-323-ci.log
```

- [ ] **Step 3: Verify live non-empty coverage artifacts**

Download the PR run's coverage artifacts and prove the core, JDBC, Spring Modulith, and example `report.xml` files exist and are non-empty. This issue-level artifact gate complements, but does not rewrite, the repository's report-only Kover workflow policy.

```bash
coverage_dir="$(mktemp -d /tmp/issue-323-coverage.XXXXXX)"
gh run download <run-id> --pattern 'coverage-*' --dir "$coverage_dir"
find "$coverage_dir" -name 'report.xml' -size +0 -print | tee /tmp/issue-323-coverage-files
test -s "$coverage_dir/coverage-core/exposed/core/build/reports/kover/report.xml"
test -s "$coverage_dir/coverage-spring-boot/spring-boot/jdbc/build/reports/kover/report.xml"
test -s "$coverage_dir/coverage-spring-modulith/spring-boot/spring-modulith/build/reports/kover/report.xml"
test -s "$coverage_dir/coverage-examples/examples/ddd-spring-modulith-demo/build/reports/kover/report.xml"
```

- [ ] **Step 4: Reopen review and merge gates**

After CI is green, re-read PR reviews and every review thread. Any unresolved or newer comment returns to the owning task and requires affected tests/review lenses again. Verify issue/PR metadata and the live PR body one final time. Stop before merge unless explicit merge authority is present; merge strategy remains GitHub rebase merge.

## Spec-To-Task Traceability

| Design requirement | Plan task |
|---|---|
| Spring-neutral immutable/stable DDD contracts | 1 |
| Explicit public JDBC publisher and active transaction checks | 2-3 |
| Immediate handoff plus default AFTER_COMMIT behavior | 2-3 |
| Identity reservation, mutation detection, poison, one synchronization | 4 |
| Commit clear, rollback/unknown preserve, cleanup isolation | 3-4 |
| Safe anomaly fields and correlation allowlist | 4 |
| REQUIRES_NEW isolation, nested/savepoint exclusion | 4, 7 |
| Single-candidate auto-configuration and executable `transactionManagerRef` multi-manager use | 5 |
| Plain Spring Boot without Spring Modulith | 5 |
| Example migration, rollback, durable publication, serializer trust | 6 |
| README locale parity, JaVers boundary, migration/runbook | 7 |
| Lifecycle SVG/PNG | 7 |
| CI/Nightly/Kover visibility | 0, 8, 10 |
| 7-Tier implementation review and durable lesson | 9 |
| Live PR metadata, retry-log inspection, coverage artifacts, review threads | 10 |

## Risk Prediction And Stop Conditions

| Risk | Signal | Mitigation / rerun point |
|---|---|---|
| Publication happens too late for transactional listeners | AFTER_COMMIT listener receives nothing | Keep `ApplicationEventPublisher` call inside Task 3 transaction; rerun Task 2-3 tests. |
| Caller catches publication/duplicate error and transaction commits | DB row commits after handoff error | Poison synchronization and fail `beforeCommit`; rerun Task 4 fail-closed tests. |
| Aggregate changes after save/handoff | Snapshot size/reference mismatch | Fail before commit and preserve buffer; return to Task 4. |
| Registry leaks across suspension/completion | Duplicate rejection in a later or REQUIRES_NEW transaction | Keep state only in synchronization list; rerun Task 4 lifecycle matrix. |
| Post-commit cleanup failure triggers command retry | Cleanup anomaly mistaken for rollback | Structured committed-cleanup log and README no-retry table; return to Tasks 4 and 7. |
| Sensitive payload enters logs/publication store | Secret marker appears in captured log or serialized row | Fail tests; return to Tasks 4 or 6 before continuing. |
| Auto-configuration implies manager identity | Multi-manager test requires injected manager | Keep bean manager-agnostic; return to Task 5. |
| Repository ignores `transactionManagerRef` | First store changes or second store remains unchanged | Forward the annotation value to the repository factory and remove hard-coded base-class qualifiers; rerun Task 5 RED/GREEN tests. |
| Old and new example publication paths coexist | Source search finds manual loop/clear | Remove both old calls in Task 6; never proceed to docs with dual publication. |
| Diagram diverges from implementation | Full-size PNG timing differs from tests/source | Correct SVG and rerun all Task 7 diagram gates. |

Stop implementation immediately and return to the approved design if safe behavior would require R2DBC, savepoint callback support, a durable outbox, a new metric/callback SPI, manager/DataSource identity proof, or a change to the one-final-call contract. These are material scope changes, not implementation details.
