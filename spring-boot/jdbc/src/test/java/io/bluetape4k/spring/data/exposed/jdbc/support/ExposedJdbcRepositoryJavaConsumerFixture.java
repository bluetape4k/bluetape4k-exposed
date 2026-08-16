package io.bluetape4k.spring.data.exposed.jdbc.support;

import io.bluetape4k.spring.data.exposed.jdbc.repository.support.ExposedEntityInformation;
import io.bluetape4k.spring.data.exposed.jdbc.repository.support.ExposedJdbcRepositoryFactory;
import io.bluetape4k.spring.data.exposed.jdbc.repository.support.SimpleExposedJdbcRepository;
import org.jetbrains.exposed.v1.dao.Entity;

final class ExposedJdbcRepositoryJavaConsumerFixture {

    private ExposedJdbcRepositoryJavaConsumerFixture() {
    }

    static <E extends Entity<ID>, ID> SimpleExposedJdbcRepository<E, ID> createRepository(
        ExposedEntityInformation<E, ID> entityInformation
    ) {
        return new SimpleExposedJdbcRepository<>(entityInformation);
    }

    static ExposedJdbcRepositoryFactory createFactory() {
        return new ExposedJdbcRepositoryFactory();
    }
}
