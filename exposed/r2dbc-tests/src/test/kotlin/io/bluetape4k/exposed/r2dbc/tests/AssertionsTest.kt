package io.bluetape4k.exposed.r2dbc.tests

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.coInvoking
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.junit5.coroutines.runSuspendIO
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import kotlin.coroutines.cancellation.CancellationException

class AssertionsTest: AbstractExposedR2dbcTest() {

    object AssertionTable: IntIdTable("assertion_r2dbc_table") {
        val name = varchar("name", 64)
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `expectException 은 지정한 예외를 검증한다`(testDB: TestDB) = runSuspendIO {
        withDb(testDB) {
            expectException<IllegalArgumentException> {
                throw IllegalArgumentException("boom")
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `expectExceptionSuspending 은 지정한 예외를 검증한다`(testDB: TestDB) = runSuspendIO {
        withDb(testDB) {
            expectExceptionSuspending<IllegalStateException> {
                throw IllegalStateException("boom")
            }
        }
    }

    @Test
    fun `expectExceptionSuspending 은 기대하지 않은 cancellation 을 재전파한다`() = runSuspendIO {
        val cancellation = CancellationException("cancelled")

        val thrown = coInvoking {
            expectExceptionSuspending<IllegalStateException> {
                throw cancellation
            }
        }.shouldThrow(CancellationException::class)

        thrown shouldBeSameInstanceAs cancellation
    }

    @Test
    fun `expectExceptionSuspending 은 명시한 cancellation 을 허용한다`() = runSuspendIO {
        expectExceptionSuspending<CancellationException> {
            throw CancellationException("expected cancellation")
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `expectException 은 다른 예외 타입이면 AssertionError 를 던진다`(testDB: TestDB) = runSuspendIO {
        withDb(testDB) {
            assertFailsWith<AssertionError> {
                expectException<IllegalArgumentException> {
                    throw IllegalStateException("unexpected")
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `assertFailAndRollback 은 실패 블록을 처리한 뒤 트랜잭션을 계속 사용할 수 있다`(testDB: TestDB) = runSuspendIO {
        withTables(testDB, AssertionTable) {
            assertFailAndRollback("block must fail") {
                AssertionTable.insert {
                    it[name] = "discarded"
                }
                error("forced failure")
            }

            AssertionTable.insert {
                it[name] = "persisted"
            }
            AssertionTable.selectAll().count() shouldBeEqualTo 1L
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `assertFailAndRollback 은 cancellation 을 보존하고 rollback 한다`(testDB: TestDB) = runSuspendIO {
        withTables(testDB, AssertionTable) {
            val cancellation = CancellationException("cancelled")
            val thrown = coInvoking {
                assertFailAndRollback("cancellation must be rethrown") {
                    AssertionTable.insert {
                        it[name] = "discarded"
                    }
                    throw cancellation
                }
            }.shouldThrow(CancellationException::class)

            thrown shouldBeSameInstanceAs cancellation
            AssertionTable.selectAll().count() shouldBeEqualTo 0L
        }
    }

    @Test
    fun `preserveFailure 는 primary 와 cleanup failure 를 보존한다`() = runSuspendIO {
        val primary = IllegalStateException("primary")
        val cleanup = IllegalArgumentException("cleanup")

        val thrown = coInvoking {
            preserveFailure(
                block = { throw primary },
                cleanup = {
                    yield()
                    throw cleanup
                },
            )
        }.shouldThrow(IllegalStateException::class)

        thrown shouldBeSameInstanceAs primary
        val suppressed = thrown.suppressed.single()
        suppressed::class shouldBeEqualTo cleanup::class
        suppressed.message shouldBeEqualTo cleanup.message
    }

    @Test
    fun `preserveFailure 는 cancellation 중에도 cleanup 을 완료한다`() = runSuspendIO {
        val cancellation = CancellationException("cancelled")
        var cleaned = false

        val thrown = coInvoking {
            preserveFailure(
                block = { throw cancellation },
                cleanup = {
                    yield()
                    cleaned = true
                },
            )
        }.shouldThrow(CancellationException::class)

        thrown shouldBeSameInstanceAs cancellation
        cleaned.shouldBeTrue()
    }
}
