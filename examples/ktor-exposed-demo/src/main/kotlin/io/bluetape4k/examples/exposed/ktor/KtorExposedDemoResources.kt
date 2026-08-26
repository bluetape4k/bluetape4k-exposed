package io.bluetape4k.examples.exposed.ktor

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.examples.exposed.ktor.order.InMemoryOrderEventPublisher
import io.bluetape4k.examples.exposed.ktor.order.OrderCommandService
import io.bluetape4k.examples.exposed.ktor.order.OrderR2dbcCaffeineRepository
import io.r2dbc.pool.ConnectionPool
import io.r2dbc.pool.ConnectionPoolConfiguration
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactoryOptions
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabaseConfig
import org.jetbrains.exposed.v1.r2dbc.SchemaUtils as R2dbcSchemaUtils
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager as R2dbcTransactionManager
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.io.Serializable
import java.time.Clock
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object DemoItems : LongIdTable("ktor_demo_items") {
    val name = varchar("name", 80)
}

data class KtorExposedDemoConfig(
    val r2dbcUrl: String = System.getenv("DEMO_POSTGRES_R2DBC_URL")
        ?: "r2dbc:postgresql://localhost:5432/ktor_exposed_demo",
    val user: String = System.getenv("DEMO_POSTGRES_USER") ?: "demo",
    val password: String = System.getenv("DEMO_POSTGRES_PASSWORD") ?: "demo",
) : Serializable {

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

data class DemoCleanupReport(val failures: List<Throwable>) : Serializable {
    val isClean: Boolean get() = failures.isEmpty()

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal class NamedCloseAction(
    val name: String,
    val close: () -> Unit,
)

internal class DemoResourceSteps {
    private val completed = ArrayList<NamedCloseAction>()

    fun completed(name: String, close: () -> Unit) {
        completed += NamedCloseAction(name, close)
    }

    @Suppress("TooGenericExceptionCaught") // resource cleanup에서 임의 close action의 failure를 집계한다.
    fun unwind(primary: RuntimeException) {
        completed.asReversed().forEach { action ->
            try {
                action.close()
            } catch (cleanupFailure: RuntimeException) {
                if (cleanupFailure !== primary) {
                    primary.addSuppressed(cleanupFailure)
                }
            }
        }
    }
}

internal class DemoResourceAcquirer {
    @Suppress("TooGenericExceptionCaught") // acquisition rollback에서 모든 close failure를 처리한다.
    fun <T> acquire(block: DemoResourceSteps.() -> T): T {
        val steps = DemoResourceSteps()
        return try {
            steps.block()
        } catch (primary: RuntimeException) {
            steps.unwind(primary)
            throw primary
        }
    }
}

internal class DemoLifecycleLease(
    private val active: AtomicBoolean = GLOBAL_DEMO_LEASE,
) {
    fun acquire(): AutoCloseable {
        check(active.compareAndSet(false, true)) {
            "Only one Ktor Exposed demo resource lifecycle may be active."
        }
        return object : AutoCloseable {
            private val released = AtomicBoolean()

            override fun close() {
                if (released.compareAndSet(false, true)) {
                    active.set(false)
                }
            }
        }
    }
}

class KtorExposedDemoResources internal constructor(
    val jdbcDatabase: Database,
    val r2dbcDatabase: R2dbcDatabase,
    val jdbcDispatcher: ExecutorCoroutineDispatcher,
    val orderRepository: OrderR2dbcCaffeineRepository,
    val eventPublisher: InMemoryOrderEventPublisher,
    val orderService: OrderCommandService,
    private val closeActions: List<NamedCloseAction>,
) : AutoCloseable {

    private val closeLock = ReentrantLock()

    @Volatile
    private var completedCloseReport: DemoCleanupReport? = null

    @Suppress("TooGenericExceptionCaught") // cleanup report가 임의 close failure를 기록하는 경계다.
    fun closeReport(): DemoCleanupReport = closeLock.withLock {
        completedCloseReport?.let { return it }

        val failures = ArrayList<Throwable>()
        closeActions.forEach { action ->
            try {
                action.close()
            } catch (failure: RuntimeException) {
                failures += failure
            }
        }
        DemoCleanupReport(failures.toList()).also { completedCloseReport = it }
    }

    override fun close() {
        closeReport()
    }

    companion object {
        @Suppress("LongMethod") // resource acquisition 순서가 이 demo의 lifecycle 계약이다.
        fun create(config: KtorExposedDemoConfig = KtorExposedDemoConfig()): KtorExposedDemoResources =
            DemoResourceAcquirer().acquire {
                val lease = DemoLifecycleLease().acquire()
                completed("lease", lease::close)

                val dataSource = createJdbcDataSource()
                completed("jdbc", dataSource::close)
                val jdbcDatabase = Database.connect(dataSource)
                initializeJdbc(jdbcDatabase)

                val jdbcDispatcher = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
                completed("dispatcher", jdbcDispatcher::close)

                val r2dbcOptions = ConnectionFactoryOptions.parse(config.r2dbcUrl)
                    .mutate()
                    .option(ConnectionFactoryOptions.USER, config.user)
                    .option(ConnectionFactoryOptions.PASSWORD, config.password)
                    .build()
                val r2dbcPool = ConnectionPool(
                    ConnectionPoolConfiguration.builder(ConnectionFactories.get(r2dbcOptions))
                        .initialSize(1)
                        .maxSize(2)
                        .maxAcquireTime(Duration.ofSeconds(R2DBC_POOL_CLOSE_TIMEOUT_SECONDS))
                        .build(),
                )
                completed("pool") {
                    r2dbcPool.disposeLater().block(Duration.ofSeconds(R2DBC_POOL_CLOSE_TIMEOUT_SECONDS))
                }

                val previousDefault = R2dbcTransactionManager.defaultDatabase
                val r2dbcDatabase = R2dbcDatabase.connect(
                    r2dbcPool,
                    databaseConfig = R2dbcDatabaseConfig {
                        connectionFactoryOptions = r2dbcOptions
                    },
                )
                R2dbcTransactionManager.defaultDatabase = r2dbcDatabase
                completed("restore-default") {
                    restorePreviousDefault(previousDefault)
                }
                completed("unregister") {
                    R2dbcTransactionManager.closeAndUnregister(r2dbcDatabase)
                }

                runBlocking {
                    suspendTransaction(r2dbcDatabase) {
                        R2dbcSchemaUtils.create(io.bluetape4k.examples.exposed.ktor.order.DemoOrders)
                    }
                }

                val orderRepository = OrderR2dbcCaffeineRepository()
                val eventPublisher = InMemoryOrderEventPublisher()
                val orderService = OrderCommandService(orderRepository, eventPublisher, Clock.systemUTC())

                KtorExposedDemoResources(
                    jdbcDatabase = jdbcDatabase,
                    r2dbcDatabase = r2dbcDatabase,
                    jdbcDispatcher = jdbcDispatcher,
                    orderRepository = orderRepository,
                    eventPublisher = eventPublisher,
                    orderService = orderService,
                    closeActions = listOf(
                        NamedCloseAction("repository", orderRepository::close),
                        NamedCloseAction("unregister") {
                            R2dbcTransactionManager.closeAndUnregister(r2dbcDatabase)
                        },
                        NamedCloseAction("restore-default") {
                            restorePreviousDefault(previousDefault)
                        },
                        NamedCloseAction("pool") {
                            r2dbcPool.disposeLater().block(Duration.ofSeconds(R2DBC_POOL_CLOSE_TIMEOUT_SECONDS))
                        },
                        NamedCloseAction("jdbc", dataSource::close),
                        NamedCloseAction("dispatcher", jdbcDispatcher::close),
                        NamedCloseAction("lease", lease::close),
                    ),
                )
            }

        private fun createJdbcDataSource(): HikariDataSource = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:ktor-exposed-demo-jdbc;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                driverClassName = "org.h2.Driver"
                username = "sa"
                maximumPoolSize = 2
                poolName = "ktor-exposed-demo-jdbc"
            },
        )

        private fun initializeJdbc(jdbcDatabase: Database) {
            transaction(db = jdbcDatabase) {
                SchemaUtils.create(DemoItems)
                if (DemoItems.selectAll().count() == 0L) {
                    DemoItems.insert { it[name] = "jdbc-item-a" }
                    DemoItems.insert { it[name] = "jdbc-item-b" }
                }
            }
        }

        private fun restorePreviousDefault(previousDefault: R2dbcDatabase?) {
            if (R2dbcTransactionManager.defaultDatabase == null && previousDefault != null) {
                R2dbcTransactionManager.defaultDatabase = previousDefault
            }
        }
    }
}

private val GLOBAL_DEMO_LEASE = AtomicBoolean()
private const val R2DBC_POOL_CLOSE_TIMEOUT_SECONDS = 5L
