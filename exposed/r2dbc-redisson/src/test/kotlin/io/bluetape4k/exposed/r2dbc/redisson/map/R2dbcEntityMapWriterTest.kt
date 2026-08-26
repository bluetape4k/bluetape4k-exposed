package io.bluetape4k.exposed.r2dbc.redisson.map

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.exposed.r2dbc.tests.AbstractExposedR2dbcTest
import io.bluetape4k.exposed.r2dbc.tests.TestDB
import io.bluetape4k.exposed.r2dbc.tests.withTables
import io.bluetape4k.junit5.coroutines.runSuspendIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.junit.jupiter.api.Test
import java.util.concurrent.ExecutionException

/**
 * [R2dbcEntityMapWriter]의 lifecycle과 close 이후 write 계약을 검증합니다.
 */
class R2dbcEntityMapWriterTest: AbstractExposedR2dbcTest() {

    private object TestTable: LongIdTable("r2dbc_entity_map_writer_test") {
        val value = varchar("value", 64)
    }

    @Test
    fun `close 후 write는 DB 작업을 예약하지 않고 실패한다`() = runSuspendIO {
        withTables(TestDB.H2, TestTable) {
            val writer = R2dbcEntityMapWriter<Long, String>(
                writeToDb = { values ->
                    values.values.forEach { value ->
                        TestTable.insert { it[TestTable.value] = value }
                    }
                },
                deleteFromDb = { },
            )

            writer.close()

            val failure = assertFailsWith<ExecutionException> {
                writer.write(mapOf(1L to "after-close")).toCompletableFuture().get()
            }
            failure.cause?.javaClass shouldBeEqualTo IllegalStateException::class.java
            TestTable.selectAll().count() shouldBeEqualTo 0L

            val deleteFailure = assertFailsWith<ExecutionException> {
                writer.delete(listOf(1L)).toCompletableFuture().get()
            }
            deleteFailure.cause?.javaClass shouldBeEqualTo IllegalStateException::class.java
        }
    }

    @Test
    fun `closeAndJoin은 진행 중 write의 cancellation cleanup 완료까지 기다린다`() = runSuspendIO {
        withTables(TestDB.H2, TestTable) {
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val completed = CompletableDeferred<Unit>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

            try {
                val writer = R2dbcEntityMapWriter<Long, String>(
                    writeToDb = {
                        started.complete(Unit)
                        try {
                            awaitCancellation()
                        } catch (e: CancellationException) {
                            cancelled.complete(Unit)
                            throw e
                        } finally {
                            completed.complete(Unit)
                        }
                    },
                    deleteFromDb = { },
                    scope = scope,
                )

                writer.write(mapOf(1L to "in-flight"))
                withTimeout(5_000) { started.await() }

                writer.closeAndJoin()

                withTimeout(5_000) { cancelled.await() }
                withTimeout(5_000) { completed.await() }
            } finally {
                scope.cancel()
            }
        }
    }
}
