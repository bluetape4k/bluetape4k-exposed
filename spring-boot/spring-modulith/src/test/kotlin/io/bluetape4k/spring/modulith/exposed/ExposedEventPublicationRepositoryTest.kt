package io.bluetape4k.spring.modulith.exposed

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.codec.Base58
import io.bluetape4k.exposed.tests.AbstractExposedTest
import io.bluetape4k.exposed.tests.TestDB
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.spring7.transaction.SpringTransactionManager
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.modulith.events.EventPublication.Status
import org.springframework.modulith.events.core.EventPublicationRepository
import org.springframework.modulith.events.core.EventSerializer
import org.springframework.modulith.events.core.PublicationTargetIdentifier
import org.springframework.modulith.events.core.TargetEventPublication
import org.springframework.modulith.events.support.CompletionMode
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@OptIn(ExperimentalUuidApi::class)
class ExposedEventPublicationRepositoryTest : AbstractExposedTest() {

    companion object {

        @JvmStatic
        fun enabledDialects(): Set<TestDB> = TestDB.enabledDialects()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("enabledDialects")
    fun `publishes completes and queries failed events in update mode`(testDB: TestDB) {
        withApplicationContext(testDB, CompletionMode.UPDATE) { context ->
            val repository = context.getBean(EventPublicationRepository::class.java)
            repository.shouldNotBeNull()

            val created = TargetEventPublication.of(
                TestEvent("created"),
                PublicationTargetIdentifier.of("listener.created"),
                Instant.parse("2026-05-16T00:00:00Z"),
            )

            repository.create(created)

            val stored = repository.findIncompletePublications().single()
            stored.identifier.shouldBeEqualTo(created.identifier)
            stored.event.shouldBeEqualTo(created.event)
            stored.status.shouldBeEqualTo(Status.PUBLISHED)
            stored.completionAttempts.shouldBeEqualTo(1)

            repository.markProcessing(created.identifier)
            repository.findByStatus(Status.PROCESSING).single().identifier.shouldBeEqualTo(created.identifier)

            repository.markCompleted(created.identifier, Instant.parse("2026-05-16T00:01:00Z"))

            repository.findIncompletePublications().size.shouldBeEqualTo(0)
            repository.findCompletedPublications().single().status.shouldBeEqualTo(Status.COMPLETED)

            val oldFailed = TargetEventPublication.of(
                TestEvent("old"),
                PublicationTargetIdentifier.of("listener.failed"),
                Instant.parse("2026-05-16T00:00:00Z"),
            )
            val newFailed = TargetEventPublication.of(
                TestEvent("new"),
                PublicationTargetIdentifier.of("listener.failed"),
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

            failed.map { it.identifier }.shouldBeEqualTo(listOf(oldFailed.identifier))
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("enabledDialects")
    fun `delete completion mode removes completed publications`(testDB: TestDB) {
        withApplicationContext(testDB, CompletionMode.DELETE) { context ->
            val repository = context.getBean(EventPublicationRepository::class.java)
            val publication = TargetEventPublication.of(
                TestEvent("delete"),
                PublicationTargetIdentifier.of("listener.delete"),
                Instant.parse("2026-05-16T00:00:00Z"),
            )

            repository.create(publication)
            repository.markCompleted(publication.identifier, Instant.parse("2026-05-16T00:01:00Z"))

            repository.findIncompletePublications().size.shouldBeEqualTo(0)
            repository.findCompletedPublications().size.shouldBeEqualTo(0)
            repository.countByStatus(Status.COMPLETED).shouldBeEqualTo(0)
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("enabledDialects")
    fun `archive completion mode moves completed publications to archive table`(testDB: TestDB) {
        withApplicationContext(testDB, CompletionMode.ARCHIVE) { context ->
            val repository = context.getBean(EventPublicationRepository::class.java)
            val publication = TargetEventPublication.of(
                TestEvent("archive"),
                PublicationTargetIdentifier.of("listener.archive"),
                Instant.parse("2026-05-16T00:00:00Z"),
            )

            repository.create(publication)
            repository.markCompleted(publication.identifier, Instant.parse("2026-05-16T00:01:00Z"))

            repository.findIncompletePublications().size.shouldBeEqualTo(0)
            repository.findCompletedPublications().single().identifier.shouldBeEqualTo(publication.identifier)
            repository.findByStatus(Status.COMPLETED).single().identifier.shouldBeEqualTo(publication.identifier)
            repository.countByStatus(Status.COMPLETED).shouldBeEqualTo(1)

            repository.deleteCompletedPublicationsBefore(Instant.parse("2026-05-16T00:02:00Z"))
            repository.findCompletedPublications().size.shouldBeEqualTo(0)
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("enabledDialects")
    fun `resubmitted publication increments attempts and can be found by event and target`(testDB: TestDB) {
        withApplicationContext(testDB, CompletionMode.UPDATE) { context ->
            val repository = context.getBean(EventPublicationRepository::class.java)
            val event = TestEvent("resubmit")
            val targetIdentifier = PublicationTargetIdentifier.of("listener.resubmit")
            val publication = TargetEventPublication.of(
                event,
                targetIdentifier,
                Instant.parse("2026-05-16T00:00:00Z"),
            )

            repository.create(publication)
            repository.markFailed(publication.identifier)

            val failed = repository.findIncompletePublicationsByEventAndTargetIdentifier(event, targetIdentifier)
            failed.isPresent.shouldBeEqualTo(true)
            failed.get().status.shouldBeEqualTo(Status.FAILED)

            repository.markResubmitted(publication.identifier, Instant.parse("2026-05-16T00:05:00Z"))
                .shouldBeEqualTo(true)
            repository.markResubmitted(publication.identifier, Instant.parse("2026-05-16T00:06:00Z"))
                .shouldBeEqualTo(false)

            val resubmitted = repository.findByStatus(Status.RESUBMITTED).single()
            resubmitted.completionAttempts.shouldBeEqualTo(2)
            resubmitted.lastResubmissionDate.shouldBeEqualTo(Instant.parse("2026-05-16T00:05:00Z"))

            repository.deletePublications(listOf(publication.identifier))
            repository.findIncompletePublications().size.shouldBeEqualTo(0)
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
            val publishedId = UUID.randomUUID()
            val failedId = UUID.randomUUID()

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
            incomplete.map { it.identifier }.toSet().shouldBeEqualTo(setOf(publishedId, failedId))

            val failed = repository.findFailedPublications(EventPublicationRepository.FailedCriteria.ALL).single()
            failed.identifier.shouldBeEqualTo(failedId)

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
        block: (ConfigurableApplicationContext) -> Unit,
    ) {
        val tableSuffix = Base58.randomString(8)
        val tableName = "EVENT_PUBLICATION_$tableSuffix"

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
                )
            )
            .run()
            .use(block)
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
    }

    data class TestEvent(val value: String)

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

    class TestEventSerializer : EventSerializer {
        override fun serialize(event: Any): Any =
            (event as TestEvent).value

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> deserialize(serialized: Any, type: Class<T>): T =
            TestEvent(serialized.toString()) as T
    }
}
