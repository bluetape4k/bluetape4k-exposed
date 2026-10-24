package io.bluetape4k.exposed.druid

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test

class DruidConnectionOptionsTest {

    companion object: KLogging()

    @Test
    fun `default options build official Avatica Router URL`() {
        DruidConnectionOptions().jdbcUrl() shouldBeEqualTo
                "jdbc:avatica:remote:url=http://localhost:8888/druid/v2/sql/avatica/;transparent_reconnection=true"
    }

    @Test
    fun `protobuf option appends serialization property`() {
        DruidConnectionOptions(
            avaticaEndpoint = "http://localhost:8888/druid/v2/sql/avatica-protobuf/",
            serialization = DruidAvaticaSerialization.PROTOBUF,
        ).jdbcUrl() shouldBeEqualTo
                "jdbc:avatica:remote:url=http://localhost:8888/druid/v2/sql/avatica-protobuf/;transparent_reconnection=true;serialization=protobuf"
    }

    @Test
    fun `context and authentication properties are copied`() {
        val properties = DruidConnectionOptions(
            user = "admin",
            password = "secret",
            contextProperties = mapOf("sqlTimeZone" to "Etc/UTC"),
            extraProperties = mapOf("custom" to "value"),
        ).toProperties()

        log.debug { "properties=$properties" }
        properties.getProperty("user") shouldBeEqualTo "admin"
        properties.getProperty("password") shouldBeEqualTo "secret"
        properties.getProperty("sqlTimeZone") shouldBeEqualTo "Etc/UTC"
        properties.getProperty("custom") shouldBeEqualTo "value"
    }

    @Test
    fun `invalid endpoint is rejected before DriverManager`() {
        assertFailsWith<IllegalArgumentException> {
            DruidConnectionOptions(avaticaEndpoint = "jdbc:h2:mem:test")
        }
    }

    @Test
    fun `blank query-only values are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            DruidConnectionOptions(contextProperties = mapOf(" " to "Etc/UTC"))
        }
        assertFailsWith<IllegalArgumentException> {
            DruidConnectionOptions(extraProperties = mapOf("x" to " "))
        }
    }
}
