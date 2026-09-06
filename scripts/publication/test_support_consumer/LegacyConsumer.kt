@file:Suppress("DEPRECATION")

package consumer

import io.bluetape4k.exposed.tests.TestDB as JdbcTestDB
import io.bluetape4k.exposed.tests.withDb as jdbcWithDb
import io.bluetape4k.exposed.tests.withDbSuspending
import io.bluetape4k.exposed.tests.withSuspendedDb
import io.bluetape4k.exposed.tests.withSuspendedTables
import io.bluetape4k.exposed.tests.withTables as jdbcWithTables
import io.bluetape4k.exposed.tests.withTablesSuspending
import io.bluetape4k.exposed.tests.withSchemas as jdbcWithSchemas
import io.bluetape4k.exposed.tests.withSchemasSuspending
import io.bluetape4k.exposed.r2dbc.tests.TestDB as R2dbcTestDB
import io.bluetape4k.exposed.r2dbc.tests.withDb as r2dbcWithDb
import io.bluetape4k.exposed.r2dbc.tests.withTables as r2dbcWithTables
import io.bluetape4k.exposed.r2dbc.tests.withSchemas as r2dbcWithSchemas
import kotlinx.coroutines.runBlocking

/** 이전 JAR로 컴파일한 기본 인자와 deprecated 진입점을 신규 JAR에서 실행한다. */
fun main() {
    var calls = 0
    jdbcWithDb(JdbcTestDB.H2) { calls++ }
    jdbcWithTables(JdbcTestDB.H2) { calls++ }
    jdbcWithSchemas(JdbcTestDB.H2) { calls++ }
    runBlocking {
        withDbSuspending(JdbcTestDB.H2) { calls++ }
        withSuspendedDb(JdbcTestDB.H2) { calls++ }
        withTablesSuspending(JdbcTestDB.H2) { calls++ }
        withSuspendedTables(JdbcTestDB.H2) { calls++ }
        withSchemasSuspending(JdbcTestDB.H2) { calls++ }
        r2dbcWithDb(R2dbcTestDB.H2) { calls++ }
        r2dbcWithTables(R2dbcTestDB.H2) { calls++ }
        r2dbcWithSchemas(R2dbcTestDB.H2) { calls++ }
    }
    check(calls == 11) { "기존 진입점의 본문이 모두 실행되어야 한다: $calls" }
}
