package io.bluetape4k.exposed.clickhouse

import kotlinx.coroutines.CancellationException
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.TimeSource

/** diagnostics callback failure를 bounded하게 관찰하는 internal counter입니다. */
internal object ClickHouseQueryDiagnosticsMetrics {
    private val listenerFailures = AtomicLong()

    val listenerFailureCount: Long get() = listenerFailures.get()

    fun incrementListenerFailures() {
        while (true) {
            val current = listenerFailures.get()
            if (current == Long.MAX_VALUE || listenerFailures.compareAndSet(current, current + 1)) return
        }
    }

    fun resetForTests() {
        listenerFailures.set(0)
    }
}

/**
 * query dispatcher에서 lifecycle event를 동기 전달하는 recorder입니다.
 *
 * 이 타입은 blocking I/O나 callback 재진입을 수행하지 않습니다. terminal event와 sink는
 * JDBC cursor/statement/transaction 정리가 끝난 뒤 호출자가 [finishAfterCleanup]을 부를 때
 * 정확히 한 번 전달됩니다.
 */
@Suppress("TooGenericExceptionCaught") // callback 실패를 원래 결과와 분리하는 경계다.
internal class ClickHouseQueryDiagnosticsRecorder(
    private val config: ClickHouseQueryDiagnosticsConfig,
) {
    private val monotonicStart = TimeSource.Monotonic.markNow()
    private val startedAt = Instant.now(UTC)
    private val terminal = AtomicBoolean(false)
    private val callbackFailure = AtomicReference<ClickHouseCallbackFailure?>(null)
    private val generatedId = config.queryId == null
    private val queryId: String = allocateQueryId(config)

    internal val queryIdValue: String get() = queryId

    @Volatile
    private var returnedRows: Long? = null

    @Volatile
    private var vendorCode: Int? = null

    @Volatile
    private var serverDisplayName: String? = null

    @Volatile
    private var responseHeaders: Map<String, String> = emptyMap()

    @Volatile
    private var finalDiagnostics: ClickHouseQueryDiagnostics? = null

    fun started() {
        emit(ClickHouseQueryEventKind.Started, terminal = false)
    }

    fun requestPrepared() {
        emit(ClickHouseQueryEventKind.RequestPrepared, terminal = false)
    }

    fun responseReceived(rows: Long?) {
        returnedRows = rows?.coerceAtLeast(0)
        emit(ClickHouseQueryEventKind.ResponseReceived, terminal = false)
    }

    fun responseMetadata(metadata: ClickHouseQueryResponseMetadata?) {
        serverDisplayName = metadata?.serverDisplayName
        responseHeaders = metadata?.responseHeaders.orEmpty()
    }

    fun finishAfterCleanup(outcome: ClickHouseQueryOutcome, vendorCode: Int? = null) {
        if (!terminal.compareAndSet(false, true)) return
        this.vendorCode = (vendorCode ?: (outcome as? ClickHouseQueryOutcome.Failure)?.vendorCode)
            ?.takeIf { it != 0 }
        val kind = when (outcome) {
            ClickHouseQueryOutcome.Success -> ClickHouseQueryEventKind.Completed
            is ClickHouseQueryOutcome.Failure -> ClickHouseQueryEventKind.Failed
            is ClickHouseQueryOutcome.Cancelled -> ClickHouseQueryEventKind.Cancelled
        }
        val diagnostics = snapshot(outcome = outcome, terminal = true)
        val event = ClickHouseQueryEvent(kind = kind, diagnostics = diagnostics, isTerminal = true)
        notifyListener(event)

        // The listener may fail while receiving the terminal event. Rebuild the value before sink.
        val afterListener = snapshot(outcome = outcome, terminal = true)
        finalDiagnostics = afterListener
        try {
            config.sink?.accept(afterListener)
        } catch (failure: Throwable) {
            recordCallbackFailure(failure)
            finalDiagnostics = snapshot(outcome = outcome, terminal = true)
        } finally {
            if (generatedId) ACTIVE_QUERY_IDS.remove(queryId)
        }
    }

    internal fun diagnostics(): ClickHouseQueryDiagnostics? = finalDiagnostics

    private fun emit(kind: ClickHouseQueryEventKind, terminal: Boolean) {
        notifyListener(ClickHouseQueryEvent(kind, snapshot(outcome = null, terminal = terminal), terminal))
    }

    private fun notifyListener(event: ClickHouseQueryEvent) {
        try {
            config.listener?.onEvent(event)
        } catch (failure: Throwable) {
            // callback failure must never replace SQL result or CancellationException.
            recordCallbackFailure(failure)
        }
    }

    private fun recordCallbackFailure(failure: Throwable) {
        ClickHouseQueryDiagnosticsMetrics.incrementListenerFailures()
        callbackFailure.compareAndSet(
            null,
            ClickHouseCallbackFailure(
                exceptionType = failure::class.qualifiedName ?: "Throwable",
                reasonCode = "CALLBACK_EXCEPTION",
                sanitizedMessage = ClickHouseQueryDiagnosticsRedaction
                    .sanitizeThrowable(failure)
                    .takeIf { it.isNotBlank() },
            ),
        )
    }

    private fun snapshot(outcome: ClickHouseQueryOutcome?, terminal: Boolean): ClickHouseQueryDiagnostics {
        val elapsed = if (terminal) {
            Duration.ofNanos(monotonicStart.elapsedNow().inWholeNanoseconds.coerceAtLeast(0))
        } else {
            null
        }
        return ClickHouseQueryDiagnostics(
            queryId = queryId,
            logComment = config.logComment?.let(ClickHouseQueryDiagnosticsRedaction::redact),
            clientName = config.clientName?.let(ClickHouseQueryDiagnosticsRedaction::redact),
            sessionSettings = config.immutableSessionSettings
                .mapValues { (name, value) -> ClickHouseQueryDiagnosticsRedaction.redactSetting(name, value) },
            startedAt = startedAt,
            finishedAt = if (terminal) Instant.now(UTC) else null,
            elapsed = elapsed,
            returnedRows = returnedRows,
            vendorCode = vendorCode,
            outcome = outcome,
            callbackFailure = callbackFailure.get(),
            serverDisplayName = serverDisplayName?.let(ClickHouseQueryDiagnosticsRedaction::redact),
            responseHeaders = responseHeaders.toMap(),
        )
    }

    private companion object {
        val UTC: Clock = Clock.systemUTC()
        val ACTIVE_QUERY_IDS: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private const val MAX_GENERATOR_ATTEMPTS = 8

        fun allocateQueryId(config: ClickHouseQueryDiagnosticsConfig): String {
            config.queryId?.let { return it }
            val generated = generateUniqueQueryId(config)
            return generated ?: generateFallbackQueryId()
        }

        private fun generateUniqueQueryId(config: ClickHouseQueryDiagnosticsConfig): String? {
            repeat(MAX_GENERATOR_ATTEMPTS) {
                val candidate = runCatching { config.queryIdGenerator.generate() }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() && it.none(Char::isISOControl) }
                    ?: UUID.randomUUID().toString()
                if (ACTIVE_QUERY_IDS.add(candidate)) return candidate
            }
            return null
        }

        private fun generateFallbackQueryId(): String {
            var candidate: String
            do {
                candidate = UUID.randomUUID().toString()
            } while (!ACTIVE_QUERY_IDS.add(candidate))
            return candidate
        }
    }
}

/**
 * V2 connection의 query settings와 application name을 요청 범위에 적용합니다.
 * 구형 driver나 wrapper가 해당 API를 제공하지 않으면 query 자체는 계속 실행되고 diagnostics에는
 * caller snapshot만 남습니다.
 */
internal fun applyClickHouseQueryDiagnostics(
    connection: Connection,
    diagnostics: ClickHouseQueryDiagnosticsConfig,
    queryId: String,
) {
    runCatching {
        diagnostics.clientName?.let { connection.setClientInfo("ApplicationName", it) }
        val implClass = Class.forName("com.clickhouse.jdbc.ConnectionImpl")
        val impl = connection.unwrap(implClass)
        val settings = implClass.getMethod("getDefaultQuerySettings").invoke(impl)
        settings.call("setQueryId", queryId)
        diagnostics.logComment?.let { settings.call("logComment", it) }
        diagnostics.sessionTimezone?.let { settings.call("setUseTimeZone", it.id) }
        if (diagnostics.immutableSessionDbRoles.isNotEmpty()) {
            settings.call("setDBRoles", diagnostics.immutableSessionDbRoles)
        }
        diagnostics.immutableSessionSettings.forEach { (name, value) ->
            settings.call("serverSetting", name, value)
        }
    }
}

/** JDBC V2 QueryResponse에서 안전하게 복사할 수 있는 선택적 metadata입니다. */
internal data class ClickHouseQueryResponseMetadata(
    val serverDisplayName: String?,
    val responseHeaders: Map<String, String>,
)

/** ResultSet 구현이 V2 response를 노출할 때만 allowlisted metadata를 읽습니다. */
internal fun clickHouseQueryResponseMetadata(resultSet: ResultSet): ClickHouseQueryResponseMetadata? = runCatching {
    val resultSetClass = Class.forName("com.clickhouse.jdbc.ResultSetImpl")
    if (!resultSet.isWrapperFor(resultSetClass)) return@runCatching null
    val resultSetImpl = resultSet.unwrap(resultSetClass)
    val responseField = resultSetClass.getDeclaredField("response")
    if (!responseField.trySetAccessible()) return@runCatching null
    val response = responseField.get(resultSetImpl) ?: return@runCatching null
    val responseHeaders = response.javaClass.getMethod("getResponseHeaders").invoke(response) as? Map<*, *>
        ?: emptyMap<Any, Any>()
    val safeHeaders = ClickHouseQueryDiagnosticsRedaction.redactResponseHeaders(responseHeaders)
    val serverDisplayName = response.javaClass.getMethod("getServerDisplayName").invoke(response) as? String
    ClickHouseQueryResponseMetadata(
        serverDisplayName = serverDisplayName?.let(ClickHouseQueryDiagnosticsRedaction::redact),
        responseHeaders = safeHeaders,
    )
}.getOrNull()

private fun Any.call(name: String, vararg args: Any?) {
    val method = javaClass.methods.firstOrNull { candidate ->
        candidate.name == name && candidate.parameterTypes.size == args.size &&
            candidate.parameterTypes.withIndex().all { (index, parameterType) ->
                val argument = args[index] ?: return@all !parameterType.isPrimitive
                parameterType.isAssignableFrom(argument.javaClass)
            }
    } ?: return
    runCatching { method.invoke(this, *args) }
}

internal fun Throwable.clickHouseSqlFailure(): ClickHouseQueryOutcome.Failure {
    val sqlException = generateSequence(this) { it.cause }
        .filterIsInstance<SQLException>()
        .firstOrNull()
    return ClickHouseQueryOutcome.Failure(
        sqlState = sqlException?.sqlState,
        vendorCode = sqlException?.errorCode?.takeIf { it != 0 },
    )
}

internal fun Throwable.clickHouseVendorCode(): Int? = generateSequence(this) { it.cause }
    .filterIsInstance<SQLException>()
    .map { it.errorCode }
    .firstOrNull { it != 0 }

internal fun CancellationException.clickHouseCancellationOutcome(): ClickHouseQueryOutcome.Cancelled =
    ClickHouseQueryOutcome.Cancelled(ClickHouseQueryCancellationState.LocalCancellation)
