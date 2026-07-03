package io.bluetape4k.exposed.starrocks

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.exposed.starrocks.dialect.StarRocksDialect
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test

/**
 * Connection and dialect registration tests for [StarRocksDatabase].
 */
class StarRocksDatabaseTest: AbstractStarRocksTest() {

    @Test
    fun `db dialect is StarRocksDialect`() {
        db.dialect.shouldBeInstanceOf<StarRocksDialect>()
    }

    @Test
    fun `db dialect name is starrocks`() {
        db.dialect.name shouldBeEqualTo "starrocks"
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
    fun `connect host port catalog database succeeds`() {
        val newDb = StarRocksDatabase.connect(
            host = host,
            port = port,
            catalog = "default_catalog",
            database = databaseName,
            user = "root",
        )

        transaction(newDb) {
            exec("SELECT 1") { rs ->
                rs.next()
                rs.getInt(1)
            }.shouldNotBeNull()
        }
    }

    @Test
    fun `connect jdbcUrl succeeds`() {
        val newDb = StarRocksDatabase.connect(jdbcUrl = jdbcUrl, user = "root")

        transaction(newDb) {
            exec("SELECT 1") { rs ->
                rs.next()
                rs.getInt(1)
            }.shouldNotBeNull()
        }
    }

    @Test
    fun `dialect disables unproven schema mutation features`() {
        db.dialect.supportsColumnTypeChange.shouldBeFalse()
        db.dialect.supportsCreateSequence.shouldBeFalse()
        db.dialect.supportsMultipleGeneratedKeys.shouldBeFalse()
    }
}
