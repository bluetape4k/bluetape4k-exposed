package io.bluetape4k.spring.data.exposed.jdbc.ddd

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.bluetape4k.exposed.core.ddd.AbstractAggregateRoot
import io.bluetape4k.exposed.core.ddd.AggregateRoot
import io.bluetape4k.exposed.core.ddd.DomainEvent
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.io.Serializable
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

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

    @Test
    fun `empty aggregate is a no-op outside a transaction`() {
        val publisher = ExposedAggregateEventPublisher(ApplicationEventPublisher { error("must not publish") })

        publisher.publishAfterSave(TestAggregate(TestId(1L)))
    }

    @Test
    fun `commit publishes in order and clears after completion`() {
        val published = mutableListOf<TestEvent>()
        val publisher = ExposedAggregateEventPublisher(ApplicationEventPublisher { published += it as TestEvent })
        val aggregate = TestAggregate(TestId(1L)).apply {
            record(1)
            record(2)
        }

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

    @Test
    fun `after commit listener runs only after a committed transaction returns`() {
        AnnotationConfigApplicationContext(ListenerTestConfiguration::class.java).use { context ->
            val transactionTemplate = context.getBean(TransactionTemplate::class.java)
            val listener = context.getBean(AfterCommitListener::class.java)
            val publisher = ExposedAggregateEventPublisher(context)
            val aggregate = TestAggregate(TestId(5L)).apply { record(1) }

            transactionTemplate.executeWithoutResult {
                publisher.publishAfterSave(aggregate)
                listener.events shouldHaveSize 0
                aggregate.domainEvents() shouldHaveSize 1
            }

            listener.events.map(TestEvent::sequence) shouldBeEqualTo listOf(1)
            aggregate.domainEvents().isEmpty().shouldBeTrue()
        }
    }

    @Test
    fun `after commit listener does not run for a rolled back transaction`() {
        AnnotationConfigApplicationContext(ListenerTestConfiguration::class.java).use { context ->
            val transactionTemplate = context.getBean(TransactionTemplate::class.java)
            val listener = context.getBean(AfterCommitListener::class.java)
            val publisher = ExposedAggregateEventPublisher(context)
            val aggregate = TestAggregate(TestId(6L)).apply { record(1) }

            transactionTemplate.executeWithoutResult { status ->
                publisher.publishAfterSave(aggregate)
                listener.events shouldHaveSize 0
                status.setRollbackOnly()
            }

            listener.events shouldHaveSize 0
            aggregate.domainEvents() shouldHaveSize 1
        }
    }

    @Test
    fun `duplicate registration poisons commit without a second snapshot or publication`() {
        val published = mutableListOf<TestEvent>()
        val publisher = ExposedAggregateEventPublisher(ApplicationEventPublisher { published += it as TestEvent })
        val aggregate = CountingAggregate(TestId(7L)).apply { record(1) }

        assertFailsWith<IllegalStateException> {
            transactionTemplate.executeWithoutResult {
                jdbcTemplate.update("INSERT INTO DOMAIN_EVENT_TEST(ID) VALUES (?)", aggregate.id.value)
                publisher.publishAfterSave(aggregate)
                aggregate.domainEventCalls shouldBeEqualTo 1
                assertFailsWith<IllegalStateException> {
                    publisher.publishAfterSave(aggregate)
                }
                aggregate.domainEventCalls shouldBeEqualTo 1
            }
        }

        published.map(TestEvent::sequence) shouldBeEqualTo listOf(1)
        aggregate.domainEvents() shouldHaveSize 1
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM DOMAIN_EVENT_TEST", Long::class.java) shouldBeEqualTo 0L
    }

    @Test
    fun `clearing after registration is rejected before the empty no-op`() {
        val publisher = ExposedAggregateEventPublisher(ApplicationEventPublisher {})
        val aggregate = TestAggregate(TestId(8L)).apply { record(1) }

        assertFailsWith<IllegalStateException> {
            transactionTemplate.executeWithoutResult {
                publisher.publishAfterSave(aggregate)
                aggregate.clearDomainEvents()
                assertFailsWith<IllegalStateException> {
                    publisher.publishAfterSave(aggregate)
                }
            }
        }
    }

    @Test
    fun `draining after registration is rejected before the empty no-op`() {
        val publisher = ExposedAggregateEventPublisher(ApplicationEventPublisher {})
        val aggregate = TestAggregate(TestId(81L)).apply { record(1) }

        assertFailsWith<IllegalStateException> {
            transactionTemplate.executeWithoutResult {
                publisher.publishAfterSave(aggregate)
                aggregate.drainDomainEvents {}
                assertFailsWith<IllegalStateException> {
                    publisher.publishAfterSave(aggregate)
                }
            }
        }
    }

    @Test
    fun `recording after registration rejects commit and preserves the buffer`() {
        val publisher = ExposedAggregateEventPublisher(ApplicationEventPublisher {})
        val aggregate = TestAggregate(TestId(9L)).apply { record(1) }

        assertFailsWith<IllegalStateException> {
            transactionTemplate.executeWithoutResult {
                jdbcTemplate.update("INSERT INTO DOMAIN_EVENT_TEST(ID) VALUES (?)", aggregate.id.value)
                publisher.publishAfterSave(aggregate)
                aggregate.record(2)
            }
        }

        aggregate.domainEvents().map { (it as TestEvent).sequence } shouldBeEqualTo listOf(1, 2)
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM DOMAIN_EVENT_TEST", Long::class.java) shouldBeEqualTo 0L
    }

    @Test
    fun `same size replacement after registration is rejected by event identity`() {
        val publisher = ExposedAggregateEventPublisher(ApplicationEventPublisher {})
        val aggregate = TestAggregate(TestId(84L)).apply { record(1) }

        assertFailsWith<IllegalStateException> {
            transactionTemplate.executeWithoutResult {
                jdbcTemplate.update("INSERT INTO DOMAIN_EVENT_TEST(ID) VALUES (?)", aggregate.id.value)
                publisher.publishAfterSave(aggregate)
                aggregate.clearDomainEvents()
                aggregate.record(2)
            }
        }

        aggregate.domainEvents().map { (it as TestEvent).sequence } shouldBeEqualTo listOf(2)
        storedIds().shouldBeEmpty()
    }

    @Test
    fun `lower ordered synchronization mutation is rejected before commit`() {
        val publisher = ExposedAggregateEventPublisher(ApplicationEventPublisher {})
        val aggregate = TestAggregate(TestId(85L)).apply { record(1) }

        assertFailsWith<IllegalStateException> {
            transactionTemplate.executeWithoutResult {
                jdbcTemplate.update("INSERT INTO DOMAIN_EVENT_TEST(ID) VALUES (?)", aggregate.id.value)
                publisher.publishAfterSave(aggregate)
                TransactionSynchronizationManager.registerSynchronization(
                    object : TransactionSynchronization {
                        override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE - 1

                        override fun beforeCommit(readOnly: Boolean) {
                            aggregate.record(2)
                        }
                    }
                )
            }
        }

        aggregate.domainEvents().map { (it as TestEvent).sequence } shouldBeEqualTo listOf(1, 2)
        storedIds().shouldBeEmpty()
    }

    @Test
    fun `caught publication exception still poisons commit and rethrows the same object`() {
        val failure = IllegalArgumentException("sensitive-publisher-message")
        val publisher = ExposedAggregateEventPublisher(ApplicationEventPublisher { throw failure })
        val aggregate = TestAggregate(TestId(10L)).apply { record(1) }

        assertFailsWith<IllegalStateException> {
            transactionTemplate.executeWithoutResult {
                jdbcTemplate.update("INSERT INTO DOMAIN_EVENT_TEST(ID) VALUES (?)", aggregate.id.value)
                try {
                    publisher.publishAfterSave(aggregate)
                    error("publication must fail")
                } catch (caught: IllegalArgumentException) {
                    (caught === failure).shouldBeTrue()
                }
            }
        }

        aggregate.domainEvents() shouldHaveSize 1
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM DOMAIN_EVENT_TEST", Long::class.java) shouldBeEqualTo 0L
    }

    @Test
    fun `caught publication error still poisons commit and rethrows the same object`() {
        val failure = AssertionError("sensitive-error-message")
        val publisher = ExposedAggregateEventPublisher(ApplicationEventPublisher { throw failure })
        val aggregate = TestAggregate(TestId(11L)).apply { record(1) }

        assertFailsWith<IllegalStateException> {
            transactionTemplate.executeWithoutResult {
                jdbcTemplate.update("INSERT INTO DOMAIN_EVENT_TEST(ID) VALUES (?)", aggregate.id.value)
                try {
                    publisher.publishAfterSave(aggregate)
                    error("publication must fail")
                } catch (caught: AssertionError) {
                    (caught === failure).shouldBeTrue()
                }
            }
        }

        aggregate.domainEvents() shouldHaveSize 1
        storedIds().shouldBeEmpty()
    }

    @Test
    fun `partial synchronous handoff remains observable but cannot commit`() {
        val published = mutableListOf<TestEvent>()
        val failure = IllegalStateException("second event failed")
        val publisher = ExposedAggregateEventPublisher(ApplicationEventPublisher { event ->
            val testEvent = event as TestEvent
            if (testEvent.sequence == 2) throw failure
            published += testEvent
        })
        val aggregate = TestAggregate(TestId(12L)).apply {
            record(1)
            record(2)
        }

        assertFailsWith<IllegalStateException> {
            transactionTemplate.executeWithoutResult {
                jdbcTemplate.update("INSERT INTO DOMAIN_EVENT_TEST(ID) VALUES (?)", aggregate.id.value)
                try {
                    publisher.publishAfterSave(aggregate)
                } catch (caught: IllegalStateException) {
                    (caught === failure).shouldBeTrue()
                }
            }
        }

        published.map(TestEvent::sequence) shouldBeEqualTo listOf(1)
        aggregate.domainEvents() shouldHaveSize 2
        storedIds().shouldBeEmpty()
    }

    @Test
    fun `synchronous reentry reserves identity before publication`() {
        val callbacks = AtomicInteger()
        lateinit var publisher: ExposedAggregateEventPublisher
        val aggregate = TestAggregate(TestId(13L)).apply { record(1) }
        publisher = ExposedAggregateEventPublisher(ApplicationEventPublisher {
            callbacks.incrementAndGet()
            publisher.publishAfterSave(aggregate)
        })

        assertFailsWith<IllegalStateException> {
            transactionTemplate.executeWithoutResult {
                try {
                    publisher.publishAfterSave(aggregate)
                } catch (_: IllegalStateException) {
                    // The stored poison must still reject commit.
                }
            }
        }

        callbacks.get() shouldBeEqualTo 1
        aggregate.domainEvents() shouldHaveSize 1
    }

    @Test
    fun `throwing after commit listener does not retry a committed command`() {
        AnnotationConfigApplicationContext(ListenerTestConfiguration::class.java).use { context ->
            val transactionTemplate = context.getBean(TransactionTemplate::class.java)
            val jdbcTemplate = JdbcTemplate(context.getBean(DataSource::class.java)).also {
                it.execute("CREATE TABLE AFTER_COMMIT_COMMAND_TEST (ID BIGINT PRIMARY KEY)")
            }
            val listener = context.getBean(AfterCommitListener::class.java)
            val publisher = ExposedAggregateEventPublisher(context)
            val aggregate = TestAggregate(TestId(82L)).apply { record(1) }
            val commandCalls = AtomicInteger()
            listener.failure = IllegalStateException("listener failed after commit")

            transactionTemplate.executeWithoutResult {
                commandCalls.incrementAndGet()
                jdbcTemplate.update("INSERT INTO AFTER_COMMIT_COMMAND_TEST(ID) VALUES (?)", aggregate.id.value)
                publisher.publishAfterSave(aggregate)
                listener.events shouldHaveSize 0
            }

            commandCalls.get() shouldBeEqualTo 1
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM AFTER_COMMIT_COMMAND_TEST",
                Long::class.java,
            ) shouldBeEqualTo 1L
            listener.events shouldHaveSize 1
            aggregate.domainEvents().shouldBeEmpty()
        }
    }

    @Test
    fun `one publisher synchronization is reused for multiple aggregates`() {
        val callbacks = mutableListOf<String>()
        val sentinels = (1..3).map { index ->
            object : TransactionSynchronization {
                override fun getOrder(): Int = index * 100

                override fun beforeCommit(readOnly: Boolean) {
                    callbacks += "before-$index"
                }

                override fun afterCompletion(status: Int) {
                    callbacks += "after-$index"
                }
            }
        }
        val publisher = ExposedAggregateEventPublisher(ApplicationEventPublisher {})
        val first = CountingAggregate(TestId(14L)).apply { record(1) }
        val second = CountingAggregate(TestId(15L)).apply { record(2) }

        transactionTemplate.executeWithoutResult {
            sentinels.forEach(TransactionSynchronizationManager::registerSynchronization)
            publisher.publishAfterSave(first)
            publisher.publishAfterSave(second)

            TransactionSynchronizationManager.getSynchronizations()
                .count { it is AggregateEventTransactionSynchronization } shouldBeEqualTo 1
            first.domainEventCalls shouldBeEqualTo 1
            second.domainEventCalls shouldBeEqualTo 1
        }

        callbacks shouldBeEqualTo listOf("before-1", "before-2", "before-3", "after-1", "after-2", "after-3")
        first.domainEventCalls shouldBeEqualTo 2
        second.domainEventCalls shouldBeEqualTo 2
        first.domainEvents().shouldBeEmpty()
        second.domainEvents().shouldBeEmpty()
    }

    @Test
    fun `publisher retains the exact aggregate snapshot object`() {
        val publisher = ExposedAggregateEventPublisher(ApplicationEventPublisher {})
        val aggregate = CountingAggregate(TestId(16L)).apply { record(1) }

        transactionTemplate.executeWithoutResult { status ->
            publisher.publishAfterSave(aggregate)
            val synchronization = TransactionSynchronizationManager.getSynchronizations()
                .filterIsInstance<AggregateEventTransactionSynchronization>()
                .single()
            (synchronization.retainedSnapshotForTest(aggregate) === aggregate.lastSnapshot).shouldBeTrue()
            status.setRollbackOnly()
        }
    }

    @Test
    fun `a committed transaction does not retain duplicate identity on the next transaction`() {
        val published = mutableListOf<TestEvent>()
        val publisher = ExposedAggregateEventPublisher(ApplicationEventPublisher { published += it as TestEvent })
        val aggregate = TestAggregate(TestId(17L)).apply { record(1) }

        transactionTemplate.executeWithoutResult { publisher.publishAfterSave(aggregate) }
        aggregate.record(2)
        transactionTemplate.executeWithoutResult { publisher.publishAfterSave(aggregate) }

        published.map(TestEvent::sequence) shouldBeEqualTo listOf(1, 2)
        aggregate.domainEvents().shouldBeEmpty()
    }

    @Test
    fun `a rolled back transaction can register the retained buffer in the next transaction`() {
        val published = mutableListOf<TestEvent>()
        val publisher = ExposedAggregateEventPublisher(ApplicationEventPublisher { published += it as TestEvent })
        val aggregate = TestAggregate(TestId(18L)).apply { record(1) }

        transactionTemplate.executeWithoutResult { status ->
            publisher.publishAfterSave(aggregate)
            status.setRollbackOnly()
        }
        transactionTemplate.executeWithoutResult { publisher.publishAfterSave(aggregate) }

        published.map(TestEvent::sequence) shouldBeEqualTo listOf(1, 1)
        aggregate.domainEvents().shouldBeEmpty()
    }

    @Test
    fun `requires new commit is isolated from outer rollback`() {
        val publisher = ExposedAggregateEventPublisher(ApplicationEventPublisher {})
        val outer = TestAggregate(TestId(19L)).apply { record(1) }
        val inner = TestAggregate(TestId(20L)).apply { record(2) }
        val requiresNew = TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }
        var outerSynchronization: AggregateEventTransactionSynchronization? = null
        var innerSynchronization: AggregateEventTransactionSynchronization? = null

        transactionTemplate.executeWithoutResult { outerStatus ->
            jdbcTemplate.update("INSERT INTO DOMAIN_EVENT_TEST(ID) VALUES (?)", outer.id.value)
            publisher.publishAfterSave(outer)
            outerSynchronization = currentPublisherSynchronization()
            requiresNew.executeWithoutResult {
                jdbcTemplate.update("INSERT INTO DOMAIN_EVENT_TEST(ID) VALUES (?)", inner.id.value)
                publisher.publishAfterSave(inner)
                innerSynchronization = currentPublisherSynchronization()
                (innerSynchronization !== outerSynchronization).shouldBeTrue()
            }
            (currentPublisherSynchronization() === outerSynchronization).shouldBeTrue()
            outer.domainEvents() shouldHaveSize 1
            inner.domainEvents().shouldBeEmpty()
            outerStatus.setRollbackOnly()
        }

        outer.domainEvents() shouldHaveSize 1
        inner.domainEvents().shouldBeEmpty()
        storedIds() shouldBeEqualTo listOf(inner.id.value)
    }

    @Test
    fun `requires new rollback is isolated from outer commit`() {
        val publisher = ExposedAggregateEventPublisher(ApplicationEventPublisher {})
        val outer = TestAggregate(TestId(21L)).apply { record(1) }
        val inner = TestAggregate(TestId(22L)).apply { record(2) }
        val requiresNew = TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }
        var outerSynchronization: AggregateEventTransactionSynchronization? = null

        transactionTemplate.executeWithoutResult {
            jdbcTemplate.update("INSERT INTO DOMAIN_EVENT_TEST(ID) VALUES (?)", outer.id.value)
            publisher.publishAfterSave(outer)
            outerSynchronization = currentPublisherSynchronization()
            requiresNew.executeWithoutResult { innerStatus ->
                jdbcTemplate.update("INSERT INTO DOMAIN_EVENT_TEST(ID) VALUES (?)", inner.id.value)
                publisher.publishAfterSave(inner)
                (currentPublisherSynchronization() !== outerSynchronization).shouldBeTrue()
                innerStatus.setRollbackOnly()
            }
            (currentPublisherSynchronization() === outerSynchronization).shouldBeTrue()
            inner.domainEvents() shouldHaveSize 1
        }

        outer.domainEvents().shouldBeEmpty()
        inner.domainEvents() shouldHaveSize 1
        storedIds() shouldBeEqualTo listOf(outer.id.value)
    }

    @Test
    fun `committed clear failure does not prevent other aggregates from clearing`() {
        withPublisherLogs { logs ->
            val publisher = ExposedAggregateEventPublisher(ApplicationEventPublisher {})
            val failing = FailingClearAggregate(TestId(23L)).apply { record(1) }
            val normal = TestAggregate(TestId(24L)).apply { record(2) }

            transactionTemplate.executeWithoutResult {
                publisher.publishAfterSave(failing)
                publisher.publishAfterSave(normal)
            }

            failing.domainEvents() shouldHaveSize 1
            normal.domainEvents().shouldBeEmpty()
            logs.map(ILoggingEvent::getFormattedMessage) shouldBeEqualTo listOf("aggregate-event-cleanup-failed")
            val event = logs.single()
            assertSanitizedLog(event, "aggregate-event-cleanup-failed")
            event.mdcPropertyMap["aggregateType"] shouldBeEqualTo requireNotNull(
                FailingClearAggregate::class.qualifiedName
            )
            event.mdcPropertyMap["eventType"] shouldBeEqualTo requireNotNull(TestEvent::class.qualifiedName)
            event.mdcPropertyMap["eventCount"] shouldBeEqualTo "1"
        }
    }

    @Test
    fun `rollback emits no completion anomaly`() {
        withPublisherLogs { logs ->
            val publisher = ExposedAggregateEventPublisher(ApplicationEventPublisher {})
            val aggregate = TestAggregate(TestId(86L)).apply { record(1) }

            transactionTemplate.executeWithoutResult { status ->
                publisher.publishAfterSave(aggregate)
                status.setRollbackOnly()
            }

            logs.shouldBeEmpty()
            aggregate.domainEvents() shouldHaveSize 1
        }
    }

    @Test
    fun `unknown completion preserves buffers and emits one sanitized row per aggregate`() {
        withPublisherLogs { logs ->
            MDC.put("traceId", "trace-123")
            MDC.put("secret", "must-not-leak")
            val publisher = ExposedAggregateEventPublisher(ApplicationEventPublisher {})
            val first = TestAggregate(TestId(25L)).apply { record(1) }
            val second = TestAggregate(TestId(26L)).apply { record(2) }

            transactionTemplate.executeWithoutResult { status ->
                publisher.publishAfterSave(first)
                publisher.publishAfterSave(second)
                MDC.clear()
                MDC.put("completionSecret", "must-not-leak")
                currentPublisherSynchronization().afterCompletion(TransactionSynchronization.STATUS_UNKNOWN)
                MDC.get("completionSecret") shouldBeEqualTo "must-not-leak"
                status.setRollbackOnly()
            }

            first.domainEvents() shouldHaveSize 1
            second.domainEvents() shouldHaveSize 1
            logs shouldHaveSize 2
            logs.forEach {
                assertSanitizedLog(it, "aggregate-event-completion-unknown")
                it.mdcPropertyMap["traceId"] shouldBeEqualTo "trace-123"
                it.mdcPropertyMap.containsKey("secret").shouldBeFalse()
                it.mdcPropertyMap.containsKey("completionSecret").shouldBeFalse()
            }
        }
    }

    @Test
    fun `unknown completion discards registration ownership for the next transaction`() {
        withPublisherLogs { logs ->
            val published = mutableListOf<TestEvent>()
            val publisher = ExposedAggregateEventPublisher(ApplicationEventPublisher { published += it as TestEvent })
            val aggregate = TestAggregate(TestId(87L)).apply { record(1) }

            transactionTemplate.executeWithoutResult { status ->
                publisher.publishAfterSave(aggregate)
                currentPublisherSynchronization().afterCompletion(TransactionSynchronization.STATUS_UNKNOWN)
                status.setRollbackOnly()
            }
            transactionTemplate.executeWithoutResult {
                publisher.publishAfterSave(aggregate)
            }

            published.map(TestEvent::sequence) shouldBeEqualTo listOf(1, 1)
            aggregate.domainEvents().shouldBeEmpty()
            logs.map(ILoggingEvent::getFormattedMessage) shouldBeEqualTo listOf(
                "aggregate-event-completion-unknown"
            )
        }
    }

    @Test
    fun `correlation allowlist accepts bounded ASCII and rejects unsafe values`() {
        val accepted = mapOf(
            "traceId" to "trace:1",
            "spanId" to "span_2",
            "requestId" to "r".repeat(128),
        )
        val rejected = listOf(
            "",
            "r".repeat(129),
            "line\nbreak",
            "tab\tvalue",
            "unicode-한글",
            "white space",
            "slash/value",
        )

        withPublisherLogs { logs ->
            accepted.forEach(MDC::put)
            rejected.forEachIndexed { index, value -> MDC.put("unsafe$index", value) }
            val publisher = ExposedAggregateEventPublisher(ApplicationEventPublisher {})
            val aggregate = TestAggregate(TestId(27L)).apply { record(1) }

            transactionTemplate.executeWithoutResult { status ->
                publisher.publishAfterSave(aggregate)
                currentPublisherSynchronization().afterCompletion(TransactionSynchronization.STATUS_UNKNOWN)
                status.setRollbackOnly()
            }

            val event = logs.single()
            accepted.forEach { (key, value) -> event.mdcPropertyMap[key] shouldBeEqualTo value }
            event.mdcPropertyMap.keys shouldBeEqualTo setOf(
                "category",
                "aggregateType",
                "eventType",
                "eventCount",
                "traceId",
                "spanId",
                "requestId",
            )
        }
    }

    @Test
    fun `unsafe values are rejected even for allowlisted correlation keys`() {
        val rejected = listOf(
            "",
            "r".repeat(129),
            "line\nbreak",
            "tab\tvalue",
            "control\u0001value",
            "unicode-한글",
            "white space",
            "slash/value",
        )

        withPublisherLogs { logs ->
            rejected.forEachIndexed { index, value ->
                MDC.clear()
                MDC.put("traceId", value)
                val publisher = ExposedAggregateEventPublisher(ApplicationEventPublisher {})
                val aggregate = TestAggregate(TestId(100L + index)).apply { record(1) }
                transactionTemplate.executeWithoutResult { status ->
                    publisher.publishAfterSave(aggregate)
                    currentPublisherSynchronization().afterCompletion(TransactionSynchronization.STATUS_UNKNOWN)
                    status.setRollbackOnly()
                }
            }

            logs shouldHaveSize rejected.size
            logs.forEach { it.mdcPropertyMap.containsKey("traceId").shouldBeFalse() }
        }
    }

    @Test
    fun `anomaly event types preserve recording order and remove duplicates`() {
        withPublisherLogs { logs ->
            val publisher = ExposedAggregateEventPublisher(ApplicationEventPublisher {})
            val aggregate = TestAggregate(TestId(83L)).apply {
                record(1)
                recordOther(2)
                record(3)
            }

            transactionTemplate.executeWithoutResult { status ->
                publisher.publishAfterSave(aggregate)
                currentPublisherSynchronization().afterCompletion(TransactionSynchronization.STATUS_UNKNOWN)
                status.setRollbackOnly()
            }

            logs.single().mdcPropertyMap["eventType"] shouldBeEqualTo listOf(
                requireNotNull(TestEvent::class.qualifiedName),
                requireNotNull(OtherTestEvent::class.qualifiedName),
            ).joinToString(",")
            logs.single().mdcPropertyMap["eventCount"] shouldBeEqualTo "3"
        }
    }

    private fun currentPublisherSynchronization(): AggregateEventTransactionSynchronization =
        TransactionSynchronizationManager.getSynchronizations()
            .filterIsInstance<AggregateEventTransactionSynchronization>()
            .single()

    private fun storedIds(): List<Long> =
        jdbcTemplate.queryForList("SELECT ID FROM DOMAIN_EVENT_TEST ORDER BY ID", Long::class.java).filterNotNull()

    private fun withPublisherLogs(block: (MutableList<ILoggingEvent>) -> Unit) {
        val logger = LoggerFactory.getLogger(ExposedAggregateEventPublisher::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply {
            context = logger.loggerContext
            start()
        }
        logger.addAppender(appender)
        try {
            block(appender.list)
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    private fun assertSanitizedLog(event: ILoggingEvent, category: String) {
        event.message shouldBeEqualTo category
        event.formattedMessage shouldBeEqualTo category
        event.mdcPropertyMap["category"] shouldBeEqualTo category
        event.mdcPropertyMap.keys.all {
            it in setOf("category", "aggregateType", "eventType", "eventCount", "traceId", "spanId", "requestId")
        }.shouldBeTrue()
        event.argumentArray.shouldBeNull()
        event.throwableProxy.shouldBeNull()
        event.markerList.isNullOrEmpty().shouldBeTrue()
        event.keyValuePairs.isNullOrEmpty().shouldBeTrue()
        val rendered = buildString {
            append(event.message)
            append(event.formattedMessage)
            append(event.mdcPropertyMap)
        }
        rendered.contains("sensitive", ignoreCase = true).shouldBeFalse()
        rendered.contains("secret", ignoreCase = true).shouldBeFalse()
        rendered.contains("pii", ignoreCase = true).shouldBeFalse()
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

    data class OtherTestEvent(
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

        fun recordOther(sequence: Int): OtherTestEvent =
            OtherTestEvent(id, sequence).also(::recordDomainEvent)
    }

    class CountingAggregate(id: TestId) : TestAggregate(id) {
        var domainEventCalls: Int = 0
            private set
        var lastSnapshot: List<DomainEvent<TestId>>? = null
            private set

        override fun domainEvents(): List<DomainEvent<TestId>> =
            super.domainEvents().also {
                domainEventCalls++
                lastSnapshot = it
            }
    }

    class FailingClearAggregate(id: TestId) : TestAggregate(id) {
        override fun clearDomainEvents() {
            error("sensitive-clear-message")
        }
    }

    class AfterCommitListener {
        val events = mutableListOf<TestEvent>()
        var failure: RuntimeException? = null

        @TransactionalEventListener
        fun on(event: TestEvent) {
            events += event
            failure?.let { throw it }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    class ListenerTestConfiguration {

        @Bean(destroyMethod = "shutdown")
        fun dataSource(): EmbeddedDatabase = EmbeddedDatabaseBuilder()
            .generateUniqueName(true)
            .setType(EmbeddedDatabaseType.H2)
            .build()

        @Bean
        fun transactionManager(dataSource: DataSource): PlatformTransactionManager =
            DataSourceTransactionManager(dataSource)

        @Bean
        fun transactionTemplate(transactionManager: PlatformTransactionManager): TransactionTemplate =
            TransactionTemplate(transactionManager)

        @Bean
        fun afterCommitListener(): AfterCommitListener = AfterCommitListener()
    }
}
