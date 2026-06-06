package io.bluetape4k.exposed.cockroachdb

import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.jdbc.JdbcDrivers
import io.bluetape4k.jdbc.hikari.hikariDataSourceOf
import io.bluetape4k.jdbc.sql.runQuery
import io.bluetape4k.jdbc.sql.withConnect
import io.bluetape4k.jdbc.sql.withStatement
import io.bluetape4k.testcontainers.database.CockroachServer
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import org.junit.jupiter.api.Test

/**
 * CockroachDB DDL boundary tests for the helper-only 1.11.0 contract.
 */
class CockroachDdlCompatibilityTest: AbstractCockroachDbTest() {

    @Test
    fun `compatibility matrix marks accepted and deferred DDL boundaries`() {
        val supported = listOf(
            CockroachDbCompatibility.PRIMARY_KEY_DDL,
            CockroachDbCompatibility.UNIQUE_AND_INDEX_DDL,
            CockroachDbCompatibility.GENERATED_ID,
            CockroachDbCompatibility.RETURNING,
            CockroachDbCompatibility.SCHEMA_METADATA,
        )

        supported.all {
            CockroachDbCompatibility.requireFeature(it).status == CockroachDbCompatibilityStatus.Supported
        }.shouldBeTrue()
        CockroachDbCompatibility.requireFeature(CockroachDbCompatibility.CREATE_DOMAIN).status shouldBeEqualTo
                CockroachDbCompatibilityStatus.Deferred
        CockroachDbCompatibility.requireFeature(CockroachDbCompatibility.RANGE_TYPES).status shouldBeEqualTo
                CockroachDbCompatibilityStatus.Deferred
        CockroachDbCompatibility.requireFeature(CockroachDbCompatibility.MIGRATION_DIFF).status shouldBeEqualTo
                CockroachDbCompatibilityStatus.Deferred
        CockroachDbCompatibility.requireFeature(CockroachDbCompatibility.CUSTOM_DIALECT).status shouldBeEqualTo
                CockroachDbCompatibilityStatus.OutOfScope
    }

    @Test
    fun `schema utils creates primary key unique index and generated ids`() =
        withAcceptedTable {
            val firstId = transaction(db) {
                CockroachAcceptedEvents.insertAndGetId {
                    it[eventName] = "started"
                    it[region] = "kr"
                }
            }
            val secondId = transaction(db) {
                CockroachAcceptedEvents.insertAndGetId {
                    it[eventName] = "completed"
                    it[region] = "us"
                }
            }

            (firstId.value > 0L).shouldBeTrue()
            (secondId.value > 0L).shouldBeTrue()
            (firstId.value != secondId.value).shouldBeTrue()

            assertFailsWith<ExposedSQLException> {
                transaction(db) {
                    CockroachAcceptedEvents.insertAndGetId {
                        it[eventName] = "started"
                        it[region] = "eu"
                    }
                }
            }

            transaction(db) {
                CockroachAcceptedEvents.selectAll().count() shouldBeEqualTo 2L
            }
        }

    @Test
    fun `raw returning works through PostgreSQL JDBC`() =
        withAcceptedTable {
            withCockroachDataSource { dataSource ->
                val id = dataSource.runQuery(
                    "INSERT INTO ${CockroachAcceptedEvents.tableName} (event_name, region) " +
                            "VALUES ('returning', 'kr') RETURNING id"
                ) { rs ->
                    rs.next()
                    rs.getLong(1)
                }

                (id > 0L).shouldBeTrue()
            }
        }

    @Test
    fun `metadata is discoverable and migration diff remains deferred`() =
        withAcceptedTable {
            tableNamesFromMetadata(CockroachAcceptedEvents.tableName) shouldBeEqualTo
                    listOf(CockroachAcceptedEvents.tableName)

            indexNamesFromMetadata(CockroachAcceptedEvents.tableName).shouldNotBeEmpty()

            transaction(db) {
                MigrationUtils.statementsRequiredForDatabaseMigration(CockroachAcceptedEvents, withLogs = false)
                    .also { statements ->
                        statements.shouldNotBeEmpty()
                        statements.any {
                            it.contains("bt4k_cockroach_ddl_events_id_seq", ignoreCase = true)
                        }.shouldBeTrue()
                    }
            }
        }

    @Test
    fun `caller managed HikariCP data source connects through CockroachDatabase`() =
        withCockroachDataSource { dataSource ->
            val pooledDb = CockroachDatabase.connect(dataSource)

            transaction(pooledDb) {
                exec("SELECT 1") { rs ->
                    rs.next()
                    rs.getInt(1)
                }.shouldNotBeNull() shouldBeEqualTo 1
            }
        }

    @Test
    fun `unsupported PostgreSQL constructs stay outside the accepted boundary`() {
        try {
            assertFailsWith<ExposedSQLException> {
                transaction(db) {
                    exec("CREATE DOMAIN bt4k_cockroach_domain AS STRING")
                }
            }

            assertFailsWith<ExposedSQLException> {
                transaction(db) {
                    exec("CREATE TABLE bt4k_cockroach_range_types (period int4range)")
                }
            }
        } finally {
            dropUnsupportedArtifacts()
        }
    }

    private fun withAcceptedTable(block: () -> Unit) {
        dropAcceptedTable()
        transaction(db) {
            SchemaUtils.create(CockroachAcceptedEvents)
        }

        try {
            block()
        } finally {
            dropAcceptedTable()
        }
    }

    private fun dropAcceptedTable() {
        transaction(db) {
            runCatching { SchemaUtils.drop(CockroachAcceptedEvents) }
        }
    }

    private fun tableNamesFromMetadata(tableName: String): List<String> =
        withCockroachDataSource { dataSource ->
            dataSource.withConnect { connection ->
                connection.metaData.getTables(null, "public", tableName, arrayOf("TABLE")).use { tables ->
                    buildList {
                        while (tables.next()) {
                            add(tables.getString("TABLE_NAME"))
                        }
                    }
                }
            }
        }

    private fun indexNamesFromMetadata(tableName: String): List<String> =
        withCockroachDataSource { dataSource ->
            dataSource.withConnect { connection ->
                connection.metaData.getIndexInfo(null, "public", tableName, false, false).use { indexes ->
                    buildList {
                        while (indexes.next()) {
                            indexes.getString("INDEX_NAME")?.let(::add)
                        }
                    }
                }
            }
        }

    private fun dropUnsupportedArtifacts() {
        withCockroachDataSource { dataSource ->
            dataSource.withStatement { statement ->
                statement.execute("DROP TABLE IF EXISTS bt4k_cockroach_range_types")
                runCatching { statement.execute("DROP DOMAIN IF EXISTS bt4k_cockroach_domain") }
            }
        }
    }

    private fun <T> withCockroachDataSource(block: (HikariDataSource) -> T): T =
        hikariDataSourceOf(
            jdbcUrl = cockroach.url,
            username = cockroach.username ?: CockroachServer.USERNAME,
            password = cockroach.password ?: CockroachServer.PASSWORD,
        ) {
            driverClassName = JdbcDrivers.DRIVER_CLASS_POSTGRESQL
            maximumPoolSize = 4
            minimumIdle = 1
            poolName = "bt4k-cockroachdb-ddl-test"
        }.use(block)

    private object CockroachAcceptedEvents: LongIdTable("bt4k_cockroach_ddl_events") {
        val eventName = varchar("event_name", 64).uniqueIndex()
        val region = varchar("region", 16).index()
    }
}
