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
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.io.Serializable
import java.time.Instant
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

    class AfterCommitListener {
        val events = mutableListOf<TestEvent>()

        @TransactionalEventListener
        fun on(event: TestEvent) {
            events += event
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
