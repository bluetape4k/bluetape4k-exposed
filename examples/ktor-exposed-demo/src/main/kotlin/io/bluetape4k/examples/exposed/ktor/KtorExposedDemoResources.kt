package io.bluetape4k.examples.exposed.ktor

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.r2dbc.pool.ConnectionPool
import io.r2dbc.pool.ConnectionPoolConfiguration
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactoryOptions
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabaseConfig
import java.time.Duration
import java.util.concurrent.Executors

object DemoItems: LongIdTable("ktor_demo_items") {
    val name = varchar("name", 80)
}

class KtorExposedDemoResources private constructor(
    private val dataSource: HikariDataSource,
    private val r2dbcPool: ConnectionPool,
    val jdbcDatabase: Database,
    val r2dbcDatabase: R2dbcDatabase,
    val jdbcDispatcher: ExecutorCoroutineDispatcher,
): AutoCloseable {

    override fun close() {
        runCatching { r2dbcPool.disposeLater().block(Duration.ofSeconds(5)) }
        runCatching { dataSource.close() }
        runCatching { jdbcDispatcher.close() }
    }

    companion object {
        fun create(name: String = "default"): KtorExposedDemoResources {
            val databaseName = "ktor-exposed-demo-$name"
            val dataSource = HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = "jdbc:h2:mem:$databaseName-jdbc;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                    driverClassName = "org.h2.Driver"
                    username = "sa"
                    maximumPoolSize = 2
                    poolName = "$databaseName-jdbc"
                }
            )
            val jdbcDatabase = Database.connect(dataSource)
            val jdbcDispatcher = Executors.newFixedThreadPool(2).asCoroutineDispatcher()

            val r2dbcUrl = "r2dbc:h2:mem:///$databaseName-r2dbc;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
            val r2dbcOptions = ConnectionFactoryOptions.parse(r2dbcUrl)
            val r2dbcPool = ConnectionPool(
                ConnectionPoolConfiguration.builder(ConnectionFactories.get(r2dbcOptions))
                    .maxSize(2)
                    .initialSize(1)
                    .build()
            )
            val r2dbcDatabase = R2dbcDatabase.connect(
                r2dbcPool,
                databaseConfig = R2dbcDatabaseConfig {
                    connectionFactoryOptions = r2dbcOptions
                },
            )

            val resources = KtorExposedDemoResources(
                dataSource = dataSource,
                r2dbcPool = r2dbcPool,
                jdbcDatabase = jdbcDatabase,
                r2dbcDatabase = r2dbcDatabase,
                jdbcDispatcher = jdbcDispatcher,
            )
            resources.initialize()
            return resources
        }
    }

    private fun initialize() {
        transaction(db = jdbcDatabase) {
            SchemaUtils.create(DemoItems)
            if (DemoItems.selectAll().count() == 0L) {
                DemoItems.insert { it[name] = "jdbc-item-a" }
                DemoItems.insert { it[name] = "jdbc-item-b" }
            }
        }
    }
}
