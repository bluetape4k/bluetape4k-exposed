package io.bluetape4k.exposed.trino

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.BatchInsertStatement
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.Serializable

private const val DEFAULT_TRINO_PAGE_SIZE = 1_000
private const val DEFAULT_TRINO_BATCH_CHUNK_SIZE = 1_000

/**
 * Options for [pagedQueryFlow].
 *
 * @property pageSize maximum number of rows requested per transaction.
 * @property initialOffset first offset passed to the page query block.
 */
data class TrinoPagedQueryOptions(
    val pageSize: Int = DEFAULT_TRINO_PAGE_SIZE,
    val initialOffset: Long = 0L,
): Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }

    init {
        require(pageSize > 0) { "pageSize must be positive: $pageSize" }
        require(initialOffset >= 0L) { "initialOffset must be non-negative: $initialOffset" }
    }
}

/**
 * Options for [trinoBatchInsert].
 *
 * Trino write support is connector-dependent and this module cannot make a
 * connector write path transactional. The defaults favor compatibility with
 * Trino JDBC by avoiding generated-key retrieval and sending rows in bounded
 * chunks.
 *
 * @property chunkSize maximum number of rows sent through one Exposed
 *   `batchInsert` call.
 * @property shouldReturnGeneratedValues whether Exposed should request
 *   generated values from JDBC. Keep this disabled for normal Trino tables.
 */
data class TrinoBatchInsertOptions(
    val chunkSize: Int = DEFAULT_TRINO_BATCH_CHUNK_SIZE,
    val shouldReturnGeneratedValues: Boolean = false,
): Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }

    init {
        require(chunkSize > 0) { "chunkSize must be positive: $chunkSize" }
    }
}

/**
 * Executes connector-dependent Trino batch inserts in bounded chunks.
 *
 * This is a thin Exposed `batchInsert` wrapper, not a Trino-specific bulk-loader
 * protocol. It is intended for connectors that already support `INSERT`, and it
 * keeps the batch size explicit so callers do not accidentally materialize or
 * submit a very large write in one JDBC call.
 *
 * Trino transactions are autocommit-like for this module. If a later chunk
 * fails, earlier chunks may already be visible and are not rolled back by
 * [TrinoConnectionWrapper.rollback].
 *
 * ```kotlin
 * Events.trinoBatchInsert(events, TrinoBatchInsertOptions(chunkSize = 500)) { event ->
 *     this[Events.eventId] = event.id
 *     this[Events.eventName] = event.name
 *     this[Events.region] = event.region
 * }
 * ```
 *
 * @param data source rows to insert.
 * @param options chunking and generated-value behavior.
 * @param body Exposed batch insert body.
 * @return generated rows returned by Exposed when
 *   [TrinoBatchInsertOptions.shouldReturnGeneratedValues] is enabled. The
 *   default returns an empty list after successful writes.
 */
fun <E> Table.trinoBatchInsert(
    data: Iterable<E>,
    options: TrinoBatchInsertOptions = TrinoBatchInsertOptions(),
    body: BatchInsertStatement.(E) -> Unit,
): List<ResultRow> {
    val generatedRows = mutableListOf<ResultRow>()
    data.asSequence().chunked(options.chunkSize).forEach { chunk ->
        val rows = batchInsert(
            data = chunk,
            shouldReturnGeneratedValues = options.shouldReturnGeneratedValues,
            body = body,
        )
        if (options.shouldReturnGeneratedValues) {
            generatedRows += rows
        }
    }
    return generatedRows
}

/**
 * Trino에서 suspend 트랜잭션을 실행합니다.
 *
 * ```kotlin
 * val db = TrinoDatabase.connect("jdbc:trino://host:8080/catalog/schema")
 *
 * // suspend 트랜잭션
 * val rows = suspendTransaction(db) {
 *     Events.selectAll().where { Events.region eq "kr" }.toList()
 * }
 *
 * // Virtual Thread 사용
 * val vtDispatcher = Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()
 * val rows = suspendTransaction(db, vtDispatcher) {
 *     Events.selectAll().toList()
 * }
 * ```
 *
 * ⚠️ Trino autocommit 주의사항:
 * - 원자성 보장 없음 — 블록 중간 실패 시 앞선 DML 롤백 안 됨
 * - rollback() no-op
 * - nested transaction 호출 허용되나 원자성 없음
 * - multi-statement 쓰기 시 부분 반영 위험
 *
 * @param db Trino 데이터베이스 연결
 * @param dispatcher 블로킹 JDBC 호출을 실행할 디스패처 (기본값: [Dispatchers.IO])
 * @param block 트랜잭션 블록
 */
suspend fun <T> suspendTransaction(
    db: Database,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    block: Transaction.() -> T,
    // Trino JDBC 호출은 블로킹 I/O이므로, 코루틴 기본 디스패처(Main/Default)를 점유하지 않도록
    // Dispatchers.IO(또는 Virtual Thread 전용 디스패처)로 컨텍스트를 전환합니다.
): T = withContext(dispatcher) {
    try {
        transaction(db) { block() }
    } catch (e: CancellationException) {
        // 코루틴 취소는 반드시 재전파해야 합니다 — 삼키면 구조적 동시성이 깨집니다.
        throw e
    }
}

/**
 * Trino 쿼리 결과를 [Flow]로 반환합니다.
 *
 * 구현상 JDBC `ResultSet` 수명과 Exposed 트랜잭션 경계를 안전하게 유지하기 위해
 * 트랜잭션 내부에서 결과를 `List`로 materialize 한 뒤 순차적으로 emit 합니다.
 * 따라서 소비 API는 [Flow]이지만, 엄밀한 의미의 row-by-row 스트리밍은 아닙니다.
 * 중간 규모 결과를 코루틴 파이프라인으로 연결할 때 적합하며,
 * 매우 큰 결과셋은 페이지네이션 또는 전용 배치 전략을 별도로 고려해야 합니다.
 *
 * ```kotlin
 * val db = TrinoDatabase.connect("jdbc:trino://host:8080/catalog/schema")
 *
 * queryFlow(db) {
 *     Events.selectAll().where { Events.region eq "kr" }
 * }.collect { row ->
 *     println(row[Events.eventId])
 * }
 * ```
 *
 * ⚠️ Trino autocommit 주의사항:
 * - 원자성 보장 없음 — 블록 중간 실패 시 앞선 DML 롤백 안 됨
 * - rollback() no-op
 * - nested transaction 호출 허용되나 원자성 없음
 * - multi-statement 쓰기 시 부분 반영 위험
 *
 * @param db Trino 데이터베이스 연결
 * @param dispatcher 블로킹 JDBC 호출을 실행할 디스패처 (기본값: [Dispatchers.IO])
 * @param block 조회 결과를 반환하는 트랜잭션 블록. 반환된 [Iterable]은 트랜잭션 안에서 즉시 materialize 됩니다.
 */
fun <T> queryFlow(
    db: Database,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    block: Transaction.() -> Iterable<T>,
): Flow<T> = flow {
    // Trino JDBC 호출은 블로킹 I/O이므로 Dispatchers.IO로 전환하고,
    // ResultSet 수명(트랜잭션 경계 내)과 Flow emit 경계가 겹치지 않도록
    // 트랜잭션 내에서 List로 완전히 materialize한 뒤 방출합니다.
    val items = try {
        withContext(dispatcher) { transaction(db) { block().toList() } }
    } catch (e: CancellationException) {
        // 코루틴 취소는 반드시 재전파해야 합니다 — 삼키면 구조적 동시성이 깨집니다.
        throw e
    }
    for (item in items) {
        currentCoroutineContext().ensureActive()
        emit(item)
    }
}

/**
 * Returns Trino query results as a page-by-page [Flow].
 *
 * This function is intentionally not a row-by-row JDBC cursor stream. Each page
 * is fetched and materialized inside a short Exposed transaction, then emitted
 * after the transaction is closed. This preserves JDBC `ResultSet` and Exposed
 * transaction lifetimes while avoiding one full-result-set materialization.
 *
 * The caller must apply the provided `limit` and `offset` to a stable,
 * deterministic query, usually with an explicit `orderBy`.
 *
 * ```kotlin
 * pagedQueryFlow(db, TrinoPagedQueryOptions(pageSize = 500)) { limit, offset ->
 *     Events.selectAll()
 *         .orderBy(Events.eventId to SortOrder.ASC)
 *         .limit(limit)
 *         .offset(offset)
 * }.collect { row ->
 *     println(row[Events.eventId])
 * }
 * ```
 *
 * Cancellation is checked before each page fetch and before each emitted item.
 * If collection is cancelled, the current transaction is allowed to close and no
 * further pages are requested.
 *
 * @param db Trino database connection.
 * @param options page size and initial offset.
 * @param dispatcher dispatcher used for blocking JDBC calls.
 * @param block page query block. It receives the page `limit` and `offset` and
 *   must return at most `limit` rows.
 */
fun <T> pagedQueryFlow(
    db: Database,
    options: TrinoPagedQueryOptions = TrinoPagedQueryOptions(),
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    block: Transaction.(limit: Int, offset: Long) -> Iterable<T>,
): Flow<T> = flow {
    var offset = options.initialOffset

    while (true) {
        currentCoroutineContext().ensureActive()

        val page = try {
            withContext(dispatcher) {
                transaction(db) {
                    block(options.pageSize, offset).toList()
                }
            }
        } catch (e: CancellationException) {
            throw e
        }

        check(page.size <= options.pageSize) {
            "pagedQueryFlow block returned ${page.size} rows for pageSize=${options.pageSize}. " +
                    "Apply the provided limit and offset."
        }
        if (page.isEmpty()) break

        for (item in page) {
            currentCoroutineContext().ensureActive()
            emit(item)
        }
        if (page.size < options.pageSize) break

        val nextOffset = offset + page.size.toLong()
        check(nextOffset > offset) { "pagedQueryFlow offset overflow at offset=$offset, pageSize=${page.size}" }
        offset = nextOffset
    }
}
