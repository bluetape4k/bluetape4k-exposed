package io.bluetape4k.exposed.starrocks

import io.bluetape4k.assertions.assertFailsWith
import org.junit.jupiter.api.Test

/**
 * Validation tests for [StarRocksDatabase].
 */
class StarRocksDatabaseValidationTest {

    @Test
    fun `connect fails when host is blank`() {
        assertFailsWith<IllegalArgumentException> {
            StarRocksDatabase.connect(host = "", port = 9030, database = "analytics")
        }
    }

    @Test
    fun `connect fails when port is below range`() {
        assertFailsWith<IllegalArgumentException> {
            StarRocksDatabase.connect(host = "localhost", port = 0, database = "analytics")
        }
    }

    @Test
    fun `connect fails when port is above range`() {
        assertFailsWith<IllegalArgumentException> {
            StarRocksDatabase.connect(host = "localhost", port = 65536, database = "analytics")
        }
    }

    @Test
    fun `connect fails when catalog is blank`() {
        assertFailsWith<IllegalArgumentException> {
            StarRocksDatabase.connect(host = "localhost", port = 9030, catalog = "", database = "analytics")
        }
    }

    @Test
    fun `connect fails when database is blank`() {
        assertFailsWith<IllegalArgumentException> {
            StarRocksDatabase.connect(host = "localhost", port = 9030, database = "")
        }
    }

    @Test
    fun `connect fails when user is blank`() {
        assertFailsWith<IllegalArgumentException> {
            StarRocksDatabase.connect(host = "localhost", port = 9030, database = "analytics", user = "")
        }
    }

    @Test
    fun `connect with jdbcUrl fails when blank`() {
        assertFailsWith<IllegalArgumentException> {
            StarRocksDatabase.connect(jdbcUrl = "")
        }
    }

    @Test
    fun `connect with jdbcUrl fails when wrong prefix`() {
        assertFailsWith<IllegalArgumentException> {
            StarRocksDatabase.connect(jdbcUrl = "jdbc:mysql://localhost:9030/default_catalog.analytics")
        }
    }

    @Test
    fun `connection options reject blank keys`() {
        assertFailsWith<IllegalArgumentException> {
            StarRocksConnectionOptions(extraProperties = mapOf("" to "value"))
        }
    }

    @Test
    fun `connection options reject blank values`() {
        assertFailsWith<IllegalArgumentException> {
            StarRocksConnectionOptions(extraProperties = mapOf("sessionVariables" to ""))
        }
    }
}
