package io.bluetape4k.exposed.clickhouse

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.statements.jdbc.JdbcResult
import org.jetbrains.exposed.v1.jdbc.transactions.inTopLevelSuspendTransaction

private object ClickHouseStreamingLog: KLoggingChannel()

/** 소비자 종료 시 생산자를 취소하고 JDBC 정리를 기다려 자원 수명을 수집 범위에 묶습니다. */
@Suppress("TooGenericExceptionCaught") // 사용자 mapper의 모든 실패를 원형 그대로 소비자에게 전달하는 경계다.
internal fun <T> clickHouseQueryFlow(
    db: Database,
    dispatcher: CoroutineDispatcher,
    query: JdbcTransaction.() -> Query,
    mapper: (ResultRow) -> T,
): Flow<T> = clickHouseQueryFlow(db, dispatcher, ClickHouseQueryDiagnosticsConfig(), query, mapper)

/** Query cursor lifecycle에 diagnostics recorder를 연결하는 내부 구현입니다. */
@Suppress(
    "TooGenericExceptionCaught",
    "CyclomaticComplexMethod",
    "LongMethod",
) // mapper/driver의 원래 Throwable을 보존하는 경계다.
internal fun <T> clickHouseQueryFlow(
    db: Database,
    dispatcher: CoroutineDispatcher,
    diagnostics: ClickHouseQueryDiagnosticsConfig,
    query: JdbcTransaction.() -> Query,
    mapper: (ResultRow) -> T,
): Flow<T> = flow {
    supervisorScope {
        val channel = Channel<T>(Channel.RENDEZVOUS)
        var closeFailure: Throwable? = null
        var producerFailure: Throwable? = null
        val producer = launch(dispatcher) {
            val recorder = ClickHouseQueryDiagnosticsRecorder(diagnostics)
            var failure: Throwable? = null
            var outcome: ClickHouseQueryOutcome? = null
            var returnedRows = 0L
            try {
                currentCoroutineContext().ensureActive()
                recorder.started()
                recorder.requestPrepared()
                ClickHouseStreamingLog.log.debug { "ClickHouse stream started queryId=${recorder.queryIdValue}" }
                inTopLevelSuspendTransaction(db, outerTransaction = null) {
                    maxAttempts = 1
                    applyClickHouseQueryDiagnostics(
                        connection.connection as java.sql.Connection,
                        diagnostics,
                        recorder.queryIdValue,
                    )
                    streamRows(
                        query = query,
                        mapper = mapper,
                        channel = channel,
                        onCloseFailure = { closeFailure = it },
                        onResponse = recorder::responseMetadata,
                        onRow = { returnedRows++ },
                        onFailure = {
                            producerFailure = it
                        },
                    )
                }
                recorder.responseReceived(returnedRows)
                outcome = ClickHouseQueryOutcome.Success
            } catch (cancelled: CancellationException) {
                failure = cancelled
                if (producerFailure == null) producerFailure = cancelled
                outcome = cancelled.clickHouseCancellationOutcome()
                throw cancelled
            } catch (caught: Throwable) {
                failure = caught
                if (producerFailure == null) producerFailure = caught
                outcome = caught.clickHouseSqlFailure()
                // 사용자 예외의 메시지·SQL·바인딩·스택은 이 logger에 넘기지 않는다.
                ClickHouseStreamingLog.log.debug { "ClickHouse stream failed queryId=${recorder.queryIdValue}" }
            } finally {
                recorder.finishAfterCleanup(
                    outcome = outcome ?: ClickHouseQueryOutcome.Failure(null, failure?.clickHouseVendorCode()),
                    vendorCode = failure?.clickHouseVendorCode(),
                )
                ClickHouseStreamingLog.log.debug {
                    "ClickHouse stream cleanup completed queryId=${recorder.queryIdValue}"
                }
                channel.close(failure)
            }
        }
        var consumerFailure: Throwable? = null
        try {
            while (true) {
                val received = channel.receiveCatching()
                if (received.isClosed) {
                    // Channel iterator가 사용하는 recoverStackTrace()는 공개 예외를 복사한다.
                    // 결과의 close cause를 직접 읽어 원래 예외 인스턴스와 취소 원인을 보존한다.
                    received.exceptionOrNull()?.let { throw it }
                    break
                }
                emit(received.getOrThrow())
            }
        } catch (cancelled: CancellationException) {
            val preserved = producerFailure?.takeIf { cancelled.containsIdentity(it) || it.containsIdentity(cancelled) }
            consumerFailure = preserved ?: cancelled
            throw preserved ?: cancelled
        } catch (caught: Throwable) {
            val preserved = producerFailure?.takeIf { caught.containsIdentity(it) || it.containsIdentity(caught) }
            consumerFailure = preserved ?: caught
            throw preserved ?: caught
        } finally {
            channel.cancel()
            producer.cancel()
            withContext(NonCancellable) { producer.join() }
            consumerFailure?.let { primary ->
                closeFailure?.let(primary::suppressDistinct)
            }
        }
    }
}

/** 직접 소유한 ResultSet만 닫고, Statement와 연결은 Exposed 트랜잭션 정리에 맡깁니다. */
// 취소·mapper 원인은 즉시 재전파하고, 정상 읽기 뒤의 close 실패만 별도로 전달한다.
@Suppress("TooGenericExceptionCaught", "ThrowsCount")
private suspend fun <T> JdbcTransaction.streamRows(
    query: JdbcTransaction.() -> Query,
    mapper: (ResultRow) -> T,
    channel: Channel<T>,
    onCloseFailure: (Throwable) -> Unit,
    onResponse: (ClickHouseQueryResponseMetadata?) -> Unit,
    onRow: () -> Unit,
    onFailure: (Throwable) -> Unit,
) {
    currentCoroutineContext().ensureActive()
    val original = try {
        query()
    } catch (failure: Throwable) {
        onFailure(failure)
        throw failure
    }
    val distinct = original.set.fields.distinct()
    val selected = if (distinct.size < original.set.fields.size) {
        original.copy().adjustSelect { select(distinct) }
    } else original
    val fields = selected.set.realFields.toSet()
        .mapIndexed { index, expression -> expression to index }.toMap()
    currentCoroutineContext().ensureActive()
    // Query.iterator()는 이 dialect에서 전체 결과를 수집하므로 직접 커서를 읽는다.
    val cursor = checkNotNull(execQuery(selected) {
        onResponse(clickHouseQueryResponseMetadata(it))
        it
    })
    var readingFailure: Throwable? = null
    var cleanupFailure: Throwable? = null
    try {
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!cursor.next()) break
            currentCoroutineContext().ensureActive()
            val item = mapper(ResultRow.create(JdbcResult(cursor), fields))
            currentCoroutineContext().ensureActive()
            channel.send(item)
            onRow()
        }
    } catch (cancelled: CancellationException) {
        readingFailure = cancelled
        onFailure(cancelled)
        throw cancelled
    } catch (caught: Throwable) {
        readingFailure = caught
        onFailure(caught)
        throw caught
    } finally {
        try {
            cursor.close()
        } catch (caught: Throwable) {
            cleanupFailure = caught
            onCloseFailure(caught)
            readingFailure?.suppressDistinct(caught)
        }
    }
    // 기존 실패가 없는 경우에만 정리 오류를 주 원인으로 전달한다.
    cleanupFailure?.let { throw it }
}

private fun Throwable.suppressDistinct(failure: Throwable) {
    if (failure !== this && suppressed.none { it === failure }) addSuppressed(failure)
}

private fun Throwable.containsIdentity(target: Throwable): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current === target) return true
        current = current.cause
    }
    return false
}
