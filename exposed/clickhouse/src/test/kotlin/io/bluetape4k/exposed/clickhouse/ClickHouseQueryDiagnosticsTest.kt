package io.bluetape4k.exposed.clickhouse

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.junit5.coroutines.runSuspendIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** JDBC V2 query diagnostics 계약을 고정하는 통합 테스트입니다. */
class ClickHouseQueryDiagnosticsTest: AbstractClickHouseTest() {

    private class MarkerCancellation(message: String): CancellationException(message)

    private object Numbers: Table("system.numbers") {
        val number = long("number")
    }

    @Test
    fun `query lifecycle은 Started부터 terminal까지 순서와 terminal once를 지킨다`() = runSuspendIO {
        val events = CopyOnWriteArrayList<ClickHouseQueryEvent>()
        val observed = CopyOnWriteArrayList<ClickHouseQueryDiagnostics>()
        val config = ClickHouseQueryDiagnosticsConfig(
            queryId = "diagnostic-query-868",
            logComment = "issue-868",
            clientName = "bluetape4k-test",
            sessionSettings = mapOf("timezone" to "UTC"),
            listener = ClickHouseQueryListener { events += it },
            sink = ClickHouseQueryDiagnosticsSink { observed += it },
        )

        queryList(db, diagnostics = config) { listOf(1, 2, 3) } shouldBeEqualTo listOf(1, 2, 3)

        events.map { it.kind } shouldBeEqualTo listOf(
            ClickHouseQueryEventKind.Started,
            ClickHouseQueryEventKind.RequestPrepared,
            ClickHouseQueryEventKind.ResponseReceived,
            ClickHouseQueryEventKind.Completed,
        )
        events.count { it.isTerminal } shouldBeEqualTo 1
        events.last().diagnostics.queryId shouldBeEqualTo "diagnostic-query-868"
        events.last().diagnostics.logComment shouldBeEqualTo "issue-868"
        events.last().diagnostics.clientName shouldBeEqualTo "bluetape4k-test"
        events.last().diagnostics.sessionSettings shouldBeEqualTo mapOf("timezone" to "UTC")
        events.last().diagnostics.outcome shouldBeEqualTo ClickHouseQueryOutcome.Success
        events.last().diagnostics.elapsed?.isNegative shouldBeEqualTo false
        observed.single().queryId shouldBeEqualTo "diagnostic-query-868"
    }

    @Test
    fun `생성 query id는 concurrent query에서 서로 다르고 elapsed는 음수가 아니다`() = runSuspendIO {
        val events = CopyOnWriteArrayList<ClickHouseQueryEvent>()
        val config = ClickHouseQueryDiagnosticsConfig(
            listener = ClickHouseQueryListener { events += it },
        )

        (1..8).map {
            async {
                queryList(db, diagnostics = config) { listOf(it) }
            }
        }.awaitAll().flatten() shouldBeEqualTo (1..8).toList()

        val terminal = events.filter { it.isTerminal }
        terminal.size shouldBeEqualTo 8
        terminal.map { it.diagnostics.queryId }.toSet().size shouldBeEqualTo 8
        terminal.forEach { event ->
            event.diagnostics.elapsed?.isNegative shouldBeEqualTo false
            event.diagnostics.startedAt.toString().endsWith("Z") shouldBeEqualTo true
            event.diagnostics.finishedAt?.toString()?.endsWith("Z") shouldBeEqualTo true
        }
    }

    @Test
    fun `listener 예외는 callbackFailure로 격리하고 원래 취소를 보존한다`() = runSuspendIO {
        ClickHouseQueryDiagnosticsMetrics.resetForTests()
        val original = MarkerCancellation("cancel-marker")
        val observed = CopyOnWriteArrayList<ClickHouseQueryDiagnostics>()
        val config = ClickHouseQueryDiagnosticsConfig(
            listener = ClickHouseQueryListener { throw IllegalStateException("listener-secret") },
            sink = ClickHouseQueryDiagnosticsSink { observed += it },
        )

        val failure = assertFailsWith<CancellationException> {
            queryFlow(
                db,
                diagnostics = config,
                query = { Numbers.selectAll().limit(1) },
                mapper = { throw original },
            ).collect()
        }

        failure.shouldBeSameInstanceAs(original)
        observed.single().callbackFailure?.sanitizedMessage.shouldNotContain("listener-secret")
        ClickHouseQueryDiagnosticsMetrics.listenerFailureCount shouldBeEqualTo 3L
    }

    @Test
    fun `queryFlow는 row summary와 terminal sink를 정확히 한 번 기록한다`() = runSuspendIO {
        val events = CopyOnWriteArrayList<ClickHouseQueryEvent>()
        val observed = CopyOnWriteArrayList<ClickHouseQueryDiagnostics>()
        val config = ClickHouseQueryDiagnosticsConfig(
            listener = ClickHouseQueryListener { events += it },
            sink = ClickHouseQueryDiagnosticsSink { observed += it },
        )

        val values = queryFlow(
            db,
            diagnostics = config,
            query = { Numbers.selectAll().limit(3) },
            mapper = { it[Numbers.number] },
        ).toList()

        values shouldBeEqualTo listOf(0L, 1L, 2L)
        events.last().kind shouldBeEqualTo ClickHouseQueryEventKind.Completed
        events.last().diagnostics.returnedRows shouldBeEqualTo 3L
        observed.size shouldBeEqualTo 1
    }

    @Test
    fun `SQL failure는 vendor code와 SQL state를 안정적으로 노출한다`() = runSuspendIO {
        val original = SQLException("driver-secret", "HY000", 1001)
        val observed = CopyOnWriteArrayList<ClickHouseQueryDiagnostics>()
        val failure = assertFailsWith<SQLException> {
            queryFlow(
                db,
                diagnostics = ClickHouseQueryDiagnosticsConfig(
                    sink = ClickHouseQueryDiagnosticsSink { observed += it },
                ),
                query = { Numbers.selectAll().limit(1) },
                mapper = { throw original },
            ).collect()
        }

        failure.shouldBeSameInstanceAs(original)
        observed.single().vendorCode shouldBeEqualTo 1001
        observed.single().outcome shouldBeEqualTo ClickHouseQueryOutcome.Failure("HY000", 1001)
    }

    @Test
    fun `query ID와 log comment는 ClickHouse query log correlation을 제공한다`() = runSuspendIO {
        val queryId = "diagnostics-log-868-${UUID.randomUUID()}"
        val logComment = "issue-868-log"
        queryList(
            db,
            diagnostics = ClickHouseQueryDiagnosticsConfig(queryId = queryId, logComment = logComment),
        ) {
            exec("SELECT 1") { resultSet ->
                resultSet.next()
                listOf(resultSet.getInt(1))
            }.orEmpty()
        } shouldBeEqualTo listOf(1)

        val observed = (1..12).firstNotNullOfOrNull {
            val result = runCatching {
                transaction(db) {
                    exec("SYSTEM FLUSH LOGS")
                    exec(
                        "SELECT query_id, log_comment FROM system.query_log " +
                            "WHERE query_id = '$queryId' AND type = 'QueryFinish' " +
                            "ORDER BY event_time_microseconds DESC LIMIT 1",
                    ) { resultSet ->
                        if (resultSet.next()) resultSet.getString(1) to resultSet.getString(2) else null
                    }
                }
            }.getOrNull()
            result ?: run {
                Thread.sleep(250)
                null
            }
        }
        observed shouldBeEqualTo (queryId to logComment)
    }

    @Test
    fun `diagnostics redaction은 SQL bind token password header와 throwable secret을 제거한다`() {
        val secret = "diagnostics-secret"
        val message = ClickHouseQueryDiagnosticsRedaction.redact(
            "SELECT * FROM users WHERE password='$secret' AND token=$secret " +
                "X-Api-Key: $secret bind=$secret",
        )
        message shouldNotContain secret

        val cause = IllegalStateException("password=$secret", RuntimeException("token=$secret"))
        val sanitized = ClickHouseQueryDiagnosticsRedaction.sanitizeThrowable(cause)
        sanitized shouldNotContain secret
        sanitized shouldNotContain "password="
        sanitized shouldNotContain "token="

        val recorder = ClickHouseQueryDiagnosticsRecorder(
            ClickHouseQueryDiagnosticsConfig(
                logComment = "token=$secret",
                sessionSettings = mapOf("password" to secret, "safe" to "value=$secret"),
            ),
        )
        recorder.started()
        recorder.finishAfterCleanup(ClickHouseQueryOutcome.Success)
        val snapshot = checkNotNull(recorder.diagnostics())
        snapshot.logComment shouldNotContain secret
        snapshot.sessionSettings.getValue("password") shouldBeEqualTo "REDACTED"
        snapshot.sessionSettings.getValue("safe") shouldNotContain secret

        val headers = ClickHouseQueryDiagnosticsRedaction.redactResponseHeaders(
            mapOf(
                "X-ClickHouse-Query-Id" to "query-868",
                "X-ClickHouse-Summary" to "rows=1 value=$secret",
                "Authorization" to "Bearer $secret",
            ),
        )
        headers.keys shouldBeEqualTo setOf("X-ClickHouse-Query-Id", "X-ClickHouse-Summary")
        headers.values.joinToString() shouldNotContain secret
    }

    @Test
    fun `diagnostics value는 driver가 제공하지 않는 metadata를 추정하지 않는다`() {
        val now = Instant.parse("2026-09-10T00:00:00Z")
        val diagnostics = ClickHouseQueryDiagnostics(
            queryId = "query-868",
            logComment = null,
            clientName = null,
            sessionSettings = emptyMap(),
            startedAt = now,
            finishedAt = null,
            elapsed = null,
            returnedRows = null,
            vendorCode = null,
            outcome = null,
            callbackFailure = null,
        )

        diagnostics.finishedAt shouldBeEqualTo null
        diagnostics.elapsed shouldBeEqualTo null
        diagnostics.returnedRows shouldBeEqualTo null
        diagnostics.vendorCode shouldBeEqualTo null
        Duration.ZERO.isNegative shouldBeEqualTo false
    }
}
