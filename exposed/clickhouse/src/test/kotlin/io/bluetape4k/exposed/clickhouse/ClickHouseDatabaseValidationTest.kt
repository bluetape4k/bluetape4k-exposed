package io.bluetape4k.exposed.clickhouse

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import java.sql.SQLException

/**
 * [ClickHouseDatabase.connect] 입력 유효성 검증 테스트.
 *
 * 컨테이너 없이 빠르게 실행되며, 잘못된 인자에 대해 [IllegalArgumentException]을
 * 명확하게 던지는지 확인합니다.
 */
class ClickHouseDatabaseValidationTest {

    companion object: KLogging()

    @Test
    fun `connect fails when host is blank`() {
        assertFailsWith<IllegalArgumentException> {
            ClickHouseDatabase.connect(host = "", port = 8123, database = "default")
        }
    }

    @Test
    fun `connect fails when port is below range`() {
        assertFailsWith<IllegalArgumentException> {
            ClickHouseDatabase.connect(host = "localhost", port = 0, database = "default")
        }
    }

    @Test
    fun `connect fails when port is above range`() {
        assertFailsWith<IllegalArgumentException> {
            ClickHouseDatabase.connect(host = "localhost", port = 65536, database = "default")
        }
    }

    @Test
    fun `connect fails when database is blank`() {
        assertFailsWith<IllegalArgumentException> {
            ClickHouseDatabase.connect(host = "localhost", port = 8123, database = "")
        }
    }

    @Test
    fun `connect with jdbcUrl fails when blank`() {
        assertFailsWith<IllegalArgumentException> {
            ClickHouseDatabase.connect(jdbcUrl = "")
        }
    }

    @Test
    fun `connect with jdbcUrl fails when wrong prefix`() {
        assertFailsWith<IllegalArgumentException> {
            ClickHouseDatabase.connect(jdbcUrl = "jdbc:postgresql://localhost/db")
        }
    }

    @Test
    fun `options jdbc URL rejects authentication keys without exposing values`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            ClickHouseV2Options().toEffectiveProperties(
                user = "default",
                password = "",
                jdbcUrl = "jdbc:clickhouse://localhost:8123/default?password=secret-canary&query_id=q",
            )
        }

        failure.message shouldNotContain "secret-canary"
        failure.message shouldContain "password=REDACTED"
    }

    @Test
    fun `options jdbc URL rejects authority userinfo without exposing credentials`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            ClickHouseDatabase.connect(
                jdbcUrl = "jdbc:clickhouse://user:secret-canary@localhost:8123/default",
                options = ClickHouseV2Options(),
            )
        }

        failure.message shouldNotContain "secret-canary"
        failure.message shouldContain "REDACTED@localhost"
    }

    @Test
    fun `options host overload rejects authority userinfo without exposing credentials`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            ClickHouseDatabase.connect(
                host = "user:secret-canary@localhost",
                options = ClickHouseV2Options(),
            )
        }

        failure.message shouldNotContain "secret-canary"
        failure.message shouldContain "REDACTED@localhost"
    }

    @Test
    fun `connection exception preserves only safe JDBC diagnostics`() {
        val original = SQLException("password=secret-canary token=token-canary", "08001", 1001)
        val failure = original.toClickHouseConnectionException(
            "jdbc:clickhouse://localhost:8123/default?password=secret-canary",
        )

        failure.message shouldNotContain "secret-canary"
        failure.message shouldNotContain "token-canary"
        failure.sqlState shouldBeEqualTo "08001"
        failure.errorCode shouldBeEqualTo 1001
        failure.cause.shouldBeNull()
        failure.suppressed shouldHaveSize 0
    }

    @Test
    fun `connection exception redacts authority userinfo without a query`() {
        val original = SQLException("connection failed", "08001", 1001)
        val failure = original.toClickHouseConnectionException(
            "jdbc:clickhouse://user:secret-canary@localhost:8123/default",
        )

        failure.message shouldNotContain "secret-canary"
        failure.message shouldContain "REDACTED@localhost"
    }
}
