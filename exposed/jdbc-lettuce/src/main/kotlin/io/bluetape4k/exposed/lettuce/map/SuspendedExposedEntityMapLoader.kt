package io.bluetape4k.exposed.lettuce.map

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.EntityIDColumnType
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * Exposed JDBC DSL을 사용해 DB에서 엔티티를 로드하는 [SuspendedEntityMapLoader] 구현체.
 *
 * ```kotlin
 * val loader = SuspendedExposedEntityMapLoader(
 *     table = ActorTable,
 *     toEntity = { row -> ActorRecord(id = row[ActorTable.id].value, name = row[ActorTable.name]) }
 * )
 * // suspend 컨텍스트(예: runSuspendIO, coroutineScope)에서 호출한다
 * val actor = loader.load(1L)
 * ```
 *
 * @param ID PK 타입
 * @param E 반환 엔티티(DTO) 타입
 * @param table Exposed [IdTable]
 * @param toEntity [ResultRow] → [E] 변환 함수
 * @param batchSize 페이징 배치 크기. 0 이하여서는 안 되며, 표준 scalar PK는 keyset page,
 * custom PK는 offset fallback으로 PK 오름차순을 유지한다.
 *
 * 전체 키 조회는 `suspendedTransactionAsync` 안에서 page를 순서대로 읽어 하나의
 * `List`로 materialize한다. 호출자가 coroutine을 취소하면 JDBC transaction도 취소되며,
 * 완료되지 않은 partial list는 반환하지 않는다.
 */
class SuspendedExposedEntityMapLoader<ID: Any, E: Any>(
    private val table: IdTable<ID>,
    private val toEntity: (ResultRow) -> E,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
): SuspendedEntityMapLoader<ID, E>() {
    companion object {
        private const val DEFAULT_BATCH_SIZE = 1000
    }

    init {
        require(batchSize > 0) { "batchSize는 0보다 커야 합니다. batchSize=$batchSize" }
    }

    override fun loadById(id: ID): E? =
        table
            .selectAll()
            .where { table.id eq id }
            .singleOrNull()
            ?.let(toEntity)

    override fun loadAllIds(): List<ID> =
        buildList {
            var offset = 0L
            var lastId: ID? = null
            var keysetSupported: Boolean? = null
            while (true) {
                val cursor = lastId
                val query = table.select(table.id).orderBy(table.id, SortOrder.ASC)
                val batch =
                    if (keysetSupported == true && cursor != null) {
                        query
                            .where { table.rawIdColumn() greater cursor.asComparableKey() }
                            .limit(batchSize)
                            .map { it[table.id].value }
                    } else {
                        query
                            .limit(batchSize)
                            .offset(offset)
                            .map { it[table.id].value }
                    }
                addAll(batch)
                if (batch.size < batchSize) break

                val currentLastId = batch.last()
                if (keysetSupported == null) {
                    keysetSupported = currentLastId.isKeysetScalar()
                }
                if (keysetSupported == true) {
                    lastId = currentLastId
                } else {
                    offset += batch.size.toLong()
                }
            }
        }
}

@Suppress("UNCHECKED_CAST")
private fun <ID: Any> ID.asComparableKey(): Comparable<Any> =
    this as? Comparable<Any>
        ?: error("keyset paging requires a Comparable primary key")

@Suppress("UNCHECKED_CAST")
private fun <ID: Any> IdTable<ID>.rawIdColumn(): Column<Comparable<Any>> =
    ((id.columnType as EntityIDColumnType<ID>).idColumn as Column<Comparable<Any>>)
