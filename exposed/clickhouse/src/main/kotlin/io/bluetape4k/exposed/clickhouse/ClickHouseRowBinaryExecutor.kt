package io.bluetape4k.exposed.clickhouse

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
class ClickHouseRowBinaryExecutor(
    private val provider: ClickHouseConnectionProvider?,
    private val options: ClickHouseRowBinaryOptions,
) {

    private var unusable = false

    /** 작업 중 terminal failure가 발생한 executor는 재사용할 수 없습니다. */
    fun executeBatch(sql: String, rows: Iterable<ClickHouseRowBinaryRow>): ClickHouseRowBinaryResult {
        checkUsable()
        val iterator = rows.iterator()
        if (!iterator.hasNext()) {
            return ClickHouseRowBinaryResult(
                updateCounts = emptyList(),
                acceptedCount = 0,
                path = if (options.enabled) ClickHouseBatchPath.ROW_BINARY else ClickHouseBatchPath.JDBC_FALLBACK,
                acceptedCountMayBeIncomplete = false,
            )
        }
        val connectionProvider = provider ?: throw UnsupportedConfiguration(
            reasonCode = "PROVIDER_ABSENT",
            message = "RowBinary/JDBC 배치에는 ClickHouseConnectionProvider가 필요합니다.",
        )

        if (!options.enabled) {
            recordFallback("ROW_BINARY_DISABLED")
            return executeWithProfile(
                connectionProvider,
                rowBinaryEnabled = false,
                sql = sql,
                rows = iterableFrom(iterator),
                path = ClickHouseBatchPath.JDBC_FALLBACK,
            )
        }

        val preflight = ClickHouseRowBinaryPreflight.inspect(sql)
        if (!preflight.eligible) {
            recordFallback(preflight.reasonCode)
            return executeWithProfile(
                connectionProvider,
                rowBinaryEnabled = false,
                sql = sql,
                rows = iterableFrom(iterator),
                path = ClickHouseBatchPath.JDBC_FALLBACK,
            )
        }

        val rowBinaryConnection = try {
            connectionProvider.open(rowBinaryEnabled = true)
        } catch (failure: Throwable) {
            if (isCapabilityUnknown(failure)) {
                recordFallback("CAPABILITY_UNKNOWN")
                return executeWithProfile(
                    connectionProvider,
                    rowBinaryEnabled = false,
                    sql = sql,
                    rows = iterableFrom(iterator),
                    path = ClickHouseBatchPath.JDBC_FALLBACK,
                )
            }
            unusable = true
            throw failure
        }

        return try {
            executeOnConnection(
                connection = rowBinaryConnection,
                sql = sql,
                rows = iterator,
                path = ClickHouseBatchPath.ROW_BINARY,
                rowBinaryEnabled = true,
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
            )
        } catch (failure: Throwable) {
            unusable = true
            throw failure
        }
    }

    private fun executeWithProfile(
        connectionProvider: ClickHouseConnectionProvider,
        rowBinaryEnabled: Boolean,
        sql: String,
        rows: Iterable<ClickHouseRowBinaryRow>,
        path: ClickHouseBatchPath,
    ): ClickHouseRowBinaryResult {
        val connection = try {
            connectionProvider.open(rowBinaryEnabled)
        } catch (failure: Throwable) {
            unusable = true
            throw failure
        }
        return try {
            executeOnConnection(connection, sql, rows.iterator(), path, rowBinaryEnabled)
        } catch (failure: Throwable) {
            unusable = true
            throw failure
        }
    }

    private fun executeOnConnection(
        connection: Connection,
        sql: String,
        rows: Iterator<ClickHouseRowBinaryRow>,
        path: ClickHouseBatchPath,
        rowBinaryEnabled: Boolean,
    ): ClickHouseRowBinaryResult {
        val counts = ArrayList<Int>()
        var acceptedCount = 0
        var acceptedCountMayBeIncomplete = false
        var committedRows = false

        try {
            connection.prepareStatement(sql).use { statement ->
                while (rows.hasNext()) {
                    val chunk = ArrayList<ClickHouseRowBinaryRow>(options.maxRowsPerFlush)
                    while (rows.hasNext() && chunk.size < options.maxRowsPerFlush) {
                        chunk += rows.next()
                    }

                    var rowAddedToStatement = false
                    try {
                        chunk.forEach { row ->
                            try {
                                row.bind(statement)
                            } catch (failure: Throwable) {
                                if (rowBinaryEnabled && !committedRows && !rowAddedToStatement && isUnsupportedSetter(failure)) {
                                    throw UnsupportedBeforeFirstByte(
                                        reasonCode = "UNSUPPORTED_SETTER",
                                        cause = failure,
                                        replayRows = iterableFrom(chunk.iterator(), rows),
                                    )
                                }
                                throw failure
                            }
                            statement.addBatch()
                            rowAddedToStatement = true
                        }
                        val chunkCounts = statement.executeBatch().toList()
                        committedRows = committedRows || chunk.isNotEmpty()
                        chunkCounts.forEach { count ->
                            counts += count
                            when {
                                count >= 0 -> acceptedCount += count
                                else -> acceptedCountMayBeIncomplete = true
                            }
                        }
                    } catch (fallback: UnsupportedBeforeFirstByte) {
                        throw fallback
                    }
                }
            }
        } catch (fallback: UnsupportedBeforeFirstByte) {
            throw fallback
        } catch (failure: Throwable) {
            closeConnection(connection, failure)
            throw failure
        }

        closeConnection(connection, null)
        return ClickHouseRowBinaryResult(
            updateCounts = counts,
            acceptedCount = acceptedCount,
            path = path,
            acceptedCountMayBeIncomplete = acceptedCountMayBeIncomplete,
        )
    }

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

    private fun checkUsable() {
        if (unusable) {
            throw UnsupportedConfiguration(
                reasonCode = "EXECUTOR_UNUSABLE",
                message = "이전 RowBinary 작업 실패 후 executor를 재사용할 수 없습니다.",
            )
        }
    }

    private fun isUnsupportedSetter(failure: Throwable): Boolean =
        failure is SQLFeatureNotSupportedException ||
            failure is UnsupportedOperationException ||
            (failure is SQLException && failure.sqlState == "0A000")

    private fun isCapabilityUnknown(failure: Throwable): Boolean =
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

            override fun next(): ClickHouseRowBinaryRow = if (first.hasNext()) first.next() else second.next()
        }
    }

    private class UnsupportedBeforeFirstByte(
        val reasonCode: String,
        cause: Throwable,
        val replayRows: Iterable<ClickHouseRowBinaryRow>,
    ) : RuntimeException(cause)

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
