package io.bluetape4k.spring.data.exposed.jdbc.support

import io.bluetape4k.spring.data.exposed.jdbc.repository.support.ExposedEntityInformation
import io.bluetape4k.spring.data.exposed.jdbc.repository.support.ExposedJdbcRepositoryFactory
import io.bluetape4k.spring.data.exposed.jdbc.repository.support.SimpleExposedJdbcRepository
import org.jetbrains.exposed.v1.dao.Entity

internal object ExposedJdbcRepositoryKotlinConsumerFixture {

    fun <E: Entity<ID>, ID: Any> createRepository(
        entityInformation: ExposedEntityInformation<E, ID>,
    ): SimpleExposedJdbcRepository<E, ID> = SimpleExposedJdbcRepository(entityInformation)

    fun createFactory(): ExposedJdbcRepositoryFactory = ExposedJdbcRepositoryFactory()
}
