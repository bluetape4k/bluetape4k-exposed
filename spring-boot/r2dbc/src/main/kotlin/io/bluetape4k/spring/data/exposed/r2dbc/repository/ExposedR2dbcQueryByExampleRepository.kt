package io.bluetape4k.spring.data.exposed.r2dbc.repository

import org.springframework.data.repository.NoRepositoryBean

/**
 * Exposed R2DBC CRUD repository에 coroutine-native Query by Example을 결합한 계약입니다.
 *
 * Reactive Query by Example executor를 상속하지 않으므로 R2DBC 저장소는 `Flow`와
 * `suspend` API만 노출합니다.
 */
@NoRepositoryBean
interface ExposedR2dbcQueryByExampleRepository<R: Any, ID: Any>:
    ExposedR2dbcRepository<R, ID>, ExposedCoroutineQueryByExampleExecutor<R>
