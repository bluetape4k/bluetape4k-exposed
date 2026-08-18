package io.bluetape4k.exposed.lettuce.map

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.EntityIDColumnType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.collections.AbstractIterator

/**
 * Exposed DSL을 사용해 DB에서 엔티티를 로드하는 [EntityMapLoader] 구현체.
 *
 * ```kotlin
 * val loader = ExposedEntityMapLoader(
 *     table = ActorTable,
 *     toEntity = { row -> ActorRecord(id = row[ActorTable.id].value, name = row[ActorTable.name]) }
 * )
 * val actor = loader.load(1L)  // DB에서 조회
 * ```
 *
 * @param ID PK 타입
 * @param E 반환 엔티티(DTO) 타입
 * @param table Exposed [IdTable]
 * @param toEntity [ResultRow] → [E] 변환 함수
 * @param batchSize 페이징 배치 크기. 0 이하여서는 안 되며, PK 오름차순으로 모든 키를 순회한다.
 */
class ExposedEntityMapLoader<ID: Any, E: Any>(
    private val table: IdTable<ID>,
    private val toEntity: (ResultRow) -> E,
    private val batchSize: Int = 1000,
): EntityMapLoader<ID, E>() {
    init {
        require(batchSize > 0) { "batchSize는 0보다 커야 합니다. batchSize=$batchSize" }
    }

    override fun loadById(id: ID): E? =
        table
            .selectAll()
            .where { table.id eq id }
            .singleOrNull()
            ?.let(toEntity)

    /**
     * 전체 키를 lazy page로 반환한다.
     *
     * 지원되는 표준 scalar PK는 keyset 경계를 사용하고, custom PK는 offset fallback을
     * 사용한다. ambient caller-owned transaction이 없으면 각 page는 별도 transaction에서
     * 읽고, 활성 transaction이 있으면 Exposed의 ambient transaction 재사용 규칙을 따른다.
     * 어느 경우에도 Exposed Query와 JDBC connection이 반환된 Iterable 뒤로 탈출하지 않는다. 전체 열거는
     * weakly consistent하며, custom ID offset fallback 경로에서는 page 사이
     * 삭제로 아직 관찰하지 않은 row를 건너뛸 수 있다.
     */
    override fun loadAllKeys(): Iterable<ID> {
        val sourceTable = table
        val pageSize = batchSize
        return Iterable {
            object : AbstractIterator<ID>() {
                private var page: List<ID> = emptyList()
                private var pageIndex = 0
                private var lastId: ID? = null
                private var offset = 0L
                private var keysetSupported: Boolean? = null
                private var exhausted = false

                override fun computeNext() {
                    if (exhausted) {
                        done()
                        return
                    }

                    if (pageIndex >= page.size) {
                        page = loadJdbcPage(sourceTable, pageSize, lastId, offset, keysetSupported)
                        pageIndex = 0

                        if (page.isEmpty()) {
                            done()
                            return
                        }

                        val currentLastId = page.last()
                        if (keysetSupported == null) {
                            keysetSupported = currentLastId.isKeysetScalar()
                        }
                        if (keysetSupported == true) {
                            lastId = currentLastId
                        } else {
                            offset += page.size.toLong()
                        }
                    }

                    setNext(page[pageIndex++])
                    if (pageIndex >= page.size && page.size < pageSize) {
                        exhausted = true
                    }
                }
            }
        }
    }

    /** 기존 abstract surface를 사용하는 subclass를 위해 bounded 결과를 유지한다. */
    override fun loadAllIds(): Iterable<ID> = loadAllKeys()
}

private fun <ID: Any> loadJdbcPage(
    table: IdTable<ID>,
    batchSize: Int,
    lastId: ID?,
    offset: Long,
    keysetSupported: Boolean?,
): List<ID> = transaction {
    val query = table.select(table.id).orderBy(table.id, SortOrder.ASC)
    val pagedQuery =
        if (keysetSupported == true && lastId != null) {
            query.where { table.rawIdColumn() greater lastId.asComparableKey() }
        } else {
            query.offset(offset)
        }

    pagedQuery
        .limit(batchSize)
        .map { it[table.id].value }
}

@Suppress("UNCHECKED_CAST")
private fun <ID: Any> ID.asComparableKey(): Comparable<Any> =
    this as? Comparable<Any>
        ?: error("keyset paging requires a Comparable primary key")

@JvmSynthetic
internal fun Any.isKeysetScalar(): Boolean =
    this is Comparable<*> &&
        when (this) {
            is Byte, is Short, is Int, is Long, is Float, is Double,
            is UByte, is UShort, is UInt, is ULong,
            is java.math.BigDecimal, is java.math.BigInteger,
            is String, is Char, is java.util.UUID,
            is java.sql.Date, is java.sql.Time, is java.sql.Timestamp -> true
            else -> javaClass.name.startsWith("java.time.")
        }

@Suppress("UNCHECKED_CAST")
private fun <ID: Any> IdTable<ID>.rawIdColumn(): Column<Comparable<Any>> =
    ((id.columnType as EntityIDColumnType<ID>).idColumn as Column<Comparable<Any>>)
