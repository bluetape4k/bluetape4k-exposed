package io.bluetape4k.spring.data.exposed.r2dbc.repository

import kotlinx.coroutines.flow.Flow
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.data.domain.Sort
import kotlin.reflect.KClass

/**
 * Exposed R2DBC Query by Example을 위한 coroutine-native fluent query입니다.
 *
 * 모든 연산은 immutable query plan을 반환하며, [all]의 [Flow]는 수집 시점에 실행됩니다.
 */
interface ExposedCoroutineFluentQuery<T: Any> {

    /** 정렬 조건을 추가한 query를 반환합니다. */
    fun sortBy(sort: Sort): ExposedCoroutineFluentQuery<T>

    /** 최대 결과 수를 제한한 query를 반환합니다. */
    fun limit(limit: Int): ExposedCoroutineFluentQuery<T>

    /** 결과 projection 타입을 변경한 query를 반환합니다. */
    fun <R: Any> asType(resultType: KClass<R>): ExposedCoroutineFluentQuery<R>

    /** 선택할 property 집합을 지정한 query를 반환합니다. */
    fun project(vararg properties: String): ExposedCoroutineFluentQuery<T>

    /** 정확히 하나의 결과를 반환하며, 결과가 없으면 null입니다. */
    suspend fun one(): T?

    /** 첫 번째 결과를 반환하며, 결과가 없으면 null입니다. */
    suspend fun first(): T?

    /** 결과를 Flow로 반환합니다. */
    fun all(): Flow<T>

    /** pageable 기준의 Page를 반환합니다. */
    suspend fun page(pageable: Pageable): Page<T>

    /** pageable 기준의 Slice를 반환합니다. */
    suspend fun slice(pageable: Pageable): Slice<T>

    /** 현재 query의 결과 수를 반환합니다. */
    suspend fun count(): Long

    /** 현재 query의 결과가 존재하는지 반환합니다. */
    suspend fun exists(): Boolean
}
