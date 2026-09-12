package consumer

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.exposed.r2dbc.tests.r2dbcTestDbFixture
import io.bluetape4k.exposed.r2dbc.tests.withDb
import io.bluetape4k.exposed.r2dbc.tests.withSchemas
import io.bluetape4k.exposed.r2dbc.tests.withTables
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.r2dbc.pool.ConnectionPool
import io.r2dbc.pool.ConnectionPoolConfiguration
import io.r2dbc.spi.ConnectionFactories
import org.jetbrains.exposed.v1.core.Schema
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabaseConfig
import org.junit.jupiter.api.Test

class R2dbcFixtureConsumerTest {

    companion object: KLoggingChannel()

    private enum class ApplicationDb { PRIMARY }

    @Test
    fun `외부 package의 enum과 pool로 모든 overload를 사용한다`() = runSuspendIO {
        val url = "r2dbc:h2:mem:///r2dbc_external_consumer;DB_CLOSE_DELAY=-1;"
        val pool = ConnectionPool(ConnectionPoolConfiguration.builder(ConnectionFactories.get(url)).maxSize(2).build())

        try {
            val fixture = r2dbcTestDbFixture(
                key = ApplicationDb.PRIMARY,
                createDatabase = { configure ->
                    R2dbcDatabase.connect(pool, R2dbcDatabaseConfig.Builder().apply { setUrl(url); configure() })
                }
            )
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
            calls shouldBeEqualTo 3
            pool.isDisposed shouldBeEqualTo false
        } finally {
            pool.dispose()
        }
    }
}
