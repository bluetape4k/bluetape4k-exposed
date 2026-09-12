package io.bluetape4k.exposed.clickhouse

import io.bluetape4k.AbstractValueObject
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import java.time.ZoneId

/**
 * ClickHouse JDBC V2 연결 옵션의 값 검증과 driver property 변환 계약을 고정합니다.
 */
class ClickHouseV2OptionsTest {

    @Test
    fun `typed values map to V2 properties with millisecond units`() {
        val options = ClickHouseV2Options(
            connectionTimeoutMillis = 1_500L,
            socketOperationTimeoutMillis = 2_000,
            connectionRequestTimeoutMillis = 3_000L,
            connectionTtlMillis = 60_000L,
            httpKeepAliveTimeoutMillis = 30_000L,
            connectionPoolEnabled = true,
            maxOpenConnections = 8,
            connectionReuseStrategy = ClickHouseV2ConnectionReuseStrategy.LIFO,
            useServerTimeZone = true,
            compressServerResponse = true,
            compressClientRequest = true,
            useHttpCompression = true,
            lz4UncompressedBufferSize = 1_048_576,
            retryOnFailure = 2,
            clientName = "bluetape-test",
            sessionDbRoles = listOf("role_a", "role_b"),
            sessionTimezone = ZoneId.of("UTC"),
            queryId = "query-865",
            logComment = "v2-options",
            serverSettings = mapOf("max_threads" to "4"),
        )

        val properties = options.toEffectiveProperties(user = "default", password = "")

        properties.getProperty("connection_timeout") shouldBeEqualTo "1500"
        properties.getProperty("socket_timeout") shouldBeEqualTo "2000"
        properties.getProperty("connection_request_timeout") shouldBeEqualTo "3000"
        properties.getProperty("connection_ttl") shouldBeEqualTo "60000"
        properties.getProperty("http_keep_alive_timeout") shouldBeEqualTo "30000"
        properties.getProperty("connection_pool_enabled") shouldBeEqualTo "true"
        properties.getProperty("max_open_connections") shouldBeEqualTo "8"
        properties.getProperty("connection_reuse_strategy") shouldBeEqualTo "LIFO"
        properties.getProperty("use_server_time_zone") shouldBeEqualTo "true"
        properties.getProperty("compress") shouldBeEqualTo "true"
        properties.getProperty("decompress") shouldBeEqualTo "true"
        properties.getProperty("client.use_http_compression") shouldBeEqualTo "true"
        properties.getProperty("compression.lz4.uncompressed_buffer_size") shouldBeEqualTo "1048576"
        properties.getProperty("retry") shouldBeEqualTo "2"
        properties.getProperty("client_name") shouldBeEqualTo "bluetape-test"
        properties.getProperty("session_db_roles") shouldBeEqualTo "role_a,role_b"
        properties.getProperty("use_time_zone") shouldBeEqualTo "UTC"
        properties.getProperty("query_id") shouldBeEqualTo "query-865"
        properties.getProperty("clickhouse_setting_log_comment") shouldBeEqualTo "v2-options"
        properties.getProperty("clickhouse_setting_max_threads") shouldBeEqualTo "4"
        properties.getProperty("user") shouldBeEqualTo "default"
        properties.getProperty("password") shouldBeEqualTo ""
    }

    @Test
    fun `raw properties are copied and unknown or owned keys are rejected`() {
        val raw = mutableMapOf("clickhouse_setting_custom" to "value")
        val options = ClickHouseV2Options(rawProperties = raw)
        raw["clickhouse_setting_custom"] = "mutated"

        options.rawProperties["clickhouse_setting_custom"] shouldBeEqualTo "value"
        assertFailsWith<IllegalArgumentException> {
            ClickHouseV2Options(rawProperties = mapOf("unknown_key" to "value"))
        }
        assertFailsWith<IllegalArgumentException> {
            ClickHouseV2Options(rawProperties = mapOf("beta.row_binary_for_simple_insert" to "true"))
        }
        assertFailsWith<IllegalArgumentException> {
            ClickHouseV2Options(rawProperties = mapOf("password" to "secret"))
        }
        assertFailsWith<IllegalArgumentException> {
            ClickHouseV2Options(rawProperties = mapOf("clickhouse_setting_bad\nkey" to "value"))
        }
        assertFailsWith<IllegalArgumentException> {
            ClickHouseV2Options(rawProperties = mapOf("clickhouse_setting_" to "value"))
        }
    }

    @Test
    fun `session database roles reject comma delimiters`() {
        assertFailsWith<IllegalArgumentException> {
            ClickHouseV2Options(sessionDbRoles = listOf("role_a,role_b"))
        }
    }

    @Test
    fun `authentication is one of basic access token or bearer token`() {
        val basic = ClickHouseV2Options(authentication = ClickHouseV2Authentication.Basic)
            .toEffectiveProperties(user = "u", password = "p")
        basic.getProperty("http_use_basic_auth") shouldBeEqualTo "true"
        basic.getProperty("user") shouldBeEqualTo "u"
        basic.getProperty("password") shouldBeEqualTo "p"

        assertFailsWith<IllegalArgumentException> {
            ClickHouseV2Options(
                authentication = ClickHouseV2Authentication.AccessToken("access-canary"),
            ).toEffectiveProperties(user = "u", password = "p")
        }
        val accessToken = ClickHouseV2Options(
            authentication = ClickHouseV2Authentication.AccessToken("access-canary"),
        ).toEffectiveProperties(user = "default", password = "")
        accessToken.getProperty("access_token") shouldBeEqualTo "access-canary"
        accessToken.getProperty("http_use_basic_auth") shouldBeEqualTo "false"
        accessToken.containsKey("password").shouldBeFalse()

        val bearerToken = ClickHouseV2Options(
            authentication = ClickHouseV2Authentication.BearerToken("bearer-canary"),
        ).toEffectiveProperties(user = "default", password = "")
        bearerToken.getProperty("bearer_token") shouldBeEqualTo "bearer-canary"
        bearerToken.getProperty("http_use_basic_auth") shouldBeEqualTo "false"
        bearerToken.containsKey("user").shouldBeFalse()

        assertFailsWith<IllegalArgumentException> {
            ClickHouseV2Options(
                authentication = ClickHouseV2Authentication.AccessToken("token"),
                rawProperties = mapOf("access_token" to "duplicate"),
            )
        }
    }

    @Test
    fun `JDBC URL query remains driver owned instead of being copied into properties`() {
        val options = ClickHouseV2Options(
            clientName = "typed-client",
            queryId = "typed-query",
        )

        val properties = options.toEffectiveProperties(
            user = "default",
            password = "",
            jdbcUrl = "jdbc:clickhouse://localhost:8123/default?client_name=url%2Bclient&query_id=url%2Bquery",
        )

        properties.getProperty("client_name") shouldBeEqualTo "typed-client"
        properties.getProperty("query_id") shouldBeEqualTo "typed-query"
    }

    @Test
    fun `custom headers are case insensitive and allow only user agent`() {
        val options = ClickHouseV2Options(
            customHeaders = mapOf("x-clickhouse-user-agent" to "bluetape/865"),
        )
        options.toEffectiveProperties("default", "")
            .getProperty("http_header_X-ClickHouse-User-Agent") shouldBeEqualTo "bluetape/865"

        assertFailsWith<IllegalArgumentException> {
            ClickHouseV2Options(customHeaders = mapOf("Authorization" to "token"))
        }
        assertFailsWith<IllegalArgumentException> {
            ClickHouseV2Options(
                customHeaders = mapOf(
                    "X-ClickHouse-User-Agent" to "a",
                    "x-clickhouse-user-agent" to "b",
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ClickHouseV2Options(customHeaders = mapOf("X-ClickHouse-User-Agent" to "bad\nvalue"))
        }
    }

    @Test
    fun `numeric bounds and proxy tls references are validated`() {
        assertFailsWith<IllegalArgumentException> { ClickHouseV2Options(connectionTimeoutMillis = 0) }
        assertFailsWith<IllegalArgumentException> { ClickHouseV2Options(socketOperationTimeoutMillis = -1) }
        assertFailsWith<IllegalArgumentException> { ClickHouseV2Options(connectionTtlMillis = 0) }
        assertFailsWith<IllegalArgumentException> { ClickHouseV2Options(connectionTtlMillis = -2) }
        assertFailsWith<IllegalArgumentException> { ClickHouseV2Options(maxOpenConnections = 0) }

        val provider = ClickHouseV2SecretProvider { "proxy-secret".toCharArray() }
        val options = ClickHouseV2Options(
            proxy = ClickHouseV2ProxyOptions(host = "proxy", port = 8080, password = provider),
            tls = ClickHouseV2TlsOptions(
                trustStore = "file:/tmp/truststore",
                sslKeyReference = "secret://key",
                sslRootCertReference = "secret://root",
                sslCertReference = "secret://cert",
            ),
        )
        options.toEffectiveProperties("default", "").getProperty("proxy_host") shouldBeEqualTo "proxy"
        options.toEffectiveProperties("default", "").getProperty("proxy_type") shouldBeEqualTo "HTTP"
        options.toEffectiveProperties("default", "").getProperty("trust_store") shouldBeEqualTo "file:/tmp/truststore"
        options.toEffectiveProperties("default", "").getProperty("ssl") shouldBeEqualTo "true"
    }

    @Test
    fun `TLS client certificate authentication is mapped as a strict boolean`() {
        val properties = ClickHouseV2Options(
            tls = ClickHouseV2TlsOptions(sslAuthentication = true),
        ).toEffectiveProperties("default", "")

        properties.getProperty("ssl_authentication") shouldBeEqualTo "true"
        properties.getProperty("ssl") shouldBeEqualTo "true"
    }

    @Test
    fun `typed log comment cannot be shadowed by a server setting`() {
        assertFailsWith<IllegalArgumentException> {
            ClickHouseV2Options(
                logComment = "typed-comment",
                serverSettings = mapOf("log_comment" to "server-comment"),
            )
        }
    }

    @Test
    fun `options use value object equality and redact secrets`() {
        val first = ClickHouseV2Options(
            authentication = ClickHouseV2Authentication.AccessToken("access-canary"),
        )
        val second = ClickHouseV2Options(
            authentication = ClickHouseV2Authentication.AccessToken("access-canary"),
        )

        val valueObject: AbstractValueObject = first
        valueObject.hashCode() shouldBeEqualTo first.hashCode()
        first shouldBeEqualTo second
        first.toString().contains("access-canary").shouldBeFalse()
        first.toString().contains("authentication=AccessToken").shouldBeTrue()
    }

    @Test
    fun `options redact opaque query metadata from their string representation`() {
        val options = ClickHouseV2Options(
            clientName = "analytics-client",
            queryId = "query-id-canary",
            logComment = "log-comment-canary",
        )

        val rendered = options.toString()

        rendered.contains("query-id-canary").shouldBeFalse()
        rendered.contains("log-comment-canary").shouldBeFalse()
        rendered.contains("queryId=***").shouldBeTrue()
        rendered.contains("logComment=***").shouldBeTrue()
    }

    @Test
    fun `nested option strings redact custom secret provider text`() {
        val provider = object: ClickHouseV2SecretProvider {
            override fun resolve(): CharArray = "proxy-secret".toCharArray()

            override fun toString(): String = "proxy-secret"
        }
        val proxy = ClickHouseV2ProxyOptions(host = "proxy", port = 8080, password = provider)
        val tls = ClickHouseV2TlsOptions(keyStorePassword = provider)

        proxy.toString().contains("proxy-secret").shouldBeFalse()
        tls.toString().contains("proxy-secret").shouldBeFalse()
    }
}
