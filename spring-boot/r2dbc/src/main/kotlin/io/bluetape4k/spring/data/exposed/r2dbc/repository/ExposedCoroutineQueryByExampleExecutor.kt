package io.bluetape4k.spring.data.exposed.r2dbc.repository

import kotlinx.coroutines.flow.Flow
import org.springframework.data.domain.Example
import org.springframework.data.domain.Sort
import org.springframework.data.repository.NoRepositoryBean

/**
 * Spring Data의 Reactor 타입에 의존하지 않는 coroutine-native Query by Example 계약입니다.
 *
 * 결과 [Flow]는 호출 시점에 쿼리를 실행하지 않고, 수집 시점에 현재 coroutine context에서
 * 실행됩니다. 따라서 호출자는 Flow를 수집하는 범위의 transaction과 resource 수명을 소유합니다.
 */
@NoRepositoryBean
interface ExposedCoroutineQueryByExampleExecutor<T: Any> {

    /** Example과 일치하는 단일 결과를 반환하며, 다중 결과는 예외로 보고합니다. */
    suspend fun findOne(example: Example<T>): T?

    /** Example과 일치하는 모든 결과를 반환합니다. */
    fun findAll(example: Example<T>): Flow<T>

    /** Example과 일치하는 결과를 [sort] 순서로 반환합니다. */
    fun findAll(example: Example<T>, sort: Sort): Flow<T>

    /** Example과 일치하는 결과 수를 반환합니다. */
    suspend fun count(example: Example<T>): Long

    /** Example과 일치하는 결과가 존재하는지 반환합니다. */
    suspend fun exists(example: Example<T>): Boolean

    /** Example 기반 fluent query를 실행합니다. */
    suspend fun <Q> findBy(
        example: Example<T>,
        queryFunction: suspend (ExposedCoroutineFluentQuery<T>) -> Q,
    ): Q
}
