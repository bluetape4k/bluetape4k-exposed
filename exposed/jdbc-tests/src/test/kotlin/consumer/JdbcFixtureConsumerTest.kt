package consumer

import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.exposed.tests.jdbcTestDbFixture
import io.bluetape4k.exposed.tests.withDb
import io.bluetape4k.exposed.tests.withDbSuspending
import io.bluetape4k.exposed.tests.withSchemas
import io.bluetape4k.exposed.tests.withSchemasSuspending
import io.bluetape4k.exposed.tests.withTables
import io.bluetape4k.exposed.tests.withTablesSuspending
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.Schema
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Test

class JdbcFixtureConsumerTest {

    companion object: KLogging()

    private enum class ApplicationDb { PRIMARY }

    @Test
    fun `외부 package의 enum과 pool로 모든 overload를 사용한다`() = runSuspendIO {
        HikariDataSource().use { pool ->
            pool.jdbcUrl = "jdbc:h2:mem:jdbc_external_consumer;DB_CLOSE_DELAY=-1"
            pool.maximumPoolSize = 2
            val fixture = jdbcTestDbFixture(ApplicationDb.PRIMARY, { configure ->
                Database.connect(pool, databaseConfig = DatabaseConfig { configure() })
            })
            val table = object: Table("external_fixture_table") {
                val id = integer("id")
            }
            val schema = Schema("external_fixture_schema")
            var calls = 0

            withDb(fixture, configure = {}) {
                calls++
                it shouldBeEqualTo ApplicationDb.PRIMARY
            }

            withTables(fixture, table) {
                calls++
                it shouldBeEqualTo ApplicationDb.PRIMARY
            }

            withSchemas(fixture, schema) {
                calls++
                it shouldBeEqualTo ApplicationDb.PRIMARY
            }

            withDbSuspending(fixture) { calls++ }
            withTablesSuspending(fixture, table) { calls++ }
            withSchemasSuspending(fixture, schema) { calls++ }

            calls shouldBeEqualTo 6
            pool.isClosed shouldBeEqualTo false
        }
    }
}
