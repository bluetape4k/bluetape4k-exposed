package io.bluetape4k.exposed.r2dbc.lettuce.map

import io.bluetape4k.redis.lettuce.map.SuspendedMapLoader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * R2DBC `suspendTransaction`을 사용해 DB에서 엔티티를 로드하는 추상 [SuspendedMapLoader] 구현체.
 *
 * `runBlocking` 없이 코루틴 네이티브로 동작한다.
 * [ExposedR2dbcLettuceSuspendedLoadedMap]이 캐시 미스 시 이 로더를 호출하여 DB에서 엔티티를 로드한다.
 *
 * ### 사용 예시
 * ```kotlin
 * class MyLoader(table: IdTable<Long>) : R2dbcEntityMapLoader<Long, MyEntity>() {
 *     override suspend fun loadById(id: Long): MyEntity? = ...
 *     override suspend fun loadAllIds(): List<Long> = ...
 * }
 * ```
 *
 * @param ID 키(PK) 타입
 * @param E 엔티티 타입
 * @see SuspendedMapLoader
 * @see R2dbcExposedEntityMapLoader
 */
abstract class R2dbcEntityMapLoader<ID: Any, E: Any>: SuspendedMapLoader<ID, E> {
    /**
     * [key]에 해당하는 엔티티를 `suspendTransaction` 내에서 DB에서 로드한다.
     * 캐시 미스 시 [ExposedR2dbcLettuceSuspendedLoadedMap]에 의해 호출된다.
     */
    override suspend fun load(key: ID): E? = suspendTransaction { loadById(key) }

    /**
     * DB에 존재하는 모든 PK 목록을 `suspendTransaction` 내에서 로드한다.
     */
    override suspend fun loadAllKeys(): List<ID> = suspendTransaction { loadAllIds() }

    /**
     * DB의 모든 PK를 streaming 방식으로 소비한다.
     *
     * 기본 구현은 기존 [loadAllKeys] 결과를 Flow로 감싼 호환 경로다. Exposed
     * concrete loader는 이 메서드를 override해 page 단위 transaction과 keyset 경계를
     * 사용한다. Flow 수집 중 cancellation은 호출자에게 재전파된다.
     */
    open fun loadAllKeysFlow(): Flow<ID> = flow {
        for (id in loadAllKeys()) {
            emit(id)
        }
    }

    /**
     * 주어진 [id]에 해당하는 엔티티를 DB에서 로드한다.
     * 서브클래스에서 실제 쿼리 로직을 구현한다.
     */
    protected abstract suspend fun loadById(id: ID): E?

    /**
     * DB에 존재하는 모든 PK를 반환한다.
     * 서브클래스에서 실제 쿼리 로직을 구현한다.
     */
    protected abstract suspend fun loadAllIds(): List<ID>
}
