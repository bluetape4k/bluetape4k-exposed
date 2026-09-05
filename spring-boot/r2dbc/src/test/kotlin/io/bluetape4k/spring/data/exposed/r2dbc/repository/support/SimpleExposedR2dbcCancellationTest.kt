package io.bluetape4k.spring.data.exposed.r2dbc.repository.support

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.exposed.r2dbc.tests.TestDB
import io.bluetape4k.exposed.r2dbc.tests.withTables
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.spring.data.exposed.r2dbc.domain.User
import io.bluetape4k.spring.data.exposed.r2dbc.domain.Users
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/** Spring repository가 소유한 최상위 transaction의 실제 입력 수집 취소를 검증한다. */
class SimpleExposedR2dbcCancellationTest {

    companion object {
        @JvmStatic
        fun databases() = TestDB.enabledDialects().filter { it == TestDB.H2 || it == TestDB.POSTGRESQL }
    }

    // 추가 상태로 coroutine stacktrace recovery의 복사를 막고 repository 경계의 동일성을 검증한다.
    private class InputCancellation(val inputId: String) : CancellationException(inputId)

    @ParameterizedTest
    @MethodSource("databases")
    fun `입력 Flow 수집 중 취소하면 부분 저장을 rollback하고 결과를 방출하지 않는다`(testDB: TestDB) = runSuspendIO {
        val previousDatabase = TransactionManager.defaultDatabase
        try {
            // withTables 안에서 실행하면 outer transaction을 재사용하므로 setup과 검증을 분리한다.
            withTables(testDB, Users, dropTables = false) {}
            TransactionManager.defaultDatabase = checkNotNull(testDB.db)
            val repository = SimpleExposedR2dbcRepository(
                Users,
                { row -> User(row[Users.id].value, row[Users.name], row[Users.email], row[Users.age]) },
                { user ->
                    mapOf<Column<*>, Any?>(
                        Users.name to user.name,
                        Users.email to user.email,
                        Users.age to user.age,
                    )
                },
                { user -> user.id },
            )
            val emitted = mutableListOf<User>()
            val cancellation = InputCancellation("cancel-input")

            // 첫 INSERT 완료 후 취소해야 하므로 임의 지연이나 stress harness 대신 barrier를 사용한다.
            withTimeout(10_000) {
                coroutineScope {
                    val inserted = CompletableDeferred<Unit>()
                    val observed = CompletableDeferred<CancellationException>()
                    val job = launch {
                        try {
                            repository.saveAll(flow {
                                emit(User(name = "Cancelled", email = "cancelled@example.com", age = 30))
                                inserted.complete(Unit)
                                awaitCancellation()
                            }).collect { emitted.add(it) }
                        } catch (cause: CancellationException) {
                            observed.complete(cause)
                            inserted.completeExceptionally(cause)
                            throw cause
                        }
                    }
                    inserted.await()
                    job.cancel(cancellation)
                    job.join()
                    observed.await() shouldBeEqualTo cancellation
                }
            }

            emitted shouldHaveSize 0
            repository.count() shouldBeEqualTo 0L
            repository.save(User(name = "Next", email = "next@example.com", age = 25))
            repository.count() shouldBeEqualTo 1L
        } finally {
            withContext(NonCancellable) {
                try {
                    withTables(testDB, Users) {}
                } finally {
                    TransactionManager.defaultDatabase = previousDatabase
                }
            }
        }
    }
}
