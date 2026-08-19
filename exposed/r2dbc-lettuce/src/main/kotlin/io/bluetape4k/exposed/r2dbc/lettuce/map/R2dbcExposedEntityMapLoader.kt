package io.bluetape4k.exposed.r2dbc.lettuce.map

import io.bluetape4k.support.requirePositiveNumber
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.EntityIDColumnType
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * Exposed R2DBC DSL을 사용해 DB에서 엔티티를 로드하는 [R2dbcEntityMapLoader] 구현체.
 *
 * `suspendTransaction` 내에서 실행되며 `runBlocking` 없이 코루틴 네이티브로 동작한다.
 * [loadAllIds]는 대용량 테이블을 위해 [batchSize] 단위로 페이징하며, PK 오름차순으로 모든 키를 로드한다.
 * 전체 열거는 weakly consistent하며, custom ID offset fallback 경로에서는 page 사이 삭제로
 * 아직 관찰하지 않은 row를 건너뛸 수 있다.
 *
 * ### 사용 예시
 * ```kotlin
 * val loader = R2dbcExposedEntityMapLoader(
 *     table = UserTable,
 *     toEntity = { toUserRecord() },
 *     batchSize = 500,
 * )
 * val user: UserRecord? = loader.load(userId)
 * val allIds: List<Long> = loader.loadAllKeys()
 * ```
 *
 * @param ID PK 타입. 지원되는 표준 scalar 타입은 keyset, 그 외 타입은 offset fallback을 사용한다.
 * @param E 반환 엔티티(DTO) 타입
 * @param table Exposed [IdTable]
 * @param toEntity [ResultRow] → [E] 변환 suspend 함수
 * @param batchSize 페이징 배치 크기 (기본: 1000). 0 이하이면 [IllegalArgumentException] 발생
 * @see R2dbcEntityMapLoader
 */
class R2dbcExposedEntityMapLoader<ID: Any, E: Any>(
    private val table: IdTable<ID>,
    private val toEntity: suspend ResultRow.() -> E,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
): R2dbcEntityMapLoader<ID, E>() {
    companion object {
        private const val DEFAULT_BATCH_SIZE = 1000
    }

    init {
        batchSize.requirePositiveNumber("batchSize")
    }

    /**
     * [id]에 해당하는 단일 엔티티를 DB에서 조회한다. 존재하지 않으면 `null`을 반환한다.
     */
    override suspend fun loadById(id: ID): E? =
        table
            .selectAll()
            .where { table.id eq id }
            .singleOrNull()
            ?.toEntity()

    /**
     * 테이블의 모든 PK를 [batchSize] 단위로 페이징하여 반환한다.
     */
    override suspend fun loadAllIds(): List<ID> =
        buildList {
            val sourceTable = table
            var offset = 0L
            var lastId: ID? = null
            var keysetSupported: Boolean? = null
            while (true) {
                val batch = loadR2dbcPage(sourceTable, batchSize, lastId, offset, keysetSupported)
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

    /**
     * 전체 PK를 page 단위 transaction으로 emit한다.
     *
     * downstream이 느리거나 취소되면 현재 page 이후 새 transaction을 열지 않는다.
     * ambient caller-owned transaction이 없으면 page마다 `suspendTransaction`을 열며,
     * 활성 transaction이 있으면 Exposed의 ambient transaction 재사용 규칙을 따른다.
     * 지원되는 표준 scalar PK에서는 keyset 경계를 사용하고, custom ID는 offset fallback을
     * 사용한다. 전체 열거는 weakly consistent하며, fallback 경로에서는 page 사이 삭제로
     * 아직 관찰하지 않은 row를 건너뛸 수 있다.
     */
    override fun loadAllKeysFlow(): Flow<ID> {
        val sourceTable = table
        val pageSize = batchSize
        return flow {
            var offset = 0L
            var lastId: ID? = null
            var keysetSupported: Boolean? = null
            var exhausted = false

            while (!exhausted) {
                val page =
                    suspendTransaction {
                        loadR2dbcPage(sourceTable, pageSize, lastId, offset, keysetSupported)
                    }
                if (page.isEmpty()) break

                for (id in page) {
                    emit(id)
                }
                exhausted = page.size < pageSize

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
        }
    }
}

private suspend fun <ID: Any> loadR2dbcPage(
    table: IdTable<ID>,
    batchSize: Int,
    lastId: ID?,
    offset: Long,
    keysetSupported: Boolean?,
): List<ID> {
    val query = table.select(table.id).orderBy(table.id, SortOrder.ASC)
    val pagedQuery =
        if (keysetSupported == true && lastId != null) {
            query.where { table.rawIdColumn() greater lastId.asComparableKey() }
        } else {
            query.offset(offset)
        }

    return pagedQuery
        .limit(batchSize)
        .map { it[table.id].value }
        .toList()
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
