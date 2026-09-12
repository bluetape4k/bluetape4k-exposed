package io.bluetape4k.exposed.tests

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.junit5.awaitility.untilSuspending
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.awaitility.kotlin.await
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import kotlin.coroutines.ContinuationInterceptor

/** latch로 permit 인계 순서를 고정한다. 일반 stress tester는 이 취소 지점을 제어하지 못한다. */
class JdbcFixtureConcurrencyTest {

    companion object: KLogging()

    private fun fixture(key: String): JdbcTestDbFixture<String> =
        jdbcTestDbFixture(
            key,
            createDatabase = { configure ->
                Database.connect(
                    "jdbc:h2:mem:$key;DB_CLOSE_DELAY=-1",
                    "org.h2.Driver",
                    databaseConfig = DatabaseConfig { configure() }
                )
            }
        )

    @Test
    fun `blocking과 suspend 대기자는 FIFO로 진행하고 다른 fixture는 막지 않는다`() = runSuspendIO {
        withTimeout(timeMillis = 5_000) {
            coroutineScope {
                val fixture = fixture("jdbc_fifo")
                val other = fixture("jdbc_fifo_other")
                val order = mutableListOf<Int>()
                fixture.semaphore.acquire()
                var initialPermitHeld = true

                try {
                    val first = launch {
                        withDb(fixture) {
                            order.add(1)
                        }
                    }
                    await.untilSuspending { fixture.semaphore.queueLength == 1 }
                    val second = launch {
                        withDbSuspending(fixture) {
                            order.add(2)
                        }
                    }
                    await.untilSuspending { fixture.semaphore.queueLength == 2 }

                    withDbSuspending(other) {
                        order.shouldBeEmpty()
                    }

                    fixture.semaphore.release()
                    initialPermitHeld = false
                    first.join()
                    second.join()
                    order shouldBeEqualTo listOf(1, 2)
                } finally {
                    if (initialPermitHeld) fixture.semaphore.release()
                }
                fixture.semaphore.availablePermits() shouldBeEqualTo 1
            }
        }
    }

    @Test
    fun `종료된 진입 토큰을 상속해도 후속 호출을 거부하지 않는다`() = runSuspendIO {
        val fixture = fixture("jdbc_expired_entry")
        var inherited: kotlin.coroutines.CoroutineContext = kotlin.coroutines.EmptyCoroutineContext

        withDbSuspending(fixture) {
            inherited = currentCoroutineContext().minusKey(Job)
        }
        withTimeout(timeMillis = 5_000) {
            withContext(inherited) {
                withDbSuspending(fixture) {}
            }
        }
        fixture.semaphore.availablePermits() shouldBeEqualTo 1
    }

    @Test
    fun `permit 획득 후 wrapper 생성 중 취소해도 본문 없이 permit을 반환한다`() = runSuspendIO {
        coroutineScope {
            lateinit var job: Job
            var bodyRan = false
            val fixture = jdbcTestDbFixture(
                key = "jdbc_acquired_cancel",
                createDatabase = { configure ->
                    job.cancel()
                    Database.connect(
                        "jdbc:h2:mem:jdbc_acquired_cancel;DB_CLOSE_DELAY=-1",
                        "org.h2.Driver",
                        databaseConfig = DatabaseConfig { configure() }
                    )
                }
            )
            job = launch(start = CoroutineStart.LAZY) {
                withDbSuspending(fixture) {
                    bodyRan = true
                }
            }
            job.start()
            job.join()

            bodyRan.shouldBeFalse()
            fixture.semaphore.availablePermits() shouldBeEqualTo 1

            withDbSuspending(fixture) {}
        }
    }

    @Test
    fun `suspend 대기 취소 뒤 permit을 후속 호출에 반환한다`() = runSuspendIO {
        withTimeout(timeMillis = 5_000) {
            coroutineScope {
                val fixture = fixture("jdbc_wait_cancel")
                val entered = CompletableDeferred<Unit>()
                val first = launch {
                    withDbSuspending(fixture) {
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                }
                entered.await()
                var cancelledBody = false
                val waitingStarted = CompletableDeferred<Unit>()

                val waiting = launch {
                    waitingStarted.complete(Unit)
                    withDbSuspending(fixture) {
                        cancelledBody = true
                    }
                }
                waitingStarted.await()
                await.untilSuspending { fixture.semaphore.hasQueuedThreads() }

                waiting.cancelAndJoin()
                first.cancelAndJoin()

                withDbSuspending(fixture) {
                    cancelledBody.shouldBeFalse()
                }
            }
        }
    }

    @Test
    fun `명시한 dispatcher에서 본문을 실행한다`() = runSuspendIO {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            .use { dispatcher ->
                withDbSuspending(fixture("jdbc_context"), context = dispatcher) {
                    currentCoroutineContext()[ContinuationInterceptor] shouldBeSameInstanceAs dispatcher
                }
            }
    }

    @Test
    fun `suspend에서 blocking과 dispatcher 전환 중첩은 즉시 거부한다`() = runSuspendIO {
        val fixture = fixture("jdbc_nested")
        withTimeout(timeMillis = 5_000) {
            withDbSuspending(fixture) {
                assertFailsWith<IllegalStateException> {
                    withDb(fixture) {}
                }
                withContext(Dispatchers.Default) {
                    assertFailsWith<IllegalStateException> {
                        withDbSuspending(fixture) {}
                    }
                }
            }
            withDbSuspending(fixture) {}
        }
    }

    @Test
    fun `blocking 중첩은 거부하고 다른 fixture는 실행한다`() {
        val first = fixture("jdbc_blocking_nested")
        val other = fixture("jdbc_other")

        withDb(first) {
            assertFailsWith<IllegalStateException> {
                withDb(first) {}
            }
            withDb(other) {
                currentJdbcTestDbFixture shouldBeSameInstanceAs other
            }
            currentJdbcTestDbFixture shouldBeSameInstanceAs first
        }
    }

    @Test
    fun `기존 enum blocking과 suspend가 같은 fixture와 기본 연결을 쓴다`() = runSuspendIO {
        val fixture = jdbcFixtureFor(TestDB.H2)

        withDb(TestDB.H2) {
            currentJdbcTestDbFixture shouldBeSameInstanceAs fixture
            currentTestDB shouldBeEqualTo TestDB.H2
        }

        withDbSuspending(TestDB.H2) {
            currentJdbcTestDbFixture shouldBeSameInstanceAs fixture
            db shouldBeSameInstanceAs TestDB.H2.db
            commit()
            currentTestDB shouldBeEqualTo TestDB.H2
        }
    }
}
