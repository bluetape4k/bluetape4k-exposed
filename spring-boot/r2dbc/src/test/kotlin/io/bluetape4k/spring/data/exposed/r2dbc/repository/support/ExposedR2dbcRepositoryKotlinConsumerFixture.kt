package io.bluetape4k.spring.data.exposed.r2dbc.repository.support

import io.bluetape4k.spring.data.exposed.r2dbc.domain.User
import io.bluetape4k.spring.data.exposed.r2dbc.repository.ExposedR2dbcQueryByExampleRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import org.springframework.data.domain.Example
import org.springframework.data.domain.Sort

/**
 * 공개 coroutine-native QBE 계약을 외부 Kotlin 소비자 관점에서 컴파일하는 fixture입니다.
 * 실행 가능한 repository를 만들지 않고 public method set과 generic 반환 타입만 고정합니다.
 */
object ExposedR2dbcRepositoryKotlinConsumerFixture {

    suspend fun compile(
        repository: ExposedR2dbcQueryByExampleRepository<User, Long>,
        example: Example<User>,
    ): List<NameProjection> {
        repository.findOne(example)
        repository.findAll(example)
        repository.findAll(example, Sort.by("name"))
        repository.count(example)
        repository.exists(example)

        val names: Flow<NameProjection> = repository.findBy(example) { query ->
            query
                .asType(NameProjection::class)
                .project("name")
                .all()
        }
        return names.toList()
    }

    interface NameProjection {
        val name: String
    }
}
