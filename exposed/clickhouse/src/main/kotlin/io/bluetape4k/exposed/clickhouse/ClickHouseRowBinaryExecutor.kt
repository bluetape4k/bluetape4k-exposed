package io.bluetape4k.exposed.clickhouse

import kotlinx.coroutines.CancellationException
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException

/**
 * ClickHouse JDBC V2의 driver-owned RowBinary writer를 한 번의 operation으로 실행합니다.
 *
 * executor는 connection profile을 직접 만들지 않고 caller-owned provider에서 빌립니다.
 * SQL preflight는 connection/statement 생성 전에 수행하며, setter 이후에는 fallback·재시도·
 * 재전송을 하지 않습니다. 부분 count는 driver가 반환한 sentinel을 그대로 보존합니다.
 */
@Suppress("TooManyFunctions")
class ClickHouseRowBinaryExecutor(
    private val provider: ClickHouseConnectionProvider?,
    private val options: ClickHouseRowBinaryOptions,
) {

    private var unusable = false

    /** 작업 중 terminal failure가 발생한 executor는 재사용할 수 없습니다. */
    fun executeBatch(sql: String, rows: Iterable<ClickHouseRowBinaryRow>): ClickHouseRowBinaryResult {
        return executeBatchInternal(sql, rows, diagnostics = null, queryId = null)
    }

    /** RowBinary/JDBC batch operation을 immutable diagnostics lifecycle에 연결합니다. */
    @Suppress("TooGenericExceptionCaught") // 원래 batch 예외와 취소 원인을 보존하는 경계다.
    fun executeBatch(
        sql: String,
        rows: Iterable<ClickHouseRowBinaryRow>,
        diagnostics: ClickHouseQueryDiagnosticsConfig,
    ): ClickHouseRowBinaryResult {
        val recorder = ClickHouseQueryDiagnosticsRecorder(diagnostics)
        recorder.started()
        recorder.requestPrepared()
        var failure: Throwable? = null
        var outcome: ClickHouseQueryOutcome? = null
        return try {
            val result = executeBatchInternal(sql, rows, diagnostics, recorder.queryIdValue)
            val rowSummary = result.acceptedCount
                .takeIf { !result.acceptedCountMayBeIncomplete }
                ?.toLong()
            recorder.responseReceived(rowSummary)
            outcome = ClickHouseQueryOutcome.Success
            result
        } catch (cancelled: CancellationException) {
            failure = cancelled
            outcome = cancelled.clickHouseCancellationOutcome()
            throw cancelled
        } catch (caught: Throwable) {
            failure = caught
            outcome = caught.clickHouseSqlFailure()
            throw caught
        } finally {
            recorder.finishAfterCleanup(
                outcome = outcome ?: ClickHouseQueryOutcome.Failure(null, failure?.clickHouseVendorCode()),
                vendorCode = failure?.clickHouseVendorCode(),
            )
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun executeBatchInternal(
        sql: String,
        rows: Iterable<ClickHouseRowBinaryRow>,
        diagnostics: ClickHouseQueryDiagnosticsConfig?,
        queryId: String?,
    ): ClickHouseRowBinaryResult {
        checkUsable()
        val iterator = rows.iterator()
        if (!iterator.hasNext()) return emptyResult()

        val connectionProvider = provider ?: throw UnsupportedConfiguration(
            reasonCode = "PROVIDER_ABSENT",
            message = "RowBinary/JDBC 배치에는 ClickHouseConnectionProvider가 필요합니다.",
        )
        return if (options.enabled) {
            executeEnabled(connectionProvider, sql, iterator, diagnostics, queryId)
        } else {
            recordFallback("ROW_BINARY_DISABLED")
            executeWithProfile(
                connectionProvider,
                rowBinaryEnabled = false,
                sql = sql,
                rows = iterableFrom(iterator),
                path = ClickHouseBatchPath.JDBC_FALLBACK,
                diagnostics = diagnostics,
                queryId = queryId,
            )
        }
    }

    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    private fun executeEnabled(
        connectionProvider: ClickHouseConnectionProvider,
        sql: String,
        rows: Iterator<ClickHouseRowBinaryRow>,
        diagnostics: ClickHouseQueryDiagnosticsConfig?,
        queryId: String?,
    ): ClickHouseRowBinaryResult {
        val preflight = ClickHouseRowBinaryPreflight.inspect(sql)
        if (!preflight.eligible) {
            recordFallback(preflight.reasonCode)
            return executeWithProfile(
                connectionProvider,
                rowBinaryEnabled = false,
                sql = sql,
                rows = iterableFrom(rows),
                path = ClickHouseBatchPath.JDBC_FALLBACK,
                diagnostics = diagnostics,
                queryId = queryId,
            )
        }

        val rowBinaryConnection = try {
            connectionProvider.open(rowBinaryEnabled = true)
        } catch (failure: Throwable) {
            if (!isUnsupported(failure)) {
                unusable = true
                throw failure
            }
            recordFallback("CAPABILITY_UNKNOWN")
            return executeWithProfile(
                connectionProvider,
                rowBinaryEnabled = false,
                sql = sql,
                rows = iterableFrom(rows),
                path = ClickHouseBatchPath.JDBC_FALLBACK,
                diagnostics = diagnostics,
                queryId = queryId,
            )
        }
        return try {
            executeOnConnection(
                connection = rowBinaryConnection,
                sql = sql,
                rows = rows,
                path = ClickHouseBatchPath.ROW_BINARY,
                rowBinaryEnabled = true,
                diagnostics = diagnostics,
                queryId = queryId,
            )
        } catch (fallback: UnsupportedBeforeFirstByte) {
            closeConnection(rowBinaryConnection, fallback.cause)
            recordFallback(fallback.reasonCode)
            executeWithProfile(
                connectionProvider,
                rowBinaryEnabled = false,
                sql = sql,
                rows = fallback.replayRows,
                path = ClickHouseBatchPath.JDBC_FALLBACK,
                diagnostics = diagnostics,
                queryId = queryId,
            )
        } catch (failure: Throwable) {
            unusable = true
            throw failure
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun executeWithProfile(
        connectionProvider: ClickHouseConnectionProvider,
        rowBinaryEnabled: Boolean,
        sql: String,
        rows: Iterable<ClickHouseRowBinaryRow>,
        path: ClickHouseBatchPath,
        diagnostics: ClickHouseQueryDiagnosticsConfig?,
        queryId: String?,
    ): ClickHouseRowBinaryResult {
        val connection = try {
            connectionProvider.open(rowBinaryEnabled)
        } catch (failure: Throwable) {
            unusable = true
            throw failure
        }
        return try {
            executeOnConnection(
                connection = connection,
                sql = sql,
                rows = rows.iterator(),
                path = path,
                rowBinaryEnabled = rowBinaryEnabled,
                diagnostics = diagnostics,
                queryId = queryId,
            )
        } catch (failure: Throwable) {
            unusable = true
            throw failure
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun executeOnConnection(
        connection: Connection,
        sql: String,
        rows: Iterator<ClickHouseRowBinaryRow>,
        path: ClickHouseBatchPath,
        rowBinaryEnabled: Boolean,
        diagnostics: ClickHouseQueryDiagnosticsConfig?,
        queryId: String?,
    ): ClickHouseRowBinaryResult {
        val aggregate = try {
            diagnostics?.let {
                applyClickHouseQueryDiagnostics(connection, it, checkNotNull(queryId))
            }
            connection.prepareStatement(sql).use { statement ->
                processRows(statement, rows, rowBinaryEnabled)
            }
        } catch (fallback: UnsupportedBeforeFirstByte) {
            throw fallback
        } catch (failure: Throwable) {
            closeConnection(connection, failure)
            throw failure
        }

        closeConnection(connection, null)
        return aggregate.toResult(path)
    }

    private fun processRows(
        statement: PreparedStatement,
        rows: Iterator<ClickHouseRowBinaryRow>,
        rowBinaryEnabled: Boolean,
    ): BatchAggregate {
        val aggregate = BatchAggregate()
        while (rows.hasNext()) {
            val chunk = nextChunk(rows)
            val chunkCounts = processChunk(
                statement = statement,
                chunk = chunk,
                remainingRows = rows,
                rowBinaryEnabled = rowBinaryEnabled,
                committedRows = aggregate.committedRows,
            )
            aggregate.add(chunk, chunkCounts)
        }
        return aggregate
    }

    @Suppress("TooGenericExceptionCaught", "ComplexCondition")
    private fun processChunk(
        statement: PreparedStatement,
        chunk: List<ClickHouseRowBinaryRow>,
        remainingRows: Iterator<ClickHouseRowBinaryRow>,
        rowBinaryEnabled: Boolean,
        committedRows: Boolean,
    ): IntArray {
        var rowAddedToStatement = false
        chunk.forEach { row ->
            try {
                row.bind(statement)
            } catch (failure: Throwable) {
                if (rowBinaryEnabled && !committedRows && !rowAddedToStatement && isUnsupported(failure)) {
                    throw UnsupportedBeforeFirstByte(
                        reasonCode = "UNSUPPORTED_SETTER",
                        cause = failure,
                        replayRows = iterableFrom(chunk.iterator(), remainingRows),
                    )
                }
                throw failure
            }
            statement.addBatch()
            rowAddedToStatement = true
        }
        return statement.executeBatch()
    }

    private fun nextChunk(rows: Iterator<ClickHouseRowBinaryRow>): List<ClickHouseRowBinaryRow> {
        val chunk = ArrayList<ClickHouseRowBinaryRow>()
        while (rows.hasNext() && chunk.size < options.maxRowsPerFlush) {
            chunk += rows.next()
        }
        return chunk
    }

    @Suppress("TooGenericExceptionCaught")
    private fun closeConnection(connection: Connection, primary: Throwable?) {
        try {
            connection.close()
        } catch (closeFailure: Throwable) {
            if (primary != null) {
                primary.addSuppressed(closeFailure)
            } else {
                unusable = true
                throw closeFailure
            }
        }
    }

    private fun recordFallback(reasonCode: String) {
        (provider as? ClickHouseRowBinaryFallbackRecorder)?.record(
            ClickHouseRowBinaryFallbackEvent(reasonCode = reasonCode, beforeFirstByte = true),
        )
    }

    private fun emptyResult(): ClickHouseRowBinaryResult = ClickHouseRowBinaryResult(
        updateCounts = emptyList(),
        acceptedCount = 0,
        path = if (options.enabled) ClickHouseBatchPath.ROW_BINARY else ClickHouseBatchPath.JDBC_FALLBACK,
        acceptedCountMayBeIncomplete = false,
    )

    private fun checkUsable() {
        if (unusable) {
            throw UnsupportedConfiguration(
                reasonCode = "EXECUTOR_UNUSABLE",
                message = "이전 RowBinary 작업 실패 후 executor를 재사용할 수 없습니다.",
            )
        }
    }

    private fun isUnsupported(failure: Throwable): Boolean =
        failure is SQLFeatureNotSupportedException ||
            failure is UnsupportedOperationException ||
            (failure is SQLException && failure.sqlState == "0A000")

    private fun iterableFrom(iterator: Iterator<ClickHouseRowBinaryRow>): Iterable<ClickHouseRowBinaryRow> =
        Iterable { iterator }

    private fun iterableFrom(
        first: Iterator<ClickHouseRowBinaryRow>,
        second: Iterator<ClickHouseRowBinaryRow>,
    ): Iterable<ClickHouseRowBinaryRow> = Iterable {
        object: Iterator<ClickHouseRowBinaryRow> {
            override fun hasNext(): Boolean = first.hasNext() || second.hasNext()

            override fun next(): ClickHouseRowBinaryRow {
                if (first.hasNext()) return first.next()
                if (second.hasNext()) return second.next()
                throw NoSuchElementException()
            }
        }
    }

    private class UnsupportedBeforeFirstByte(
        val reasonCode: String,
        cause: Throwable,
        val replayRows: Iterable<ClickHouseRowBinaryRow>,
    ) : RuntimeException(cause)

    private class BatchAggregate {
        val counts = ArrayList<Int>()
        var acceptedCount = 0
        var acceptedCountMayBeIncomplete = false
        var committedRows = false

        fun add(chunk: List<ClickHouseRowBinaryRow>, chunkCounts: IntArray) {
            committedRows = committedRows || chunk.isNotEmpty()
            chunkCounts.forEach { count ->
                counts += count
                if (count >= 0) acceptedCount += count else acceptedCountMayBeIncomplete = true
            }
        }

        fun toResult(path: ClickHouseBatchPath): ClickHouseRowBinaryResult = ClickHouseRowBinaryResult(
            updateCounts = counts.toList(),
            acceptedCount = acceptedCount,
            path = path,
            acceptedCountMayBeIncomplete = acceptedCountMayBeIncomplete,
        )
    }

    /** RowBinary provider 또는 preflight 조건을 충족하지 못한 구성입니다. */
    class UnsupportedConfiguration(
        val reasonCode: String,
        message: String,
    ) : IllegalStateException(message)
}

/** JDBC PreparedStatement에 한 행의 값을 바인딩하는 함수형 계약입니다. */
fun interface ClickHouseRowBinaryRow {
    fun bind(statement: PreparedStatement)
}
