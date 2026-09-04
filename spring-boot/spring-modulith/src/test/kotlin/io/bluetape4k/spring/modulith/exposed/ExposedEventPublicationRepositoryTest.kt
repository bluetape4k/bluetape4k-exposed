package io.bluetape4k.spring.modulith.exposed

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.codec.Base58
import io.bluetape4k.exposed.tests.AbstractExposedTest
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.idgenerators.uuid.Uuid as BluetapeUuid
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.spring7.transaction.SpringTransactionManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.modulith.events.EventPublication.Status
import org.springframework.modulith.events.core.EventPublicationRepository
import org.springframework.modulith.events.core.EventSerializer
import org.springframework.modulith.events.support.CompletionMode
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.io.Serializable
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@OptIn(ExperimentalUuidApi::class)
class ExposedEventPublicationRepositoryTest : AbstractExposedTest() {

    companion object {

        private const val OUTSTANDING_LISTENER_ID =
            "io.bluetape4k.spring.modulith.exposed.outstanding-test-listener"

        private const val REPLAY_BOUNDARY_LISTENER_ID =
            "io.bluetape4k.spring.modulith.exposed.replay-boundary-test-listener"

        private const val REPLAY_EVENT_SEPARATOR = "\u001F"

        private val republishedEvents = CopyOnWriteArrayList<String>()

        private val replayDeliveryIds = CopyOnWriteArrayList<String>()

        private val replayDeduplicationKeys = ConcurrentHashMap.newKeySet<String>()

        private val replayAppliedSideEffectIds = CopyOnWriteArrayList<String>()

        private val failReplayAfterSideEffect = AtomicBoolean()

        @JvmStatic
        fun enabledDialects(): Set<TestDB> = TestDB.enabledDialects()

        @JvmStatic
        fun dialectCompletionModes(): List<Arguments> =
            enabledDialects().flatMap { testDB ->
                CompletionMode.entries.map { completionMode -> Arguments.of(testDB, completionMode) }
            }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("enabledDialects")
    fun `publishes completes and queries failed events in update mode`(testDB: TestDB) {
        withApplicationContext(testDB, CompletionMode.UPDATE) { context ->
            val repository = context.getBean(EventPublicationRepository::class.java)
            repository.shouldNotBeNull()

            val created = targetEventPublicationOf(
                TestEvent("created"),
                publicationTargetIdentifierOf("listener.created"),
                Instant.parse("2026-05-16T00:00:00Z"),
            )

            repository.create(created)

            val stored = repository.findIncompletePublications().single()
            stored.identifier shouldBeEqualTo created.identifier
            stored.event shouldBeEqualTo created.event
            stored.status shouldBeEqualTo Status.PUBLISHED
            stored.completionAttempts shouldBeEqualTo 1

            repository.markProcessing(created.identifier)
            repository.findByStatus(Status.PROCESSING).single().identifier shouldBeEqualTo created.identifier

            repository.markCompleted(created.identifier, Instant.parse("2026-05-16T00:01:00Z"))

            repository.findIncompletePublications().shouldBeEmpty()
            repository.findCompletedPublications().single().status shouldBeEqualTo Status.COMPLETED

            val oldFailed = targetEventPublicationOf(
                TestEvent("old"),
                publicationTargetIdentifierOf("listener.failed"),
                Instant.parse("2026-05-16T00:00:00Z"),
            )
            val newFailed = targetEventPublicationOf(
                TestEvent("new"),
                publicationTargetIdentifierOf("listener.failed"),
                Instant.parse("2026-05-16T01:00:00Z"),
            )

            repository.create(oldFailed)
            repository.create(newFailed)
            repository.markFailed(oldFailed.identifier)
            repository.markFailed(newFailed.identifier)

            val failed = repository.findFailedPublications(
                EventPublicationRepository.FailedCriteria.ALL
                    .withPublicationsPublishedBefore(Instant.parse("2026-05-16T00:30:00Z"))
            )

            failed.map { it.identifier } shouldBeEqualTo listOf(oldFailed.identifier)
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("enabledDialects")
    fun `findFailedPublications - 무제한 sentinel은 유지하고 안전하지 않은 상한은 거부한다`(testDB: TestDB) {
        withApplicationContext(testDB, CompletionMode.UPDATE) { context ->
            val repository = context.getBean(EventPublicationRepository::class.java)
            val failedPublication = targetEventPublicationOf(
                TestEvent("failed-limit-${testDB.name.lowercase()}"),
                publicationTargetIdentifierOf("listener.failed.limit"),
                Instant.parse("2026-05-16T00:00:00Z"),
            )

            repository.create(failedPublication)
            repository.markFailed(failedPublication.identifier)

            repository.findFailedPublications(
                EventPublicationRepository.FailedCriteria.ALL.withItemsToRead(-1L),
            ).map { it.identifier } shouldBeEqualTo listOf(failedPublication.identifier)
            repository.findFailedPublications(
                EventPublicationRepository.FailedCriteria.ALL.withItemsToRead(Int.MAX_VALUE.toLong()),
            ).map { it.identifier } shouldBeEqualTo listOf(failedPublication.identifier)

            listOf(Int.MAX_VALUE.toLong() + 1L, Long.MAX_VALUE).forEach { maxItemsToRead ->
                val error = assertFailsWith<IllegalArgumentException> {
                    repository.findFailedPublications(
                        EventPublicationRepository.FailedCriteria.ALL.withItemsToRead(maxItemsToRead),
                    )
                }
                error.message shouldContain maxItemsToRead.toString()
            }
        }

        listOf(0L, -2L, Long.MIN_VALUE).forEach { maxItemsToRead ->
            assertFailsWith<IllegalArgumentException> {
                EventPublicationRepository.FailedCriteria.ALL.withItemsToRead(maxItemsToRead)
            }
        }
    }

    @Test
    fun `kotlin factories create publications and validate target identifiers`() {
        val targetIdentifier = publicationTargetIdentifierOf("listener.kotlin")
        val publication = targetEventPublicationOf(
            TestEvent("kotlin-factory"),
            targetIdentifier,
            Instant.parse("2026-05-16T00:00:00Z"),
        )

        targetIdentifier.value shouldBeEqualTo "listener.kotlin"
        publication.event shouldBeEqualTo TestEvent("kotlin-factory")
        publication.targetIdentifier shouldBeEqualTo targetIdentifier

        assertFailsWith<IllegalArgumentException> {
            publicationTargetIdentifierOf(" ")
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("enabledDialects")
    fun `delete completion mode removes completed publications`(testDB: TestDB) {
        withApplicationContext(testDB, CompletionMode.DELETE) { context ->
            val repository = context.getBean(EventPublicationRepository::class.java)
            val publication = targetEventPublicationOf(
                TestEvent("delete"),
                publicationTargetIdentifierOf("listener.delete"),
                Instant.parse("2026-05-16T00:00:00Z"),
            )

            repository.create(publication)
            repository.markCompleted(publication.identifier, Instant.parse("2026-05-16T00:01:00Z"))

            repository.findIncompletePublications().shouldBeEmpty()
            repository.findCompletedPublications().shouldBeEmpty()
            repository.countByStatus(Status.COMPLETED) shouldBeEqualTo 0
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("enabledDialects")
    fun `archive completion mode moves completed publications to archive table`(testDB: TestDB) {
        withApplicationContext(testDB, CompletionMode.ARCHIVE) { context ->
            val repository = context.getBean(EventPublicationRepository::class.java)
            val publication = targetEventPublicationOf(
                TestEvent("archive"),
                publicationTargetIdentifierOf("listener.archive"),
                Instant.parse("2026-05-16T00:00:00Z"),
            )

            repository.create(publication)
            repository.markCompleted(publication.identifier, Instant.parse("2026-05-16T00:01:00Z"))

            repository.findIncompletePublications().shouldBeEmpty()
            repository.findCompletedPublications().single().identifier shouldBeEqualTo publication.identifier
            repository.findByStatus(Status.COMPLETED).single().identifier shouldBeEqualTo publication.identifier
            repository.countByStatus(Status.COMPLETED) shouldBeEqualTo 1

            repository.deleteCompletedPublicationsBefore(Instant.parse("2026-05-16T00:02:00Z"))
            repository.findCompletedPublications().shouldBeEmpty()
        }
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("dialectCompletionModes")
    fun `duplicate identifier completion is idempotent`(testDB: TestDB, completionMode: CompletionMode) {
        withApplicationContext(testDB, completionMode) { context ->
            val repository = context.getBean(EventPublicationRepository::class.java)
            val publication = targetEventPublicationOf(
                TestEvent("duplicate-identifier-${completionMode.name.lowercase()}"),
                publicationTargetIdentifierOf("listener.duplicate.identifier"),
                Instant.parse("2026-05-16T00:00:00Z"),
            )
            val firstCompletionDate = Instant.parse("2026-05-16T00:01:00Z")
            val duplicateCompletionDate = Instant.parse("2026-05-16T00:02:00Z")

            repository.create(publication)

            repository.markCompleted(publication.identifier, firstCompletionDate)
            repository.markCompleted(publication.identifier, duplicateCompletionDate)

            repository.findIncompletePublications().shouldBeEmpty()

            when (completionMode) {
                CompletionMode.DELETE ->
                    repository.findCompletedPublications().shouldBeEmpty()

                CompletionMode.ARCHIVE,
                CompletionMode.UPDATE -> {
                    val completed = repository.findCompletedPublications().single()
                    completed.identifier shouldBeEqualTo publication.identifier
                    completed.status shouldBeEqualTo Status.COMPLETED
                    completed.completionDate.orElseThrow() shouldBeEqualTo firstCompletionDate
                }
            }
        }
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("dialectCompletionModes")
    fun `event and listener completion handles duplicate rows idempotently`(
        testDB: TestDB,
        completionMode: CompletionMode,
    ) {
        withApplicationContext(testDB, completionMode) { context ->
            val repository = context.getBean(EventPublicationRepository::class.java)
            val event = TestEvent("shared-event")
            val targetIdentifier = publicationTargetIdentifierOf("listener.shared")
            val first = targetEventPublicationOf(
                event,
                targetIdentifier,
                Instant.parse("2026-05-16T00:00:00Z"),
            )
            val second = targetEventPublicationOf(
                event,
                targetIdentifier,
                Instant.parse("2026-05-16T00:01:00Z"),
            )
            val firstCompletionDate = Instant.parse("2026-05-16T00:02:00Z")
            val duplicateCompletionDate = Instant.parse("2026-05-16T00:03:00Z")

            repository.create(first)
            repository.create(second)

            repository.markCompleted(event, targetIdentifier, firstCompletionDate)
            repository.markCompleted(event, targetIdentifier, duplicateCompletionDate)

            repository.findIncompletePublications().shouldBeEmpty()

            when (completionMode) {
                CompletionMode.DELETE ->
                    repository.findCompletedPublications().shouldBeEmpty()

                CompletionMode.ARCHIVE,
                CompletionMode.UPDATE -> {
                    val completed = repository.findCompletedPublications()
                    completed shouldHaveSize 2
                    completed.map { it.identifier }
                        .toSet() shouldBeEqualTo setOf(first.identifier, second.identifier)
                    completed.map { it.status }.toSet() shouldBeEqualTo setOf(Status.COMPLETED)
                    completed.map { it.completionDate.orElseThrow() }
                        .toSet() shouldBeEqualTo setOf(firstCompletionDate)
                }
            }
        }
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("dialectCompletionModes")
    fun `repeated resubmission leaves attempts and timestamp unchanged`(
        testDB: TestDB,
        completionMode: CompletionMode,
    ) {
        withApplicationContext(testDB, completionMode) { context ->
            val repository = context.getBean(EventPublicationRepository::class.java)
            val publication = targetEventPublicationOf(
                TestEvent("resubmission-${completionMode.name.lowercase()}"),
                publicationTargetIdentifierOf("listener.resubmission"),
                Instant.parse("2026-05-16T00:00:00Z"),
            )
            val firstResubmissionDate = Instant.parse("2026-05-16T00:05:00Z")
            val duplicateResubmissionDate = Instant.parse("2026-05-16T00:06:00Z")

            repository.create(publication)
            repository.markFailed(publication.identifier)

            repository.markResubmitted(publication.identifier, firstResubmissionDate).shouldBeTrue()
            repository.markResubmitted(publication.identifier, duplicateResubmissionDate).shouldBeFalse()

            val resubmitted = repository.findByStatus(Status.RESUBMITTED).single()
            resubmitted.identifier shouldBeEqualTo publication.identifier
            resubmitted.completionAttempts shouldBeEqualTo 2
            resubmitted.lastResubmissionDate shouldBeEqualTo firstResubmissionDate
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("enabledDialects")
    fun `resubmitted publication increments attempts and can be found by event and target`(testDB: TestDB) {
        withApplicationContext(testDB, CompletionMode.UPDATE) { context ->
            val repository = context.getBean(EventPublicationRepository::class.java)
            val event = TestEvent("resubmit")
            val targetIdentifier = publicationTargetIdentifierOf("listener.resubmit")
            val publication = targetEventPublicationOf(
                event,
                targetIdentifier,
                Instant.parse("2026-05-16T00:00:00Z"),
            )

            repository.create(publication)
            repository.markFailed(publication.identifier)

            val failed = repository.findIncompletePublicationsByEventAndTargetIdentifier(event, targetIdentifier)
            failed.isPresent.shouldBeTrue()
            failed.get().status shouldBeEqualTo Status.FAILED

            repository.markResubmitted(publication.identifier, Instant.parse("2026-05-16T00:05:00Z")).shouldBeTrue()
            repository.markResubmitted(publication.identifier, Instant.parse("2026-05-16T00:06:00Z")).shouldBeFalse()

            val resubmitted = repository.findByStatus(Status.RESUBMITTED).single()
            resubmitted.completionAttempts shouldBeEqualTo 2
            resubmitted.lastResubmissionDate shouldBeEqualTo Instant.parse("2026-05-16T00:05:00Z")

            repository.deletePublications(listOf(publication.identifier))
            repository.findIncompletePublications().shouldBeEmpty()
        }
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("dialectCompletionModes")
    fun `outstanding publications are republished on restart and completed publications are skipped`(
        testDB: TestDB,
        completionMode: CompletionMode,
    ) {
        val tableName = eventPublicationTableName()
        val outstandingEvent = TestEvent("outstanding-${completionMode.name.lowercase()}")
        val completedEvent = TestEvent("completed-${completionMode.name.lowercase()}")

        republishedEvents.clear()

        withApplicationContext(testDB, completionMode, tableName = tableName) { context ->
            val repository = context.getBean(EventPublicationRepository::class.java)

            context.publishEvent(outstandingEvent)
            context.publishEvent(completedEvent)

            val stored = repository.findIncompletePublications()
            stored.map { it.event }.toSet() shouldBeEqualTo setOf(outstandingEvent, completedEvent)

            val completedPublication = stored.single { it.event == completedEvent }
            repository.markCompleted(completedPublication.identifier, Instant.parse("2026-05-16T00:02:00Z"))

            repository.findIncompletePublications().map { it.event } shouldBeEqualTo listOf(outstandingEvent)
        }

        republishedEvents.clear()

        withApplicationContext(
            testDB,
            completionMode,
            tableName = tableName,
            republishOutstandingOnRestart = true,
        ) { context ->
            val repository = context.getBean(EventPublicationRepository::class.java)

            awaitCondition { republishedEvents == listOf(outstandingEvent.value) }
            republishedEvents shouldBeEqualTo listOf(outstandingEvent.value)
            republishedEvents shouldNotContain completedEvent.value
            awaitCondition { repository.findIncompletePublications().isEmpty() }
            repository.findIncompletePublications().shouldBeEmpty()
        }
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("dialectCompletionModes")
    fun `processing publication replays with stable identity after crash window`(
        testDB: TestDB,
        completionMode: CompletionMode,
    ) {
        val tableName = eventPublicationTableName()
        val event = ReplayBoundaryEvent(
            eventId = "event-${completionMode.name.lowercase()}-${Base58.randomString(8)}",
            value = "sensitive-order-payload",
        )

        replayDeliveryIds.clear()
        replayDeduplicationKeys.clear()
        replayAppliedSideEffectIds.clear()
        failReplayAfterSideEffect.set(true)

        withApplicationContext(testDB, completionMode, tableName = tableName) { context ->
            val repository = context.getBean(EventPublicationRepository::class.java)
            val txManager = context.getBean("springTransactionManager", PlatformTransactionManager::class.java)

            TransactionTemplate(txManager).executeWithoutResult {
                context.publishEvent(event)
            }

            awaitCondition { replayDeliveryIds == listOf(event.eventId) }
            awaitCondition {
                repository.findIncompletePublications().single().status == Status.FAILED
            }
            val failed = repository.findIncompletePublications().single()
            failed.event shouldBeEqualTo event
            failed.status shouldBeEqualTo Status.FAILED
            replayDeliveryIds shouldBeEqualTo listOf(event.eventId)
            replayAppliedSideEffectIds shouldBeEqualTo listOf(event.eventId)
        }

        withApplicationContext(
            testDB,
            completionMode,
            tableName = tableName,
            republishOutstandingOnRestart = true,
        ) { context ->
            val repository = context.getBean(EventPublicationRepository::class.java)

            awaitCondition { replayDeliveryIds.size == 2 }
            awaitCondition { repository.findIncompletePublications().isEmpty() }

            replayDeliveryIds shouldBeEqualTo listOf(event.eventId, event.eventId)
            replayAppliedSideEffectIds shouldBeEqualTo listOf(event.eventId)

            when (completionMode) {
                CompletionMode.DELETE ->
                    repository.findCompletedPublications().shouldBeEmpty()

                CompletionMode.ARCHIVE,
                CompletionMode.UPDATE -> {
                    val completed = repository.findCompletedPublications().single()
                    completed.event shouldBeEqualTo event
                    completed.status shouldBeEqualTo Status.COMPLETED
                }
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("enabledDialects")
    fun `publications with unloadable event classes remain visible and fail on event access`(testDB: TestDB) {
        withApplicationContext(testDB, CompletionMode.UPDATE) { context ->
            val repository = context.getBean(EventPublicationRepository::class.java)
            val table = context.getBean("eventPublicationTable", ExposedEventPublicationTable::class.java)
            val txManager = context.getBean("springTransactionManager", PlatformTransactionManager::class.java)
            val missingEventType = "com.example.missing.LegacyEvent"
            val publishedId = nextJavaUuid()
            val failedId = nextJavaUuid()

            TransactionTemplate(txManager).executeWithoutResult {
                table.insertUnknownPublication(
                    id = publishedId,
                    eventType = missingEventType,
                    listenerId = "listener.unloadable.published",
                    status = Status.PUBLISHED,
                )
                table.insertUnknownPublication(
                    id = failedId,
                    eventType = missingEventType,
                    listenerId = "listener.unloadable.failed",
                    status = Status.FAILED,
                )
            }

            val incomplete = repository.findIncompletePublications()
            incomplete.map { it.identifier }.toSet() shouldBeEqualTo setOf(publishedId, failedId)

            val failed = repository.findFailedPublications(EventPublicationRepository.FailedCriteria.ALL).single()
            failed.identifier shouldBeEqualTo failedId

            assertFailsWith<UnloadableEventPublicationException> {
                incomplete.single { it.identifier == publishedId }.event
            }
            assertFailsWith<UnloadableEventPublicationException> {
                failed.event
            }
        }
    }

    private fun withApplicationContext(
        testDB: TestDB,
        completionMode: CompletionMode,
        tableName: String = eventPublicationTableName(),
        republishOutstandingOnRestart: Boolean = false,
        block: (ConfigurableApplicationContext) -> Unit,
    ) {
        SpringApplicationBuilder(TestConfig::class.java)
            .web(WebApplicationType.NONE)
            .properties(
                mapOf(
                    "spring.application.name" to "exposed-spring-modulith-${testDB.name.lowercase()}-test",
                    "spring.datasource.url" to testDB.connection(),
                    "spring.datasource.driver-class-name" to testDB.driver,
                    "spring.datasource.username" to testDB.user,
                    "spring.datasource.password" to testDB.pass,
                    "bluetape4k.spring.modulith.exposed.table-name" to tableName,
                    "bluetape4k.spring.modulith.exposed.archive-table-name" to "${tableName}_ARCHIVE",
                    "bluetape4k.spring.modulith.exposed.completion-mode" to completionMode.name,
                    "bluetape4k.spring.modulith.exposed.initialize-schema" to "true",
                    "spring.modulith.events.republish-outstanding-events-on-restart" to
                            republishOutstandingOnRestart.toString(),
                )
            )
            .run()
            .use(block)
    }

    private fun eventPublicationTableName(): String =
        "EVENT_PUBLICATION_${Base58.randomString(8)}"

    private fun awaitCondition(predicate: () -> Boolean) {
        repeat(100) {
            if (predicate()) return
            Thread.sleep(10)
        }
        predicate().shouldBeTrue()
    }

    @Configuration
    @EnableAutoConfiguration(
        excludeName = [
            "org.jetbrains.exposed.v1.spring.boot4.autoconfigure.ExposedAutoConfiguration",
            "org.springframework.boot.data.jdbc.autoconfigure.DataJdbcRepositoriesAutoConfiguration",
        ]
    )
    class TestConfig {
        @Bean
        fun dataSource(environment: Environment): DataSource {
            val config = HikariConfig().apply {
                jdbcUrl = environment.getRequiredProperty("spring.datasource.url")
                driverClassName = environment.getRequiredProperty("spring.datasource.driver-class-name")
                username = environment.getRequiredProperty("spring.datasource.username")
                password = environment.getRequiredProperty("spring.datasource.password")
                maximumPoolSize = 4
            }
            return HikariDataSource(config)
        }

        @Bean("springTransactionManager")
        fun springTransactionManager(dataSource: DataSource): PlatformTransactionManager =
            SpringTransactionManager(dataSource, DatabaseConfig {}, false)

        @Bean
        fun eventSerializer(): EventSerializer = TestEventSerializer()

        @Bean
        fun outstandingTestListener(): OutstandingTestListener =
            OutstandingTestListener()
    }

    open class OutstandingTestListener {

        @ApplicationModuleListener(id = OUTSTANDING_LISTENER_ID)
        open fun on(event: TestEvent) {
            republishedEvents += event.value
        }

        @ApplicationModuleListener(id = REPLAY_BOUNDARY_LISTENER_ID)
        open fun onReplayBoundary(event: ReplayBoundaryEvent) {
            replayDeliveryIds += event.eventId

            if (replayDeduplicationKeys.add(event.eventId)) {
                replayAppliedSideEffectIds += event.eventId
            }

            check(!failReplayAfterSideEffect.getAndSet(false)) {
                "Simulated process failure after side effect and before durable completion"
            }
        }
    }

    data class TestEvent(val value: String) : Serializable {
        companion object {
            private const val serialVersionUID: Long = 7240327694587830410L
        }
    }

    data class ReplayBoundaryEvent(
        val eventId: String,
        val value: String,
    ) : Serializable {
        companion object {
            private const val serialVersionUID: Long = -508289256945556177L
        }
    }

    private fun ExposedEventPublicationTable.insertUnknownPublication(
        id: UUID,
        eventType: String,
        listenerId: String,
        status: Status,
    ) {
        val publicationDate = Instant.parse("2026-05-16T00:00:00Z")
        insert { row ->
            row[this@insertUnknownPublication.id] = Uuid.parse(id.toString())
            row[this@insertUnknownPublication.eventType] = eventType
            row[this@insertUnknownPublication.listenerId] = listenerId
            row[serializedEvent] = """{"value":"legacy"}"""
            row[this@insertUnknownPublication.publicationDate] = publicationDate
            row[completionDate] = null
            row[this@insertUnknownPublication.status] = status.name
            row[completionAttempts] = 1
            row[lastResubmissionDate] = publicationDate
        }
    }

    private fun nextJavaUuid(): UUID =
        BluetapeUuid.V7.nextId()

    class TestEventSerializer : EventSerializer {
        override fun serialize(event: Any): Any =
            when (event) {
                is ReplayBoundaryEvent -> event.eventId + REPLAY_EVENT_SEPARATOR + event.value
                is TestEvent -> event.value
                else -> error("Unsupported test event type: ${event.javaClass.name}")
            }

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> deserialize(serialized: Any, type: Class<T>): T {
            val event = when (type) {
                ReplayBoundaryEvent::class.java -> {
                    val (eventId, value) = serialized.toString().split(REPLAY_EVENT_SEPARATOR, limit = 2)
                    ReplayBoundaryEvent(eventId, value)
                }

                TestEvent::class.java -> TestEvent(serialized.toString())
                else -> error("Unsupported test event type: ${type.name}")
            }
            return event as T
        }
    }
}
