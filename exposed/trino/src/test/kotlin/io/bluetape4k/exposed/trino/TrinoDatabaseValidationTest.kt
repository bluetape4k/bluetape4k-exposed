package io.bluetape4k.exposed.trino

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test

/**
 * [TrinoDatabase] 입력값 검증 로직을 단위 검증하는 테스트.
 *
 * Docker/Testcontainers 없이 파라미터 유효성 검사만 확인합니다.
 * 실제 연결을 시도하지 않으므로, DriverManager.getConnection 호출 이전에
 * 예외가 발생하는지를 확인합니다.
 */
class TrinoDatabaseValidationTest {

    companion object: KLogging()

    // ----------------------------------------------------------------
    // connect(host, port, catalog, schema, user) 오버로드 검증
    // ----------------------------------------------------------------

    @Test
    fun `host 가 공백이면 IllegalArgumentException 을 던진다`() {
        assertFailsWith<IllegalArgumentException> {
            TrinoDatabase.connect(
                host = "",
                port = 8080,
                catalog = "memory",
                schema = "default"
            )
        }
    }

    @Test
    fun `port 가 0 이면 IllegalArgumentException 을 던진다`() {
        assertFailsWith<IllegalArgumentException> {
            TrinoDatabase.connect(
                host = "localhost",
                port = 0,
                catalog = "memory",
                schema = "default"
            )
        }
    }

    @Test
    fun `port 가 65536 이면 IllegalArgumentException 을 던진다`() {
        assertFailsWith<IllegalArgumentException> {
            TrinoDatabase.connect(
                host = "localhost",
                port = 65536,
                catalog = "memory",
                schema = "default"
            )
        }
    }

    @Test
    fun `catalog 가 공백이면 IllegalArgumentException 을 던진다`() {
        assertFailsWith<IllegalArgumentException> {
            TrinoDatabase.connect(
                host = "localhost",
                port = 8080,
                catalog = "",
                schema = "default"
            )
        }
    }

    @Test
    fun `schema 가 공백이면 IllegalArgumentException 을 던진다`() {
        assertFailsWith<IllegalArgumentException> {
            TrinoDatabase.connect(
                host = "localhost",
                port = 8080,
                catalog = "memory",
                schema = ""
            )
        }
    }

    // ----------------------------------------------------------------
    // connect(jdbcUrl, user) 오버로드 검증
    // ----------------------------------------------------------------

    @Test
    fun `jdbcUrl 이 공백이면 IllegalArgumentException 을 던진다`() {
        assertFailsWith<IllegalArgumentException> {
            TrinoDatabase.connect(jdbcUrl = "")
        }
    }

    @Test
    fun `jdbcUrl 이 trino 프로토콜로 시작하지 않으면 IllegalArgumentException 을 던진다`() {
        assertFailsWith<IllegalArgumentException> {
            TrinoDatabase.connect(jdbcUrl = "jdbc:postgresql://localhost/mydb")
        }
    }

    @Test
    fun `connect 로그는 credential 포함 JDBC URL을 노출하지 않는다`() {
        val sensitiveValue = "trino-url-redaction-sentinel"
        val jdbcUrl = "jdbc:trino://localhost:8080/memory/default?token=$sensitiveValue"

        RecordingLogAppender().use { appender ->
            TrinoDatabase.connect(jdbcUrl = jdbcUrl)

            appender.rendered shouldNotContain sensitiveValue
            appender.rendered shouldNotContain jdbcUrl
        }
    }
}
