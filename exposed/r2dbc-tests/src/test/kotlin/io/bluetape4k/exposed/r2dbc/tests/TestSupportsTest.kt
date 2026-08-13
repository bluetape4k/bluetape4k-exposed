package io.bluetape4k.exposed.r2dbc.tests

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.junit5.coroutines.runSuspendIO
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.r2dbc.exists
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger

class TestSupportsTest: AbstractExposedR2dbcTest() {

    object UtilityTable: IntIdTable("utility_r2dbc_table") {
        val name = varchar("name", 64)
    }

    object FailingCleanupTable: IntIdTable("utility_r2dbc_cleanup_failure_table") {
        val name = varchar("name", 64)

        override fun dropStatement(): List<String> = error("forced cleanup failure")
    }

    object CancellationCleanupTable: IntIdTable("utility_r2dbc_cancellation_cleanup_table") {
        val name = varchar("name", 64)
        private val dropAttempts = AtomicInteger()

        fun resetDropAttempts() {
            dropAttempts.set(0)
        }

        override fun dropStatement(): List<String> =
            if (dropAttempts.incrementAndGet() == 1) super.dropStatement()
            else throw CancellationException("cleanup cancelled")
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `withAutoCommit 은 예외가 발생해도 autoCommit 을 원복한다`(testDB: TestDB) = runSuspendIO {
        withDb(testDB) {
            val conn = connection()
            val originalAutoCommit = conn.getAutoCommit()

            assertFailsWith<IllegalStateException> {
                withAutoCommit(autoCommit = !originalAutoCommit) {
                    throw IllegalStateException("boom")
                }
            }

            conn.getAutoCommit() shouldBeEqualTo originalAutoCommit
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `withTables 는 statement 예외를 호출자에게 전파하고 테이블을 정리한다`(testDB: TestDB) = runSuspendIO {
        assertFailsWith<IllegalStateException> {
            withTables(testDB, UtilityTable) {
                throw IllegalStateException("boom")
            }
        }

        withDb(testDB) {
            UtilityTable.exists().shouldBeFalse()
        }
    }

    @Test
    fun `withTables 는 cleanup 실패가 발생해도 실제 coroutine 취소를 보존한다`() = runSuspendIO {
        CancellationCleanupTable.resetDropAttempts()
        val cancellation = coroutineScope {
            val statementEntered = CompletableDeferred<Unit>()
            val job = async {
                withTables(TestDB.H2, CancellationCleanupTable) {
                    statementEntered.complete(Unit)
                    awaitCancellation()
                }
            }

            statementEntered.await()
            job.cancel()
            assertFailsWith<CancellationException> { job.await() }
        }

        cancellation.suppressed.shouldBeEmpty()
        (cancellation.message?.contains("cleanup cancelled") ?: false).shouldBeFalse()
    }

    @Test
    fun `withTables 는 statement 실패 아래 cleanup 실패를 suppressed 로 보존한다`() = runSuspendIO {
        val failure = assertFailsWith<IllegalStateException> {
            withTables(TestDB.H2, FailingCleanupTable) {
                throw IllegalStateException("statement failure")
            }
        }

        generateSequence<Throwable>(failure) { it.cause }
            .last { it.message == "statement failure" }
            .suppressed
            .size shouldBeEqualTo 2
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `connect 는 db 필드를 설정한다`(testDB: TestDB) = runSuspendIO {
        val database = testDB.connect()
        testDB.db.shouldNotBeNull() shouldBeEqualTo database
    }
}
