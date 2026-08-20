package io.bluetape4k.exposed.redisson.map

import io.bluetape4k.exposed.jdbc.JdbcKeyRange
import io.bluetape4k.exposed.jdbc.JdbcParallelKeyEnumerationOptions
import io.bluetape4k.exposed.jdbc.parallelJdbcKeyEnumeration
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.error
import io.bluetape4k.support.requirePositiveNumber
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
 * Exposed [IdTable]에서 엔티티를 읽어 Redisson read-through에 공급하는 [EntityMapLoader] 구현입니다.
 *
 * ## 동작/계약
 * - 단건 조회는 `selectAll().where { id eq ... }.singleOrNull()` 결과를 [toEntity]로 변환합니다.
 * - 전체 키 조회는 표준 scalar PK의 keyset page 또는 custom PK의 offset fallback으로
 *   [batchSize] 단위 ID를 PK 오름차순으로 수집합니다.
 * - [loadAllKeys]는 하나의 JDBC transaction 안에서 모든 page를 순서대로 읽고,
 *   반환 전에 전체 ID를 materialize하는 [Iterable]입니다. page loop는 transaction
 *   경계 안에서 실행되며 전체 열거는 weakly consistent합니다.
 * - [batchSize]가 0 이하이면 초기화 시 [IllegalArgumentException]이 발생합니다.
 * - `loadAllKeys()`는 DB 오류를 로깅 후 예외를 다시 던집니다.
 *
 * ```kotlin
 * val loader = ExposedEntityMapLoader(
 *     entityTable = LoaderTable,
 *     batchSize = 2,
 *     toEntity = { toLoaderEntity() },
 * )
 * val ids = requireNotNull(loader.loadAllKeys()).toList()
 * // ids.size == 3
 * ```
 *
 * @param entityTable `EntityID<ID>` 를 id 컬럼으로 가진 [IdTable] 입니다.
 * @param batchSize 배치 사이즈
 * @param toEntity ResultRow를 엔티티로 변환하는 함수
 */
open class ExposedEntityMapLoader<ID: Any, E: Any>(
    private val entityTable: IdTable<ID>,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    private val toEntity: ResultRow.() -> E,
): EntityMapLoader<ID, E>(
    loadByIdFromDB = { id: ID ->
        entityTable
            .selectAll()
            .where { entityTable.id eq id }
            .singleOrNull()
            ?.toEntity()
    },
    loadAllIdsFromDB = {
        val loadedIds = mutableListOf<ID>()
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
                    } else {
                        query
                            .limit(batchSize)
                            .offset(offset)
                            .map { it[entityTable.id].value }
                    }

                if (chunk.isEmpty()) {
                    hasMore = false
                } else {
                    loadedIds += chunk
                    val currentLastId = chunk.last()
                    if (keysetSupported == null) {
                        keysetSupported = currentLastId.isKeysetScalar()
                    }
                    if (keysetSupported == true) {
                        lastId = currentLastId
                    } else {
                        offset += chunk.size.toLong()
                    }
                    log.debug { "DB에서 모든 ID 로딩 중... 로딩된 id 수=${loadedIds.size}" }
                    hasMore = chunk.size == batchSize
                }
            }

            log.debug { "DB에서 모든 ID 로딩 완료. 로딩된 id 수=${loadedIds.size}" }
            loadedIds
        } catch (cause: Throwable) {
            log.error { "DB에서 모든 ID 로딩 중 오류가 발생했습니다." }
            throw cause
        }
    }
) {
    companion object: KLogging() {
        private const val DEFAULT_BATCH_SIZE = 1000
    }

    init {
        batchSize.requirePositiveNumber("batchSize")
    }

    /**
     * 호출자가 명시한 PK range를 Virtual Thread JDBC transaction으로 병렬 열거합니다.
     *
     * 각 range는 독립 connection에서 읽고 입력 range 순서로 결과를 병합합니다.
     * 이 method는 opt-in materialization 경로이며 Redisson [MapLoader]의 기존
     * [loadAllKeys] callback, query timeout, sequential keyset/offset fallback을
     * 변경하지 않습니다. `maxConcurrency`는 caller JDBC connection pool보다 크게
     * 설정하지 않아야 하며, 전체 결과에 하나의 읽기 일관성 기준을 보장하지 않습니다.
     *
     * @param ranges `[lowerInclusive, upperExclusive)` PK 구간 목록
     * @param options executor, database, transaction isolation과 동시성 제한
     * @return range 선언 순서로 병합한 ID 목록
     */
    fun loadAllKeysInParallel(
        ranges: List<JdbcKeyRange<ID>>,
        options: JdbcParallelKeyEnumerationOptions<ID> = JdbcParallelKeyEnumerationOptions(),
    ): List<ID> = parallelJdbcKeyEnumeration(entityTable, ranges, options)
}

@Suppress("UNCHECKED_CAST")
private fun <ID: Any> ID.asComparableKey(): Comparable<Any> =
    this as? Comparable<Any>
        ?: error("keyset paging requires a Comparable primary key")

@Suppress("UNCHECKED_CAST")
private fun <ID: Any> IdTable<ID>.rawIdColumn(): Column<Comparable<Any>> =
    ((id.columnType as EntityIDColumnType<ID>).idColumn as Column<Comparable<Any>>)
