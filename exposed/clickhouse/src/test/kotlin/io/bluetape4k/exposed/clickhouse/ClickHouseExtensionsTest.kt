package io.bluetape4k.exposed.clickhouse

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.junit5.coroutines.runSuspendIO
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClickHouseExtensionsTest: AbstractClickHouseTest() {

    // 추가 상태로 코루틴 stacktrace recovery의 복사를 배제하고 원래 원인을 관측한다.
    private class QueryFailure(val marker: String): IllegalStateException(marker)

    private fun database(attempts: Int): Database = Database.connect(
        getNewConnection = {
            ClickHouseConnectionWrapper(
                DriverManager.getConnection(
                    "jdbc:clickhouse://${clickhouse.host}:${clickhouse.port}/default",
                    clickhouse.username, clickhouse.password,
                )
            )
        },
        databaseConfig = DatabaseConfig {
            defaultMaxAttempts = attempts
            defaultMinRetryDelay = 0
            defaultMaxRetryDelay = 0
        },
    )

    @Test
    fun `queryList는 설정된 SQLException 시도 상한을 따른다`() = runSuspendIO {
        for (attempts in listOf(1, 2)) {
            val calls = AtomicInteger()
            assertFailsWith<SQLException> {
                queryList<Int>(database(attempts)) {
                    calls.incrementAndGet()
                    throw SQLException("retry test")
                }
            }
            calls.get() shouldBeEqualTo attempts
        }
    }

    @Test
    fun `queryList는 일반 예외를 재시도하지 않는다`() = runSuspendIO {
        val calls = AtomicInteger()
        assertFailsWith<QueryFailure> {
            queryList<Int>(database(2)) {
                calls.incrementAndGet()
                throw QueryFailure("no retry")
            }
        }
        calls.get() shouldBeEqualTo 1
    }

    @Test
    fun `queryList의 실제 Job 취소는 결과를 전달하거나 재시도하지 않는다`() = runSuspendIO {
        val calls = AtomicInteger()
        val reached = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        // 동기 블록의 반환 경계를 고정하므로 유한 latch를 사용한다.
        val job = launch {
            queryList(database(2)) {
                calls.incrementAndGet()
                reached.complete(Unit)
                check(release.await(5, TimeUnit.SECONDS))
                listOf(1)
            }
            error("cancelled result must not be delivered")
        }
        try {
            reached.await()
            job.cancel()
        } finally {
            release.countDown()
            job.cancelAndJoin()
        }
        job.isCancelled.shouldBeTrue()
        calls.get() shouldBeEqualTo 1
    }

    @Test
    fun `queryList는 반환 전에 모든 항목을 수집한다`() = runSuspendIO {
        val visited = mutableListOf<Int>()
        val result = queryList(db) {
            Iterable { (1..3).asSequence().onEach(visited::add).iterator() }
        }
        visited shouldBeEqualTo listOf(1, 2, 3)
        result shouldBeEqualTo listOf(1, 2, 3)
    }

    @Test
    fun `queryList는 빈 결과를 반환한다`() = runSuspendIO {
        queryList(db) { emptyList<Int>() }.shouldBeEmpty()
    }

    @Test
    fun `queryList는 블록의 예외를 보존한다`() = runSuspendIO {
        val expected = QueryFailure("queryList failure")
        val caught = assertFailsWith<QueryFailure> {
            queryList<Int>(db) { throw expected }
        }
        caught.shouldBeSameInstanceAs(expected)
    }

    @Test
    fun `기존 queryFlow는 첫 방출 전에 전체 수집한다`() = runSuspendIO {
        val visited = mutableListOf<Int>()
        queryFlow(db) {
            Iterable { (1..3).asSequence().onEach(visited::add).iterator() }
        }
            .take(1)
            .collect {
                visited shouldBeEqualTo listOf(1, 2, 3)
            }
    }

    @Test
    fun `suspendTransaction - 정상 결과 반환`() = runSuspendIO(timeout = 30.seconds) {
        val result = suspendTransaction(db) {
            exec("SELECT 1") { rs: ResultSet -> rs.next(); rs.getInt(1) }
        }
        result shouldBeEqualTo 1
    }

    @Test
    fun `suspendTransaction - 예외 전파`() {
        assertFailsWith<RuntimeException> {
            runBlocking {
                suspendTransaction(db) { error("test") }
            }
        }
    }

    @Test
    fun `queryFlow - 빈 결과 collect`() = runSuspendIO(timeout = 30.seconds) {
        val results = queryFlow(db) {
            emptyList<Int>()
        }.toList()

        results.shouldBeEmpty()
    }
}
