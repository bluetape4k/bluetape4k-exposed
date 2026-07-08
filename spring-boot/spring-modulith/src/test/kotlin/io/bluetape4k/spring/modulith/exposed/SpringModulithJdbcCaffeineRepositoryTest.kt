package io.bluetape4k.spring.modulith.exposed

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.exposed.cache.CacheWriteMode
import io.bluetape4k.exposed.cache.LocalCacheConfig
import io.bluetape4k.exposed.tests.AbstractExposedTest
import io.bluetape4k.exposed.tests.TestDB
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.statements.BatchInsertStatement
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp
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
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.modulith.events.core.EventSerializer
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.io.Serializable
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SpringModulithJdbcCaffeineRepositoryTest: AbstractExposedTest() {

    companion object {
        @JvmStatic
        fun enabledDialects(): Set<TestDB> = setOf(TestDB.H2_MYSQL)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("enabledDialects")
    fun `write-through publishes through real Spring application event path`(testDB: TestDB) {
        withApplicationContext(testDB, CacheWriteMode.WRITE_THROUGH) { context ->
            val repository = context.getBean(CacheActorRepository::class.java)
            val listenerState = context.getBean(CacheActorListenerState::class.java)
            val txManager = context.getBean("springTransactionManager", PlatformTransactionManager::class.java)
            val actor = repository.findAllFromDb(emptyList()).let {
                transaction { repository.table.selectAll().first().let { row -> with(repository) { row.toEntity() } } }
            }
            val updated = actor.copy(name = "event-write-through")

            TransactionTemplate(txManager).executeWithoutResult {
                repository.put(actor.id, updated)
            }

            listenerState.receivedLatch.await(5, TimeUnit.SECONDS).shouldBeTrue()
            listenerState.events shouldHaveSize 1
            listenerState.events.single().actorId shouldBeEqualTo actor.id
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("enabledDialects")
    fun `write-behind publishes through real Spring application event path after flush`(testDB: TestDB) {
        withApplicationContext(testDB, CacheWriteMode.WRITE_BEHIND) { context ->
            val repository = context.getBean(CacheActorRepository::class.java)
            val listenerState = context.getBean(CacheActorListenerState::class.java)
            val actor =
                transaction {
                    repository.table.selectAll().first().let { row -> with(repository) { row.toEntity() } }
                }
            val updated = actor.copy(name = "event-write-behind")

            repository.put(actor.id, updated)

            listenerState.receivedLatch.await(5, TimeUnit.SECONDS).shouldBeTrue()
            awaitCondition { repository.validateConsistency().queueDepth == 0 }
            listenerState.events shouldHaveSize 1
            listenerState.events.single().actorId shouldBeEqualTo actor.id
        }
    }

    private fun withApplicationContext(
        testDB: TestDB,
        writeMode: CacheWriteMode,
        block: (ConfigurableApplicationContext) -> Unit,
    ) {
        val tableName = "CACHE_ACTOR_${writeMode.name}_${System.nanoTime()}".take(48)

        SpringApplicationBuilder(TestConfig::class.java)
            .web(WebApplicationType.NONE)
            .properties(
                mapOf(
                    "spring.application.name" to "cache-event-${testDB.name.lowercase()}-test",
                    "spring.datasource.url" to testDB.connection(),
                    "spring.datasource.driver-class-name" to testDB.driver,
                    "spring.datasource.username" to testDB.user,
                    "spring.datasource.password" to testDB.pass,
                    "bluetape4k.spring.modulith.exposed.table-name" to "EVENT_PUBLICATION_${System.nanoTime()}",
                    "bluetape4k.spring.modulith.exposed.initialize-schema" to "true",
                    "test.cache.actor.table-name" to tableName,
                    "test.cache.actor.write-mode" to writeMode.name,
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
        fun eventSerializer(): EventSerializer = CacheEventSerializer()

        @Bean
        fun cacheActorTable(environment: Environment): CacheActorTable =
            CacheActorTable(environment.getRequiredProperty("test.cache.actor.table-name"))

        @Bean
        fun cacheActorSchemaInitializer(table: CacheActorTable): org.springframework.beans.factory.SmartInitializingSingleton =
            org.springframework.beans.factory.SmartInitializingSingleton {
                transaction {
                    SchemaUtils.create(table)
                    table.insert {
                        it[name] = "initial"
                    }
                }
            }

        @Bean
        fun cacheActorRepository(
            environment: Environment,
            table: CacheActorTable,
            events: org.springframework.context.ApplicationEventPublisher,
            txManager: PlatformTransactionManager,
        ): CacheActorRepository =
            CacheActorRepository(
                table = table,
                config = LocalCacheConfig(
                    keyPrefix = "spring-modulith-cache-event-test",
                    writeMode = CacheWriteMode.valueOf(environment.getRequiredProperty("test.cache.actor.write-mode")),
                    writeBehindBatchSize = 1,
                    writeBehindQueueCapacity = 16,
                ),
                events = events,
                transactionOperations = TransactionTemplate(txManager),
            )

        @Bean
        fun cacheActorListenerState(): CacheActorListenerState =
            CacheActorListenerState()

        @Bean
        fun cacheActorListener(state: CacheActorListenerState): CacheActorListener =
            CacheActorListener(state)
    }

    class CacheActorTable(tableName: String): LongIdTable(tableName) {
        val name = varchar("name", 80)
        val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
    }

    data class CacheActorRecord(
        val id: Long,
        val name: String,
        val updatedAt: Instant = Instant.now(),
    ): Serializable {
        companion object {
            private const val serialVersionUID: Long = 125181816558894574L
        }
    }

    data class CacheActorUpdatedEvent(
        val actorId: Long,
    ): Serializable {
        companion object {
            private const val serialVersionUID: Long = 6750798894544568113L
        }
    }

    class CacheActorRepository(
        override val table: CacheActorTable,
        config: LocalCacheConfig,
        events: org.springframework.context.ApplicationEventPublisher,
        transactionOperations: TransactionTemplate,
    ): SpringModulithJdbcCaffeineRepository<Long, CacheActorRecord>(
        config,
        events,
        transactionOperations,
    ) {

        override fun ResultRow.toEntity(): CacheActorRecord =
            CacheActorRecord(
                id = this[table.id].value,
                name = this[table.name],
                updatedAt = this[table.updatedAt],
            )

        override fun UpdateStatement.updateEntity(entity: CacheActorRecord) {
            this[this@CacheActorRepository.table.name] = entity.name
        }

        override fun BatchInsertStatement.insertEntity(entity: CacheActorRecord) {
            this[this@CacheActorRepository.table.name] = entity.name
        }

        override fun extractId(entity: CacheActorRecord): Long = entity.id

        override fun toDomainEvent(id: Long, entity: CacheActorRecord): Any =
            CacheActorUpdatedEvent(actorId = id)
    }

    class CacheActorListenerState {
        val receivedLatch = CountDownLatch(1)
        val events = CopyOnWriteArrayList<CacheActorUpdatedEvent>()
    }

    open class CacheActorListener(
        private val state: CacheActorListenerState,
    ) {

        @ApplicationModuleListener
        open fun on(event: CacheActorUpdatedEvent) {
            state.events += event
            state.receivedLatch.countDown()
        }
    }

    class CacheEventSerializer: EventSerializer {
        override fun serialize(event: Any): Any =
            when (event) {
                is CacheActorUpdatedEvent -> event.actorId.toString()
                else -> event.toString()
            }

        @Suppress("UNCHECKED_CAST")
        override fun <T: Any> deserialize(serialized: Any, type: Class<T>): T =
            CacheActorUpdatedEvent(serialized.toString().toLong()) as T
    }

    private fun awaitCondition(predicate: () -> Boolean) {
        repeat(100) {
            if (predicate()) return
            Thread.sleep(10)
        }
        predicate().shouldBeTrue()
    }
}
