package io.bluetape4k.batch.r2dbc

import io.bluetape4k.batch.api.BatchWriter
import io.bluetape4k.support.requireLe
import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.statements.BatchInsertStatement
import org.jetbrains.exposed.v1.core.vendors.SQLiteDialect
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.batchInsert
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * Exposed R2DBC 기반 [BatchWriter] 구현 — 네이티브 suspend.
 *
 * 청크 단위로 `batchInsert`를 수행한다. 빈 리스트는 no-op.
 * [useMultiRowValues]를 켜면 `rows × table.columns.size` 추정치를 기준으로
 * 일반 방언은 65,535개(SQLite는 32,766개)까지 transaction과 바인더 전에 검사한다.
 * writer가 소유한 transaction의 실패는 rollback하며, 이미 commit한 쓰기는 유지한다.
 * 기존 transaction에 참여하면 최종 commit/rollback은 호출자 책임이다.
 * 생성 키를 반환하지 않고 성공 여부만 노출하며, 취소는 호출자에게 전파한다.
 *
 * ## 사용 예
 * ```kotlin
 * val writer = ExposedR2dbcBatchWriter(
 *     database = db,
 *     table = OrderTable,
 * ) { order: OrderRecord ->
 *     this[OrderTable.customerId] = order.customerId
 *     this[OrderTable.amount] = order.amount
 *     this[OrderTable.createdAt] = order.createdAt
 * }
 * writer.write(listOf(order1, order2, order3))
 * ```
 *
 * @param T 저장할 아이템 타입
 * @param database Exposed R2DBC Database
 * @param table 저장 대상 Exposed 테이블
 * @param useMultiRowValues Exposed 1.5.0 multi-row VALUES 사용 여부. 기본 생성자는 기존 batch 경로를 사용한다.
 * @param bind [BatchInsertStatement]에 아이템 필드를 바인딩하는 람다
 */
class ExposedR2dbcBatchWriter<T : Any>(
    private val database: R2dbcDatabase,
    private val table: Table,
    private val useMultiRowValues: Boolean,
    private val bind: BatchInsertStatement.(T) -> Unit,
) : BatchWriter<T> {

    /** 기존 생성자와 호출 호환성을 유지하는 진입점입니다. */
    constructor(
        database: R2dbcDatabase,
        table: Table,
        bind: BatchInsertStatement.(T) -> Unit,
    ) : this(database, table, false, bind)

    companion object : KLoggingChannel()

    /**
     * 아이템 목록을 DB에 일괄 삽입한다.
     *
     * @param items 저장할 아이템 목록. 빈 리스트면 no-op.
     */
    override suspend fun write(items: List<T>) {
        if (items.isEmpty()) return
        if (useMultiRowValues) {
            val columns = table.columns.size
            val parameterLimit = if (database.dialect is SQLiteDialect) 32_766 else 65_535
            (items.size.toLong() * columns.toLong()).requireLe(parameterLimit.toLong()) {
                "Multi-row VALUES limit exceeded: rows=${items.size}, columns=$columns, parameterLimit=$parameterLimit"
            }
        }
        suspendTransaction(db = database) {
            if (useMultiRowValues) {
                table.batchInsert(
                    items,
                    useMultiRowValues = true,
                    shouldReturnGeneratedValues = false,
                ) { item -> bind(item) }
            } else {
                table.batchInsert(items, shouldReturnGeneratedValues = false) { item -> bind(item) }
            }
        }
    }
}
