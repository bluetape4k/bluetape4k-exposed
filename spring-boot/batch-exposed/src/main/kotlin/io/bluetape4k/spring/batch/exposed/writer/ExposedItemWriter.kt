package io.bluetape4k.spring.batch.exposed.writer

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requireLe
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.statements.BatchInsertStatement
import org.jetbrains.exposed.v1.core.vendors.SQLiteDialect
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.springframework.batch.infrastructure.item.Chunk
import org.springframework.batch.infrastructure.item.ItemWriter

/**
 * Exposed `batchInsert` 기반 [ItemWriter].
 *
 * SpringTransactionManager가 제공하는 청크 트랜잭션에 참여한다.
 * 별도 트랜잭션을 만들거나 commit하지 않으며, 예외를 호출자에게 전파한다.
 * 기본 생성자는 기존 driver-level batch 경로를 유지한다.
 * `useMultiRowValues = true`는 여러 행을 하나의 VALUES SQL로 전달한다.
 * 빈 청크는 트랜잭션 없이도 no-op이며, 생성 키를 요청하거나 반환하지 않는다.
 * 중복 무시와 자동 청크 분할은 지원하지 않는다.
 *
 * multi-row 경로는 바인더와 SQL 실행 전에 `행 수 × 전체 테이블 컬럼 수`를 검사한다.
 * 추정 한도는 일반 방언 65,535개, SQLite 32,766개이며 초과 시
 * [IllegalArgumentException]을 던진다. 이는 모든 driver의 실제 bind 한도를 보장하지 않는다.
 * H2/PostgreSQL을 검증 대상으로 하며 다른 방언은 upstream 지원 여부를 확인해야 한다.
 *
 * 사용 예시:
 * ```kotlin
 * val writer = ExposedItemWriter(table = TargetTable) {
 *     this[TargetTable.name] = it.name
 *     this[TargetTable.value] = it.value
 * }
 * ```
 *
 * @param T 입력 타입
 * @param table 대상 Exposed [Table]
 * @param useMultiRowValues multi-row VALUES 사용 여부. 기존 생성자는 false이다.
 * @param insertBody `batchInsert` 람다
 */
class ExposedItemWriter<T : Any>(
    private val table: Table,
    private val useMultiRowValues: Boolean,
    private val insertBody: BatchInsertStatement.(T) -> Unit,
) : ItemWriter<T> {

    /** 기존 Kotlin trailing-lambda 호출과 JVM 생성자를 유지한다. */
    constructor(
        table: Table,
        insertBody: BatchInsertStatement.(T) -> Unit,
    ) : this(table, false, insertBody)

    companion object : KLogging()

    override fun write(chunk: Chunk<out T>) {
        if (chunk.isEmpty) return

        val items = chunk.items

        if (useMultiRowValues) {
            val columns = table.columns.size
            val dialect = TransactionManager.current().db.dialect
            val parameterLimit = if (dialect is SQLiteDialect) 32_766 else 65_535
            (items.size.toLong() * columns.toLong()).requireLe(parameterLimit.toLong()) {
                "Multi-row VALUES limit exceeded: rows=${items.size}, columns=$columns, parameterLimit=$parameterLimit"
            }
            table.batchInsert(items, useMultiRowValues = true, shouldReturnGeneratedValues = false) { item ->
                insertBody(item)
            }
        } else {
            table.batchInsert(items, shouldReturnGeneratedValues = false) { item ->
                insertBody(item)
            }
        }

        log.debug { "${items.size}건 batchInsert 완료 (table=${table.tableName})" }
    }
}
