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
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.decimalLiteral
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.inTopLevelSuspendTransaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.LoggerFactory
import java.net.ServerSocket
import java.sql.BatchUpdateException
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.SQLTimeoutException
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

    private inner class Fixture(
        private val jdbcOptions: String = "",
        driverClassName: String? = null,
    ): AutoCloseable {
        val observed = JdbcObservation()
        val pool = HikariDataSource(HikariConfig().apply {
            jdbcUrl = "jdbc:clickhouse://${clickhouse.host}:${clickhouse.port}/default$jdbcOptions"
            driverClassName?.let { this.driverClassName = it }
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

        fun <T> withTrackedConnection(
            observation: JdbcObservation = observed,
            block: (Connection) -> T,
        ): T = TrackingClickHouseConnection(ClickHouseConnectionWrapper(pool.connection), observation).use(block)

        fun rows(limit: Int = 10) = queryFlow(
            database,
            query = { Numbers.selectAll().limit(limit) },
            mapper = { it[Numbers.number] },
        )

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
    fun `실제 driver row limit 초과는 예외와 정리를 보존하고 재수집하지 않는다`() = runSuspendIO {
        Fixture("?clickhouse_setting_max_result_rows=2&clickhouse_setting_result_overflow_mode=throw").use { fixture ->
            val emitted = AtomicInteger()
            val failure = assertFailsWith<SQLException> {
                queryFlow(
                    fixture.database,
                    query = { Numbers.selectAll().limit(10) },
                    mapper = { emitted.incrementAndGet(); it[Numbers.number] },
                ).toList()
            }

            failure.javaClass shouldBeEqualTo ExposedSQLException::class.java
            failure.cause?.javaClass shouldBeEqualTo SQLException::class.java
            failure.message.orEmpty().contains("Code: 396").shouldBeTrue()
            failure.message.orEmpty().contains("TOO_MANY_ROWS_OR_BYTES").shouldBeTrue()
            emitted.get() shouldBeEqualTo 0
            fixture.observed.executed.get() shouldBeEqualTo 1
            fixture.assertReleased()
            fixture.rows(limit = 1).toList() shouldBeEqualTo listOf(0L)
            fixture.assertReleased()
        }
    }

    @Test
    fun `실제 driver row limit break는 부분 결과를 반복 수집마다 재현한다`() = runSuspendIO {
        Fixture(
            "?clickhouse_setting_max_result_rows=2&clickhouse_setting_max_block_size=2" +
                "&clickhouse_setting_result_overflow_mode=break",
        ).use { fixture ->
            val expected = listOf(0L, 1L)
            val firstEmitted = AtomicInteger()
            queryFlow(
                fixture.database,
                query = { Numbers.selectAll().limit(10) },
                mapper = { firstEmitted.incrementAndGet(); it[Numbers.number] },
            ).toList() shouldBeEqualTo expected
            firstEmitted.get() shouldBeEqualTo expected.size
            fixture.assertReleased()
            val secondEmitted = AtomicInteger()
            queryFlow(
                fixture.database,
                query = { Numbers.selectAll().limit(10) },
                mapper = { secondEmitted.incrementAndGet(); it[Numbers.number] },
            ).toList() shouldBeEqualTo expected
            secondEmitted.get() shouldBeEqualTo expected.size
            fixture.assertReleased()
            fixture.observed.executed.get() shouldBeEqualTo 2
        }
    }

    @Test
    fun `V1 driver read timeout은 부분 결과 없이 실패하고 연결을 반환한다`() = runSuspendIO {
        Fixture(
            "?socket_timeout=200&clickhouse_setting_max_block_size=1",
            // catalog 기본 ClickHouseDriver(V2)는 지연 행의 socket timeout을
            // 재현 가능하게 적용하지 않아, 확인된 계약을 V1 경로로 한정합니다.
            driverClassName = "com.clickhouse.jdbc.DriverV1",
        ).use { fixture ->
            val sleepEachRow = CustomFunction<Long>(
                "sleepEachRow",
                LongColumnType(),
                decimalLiteral("1".toBigDecimal()),
            )
            val emitted = AtomicInteger()
            val failure = assertFailsWith<SQLException> {
                queryFlow(
                    fixture.database,
                    query = { Numbers.select(sleepEachRow, Numbers.number).limit(3) },
                    mapper = { emitted.incrementAndGet(); it[Numbers.number] },
                ).toList()
            }

            failure.javaClass shouldBeEqualTo ExposedSQLException::class.java
            failure.cause?.javaClass shouldBeEqualTo BatchUpdateException::class.java
            failure.cause?.message shouldBeEqualTo "Read timed out"
            emitted.get() shouldBeEqualTo 0
            fixture.assertReleased()
            // timeout이 연결을 pool로 되돌린 직후에도 같은 pool로 다음 조회를 수행할 수 있어야 합니다.
            fixture.rows().take(1).toList() shouldBeEqualTo listOf(0L)
            fixture.assertReleased()
        }
    }

    @Test
    fun `기본 ClickHouseDriver는 catalog V2 경로와 버전을 사용한다`() = runSuspendIO {
        Fixture().use { fixture ->
            fixture.withTrackedConnection { connection ->
                val v2ConnectionClass = Class.forName("com.clickhouse.jdbc.ConnectionImpl")

                connection.metaData.driverName.contains("ClickHouse").shouldBeTrue()
                connection.metaData.driverVersion.startsWith("0.9.9").shouldBeTrue()
                connection.isWrapperFor(v2ConnectionClass).shouldBeTrue()
                connection.unwrap(v2ConnectionClass).javaClass.name shouldBeEqualTo v2ConnectionClass.name
            }
            fixture.assertReleased()
        }
    }

    @Test
    fun `기본 V2 setQueryTimeout은 server execution timeout으로 종료하고 재사용한다`() = runSuspendIO {
        Fixture("?clickhouse_setting_max_block_size=1").use { fixture ->
            val directObserved = JdbcObservation()
            val timeoutFailures = (1..3).map {
                var emitted = 0
                val failure = fixture.withTrackedConnection(directObserved) { connection ->
                    connection.prepareStatement(
                        "SELECT sleepEachRow(1), number FROM system.numbers LIMIT 3",
                    ).use { statement ->
                        statement.queryTimeout = 1
                        try {
                            statement.executeQuery().use { result ->
                                while (result.next()) emitted++
                            }
                            null
                        } catch (caught: SQLException) {
                            caught
                        }
                    }
                }
                check(failure != null) { "V2 query timeout must fail the delayed query" }
                failure.javaClass shouldBeEqualTo SQLTimeoutException::class.java
                failure.message.orEmpty().contains("Query execution time exceeded limit").shouldBeTrue()
                emitted shouldBeEqualTo 0
                fixture.assertReleased()
                fixture.rows(limit = 1).toList() shouldBeEqualTo listOf(0L)
                fixture.assertReleased()
                failure
            }

            timeoutFailures.size shouldBeEqualTo 3
            directObserved.executed.get() shouldBeEqualTo 3
            directObserved.queryTimeouts.get() shouldBeEqualTo 3
            directObserved.connections.get() shouldBeEqualTo 0
            directObserved.statements.get() shouldBeEqualTo 0
            directObserved.results.get() shouldBeEqualTo 0
        }
    }

    @Test
    fun `기본 V2 socket timeout probe는 세 번의 bounded 결과를 기록한다`() = runSuspendIO {
        Fixture("?socket_timeout=200&clickhouse_setting_max_block_size=1").use { fixture ->
            val outcomes = (1..3).map { attempt ->
                val started = System.nanoTime()
                var values: List<Long>? = null
                var failure: SQLException? = null
                var emitted = 0
                try {
                    values = delayedRows(fixture, limit = 2) { emitted++ }.toList()
                } catch (caught: SQLException) {
                    failure = caught
                }
                val elapsedMillis = (System.nanoTime() - started) / 1_000_000
                (elapsedMillis < 10_000L).shouldBeTrue()
                values?.let { rows -> rows.all { it in 0L..1L }.shouldBeTrue() }
                fixture.assertReleased()
                fixture.rows(limit = 1).toList() shouldBeEqualTo listOf(0L)
                fixture.assertReleased()
                ProbeOutcome(attempt, elapsedMillis, emitted, values, failure)
            }

            outcomes.size shouldBeEqualTo 3
            outcomes.all { it.elapsedMillis < 10_000L }.shouldBeTrue()
            LoggerFactory.getLogger("io.bluetape4k.exposed.clickhouse.ClickHouseV2Probe").info(
                "V2 socket_timeout probe outcomes: {}",
                outcomes.joinToString { it.summary() },
            )
        }
    }

    @Test
    fun `기본 V2 connect_timeout 연결 시도는 세 번의 bounded 결과를 기록한다`() = runSuspendIO {
        Class.forName("com.clickhouse.jdbc.ClickHouseDriver")
        val unavailablePort = ServerSocket(0).use { it.localPort }
        val outcomes = (1..3).map { attempt ->
            val started = System.nanoTime()
            var connection: Connection? = null
            var failure: SQLException? = null
            try {
                connection = DriverManager.getConnection(
                    "jdbc:clickhouse://127.0.0.1:$unavailablePort/default?connect_timeout=200&connection_timeout=200",
                )
            } catch (caught: SQLException) {
                failure = caught
            } finally {
                connection?.close()
            }
            val elapsedMillis = (System.nanoTime() - started) / 1_000_000
            (elapsedMillis < 5_000L).shouldBeTrue()
            (failure != null).shouldBeTrue()
            ConnectOutcome(attempt, elapsedMillis, failure)
        }

        outcomes.size shouldBeEqualTo 3
        outcomes.all { it.elapsedMillis < 5_000L }.shouldBeTrue()
        LoggerFactory.getLogger("io.bluetape4k.exposed.clickhouse.ClickHouseV2Probe").info(
            "V2 connect_timeout probe outcomes: {}",
            outcomes.joinToString { it.summary() },
        )
    }

    @Test
    fun `기본 V2 downstream cancellation은 자원을 정리하고 후속 조회를 재사용한다`() = runSuspendIO {
        Fixture("?clickhouse_setting_max_block_size=1").use { fixture ->
            repeat(3) {
                val started = System.nanoTime()
                val values = delayedRows(fixture, limit = 3).take(1).toList()
                values shouldBeEqualTo listOf(0L)
                ((System.nanoTime() - started) / 1_000_000 < 10_000L).shouldBeTrue()
                fixture.assertReleased()
                fixture.rows(limit = 1).toList() shouldBeEqualTo listOf(0L)
                fixture.assertReleased()
            }
            fixture.observed.executed.get() shouldBeEqualTo 6
        }
    }

    @Test
    fun `기본 V2 Statement cancel 요청은 KILL QUERY 경로를 호출한다`() = runSuspendIO {
        Fixture().use { fixture ->
            repeat(3) {
                fixture.withTrackedConnection { connection ->
                    connection.prepareStatement("SELECT number FROM system.numbers LIMIT 1").use { statement ->
                        statement.executeQuery().use { result ->
                            result.next().shouldBeTrue()
                        }
                        statement.cancel()
                    }
                }
                fixture.assertReleased()
            }
            fixture.observed.cancels.get() shouldBeEqualTo 3
            fixture.rows(limit = 1).toList() shouldBeEqualTo listOf(0L)
            fixture.assertReleased()
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

    private data class ProbeOutcome(
        val attempt: Int,
        val elapsedMillis: Long,
        val emitted: Int,
        val values: List<Long>?,
        val failure: Throwable?,
    ) {
        fun summary(): String =
            "attempt=$attempt elapsedMillis=$elapsedMillis emitted=$emitted " +
                "rows=${values?.size ?: 0} failure=${failure?.javaClass?.simpleName ?: "none"}"
    }

    private data class ConnectOutcome(
        val attempt: Int,
        val elapsedMillis: Long,
        val failure: SQLException?,
    ) {
        fun summary(): String =
            "attempt=$attempt elapsedMillis=$elapsedMillis failure=${failure?.javaClass?.simpleName ?: "none"}"
    }

    private fun delayedRows(
        fixture: Fixture,
        limit: Int,
        onMap: () -> Unit = {},
    ) = queryFlow(
        fixture.database,
        query = {
            val sleepEachRow = CustomFunction<Long>(
                "sleepEachRow",
                LongColumnType(),
                decimalLiteral("1".toBigDecimal()),
            )
            Numbers.select(sleepEachRow, Numbers.number).limit(limit)
        },
        mapper = {
            onMap()
            it[Numbers.number]
        },
    )
}
