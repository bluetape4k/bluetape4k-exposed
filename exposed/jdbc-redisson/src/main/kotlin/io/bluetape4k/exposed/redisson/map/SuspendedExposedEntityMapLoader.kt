package io.bluetape4k.exposed.redisson.map

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.error
import io.bluetape4k.logging.trace
import io.bluetape4k.support.requirePositiveNumber
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.EntityIDColumnType
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * Exposed [IdTable]을 코루틴으로 조회해 Redisson 비동기 read-through에 공급하는 loader입니다.
 *
 * ## 동작/계약
 * - 단건 조회는 `selectAll().where { id eq ... }.singleOrNull()` 결과를 [toEntity]로 변환합니다.
 * - 전체 키 조회는 지원되는 표준 scalar PK의 keyset page 또는 custom ID의 offset fallback으로
 *   채널에 키를 전송합니다.
 * - 전체 열거는 weakly consistent하며, custom ID fallback 경로에서는 page 사이 삭제로
 *   아직 관찰하지 않은 row를 건너뛸 수 있습니다.
 * - [batchSize]가 0 이하이면 초기화 시 [IllegalArgumentException]이 발생합니다.
 * - 채널 전송 실패나 DB 오류는 로깅 후 예외를 그대로 전파합니다.
 * - 외부 channel 방출을 포함하는 producer transaction은 `maxAttempts = 1`로 실행되어
 *   Exposed retry가 이미 방출한 ID를 재생하지 않습니다.
 *
 * ```kotlin
 * val loader = SuspendedExposedEntityMapLoader(
 *     entityTable = LoaderTable,
 *     batchSize = 2,
 *     toEntity = { toLoaderEntity() },
 * )
 * // batchSize = 0 이면 IllegalArgumentException 발생
 * ```
 *
 * @param entityTable `EntityID<ID>` 를 id 컬럼으로 가진 [IdTable] 입니다.
 * @param scope CoroutineScope
 * @param batchSize 배치 사이즈
 * @param toEntity ResultRow 를 E 타입으로 변환하는 함수입니다.
 */
open class SuspendedExposedEntityMapLoader<ID: Any, E: Any>(
    private val entityTable: IdTable<ID>,
    scope: CoroutineScope = defaultMapLoaderCoroutineScope,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    private val toEntity: ResultRow.() -> E,
): SuspendedEntityMapLoader<ID, E>(
    loadByIdFromDB = { id: ID ->
        entityTable
            .selectAll()
            .where { entityTable.id eq id }
            .singleOrNull()
            ?.toEntity()
    },
    loadAllIdsFromDB = { channel ->
        var rowCount = 0
        var offset = 0L
        var lastId: ID? = null
        var keysetSupported: Boolean? = null
        var hasMore = true

        try {
            while (hasMore) {
                val cursor = lastId
                val query = entityTable.select(entityTable.id).orderBy(entityTable.id, SortOrder.ASC)
                val rows =
                    if (keysetSupported == true && cursor != null) {
                        query
                            .where { entityTable.rawIdColumn() greater cursor.asComparableKey() }
                            .limit(batchSize)
                            .toList()
                    } else {
                        query
                            .limit(batchSize)
                            .offset(offset)
                            .toList()
                    }

                if (rows.isEmpty()) {
                    hasMore = false
                } else {
                    for (row in rows) {
                        val id = row[entityTable.id].value
                        channel.send(id)
                        rowCount += 1
                    }
                    log.trace { "DB에서 모든 ID 로딩 중... 로딩된 id 수=$rowCount" }

                    val currentLastId = rows.last()[entityTable.id].value
                    if (keysetSupported == null) {
                        keysetSupported = currentLastId.isKeysetScalar()
                    }
                    if (keysetSupported == true) {
                        lastId = currentLastId
                    } else {
                        offset += rows.size.toLong()
                    }
                    hasMore = rows.size == batchSize
                }
            }
            log.debug { "DB에서 모든 ID 로딩 완료. 로딩된 id 수=$rowCount" }
        } catch (cause: kotlinx.coroutines.CancellationException) {
            throw cause
        } catch (cause: Throwable) {
            log.error { "DB에서 모든 ID 로딩 중 오류가 발생했습니다." }
            throw cause
        }
    },
    scope = scope
) {
    companion object: KLoggingChannel() {
        private const val DEFAULT_BATCH_SIZE = 1000
    }

    init {
        batchSize.requirePositiveNumber("batchSize")
    }

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
