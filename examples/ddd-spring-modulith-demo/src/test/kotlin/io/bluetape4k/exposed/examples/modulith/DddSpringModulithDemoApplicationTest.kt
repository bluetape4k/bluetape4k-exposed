package io.bluetape4k.exposed.examples.modulith

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import ch.qos.logback.core.read.ListAppender
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.exposed.examples.modulith.orders.AcceptOrderCommand
import io.bluetape4k.exposed.examples.modulith.orders.OrderApplicationService
import io.bluetape4k.exposed.examples.modulith.orders.OrderHandoffFailedException
import io.bluetape4k.exposed.examples.modulith.orders.OrderId
import io.bluetape4k.exposed.examples.modulith.orders.events.OrderAcceptedEvent
import io.bluetape4k.exposed.examples.modulith.orders.internal.OrderRepository
import io.bluetape4k.exposed.examples.modulith.shipping.internal.ShippingReservationRepository
import io.bluetape4k.spring.modulith.exposed.ExposedEventPublicationTable
import io.bluetape4k.spring.data.exposed.jdbc.ddd.ExposedAggregateEventPublisher
import org.awaitility.kotlin.await
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.LoggerFactory
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.modulith.core.ApplicationModules
import org.springframework.modulith.core.Violations
import org.springframework.modulith.events.EventPublication.Status
import org.springframework.modulith.events.core.EventPublicationRepository
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.transaction.support.TransactionTemplate
import java.io.Serializable
import java.time.Duration
import java.time.Instant

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DddSpringModulithDemoApplicationTest {

    @Test
    fun `application modules allow shipping to depend only on order events`() {
        ApplicationModules.of(DddSpringModulithDemoApplication::class.java).verify()
    }

    @Test
    fun `boundary verifier rejects shipping dependency on order internals`() {
        val modules = ApplicationModules.of(
            io.bluetape4k.exposed.examples.modulithinvalid.InvalidBoundaryApplication::class.java,
        )

        val violations = assertFailsWith<Violations> {
            modules.verify()
        }

        violations.message.shouldNotBeNull() shouldContain "orders"
        violations.message.shouldNotBeNull() shouldContain "internal"
    }

    @Test
    fun `application context exposes Exposed backed publication repository`() {
        withApplicationContext() { context ->
            context.getBean(EventPublicationRepository::class.java)
                .shouldNotBeNull()
        }
    }

    @Test
    fun `accepting an order persists reservation through Modulith publication`() {
        withApplicationContext() { context ->
            val orders = context.getBean(OrderRepository::class.java)
            val reservations = context.getBean(ShippingReservationRepository::class.java)
            val service = context.getBean(OrderApplicationService::class.java)
            val publications = context.getBean(EventPublicationRepository::class.java)

            val accepted = service.accept(
                AcceptOrderCommand(
                    orderKey = "order-key-100",
                    customerId = "customer-100",
                )
            )

            await.atMost(Duration.ofSeconds(5)).untilAsserted {
                orders.count() shouldBeEqualTo 1L
                reservations.count() shouldBeEqualTo 1L
                publications.countByStatus(Status.COMPLETED) shouldBeEqualTo 1
                publications.countByStatus(Status.PUBLISHED) shouldBeEqualTo 0
                publications.countByStatus(Status.FAILED) shouldBeEqualTo 0
            }
            reservations.existsByOrderId(accepted.id) shouldBeEqualTo true
            accepted.domainEvents().isEmpty().shouldBeTrue()
        }
    }

    @Test
    fun `publication row stores only opaque event data`() {
        withApplicationContext() { context ->
            val service = context.getBean(OrderApplicationService::class.java)
            val publicationTable = context.getBean("eventPublicationTable", ExposedEventPublicationTable::class.java)
            val orderKey = "ORDER-KEY-SHOULD-NOT-LEAK"
            val customerId = "CUSTOMER-SHOULD-NOT-LEAK"
            val secret = "SECRET-SHOULD-NOT-LEAK"

            service.accept(AcceptOrderCommand(orderKey = orderKey, customerId = customerId, note = secret))

            await.atMost(Duration.ofSeconds(5)).untilAsserted {
                val serialized = serializedEvents(publicationTable).single()
                serialized shouldNotContain orderKey
                serialized shouldNotContain customerId
                serialized shouldNotContain secret
                serialized shouldContain "aggregateId"
                serialized shouldContain "eventId"
                serialized shouldContain "occurredAt"
                serialized shouldContain "\"aggregateId\":\"order-"
                serialized shouldContain "\"eventId\":\"event-"
                serialized shouldNotContain "@class"
                serialized shouldNotContain "@type"
            }
        }
    }

    @Test
    fun `duplicate order accepted events keep shipping reservation idempotent`() {
        withApplicationContext() { context ->
            val reservations = context.getBean(ShippingReservationRepository::class.java)
            val event = OrderAcceptedEvent(
                aggregateId = OrderId("order-duplicate"),
                eventId = "event-duplicate",
                occurredAt = Instant.parse("2026-07-09T00:00:00Z"),
            )

            reservations.reserve(event)
            reservations.reserve(event)

            reservations.count() shouldBeEqualTo 1L
        }
    }

    @Test
    fun `restart republishes incomplete order event without duplicate reservation`() {
        val databaseName = "ddd_modulith_restart_${System.nanoTime()}"
        val orderId = withApplicationContext(databaseName) { first ->
            val service = first.getBean(OrderApplicationService::class.java)
            val reservations = first.getBean(ShippingReservationRepository::class.java)
            val publicationTable = first.getBean("eventPublicationTable", ExposedEventPublicationTable::class.java)

            val accepted = service.accept(AcceptOrderCommand(orderKey = "restart", customerId = "customer"))

            await.atMost(Duration.ofSeconds(5)).untilAsserted {
                reservations.count() shouldBeEqualTo 1L
            }
            resetPublicationToIncomplete(publicationTable, accepted.id)
            accepted.id
        }

        withApplicationContext(
            databaseName = databaseName,
            extraProperties = mapOf("spring.modulith.events.republish-outstanding-events-on-restart" to "true"),
        ) { second ->
            val reservations = second.getBean(ShippingReservationRepository::class.java)
            val publications = second.getBean(EventPublicationRepository::class.java)

            await.atMost(Duration.ofSeconds(5)).untilAsserted {
                reservations.existsByOrderId(orderId) shouldBeEqualTo true
                reservations.count() shouldBeEqualTo 1L
                publications.countByStatus(Status.COMPLETED) shouldBeEqualTo 1
            }
        }
    }

    @Test
    fun `failed command transaction leaves no order reservation or publication row`() {
        withApplicationContext() { context ->
            val service = context.getBean(OrderApplicationService::class.java)
            val orders = context.getBean(OrderRepository::class.java)
            val reservations = context.getBean(ShippingReservationRepository::class.java)
            val publications = context.getBean(EventPublicationRepository::class.java)

            val failedOrder = assertFailsWith<OrderHandoffFailedException> {
                service.accept(
                    AcceptOrderCommand(orderKey = "rollback", customerId = "customer"),
                    failAfterPublish = true,
                )
            }.aggregate

            failedOrder.domainEvents().size shouldBeEqualTo 1
            orders.count() shouldBeEqualTo 0L
            reservations.count() shouldBeEqualTo 0L
            publications.countByStatus(Status.PUBLISHED) shouldBeEqualTo 0
            publications.countByStatus(Status.COMPLETED) shouldBeEqualTo 0
            publications.countByStatus(Status.FAILED) shouldBeEqualTo 0
        }
    }

    @Test
    fun `failed handoff keeps aggregate domain events recorded`() {
        withApplicationContext() { context ->
            val service = OrderApplicationService(
                orderRepository = context.getBean(OrderRepository::class.java),
                aggregateEventPublisher = ExposedAggregateEventPublisher(
                    org.springframework.context.ApplicationEventPublisher {
                        throw IllegalStateException("Synthetic publication handoff failure")
                    }
                ),
                transactionTemplate = context.getBean(TransactionTemplate::class.java),
            )
            val orders = context.getBean(OrderRepository::class.java)
            val reservations = context.getBean(ShippingReservationRepository::class.java)
            val publications = context.getBean(EventPublicationRepository::class.java)

            val order = assertFailsWith<OrderHandoffFailedException> {
                service.accept(
                    AcceptOrderCommand(orderKey = "handoff", customerId = "customer"),
                )
            }.aggregate

            order.domainEvents().size shouldBeEqualTo 1
            orders.count() shouldBeEqualTo 0L
            reservations.count() shouldBeEqualTo 0L
            publications.countByStatus(Status.PUBLISHED) shouldBeEqualTo 0
        }
    }

    @Test
    fun `serializer rejects unsupported event type without exposing payload`() {
        withApplicationContext(
            additionalSources = arrayOf(UnsupportedEventListenerConfiguration::class.java),
        ) { context ->
            val secret = "UNSUPPORTED-SECRET-MUST-NOT-LEAK"
            val logCapture = attachRootAndNonAdditiveLoggers()
            val transactionTemplate = context.getBean(TransactionTemplate::class.java)
            val publicationRepository = context.getBean(EventPublicationRepository::class.java)

            try {
                val error = assertFailsWith<IllegalArgumentException> {
                    transactionTemplate.executeWithoutResult {
                        context.publishEvent(UnsupportedEvent(secret))
                    }
                }
                error.assertNoSecretInMessageOrCauseChain(secret)
                Status.entries.sumOf(publicationRepository::countByStatus) shouldBeEqualTo 0
                logCapture.allEvents().forEach { event ->
                    event.assertNoSecretInAnyField(secret)
                }
            } finally {
                logCapture.detachStopAndRestoreLoggerState()
            }
        }
    }

    private fun <T> withApplicationContext(
        databaseName: String = "ddd_modulith_${System.nanoTime()}",
        extraProperties: Map<String, Any> = emptyMap(),
        additionalSources: Array<Class<*>> = emptyArray(),
        block: (ConfigurableApplicationContext) -> T,
    ): T {
        return SpringApplicationBuilder(DddSpringModulithDemoApplication::class.java)
            .sources(*additionalSources)
            .web(WebApplicationType.NONE)
            .properties(
                mapOf(
                    "spring.application.name" to "ddd-spring-modulith-demo-test",
                    "spring.datasource.url" to "jdbc:h2:mem:$databaseName;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL",
                    "spring.datasource.driver-class-name" to "org.h2.Driver",
                    "spring.datasource.username" to "sa",
                    "spring.datasource.password" to "",
                    "bluetape4k.spring.modulith.exposed.initialize-schema" to "true",
                ) + extraProperties
            )
            .run()
            .use(block)
    }

    private fun serializedEvents(publicationTable: ExposedEventPublicationTable): List<String> =
        transaction {
            publicationTable.selectAll().map { row -> row[publicationTable.serializedEvent] }
        }

    private fun resetPublicationToIncomplete(publicationTable: ExposedEventPublicationTable, orderId: OrderId) {
        transaction {
            publicationTable.update({ publicationTable.serializedEvent like "%${orderId.value}%" }) {
                it[publicationTable.completionDate] = null
                it[publicationTable.status] = Status.PUBLISHED.name
            }
        }
    }

    private fun attachRootAndNonAdditiveLoggers(): LogCapture {
        val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
        val loggers = buildList {
            add(loggerContext.getLogger(Logger.ROOT_LOGGER_NAME))
            addAll(loggerContext.loggerList.filter { !it.isAdditive })
        }.distinct()
        val attachments = loggers.map { logger ->
            val appender = ListAppender<ILoggingEvent>().apply {
                context = loggerContext
                start()
            }
            logger.addAppender(appender)
            LoggerAttachment(logger, appender, logger.isAdditive)
        }
        return LogCapture(attachments)
    }

    private fun Throwable.assertNoSecretInMessageOrCauseChain(secret: String) {
        var current: Throwable? = this
        while (current != null) {
            current.message.orEmpty() shouldNotContain secret
            current = current.cause
        }
    }

    private fun ILoggingEvent.assertNoSecretInAnyField(secret: String) {
        message.orEmpty() shouldNotContain secret
        formattedMessage.orEmpty() shouldNotContain secret
        mdcPropertyMap.toString() shouldNotContain secret
        argumentArray?.contentDeepToString().orEmpty() shouldNotContain secret
        keyValuePairs?.toString().orEmpty() shouldNotContain secret
        markerList?.toString().orEmpty() shouldNotContain secret
        throwableProxy.flatten().shouldNotContain(secret)
    }

    private fun IThrowableProxy?.flatten(): String {
        if (this == null) return ""
        return buildString {
            append(className).append(':').append(message)
            stackTraceElementProxyArray?.forEach(::append)
            suppressed?.forEach { append(it.flatten()) }
            append(cause.flatten())
        }
    }

    private class LoggerAttachment(
        val logger: Logger,
        val appender: ListAppender<ILoggingEvent>,
        val additive: Boolean,
    )

    private class LogCapture(
        private val attachments: List<LoggerAttachment>,
    ) {
        fun allEvents(): List<ILoggingEvent> = attachments.flatMap { it.appender.list }

        fun detachStopAndRestoreLoggerState() {
            attachments.forEach { attachment ->
                attachment.logger.detachAppender(attachment.appender)
                attachment.appender.stop()
                attachment.logger.isAdditive = attachment.additive
            }
        }
    }

    data class UnsupportedEvent(val secret: String) : Serializable {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class UnsupportedEventListenerConfiguration {
        @Bean
        fun unsupportedEventListener(): UnsupportedEventListener = UnsupportedEventListener()
    }

    open class UnsupportedEventListener {
        @ApplicationModuleListener(id = "test.unsupported-event")
        open fun on(event: UnsupportedEvent) = Unit
    }
}
