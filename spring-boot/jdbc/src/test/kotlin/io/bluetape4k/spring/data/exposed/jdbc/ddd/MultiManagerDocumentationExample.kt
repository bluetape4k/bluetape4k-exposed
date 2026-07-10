package io.bluetape4k.spring.data.exposed.jdbc.ddd

import io.bluetape4k.exposed.core.ddd.AbstractAggregateRoot
import io.bluetape4k.exposed.core.ddd.DomainEvent
import io.bluetape4k.spring.data.exposed.jdbc.annotation.ExposedEntity
import io.bluetape4k.spring.data.exposed.jdbc.repository.ExposedJdbcRepository
import io.bluetape4k.spring.data.exposed.jdbc.repository.config.EnableExposedJdbcRepositories
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.dao.LongEntity
import org.jetbrains.exposed.v1.dao.LongEntityClass
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.spring7.transaction.SpringTransactionManager
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.transaction.support.TransactionTemplate
import java.io.Serializable
import java.time.Instant
import javax.sql.DataSource

object Orders : LongIdTable("issue_323_orders") {
    val description = varchar("description", 200)
}

@ExposedEntity
class OrderEntity(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<OrderEntity>(Orders) {
        fun from(aggregate: OrderAggregate): OrderEntity = new {
            description = aggregate.description
        }
    }

    var description: String by Orders.description
}

interface OrderRepository : ExposedJdbcRepository<OrderEntity, Long>

class OrderAggregate(
    override val id: Long,
    val description: String,
) : AbstractAggregateRoot<Long>() {
    fun recordCreated(): OrderCreated = OrderCreated(id).also(::recordDomainEvent)
}

data class OrderCreated(
    override val aggregateId: Long,
    override val occurredAt: Instant = Instant.parse("2026-07-11T00:00:00Z"),
) : DomainEvent<Long>, Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

@Configuration(proxyBeanMethods = false)
class FirstOrderStoreConfiguration {
    @Bean(name = ["springTransactionManager", "firstTransactionManager"])
    fun firstTransactionManager(
        @Qualifier("firstDataSource") dataSource: DataSource,
    ): PlatformTransactionManager = SpringTransactionManager(dataSource, DatabaseConfig {}, false)
}

// issue-323-multi-manager:start
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
            repository.save(OrderEntity.from(aggregate))
            aggregateEventPublisher.publishAfterSave(aggregate)
            if (rollback) status.setRollbackOnly()
        }
    }
}
// issue-323-multi-manager:end

@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement
class MultiManagerTestConfiguration {
    @Bean(destroyMethod = "shutdown")
    fun firstDataSource(): EmbeddedDatabase = EmbeddedDatabaseBuilder()
        .generateUniqueName(true)
        .setType(EmbeddedDatabaseType.H2)
        .build()

    @Bean(destroyMethod = "shutdown")
    fun secondDataSource(): EmbeddedDatabase = EmbeddedDatabaseBuilder()
        .generateUniqueName(true)
        .setType(EmbeddedDatabaseType.H2)
        .build()

    @Bean
    fun aggregateEventPublisher(applicationEventPublisher: ApplicationEventPublisher): ExposedAggregateEventPublisher =
        ExposedAggregateEventPublisher(applicationEventPublisher)

    @Bean
    fun orderCommandService(
        repository: OrderRepository,
        aggregateEventPublisher: ExposedAggregateEventPublisher,
        @Qualifier("secondTransactionTemplate") transactionTemplate: TransactionTemplate,
    ): OrderCommandService = OrderCommandService(repository, aggregateEventPublisher, transactionTemplate)
}

class MultiManagerDocumentationExampleTest {

    @Test
    fun `repository count uses transactionManagerRef store`() {
        withContext { context ->
            seed(context, "firstTransactionManager", 1)
            seed(context, "secondTransactionManager", 2)

            context.getBean(OrderRepository::class.java).count() shouldBeEqualTo 2L
        }
    }

    @Test
    fun `repository deleteAll changes only transactionManagerRef store`() {
        withContext { context ->
            seed(context, "firstTransactionManager", 1)
            seed(context, "secondTransactionManager", 2)

            context.getBean(OrderRepository::class.java).deleteAll()

            count(context, "firstTransactionManager") shouldBeEqualTo 1L
            count(context, "secondTransactionManager") shouldBeEqualTo 0L
        }
    }

    @Test
    fun `command commits only the selected store and clears events`() {
        withContext { context ->
            prepareSchemas(context)
            val aggregate = OrderAggregate(1L, "committed").apply { recordCreated() }

            context.getBean(OrderCommandService::class.java).save(aggregate)

            count(context, "firstTransactionManager") shouldBeEqualTo 0L
            count(context, "secondTransactionManager") shouldBeEqualTo 1L
            aggregate.domainEvents().isEmpty().shouldBeTrue()
        }
    }

    @Test
    fun `command rollback preserves events and removes the selected store write`() {
        withContext { context ->
            prepareSchemas(context)
            val aggregate = OrderAggregate(2L, "rolled-back").apply { recordCreated() }

            context.getBean(OrderCommandService::class.java).save(aggregate, rollback = true)

            count(context, "firstTransactionManager") shouldBeEqualTo 0L
            count(context, "secondTransactionManager") shouldBeEqualTo 0L
            aggregate.domainEvents().size shouldBeEqualTo 1
        }
    }

    private fun withContext(block: (AnnotationConfigApplicationContext) -> Unit) {
        AnnotationConfigApplicationContext().use { context ->
            context.register(
                MultiManagerTestConfiguration::class.java,
                FirstOrderStoreConfiguration::class.java,
                SecondOrderStoreConfiguration::class.java,
            )
            context.refresh()
            block(context)
        }
    }

    private fun prepareSchemas(context: AnnotationConfigApplicationContext) {
        seed(context, "firstTransactionManager", 0)
        seed(context, "secondTransactionManager", 0)
    }

    private fun seed(context: AnnotationConfigApplicationContext, managerName: String, rows: Int) {
        val manager = context.getBean(managerName, PlatformTransactionManager::class.java)
        TransactionTemplate(manager).executeWithoutResult {
            SchemaUtils.create(Orders)
            repeat(rows) { index ->
                Orders.insert { it[description] = "$managerName-$index" }
            }
        }
    }

    private fun count(context: AnnotationConfigApplicationContext, managerName: String): Long {
        val manager = context.getBean(managerName, PlatformTransactionManager::class.java)
        return requireNotNull(TransactionTemplate(manager).execute { Orders.selectAll().count() })
    }
}
