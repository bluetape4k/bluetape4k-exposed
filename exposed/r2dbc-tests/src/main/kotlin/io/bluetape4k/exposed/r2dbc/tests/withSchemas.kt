// 호출자 callback의 Error까지 원형대로 전파하고 cleanup 실패가 덮지 않도록 포착한다.
@file:Suppress("TooGenericExceptionCaught")

package io.bluetape4k.exposed.r2dbc.tests

import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.Schema
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import java.util.concurrent.CancellationException

/** enum fixture의 요청 schema를 생성·정리하며 미지원 dialect에서는 본문을 실행하지 않습니다. */
suspend fun withSchemas(
    dialect: TestDB,
    vararg schemas: Schema,
    configure: (DatabaseConfig.Builder.() -> Unit)? = null,
    statement: suspend R2dbcTransaction.() -> Unit,
) = withSchemas(r2dbcFixtureFor(dialect), *schemas, configure = configure) { statement() }

/**
 * 호출자가 소유한 요청 schema만 생성하고 cascade 정리합니다.
 * 부분 생성 실패도 정리하며 원래 본문 실패/취소를 우선하고 cleanup 실패를 suppressed로 보존합니다.
 * 공유 schema나 production schema를 전달해서는 안 됩니다.
 */
suspend fun <K> withSchemas(
    fixture: R2dbcTestDbFixture<K>,
    vararg schemas: Schema,
    configure: (DatabaseConfig.Builder.() -> Unit)? = null,
    statement: suspend R2dbcTransaction.(K) -> Unit,
) {
    withDb(fixture, configure = configure) { key ->
        if (currentDialectTest.supportsCreateSchema) {
            var failure: Throwable? = null
            try {
                SchemaUtils.createSchema(*schemas)
                statement(key)
                commit()
            } catch (thrown: CancellationException) {
                failure = thrown
                throw thrown
            } catch (thrown: Throwable) {
                failure = thrown
                throw thrown
            } finally {
                cleanupR2dbcFixture(failure, recover = false, suppressOnCancellation = true) {
                    SchemaUtils.dropSchema(*schemas, cascade = true)
                }
            }
        }
    }
}
