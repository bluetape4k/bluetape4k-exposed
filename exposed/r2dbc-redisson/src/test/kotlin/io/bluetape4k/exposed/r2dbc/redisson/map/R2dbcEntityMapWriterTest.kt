package io.bluetape4k.exposed.r2dbc.redisson.map

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.coroutines.support.awaitUntil
import io.bluetape4k.exposed.r2dbc.tests.AbstractExposedR2dbcTest
import io.bluetape4k.exposed.r2dbc.tests.TestDB
import io.bluetape4k.exposed.r2dbc.tests.withDb
import io.bluetape4k.exposed.r2dbc.tests.withTables
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.future.await
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * [R2dbcEntityMapWriter]의 lifecycle과 close 이후 write 계약을 검증합니다.
 */
class R2dbcEntityMapWriterTest: AbstractExposedR2dbcTest() {

    companion object: KLoggingChannel()

    private object TestTable: LongIdTable("r2dbc_entity_map_writer_test") {
        val value = varchar("value", 64)
    }

    @Test
    fun `write 실패 로그는 errorType만 기록하고 원시 예외를 첨부하지 않는다`() = runSuspendIO {
        withDb(TestDB.H2) {
            val secret = "credential=r2dbc-writer-secret"
            val writer = R2dbcEntityMapWriter<Long, String>(
                writeToDb = { throw IllegalStateException(secret) },
                deleteFromDb = { },
            )

            RecordingLogAppender().use { appender ->
                assertFailsWith<IllegalStateException> {
                    writer.write(mapOf(1L to "value")).await()
                }

                val errorEvent = appender.events.first { event ->
                    event.formattedMessage.contains("엔티티 Write 중 오류")
                }
                errorEvent.formattedMessage shouldContain "errorType=IllegalStateException"
                errorEvent.throwableProxy.shouldBeNull()
                appender.rendered shouldNotContain secret
                writer.closeAndJoin()
            }
        }
    }

    @Test
    fun `delete 실패 로그는 errorType만 기록하고 원시 예외를 첨부하지 않는다`() = runSuspendIO {
        withDb(TestDB.H2) {
            val secret = "token=r2dbc-delete-secret"
            val writer = R2dbcEntityMapWriter<Long, String>(
                writeToDb = { },
                deleteFromDb = { throw IllegalArgumentException(secret) },
            )

            RecordingLogAppender().use { appender ->
                assertFailsWith<IllegalArgumentException> {
                    writer.delete(listOf(1L)).await()
                }

                val errorEvent = appender.events.first { event ->
                    event.formattedMessage.contains("엔티티 삭제 중 오류")
                }
                errorEvent.formattedMessage shouldContain "errorType=IllegalArgumentException"
                errorEvent.throwableProxy.shouldBeNull()
                appender.rendered shouldNotContain secret
                writer.closeAndJoin()
            }
        }
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

            assertFailsWith<IllegalStateException> {
                writer.write(mapOf(1L to "after-close")).await()
            }
            TestTable.selectAll().count() shouldBeEqualTo 0L

            assertFailsWith<IllegalStateException> {
                writer.delete(listOf(1L)).await()
            }
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
                started.awaitUntil(5.seconds)

                writer.closeAndJoin()

                cancelled.awaitUntil(5.seconds)
                completed.awaitUntil(5.seconds)
            } finally {
                scope.cancel()
            }
        }
    }
}
