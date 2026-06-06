package io.bluetape4k.exposed.cockroachdb

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test

/**
 * Smoke tests for [CockroachDatabase].
 */
class CockroachDatabaseTest: AbstractCockroachDbTest() {

    @Test
    fun `buildJdbcUrl builds PostgreSQL wire URL`() {
        CockroachDatabase.buildJdbcUrl("localhost", 26257, "defaultdb") shouldBeEqualTo
                "jdbc:postgresql://localhost:26257/defaultdb"
    }

    @Test
    fun `blank arguments are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            CockroachDatabase.buildJdbcUrl(" ", 26257, "defaultdb")
        }
        assertFailsWith<IllegalArgumentException> {
            CockroachDatabase.connect(jdbcUrl = " ")
        }
        assertFailsWith<IllegalArgumentException> {
            CockroachDatabase.connect(jdbcUrl = "jdbc:h2:mem:test")
        }
    }

    @Test
    fun `transaction exec SELECT 1 succeeds`() {
        transaction(db) {
            exec("SELECT 1") { rs ->
                rs.next()
                rs.getInt(1)
            }.shouldNotBeNull()
        }
    }

    @Test
    fun `simple schema create insert select and drop succeeds`() {
        transaction(db) {
            runCatching { SchemaUtils.drop(CockroachSmokeEvents) }
            SchemaUtils.create(CockroachSmokeEvents)

            CockroachSmokeEvents.insert {
                it[eventName] = "started"
            }

            CockroachSmokeEvents.selectAll().count() shouldBeEqualTo 1L

            SchemaUtils.drop(CockroachSmokeEvents)
        }
    }

    object CockroachSmokeEvents: Table("bt4k_cockroach_smoke_events") {
        val id = long("id").autoIncrement()
        val eventName = varchar("event_name", 64)

        override val primaryKey: PrimaryKey = PrimaryKey(id)
    }
}
