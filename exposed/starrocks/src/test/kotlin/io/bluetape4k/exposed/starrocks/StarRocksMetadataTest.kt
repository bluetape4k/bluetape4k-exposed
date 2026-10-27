package io.bluetape4k.exposed.starrocks

import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.equalsIgnoreCase
import org.junit.jupiter.api.Test
import java.sql.DriverManager

/**
 * StarRocks Connector/J metadata smoke tests.
 */
class StarRocksMetadataTest: AbstractStarRocksTest() {

    companion object: KLogging()

    @Test
    fun `DatabaseMetaData discovers fixture table and columns`() {
        DriverManager.getConnection(jdbcUrl, connectionProperties).use { conn ->
            val metaData = conn.metaData

            val tableFound = metaData.getTables("default_catalog", databaseName, "events", arrayOf("TABLE")).use { rs ->
                generateSequence { if (rs.next()) rs.getString("TABLE_NAME") else null }
                    .any { it.equalsIgnoreCase("events") }
            }

            val columns = metaData.getColumns("default_catalog", databaseName, "events", "%").use { rs ->
                buildSet {
                    while (rs.next()) {
                        add(rs.getString("COLUMN_NAME").lowercase())
                    }
                }
            }

            tableFound.shouldBeTrue()
            columns shouldContain "event_id"
            columns shouldContain "event_name"
            columns shouldContain "region"
        }
    }
}
