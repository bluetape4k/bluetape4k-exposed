package io.bluetape4k.exposed.r2dbc.tests

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabaseConfig
import org.junit.jupiter.api.Test

/** 결정적인 대기/취소 순서를 제어하므로 범용 stress tester 대신 deferred를 사용한다. */
class R2dbcFixtureConcurrencyTest {

    companion object: KLoggingChannel()

    private fun fixture(key: String) = r2dbcTestDbFixture(
        key,
        createDatabase = { configure ->
            R2dbcDatabase.connect(databaseConfig = R2dbcDatabaseConfig {
                setUrl("r2dbc:h2:mem:///$key;DB_CLOSE_DELAY=-1;")
                configure()
            })
        }
    )

    @Test
    fun `종료된 진입 토큰은 후속 호출을 거부하지 않는다`() = runSuspendIO {
        val fixture = fixture("r2dbc_expired_entry")
        var inherited: kotlin.coroutines.CoroutineContext = kotlin.coroutines.EmptyCoroutineContext

        withDb(fixture) {
            inherited = currentCoroutineContext().minusKey(kotlinx.coroutines.Job)
        }

        withTimeout(timeMillis = 5_000) {
            withContext(inherited) { withDb(fixture) {} }
        }

        fixture.semaphore.availablePermits shouldBeEqualTo 1
    }

    @Test
    fun `permit 획득 후 생성 callback에서 취소되어도 반환한다`() = runSuspendIO {
        coroutineScope {
            lateinit var job: Job
            var bodyRan = false

            val fixture = r2dbcTestDbFixture(
                key = "r2dbc_acquired_cancel",
                createDatabase = { configure ->
                    job.cancel()
                    R2dbcDatabase.connect(databaseConfig = R2dbcDatabaseConfig {
                        setUrl("r2dbc:h2:mem:///r2dbc_acquired_cancel;DB_CLOSE_DELAY=-1;")
                        configure()
                    })
                }
            )

            job = launch(start = CoroutineStart.LAZY) {
                withDb(fixture) {
                    bodyRan = true
                }
            }
            job.start()
            job.join()

            bodyRan.shouldBeFalse()
            fixture.semaphore.availablePermits shouldBeEqualTo 1

            withDb(fixture) {}
        }
    }

    @Test
    fun `대기자는 FIFO로 진행하고 다른 fixture는 막지 않는다`() = runSuspendIO {
        withTimeout(timeMillis = 5_000) {
            coroutineScope {
                val fixture = fixture("r2dbc_fifo")
                val other = fixture("r2dbc_fifo_other")
                val order = mutableListOf<Int>()

                fixture.semaphore.acquire()
                val first = launch(start = CoroutineStart.UNDISPATCHED) {
                    withDb(fixture) {
                        order.add(1)
                    }
                }
                val second = launch(start = CoroutineStart.UNDISPATCHED) {
                    withDb(fixture) {
                        order.add(2)
                    }
                }

                withDb(other) {
                    order.shouldBeEmpty()
                }

                fixture.semaphore.release()
                first.join()
                second.join()

                order shouldBeEqualTo listOf(1, 2)
                fixture.semaphore.availablePermits shouldBeEqualTo 1
            }
        }
    }

    @Test
    fun `대기 취소 뒤 후속 호출이 실행된다`() = runSuspendIO {
        withTimeout(timeMillis = 5_000) {
            coroutineScope {
                val fixture = fixture("r2dbc_wait_cancel")
                val entered = CompletableDeferred<Unit>()
                val first = launch {
                    withDb(fixture) {
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                }
                entered.await()

                var cancelledBody = false
                val waiting = launch(start = CoroutineStart.UNDISPATCHED) {
                    withDb(fixture) {
                        cancelledBody = true
                    }
                }
                waiting.cancelAndJoin()
                first.cancelAndJoin()

                withDb(fixture) {
                    cancelledBody.shouldBeFalse()
                }
            }
        }
    }

    @Test
    fun `dispatcher와 자식 coroutine에서 활성 중첩을 거부한다`() = runSuspendIO {
        val fixture = fixture("r2dbc_nested")

        withTimeout(timeMillis = 5_000) {
            withDb(fixture) {
                withContext(Dispatchers.Default) {
                    assertFailsWith<IllegalStateException> {
                        withDb(fixture) {}
                    }
                }
                coroutineScope {
                    launch {
                        assertFailsWith<IllegalStateException> {
                            withDb(fixture) {}
                        }
                    }.join()
                }
            }
            withDb(fixture) {}
        }
    }
}
