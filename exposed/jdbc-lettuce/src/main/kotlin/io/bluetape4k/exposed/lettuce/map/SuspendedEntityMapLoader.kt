package io.bluetape4k.exposed.lettuce.map

import io.bluetape4k.exposed.jdbc.suspendTransactionWithIO
import io.bluetape4k.redis.lettuce.map.SuspendedMapLoader
import kotlinx.coroutines.Dispatchers

/**
 * JDBC `suspendedTransactionAsync`를 사용해 DB에서 엔티티를 로드하는 추상 [SuspendedMapLoader] 구현체.
 *
 * Blocking JDBC I/O를 [Dispatchers.IO]에서 안전하게 실행한다.
 *
 * ```kotlin
 * class MyLoader : SuspendedEntityMapLoader<Long, MyEntity>() {
 *     override fun loadById(id: Long): MyEntity? =
 *         MyTable.selectAll().where { MyTable.id eq id }.singleOrNull()?.toMyEntity()
 *     override fun loadAllIds(): List<Long> =
 *         MyTable.select(MyTable.id).map { it[MyTable.id].value }
 * }
 * ```
 *
 * @param ID 키 타입
 * @param E 엔티티 타입
 */
abstract class SuspendedEntityMapLoader<ID: Any, E: Any>: SuspendedMapLoader<ID, E> {

    override suspend fun load(key: ID): E? =
        suspendTransactionWithIO { loadById(key) }

    override suspend fun loadAllKeys(): List<ID> =
        suspendTransactionWithIO { loadAllIds() }

    /**
     * 주어진 [id]에 해당하는 엔티티를 DB에서 로드한다.
     */
    protected abstract fun loadById(id: ID): E?

    /**
     * DB에 존재하는 모든 키를 반환한다.
     */
    protected abstract fun loadAllIds(): List<ID>
}
