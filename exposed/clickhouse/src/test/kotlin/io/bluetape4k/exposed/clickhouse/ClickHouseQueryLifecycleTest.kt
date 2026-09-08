package io.bluetape4k.exposed.clickhouse

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.exposed.clickhouse.support.JdbcObservation
import io.bluetape4k.exposed.clickhouse.support.TrackingClickHouseConnection
import io.bluetape4k.junit5.awaitility.untilSuspending
import io.bluetape4k.junit5.coroutines.runSuspendIO
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import org.awaitility.Awaitility.await
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.inTopLevelSuspendTransaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClickHouseQueryLifecycleTest: AbstractClickHouseTest() {
    private object Numbers: Table("system.numbers") {
        val number = long("number")
    }

    private class MarkerFailure(val marker: String): IllegalStateException(marker)
    private class SqlFailure(val marker: String): SQLException(marker)
    private class MarkerCancellation(val marker: String): CancellationException(marker)

    private inner class Fixture: AutoCloseable {
        val observed = JdbcObservation()
        val pool = HikariDataSource(HikariConfig().apply {
            jdbcUrl = "jdbc:clickhouse://${clickhouse.host}:${clickhouse.port}/default"
            username = clickhouse.username
            password = clickhouse.password
            maximumPoolSize = 2
            minimumIdle = 0
            connectionTimeout = 500
            isAutoCommit = true
        })
        val database = Database.connect(getNewConnection = {
            TrackingClickHouseConnection(ClickHouseConnectionWrapper(pool.connection), observed)
        })

        fun rows() = queryFlow(database, query = { Numbers.selectAll().limit(10) }, mapper = { it[Numbers.number] })

        fun assertReleased() {
            observed.connections.get() shouldBeEqualTo 0
            observed.statements.get() shouldBeEqualTo 0
            observed.results.get() shouldBeEqualTo 0
            pool.hikariPoolMXBean.activeConnections shouldBeEqualTo 0
            pool.isClosed.shouldBeFalse()
        }

        override fun close() = pool.close()
    }

    @Test
    fun `확장 logger는 mapper 예외 내용을 기록하지 않는다`() = runSuspendIO {
        val logger = LoggerFactory.getLogger("io.bluetape4k.exposed.clickhouse.ClickHouseStreamingLog") as Logger
        val events = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(events)
        try {
            Fixture().use { fixture ->
                assertFailsWith<MarkerFailure> {
                    queryFlow(fixture.database, query = { Numbers.selectAll().limit(1) }, mapper = {
                        throw MarkerFailure("mapper-sensitive-marker")
                    }).collect()
                }
                events.list.isNotEmpty().shouldBeTrue()
                events.list.none { it.formattedMessage.contains("mapper-sensitive-marker") }.shouldBeTrue()
                events.list.all { it.throwableProxy == null }.shouldBeTrue()
                fixture.assertReleased()
            }
        } finally {
            logger.detachAppender(events)
            events.stop()
        }
    }

    @Test
    fun `정리 실패 정책은 Exposed 로그에 남고 호출자 풀은 재사용된다`() = runSuspendIO {
        val logger = LoggerFactory.getLogger("Exposed") as Logger
        val events = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(events)
        try {
            for (boundary in listOf("statement", "connection")) {
                Fixture().use { fixture ->
                    // 초기 메타데이터 연결의 close가 아니라 트랜잭션 종료 정책을 주입한다.
                    fixture.rows().take(1).toList() shouldBeEqualTo listOf(0L)
                    events.list.clear()
                    val failure = MarkerFailure("cleanup-$boundary")
                    if (boundary == "statement") fixture.observed.statementCloseFailure = failure
                    else fixture.observed.connectionCloseFailure = failure
                    fixture.rows().toList() shouldBeEqualTo (0L..9L).toList()
                    fixture.assertReleased()
                    val expected = if (boundary == "statement") {
                        "Statements close failed"
                    } else {
                        "Transaction close failed"
                    }
                    events.list.any { it.formattedMessage.contains(expected) }.shouldBeTrue()
                    fixture.observed.statementCloseFailure = null
                    fixture.observed.connectionCloseFailure = null
                    fixture.rows().take(1).toList() shouldBeEqualTo listOf(0L)
                    fixture.assertReleased()
                }
            }
        } finally {
            logger.detachAppender(events)
            events.stop()
        }
    }

    @Test
    fun `동시 수집은 서로 다른 연결에서 독립적으로 종료한다`() = runSuspendIO {
        Fixture().use { fixture ->
            val reached = AtomicInteger()
            val release = CompletableDeferred<Unit>()
            val rows = fixture.rows()
            val first = async { rows.take(1).collect { reached.incrementAndGet(); release.await() } }
            val second = async { rows.take(1).collect { reached.incrementAndGet(); release.await() } }
            try {
                await().atMost(Duration.ofSeconds(5)).untilSuspending { reached.get() == 2 }
                fixture.pool.hikariPoolMXBean.activeConnections shouldBeEqualTo 2
            } finally {
                release.complete(Unit)
                first.await()
                second.await()
            }
            fixture.assertReleased()
            fixture.observed.executed.get() shouldBeEqualTo 2
        }
    }

    @Test
    fun `외부 트랜잭션의 연결 지역 상태를 상속하거나 연결을 닫지 않는다`() = runSuspendIO {
        Fixture().use { fixture ->
            inTopLevelSuspendTransaction(fixture.database, outerTransaction = null) {
                val outer = connection.connection as java.sql.Connection
                outer.setClientInfo("tenant-marker", "outer-only")
                queryFlow(fixture.database, query = {
                    val inner = connection.connection as java.sql.Connection
                    (inner !== outer).shouldBeTrue()
                    inner.getClientInfo("tenant-marker") shouldBeEqualTo null
                    Numbers.selectAll().limit(1)
                }, mapper = { it[Numbers.number] }).toList() shouldBeEqualTo listOf(0L)
                fixture.pool.hikariPoolMXBean.activeConnections shouldBeEqualTo 1
                outer.isClosed.shouldBeFalse()
                outer.getClientInfo("tenant-marker") shouldBeEqualTo "outer-only"
            }
            fixture.assertReleased()
        }
    }

    @Test
    fun `풀 획득 timeout 후에도 호출자 풀을 닫지 않고 다음 조회가 성공한다`() = runSuspendIO {
        Fixture().use { fixture ->
            fixture.pool.connection.use {
                fixture.pool.connection.use {
                    assertFailsWith<SQLException> { fixture.rows().toList() }
                    fixture.pool.isClosed.shouldBeFalse()
                }
            }
            fixture.rows().take(1).toList() shouldBeEqualTo listOf(0L)
            fixture.assertReleased()
        }
    }

    @Test
    fun `수집 전에 취소하면 쿼리와 연결을 시작하지 않는다`() = runSuspendIO {
        Fixture().use { fixture ->
            val job = launch(start = CoroutineStart.LAZY) {
                fixture.rows().collect { error("cancelled flow must not emit") }
            }
            job.cancel()
            job.join()
            job.isCancelled.shouldBeTrue()
            fixture.observed.executed.get() shouldBeEqualTo 0
            fixture.assertReleased()
        }
    }

    @Test
    fun `명시적 취소 원인에 ResultSet 정리 오류를 보존한다`() = runSuspendIO {
        Fixture().use { fixture ->
            val reached = CompletableDeferred<Unit>()
            val hold = CompletableDeferred<Unit>()
            val cancellation = MarkerCancellation("caller cancel")
            val cleanup = MarkerFailure("close on cancel")
            fixture.observed.resultCloseFailure = cleanup
            var observed: Throwable? = null
            val job = launch {
                try {
                    fixture.rows().collect { reached.complete(Unit); hold.await() }
                } catch (caught: CancellationException) {
                    observed = caught
                    throw caught
                }
            }
            try {
                reached.await()
                job.cancel(cancellation)
            } finally {
                job.cancelAndJoin()
            }
            observed.shouldBeSameInstanceAs(cancellation)
            cancellation.suppressed.any { it === cleanup }.shouldBeTrue()
            fixture.assertReleased()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["query", "execute", "next"])
    fun `조회 단계 오류 후 모든 자원을 반환하고 다음 조회가 성공한다`(boundary: String) = runSuspendIO {
        Fixture().use { fixture ->
            val failure = MarkerFailure(boundary)
            if (boundary == "next") fixture.observed.beforeNext = { throw failure }
            if (boundary == "execute") {
                val missing = object: Table("missing_issue_857_table") { val id = long("id") }
                assertFailsWith<SQLException> {
                    queryFlow(fixture.database, query = { missing.selectAll() }, mapper = { it[missing.id] }).toList()
                }
            } else {
                assertFailsWith<MarkerFailure> {
                    queryFlow(fixture.database, query = {
                        if (boundary == "query") throw failure
                        Numbers.selectAll().limit(1)
                    }, mapper = { it[Numbers.number] }).toList()
                }.shouldBeSameInstanceAs(failure)
            }
            fixture.assertReleased()
            fixture.observed.beforeNext = {}
            fixture.rows().take(1).toList() shouldBeEqualTo listOf(0L)
            fixture.assertReleased()
        }
    }

    @Test
    fun `정상 종료와 take는 반환 전에 모든 자원을 정리한다`() = runSuspendIO {
        Fixture().use { fixture ->
            fixture.rows().toList() shouldBeEqualTo (0L..9L).toList()
            fixture.assertReleased()
            fixture.rows().take(1).toList() shouldBeEqualTo listOf(0L)
            fixture.assertReleased()
            fixture.observed.executed.get() shouldBeEqualTo 2
        }
    }

    @Test
    fun `중간 SQL 오류는 이미 전달한 행을 재실행하지 않는다`() = runSuspendIO {
        Fixture().use { fixture ->
            val values = mutableListOf<Long>()
            val failure = SqlFailure("partial result")
            assertFailsWith<SqlFailure> {
                queryFlow(fixture.database, query = { Numbers.selectAll().limit(10) }, mapper = {
                    val value = it[Numbers.number]
                    if (value == 2L) throw failure
                    value
                }).collect { values.add(it) }
            }.shouldBeSameInstanceAs(failure)
            values shouldBeEqualTo listOf(0L, 1L)
            fixture.observed.executed.get() shouldBeEqualTo 1
            fixture.assertReleased()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["none", "mapper", "collector", "take"])
    fun `ResultSet close 오류는 원래 종료 원인을 대체하지 않는다`(boundary: String) = runSuspendIO {
        Fixture().use { fixture ->
            val primary = MarkerFailure(boundary)
            val cleanup = MarkerFailure("close")
            fixture.observed.resultCloseFailure = cleanup
            val rows = queryFlow(fixture.database, query = { Numbers.selectAll().limit(10) }, mapper = {
                if (boundary == "mapper") throw primary
                it[Numbers.number]
            })
            when (boundary) {
                "take" -> rows.take(1).toList() shouldBeEqualTo listOf(0L)
                "none" -> assertFailsWith<MarkerFailure> { rows.toList() }.shouldBeSameInstanceAs(cleanup)
                else -> {
                    assertFailsWith<MarkerFailure> {
                        rows.collect { if (boundary == "collector") throw primary }
                    }.shouldBeSameInstanceAs(primary)
                    primary.suppressed.any { it === cleanup }.shouldBeTrue()
                }
            }
            fixture.assertReleased()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["query", "beforeNext", "afterNext", "mapper"])
    fun `실제 취소는 블로킹 경계 반환 후 매핑과 방출을 중단한다`(boundary: String) = runSuspendIO {
        Fixture().use { fixture ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val delivered = AtomicInteger()
            val gate = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "test gate timed out" }
            }
            if (boundary == "beforeNext") fixture.observed.beforeNext = gate
            if (boundary == "afterNext") fixture.observed.afterNext = gate
            val job = launch {
                queryFlow(
                    fixture.database,
                    query = {
                        if (boundary == "query") gate()
                        Numbers.selectAll().limit(10)
                    },
                    mapper = {
                        if (boundary == "mapper") gate()
                        it[Numbers.number]
                    },
                ).collect { delivered.incrementAndGet() }
            }
            try {
                await().atMost(Duration.ofSeconds(5)).untilSuspending { entered.count == 0L }
                job.cancel()
            } finally {
                release.countDown()
                job.cancelAndJoin()
            }
            job.isCancelled.shouldBeTrue()
            delivered.get() shouldBeEqualTo 0
            if (boundary == "query") fixture.observed.executed.get() shouldBeEqualTo 0
            fixture.assertReleased()
            fixture.observed.beforeNext = {}
            fixture.observed.afterNext = {}
            fixture.rows().take(1).toList() shouldBeEqualTo listOf(0L)
            fixture.assertReleased()
        }
    }

    @Test
    fun `느린 소비자 앞에서 생산자는 한 행만 더 매핑하고 취소하면 반환한다`() = runSuspendIO {
        Fixture().use { fixture ->
            val received = CompletableDeferred<Unit>()
            val secondMapped = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val mapped = AtomicInteger()
            val job = launch {
                queryFlow(fixture.database, query = { Numbers.selectAll().limit(10) }, mapper = {
                    if (mapped.incrementAndGet() == 2) secondMapped.complete(Unit)
                    it[Numbers.number]
                }).collect {
                    received.complete(Unit)
                    release.await()
                }
            }
            try {
                received.await()
                secondMapped.await()
                // 첫 행을 소비자가 붙잡고 있는 동안 두 번째 행의 rendezvous send가 대기한다.
                fixture.observed.next.get() shouldBeEqualTo 2
            } finally {
                job.cancel()
                release.complete(Unit)
                job.cancelAndJoin()
            }
            mapped.get() shouldBeEqualTo 2
            fixture.assertReleased()
        }
    }
}
