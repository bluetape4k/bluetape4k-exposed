package io.bluetape4k.exposed.druid

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.assertions.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Manual/local-container smoke for a prepared Druid quickstart datasource.
 *
 * Run after starting Druid and loading a fixture datasource, for example:
 *
 * ```bash
 * EXPOSED_DRUID_SMOKE=true \
 * EXPOSED_DRUID_AVATICA_ENDPOINT='http://localhost:8888/druid/v2/sql/avatica/' \
 * EXPOSED_DRUID_DATASOURCE=wikipedia \
 * ./gradlew --no-parallel :bluetape4k-exposed-druid:test --tests '*DruidJdbcSmokeTest'
 * ```
 */
@EnabledIfEnvironmentVariable(named = "EXPOSED_DRUID_SMOKE", matches = "true")
class DruidJdbcSmokeTest {

    private val options = DruidConnectionOptions(
        avaticaEndpoint = System.getenv("EXPOSED_DRUID_AVATICA_ENDPOINT")
            ?: "http://localhost:8888/druid/v2/sql/avatica/",
        contextProperties = mapOf("sqlTimeZone" to "Etc/UTC"),
    )

    private val datasource = System.getenv("EXPOSED_DRUID_DATASOURCE") ?: "wikipedia"

    @Test
    fun `Druid Avatica connection executes SELECT 1`() {
        val values = DruidJdbc.query("SELECT 1 AS one", options) { rs -> rs.getInt("one") }
        values shouldBeEqualTo listOf(1)
    }

    @Test
    fun `Druid metadata discovers fixture datasource columns`() {
        val columns = DruidJdbc.listColumns(datasource = datasource, options = options)
        columns.shouldNotBeEmpty()
        columns.first().columnName.shouldNotBeNull()
    }

    @Test
    fun `Druid SELECT reads fixture datasource`() {
        val rows = DruidJdbc.query("SELECT * FROM \"$datasource\" LIMIT 1", options) { rs -> rs.getObject(1) }
        rows.shouldNotBeEmpty()
    }
}
