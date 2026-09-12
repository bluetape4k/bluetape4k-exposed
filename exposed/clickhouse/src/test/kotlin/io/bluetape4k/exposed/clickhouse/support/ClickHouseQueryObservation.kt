package io.bluetape4k.exposed.clickhouse.support

import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.util.concurrent.TimeUnit

/** 원격 query 관찰 결과를 성공 보장과 분리해 기록합니다. */
internal enum class QueryObservationOutcome {
    PRESENT,
    DISAPPEARED,
    UNAVAILABLE,
    TIMEOUT,
}

/** query 본문·URL·credential을 포함하지 않는 안전한 관찰 receipt입니다. */
internal data class ClickHouseQueryObservationResult(
    val outcome: QueryObservationOutcome,
    val reasonCode: String,
)

/** `system.processes`를 유한 deadline으로 polling하는 test-only 관찰기입니다. */
internal class ClickHouseQueryObservation(
    private val connectionFactory: () -> Connection,
    private val pollInterval: Duration = Duration.ofMillis(100),
) {

    fun awaitDisappearance(queryId: String, timeout: Duration): ClickHouseQueryObservationResult {
        val validation = validate(queryId, timeout)
        val timeoutNanos = timeoutNanos(timeout)
        return validation
            ?: timeoutNanos?.let { observe(queryId, it) }
            ?: unavailable("DEADLINE_OVERFLOW")
    }

    private fun validate(queryId: String, timeout: Duration): ClickHouseQueryObservationResult? = when {
        queryId.isBlank() -> unavailable("EMPTY_QUERY_ID")
        timeout.isZero || timeout.isNegative ->
            ClickHouseQueryObservationResult(QueryObservationOutcome.TIMEOUT, "DEADLINE_EXPIRED")
        else -> null
    }

    private fun timeoutNanos(timeout: Duration): Long? = runCatching { timeout.toNanos() }.getOrNull()

    private fun observe(queryId: String, timeoutNanos: Long): ClickHouseQueryObservationResult {
        val deadline = System.nanoTime() + timeoutNanos
        return try {
            connectionFactory().use { connection ->
                connection.prepareStatement(PROCESS_QUERY).use { statement ->
                    statement.setString(1, queryId)
                    poll(statement, deadline)
                }
            }
        } catch (failure: SQLException) {
            unavailable(classify(failure))
        } catch (_: RuntimeException) {
            unavailable("OBSERVATION_CONNECTION_FAILED")
        }
    }

    private fun poll(
        statement: java.sql.PreparedStatement,
        deadline: Long,
    ): ClickHouseQueryObservationResult {
        var seen = false
        var observation: ClickHouseQueryObservationResult? = null
        while (observation == null) {
            val presence = readPresence(statement)
            seen = seen || presence == Presence.PRESENT
            observation = outcomeFor(presence, seen, deadline)
            if (observation == null) {
                observation = sleepUntil(deadline, seen)
            }
        }
        return observation
    }

    private fun readPresence(statement: java.sql.PreparedStatement): Presence =
        statement.executeQuery().use { result ->
            when {
                !result.next() -> Presence.EMPTY
                result.getLong(1) > 0 -> Presence.PRESENT
                else -> Presence.ABSENT
            }
        }

    private fun outcomeFor(
        presence: Presence,
        seen: Boolean,
        deadline: Long,
    ): ClickHouseQueryObservationResult? = when {
        presence == Presence.EMPTY -> unavailable("EMPTY_OBSERVATION")
        presence == Presence.ABSENT && seen ->
            ClickHouseQueryObservationResult(QueryObservationOutcome.DISAPPEARED, "PROCESS_ROW_ABSENT")
        deadline - System.nanoTime() <= 0 -> deadlineOutcome(seen)
        else -> null
    }

    private fun sleepUntil(deadline: Long, seen: Boolean): ClickHouseQueryObservationResult? {
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) return deadlineOutcome(seen)
        return try {
            TimeUnit.NANOSECONDS.sleep(minOf(intervalNanos, remaining))
            null
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            unavailable("POLL_INTERRUPTED")
        }
    }

    private fun deadlineOutcome(seen: Boolean): ClickHouseQueryObservationResult =
        ClickHouseQueryObservationResult(
            if (seen) QueryObservationOutcome.PRESENT else QueryObservationOutcome.TIMEOUT,
            "POLL_DEADLINE_EXPIRED",
        )

    private val intervalNanos: Long
        get() = runCatching { pollInterval.toNanos() }
            .getOrDefault(TimeUnit.MILLISECONDS.toNanos(100))
            .coerceAtLeast(1L)

    private fun unavailable(reasonCode: String): ClickHouseQueryObservationResult =
        ClickHouseQueryObservationResult(QueryObservationOutcome.UNAVAILABLE, reasonCode)

    private fun classify(failure: SQLException): String = when (failure.sqlState) {
        "42501" -> "INSUFFICIENT_PRIVILEGE"
        else -> "OBSERVATION_QUERY_FAILED"
    }

    private enum class Presence {
        PRESENT,
        ABSENT,
        EMPTY,
    }

    private companion object {
        const val PROCESS_QUERY = "SELECT count() FROM system.processes WHERE query_id = ?"
    }
}
