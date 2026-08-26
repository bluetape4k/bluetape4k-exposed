package io.bluetape4k.exposed.r2dbc.redisson.map

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.error
import io.bluetape4k.support.requirePositiveNumber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.EntityIDColumnType
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll

/**
 * Exposed [IdTable]에서 엔티티/ID를 읽어 Redisson read-through에 공급하는 R2DBC loader입니다.
 *
 * ## 동작/계약
 * - 단건 조회는 `selectAll().where { id eq ... }.singleOrNull()` 결과를 [toEntity]로 변환합니다.
 * - 전체 키 조회는 표준 scalar PK의 keyset page 또는 custom PK의 offset fallback으로
 *   [batchSize] 단위 키를 채널에 전송합니다.
 * - rendezvous channel back-pressure로 한 번에 한 ID만 소비자에게 전달하며, page 사이
 *   mutation에는 weakly consistent semantics를 적용합니다. caller가 주입한 scope의
 *   cancellation과 producer 오류/timeout 전파, top-level/ambient retry 경계는
 *   [R2dbcEntityMapLoader] 계약을 따릅니다.
 * - [batchSize]가 0 이하이면 초기화 시 [IllegalArgumentException]이 발생합니다.
 * - 테스트 기준으로 `batchSize=2`일 때도 3건 키를 모두 로드합니다.
 *
 * ```kotlin
 * val loader = R2dbcExposedEntityMapLoader(
 *     entityTable = LoaderTable,
 *     batchSize = 2,
 * ) { toLoaderEntity() }
 * val ids = loader.loadAllKeys().toList()
 * // ids.size == 3
 * ```
 *
 * @param entityTable `EntityID<ID>` 를 id 컬럼으로 가진 [IdTable] 입니다.
 * @param scope CoroutineScope
 * @param batchSize 배치 사이즈
 * @param toEntity ResultRow 를 E 타입으로 변환하는 함수입니다.
 */
open class R2dbcExposedEntityMapLoader<ID: Any, E: Any>(
    private val entityTable: IdTable<ID>,
    scope: CoroutineScope = newR2dbcMapCoroutineScope("R2dbc-Loader"),
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    private val toEntity: suspend ResultRow.() -> E,
): R2dbcEntityMapLoader<ID, E>(
    loadByIdFromDB = { id: ID ->
        entityTable
            .selectAll()
            .where { entityTable.id eq id }
            .singleOrNull()
            ?.toEntity()
    },
    loadAllIdsFromDB = { channel ->
        // 성능 문제를 피하기 위해 배치 단위로 모든 ID를 로드합니다.

        var loadedIds = 0
        var offset = 0L
        var lastId: ID? = null
        var keysetSupported: Boolean? = null
        var hasMore = true

        try {
            while (hasMore) {
                val cursor = lastId
                val query = entityTable.select(entityTable.id).orderBy(entityTable.id, SortOrder.ASC)
                val chunk =
                    if (keysetSupported == true && cursor != null) {
                        query
                            .where { entityTable.rawIdColumn() greater cursor.asComparableKey() }
                            .limit(batchSize)
                            .map { it[entityTable.id].value }
                            .toList()
                    } else {
                        query
                            .limit(batchSize)
                            .offset(offset)
                            .map { it[entityTable.id].value }
                            .toList()
                    }

                if (chunk.isEmpty()) {
                    hasMore = false
                } else {
                    chunk.forEach { id ->
                        loadedIds++
                        channel.send(id)
                    }
                    val currentLastId = chunk.last()
                    if (keysetSupported == null) {
                        keysetSupported = currentLastId.isKeysetScalar()
                    }
                    if (keysetSupported == true) {
                        lastId = currentLastId
                    } else {
                        offset += chunk.size.toLong()
                    }
                    log.debug { "DB에서 모든 ID 로딩 중... 로딩된 id 수=$loadedIds" }
                    hasMore = chunk.size == batchSize
                }
            }
            log.debug { "DB에서 모든 ID 로딩 완료. 로딩된 id 수=$loadedIds" }
        } catch (cause: CancellationException) {
            // 코루틴 취소는 반드시 재전파해야 한다 — 삼키면 구조적 동시성이 깨진다
            throw cause
        } catch (cause: Throwable) {
            log.error(cause) { "R2dbc를 이용한 모든 ID 로딩 중 오류가 발생했습니다." }
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
