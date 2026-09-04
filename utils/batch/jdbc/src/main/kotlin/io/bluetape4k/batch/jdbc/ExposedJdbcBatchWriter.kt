package io.bluetape4k.batch.jdbc

import io.bluetape4k.batch.api.BatchWriter
import io.bluetape4k.support.requireLe
import io.bluetape4k.concurrent.virtualthread.VT
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.statements.BatchInsertStatement
import org.jetbrains.exposed.v1.core.vendors.SQLiteDialect
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Exposed JDBC 기반 [BatchWriter] 구현.
 *
 * 청크 단위로 [batchInsert]를 수행한다. 빈 리스트는 no-op.
 * [useMultiRowValues]를 켜면 `rows × table.columns.size` 추정치를 기준으로
 * 일반 방언은 65,535개(SQLite는 32,766개)까지 transaction과 바인더 전에 검사한다.
 * writer가 소유한 transaction의 실패는 rollback하며, 이미 commit한 쓰기는 유지한다.
 * 기존 transaction에 참여하면 최종 commit/rollback은 호출자 책임이다.
 * 생성 키를 반환하지 않고 성공 여부만 노출한다.
 *
 * ## 사용 예
 * ```kotlin
 * val writer = ExposedJdbcBatchWriter(
 *     database = db,
 *     table = OrderTable,
 *     bind = { order ->
 *         this[OrderTable.id]   = order.id
 *         this[OrderTable.name] = order.name
 *     },
 * )
 * ```
 *
 * @param T 아이템 타입
 * @param database Exposed JDBC [Database]
 * @param table 대상 [Table]
 * @param ignore `INSERT IGNORE` 사용 여부 (중복 무시)
 * @param useMultiRowValues Exposed 1.5.0 multi-row VALUES 사용 여부. 기본 생성자는 기존 batch 경로를 사용한다.
 * @param bind 아이템 → 컬럼 바인딩 함수
 */
class ExposedJdbcBatchWriter<T: Any>(
    private val database: Database,
    private val table: Table,
    private val ignore: Boolean = false,
    private val useMultiRowValues: Boolean,
    private val bind: BatchInsertStatement.(T) -> Unit,
): BatchWriter<T> {

    /** 기존 생성자와 JVM 기본 인자 bridge를 유지하는 호환 진입점입니다. */
    constructor(
        database: Database,
        table: Table,
        ignore: Boolean = false,
        bind: BatchInsertStatement.(T) -> Unit,
    ) : this(database, table, ignore, false, bind)

    companion object: KLoggingChannel()

    override suspend fun write(items: List<T>) {
        if (items.isEmpty()) return
        if (useMultiRowValues) {
            val columns = table.columns.size
            val parameterLimit = if (database.dialect is SQLiteDialect) 32_766 else 65_535
            (items.size.toLong() * columns.toLong()).requireLe(parameterLimit.toLong()) {
                "Multi-row VALUES limit exceeded: rows=${items.size}, columns=$columns, parameterLimit=$parameterLimit"
            }
        }
        withContext(Dispatchers.VT) {
            transaction(database) {
                if (useMultiRowValues) {
                    table.batchInsert(
                        items,
                        useMultiRowValues = true,
                        ignore = ignore,
                        shouldReturnGeneratedValues = true,
                    ) { item -> bind(item) }
                } else {
                    table.batchInsert(items, ignore = ignore) { item -> bind(item) }
                }
            }
        }
        log.debug { "batchInsert 완료: table=${table.tableName}, count=${items.size}" }
    }
}
