package io.bluetape4k.spring.data.exposed.r2dbc.repository.support;

import io.bluetape4k.spring.data.exposed.r2dbc.domain.UserNameRecord;

/** Java classpath fixture for the projection target; no Java-only QBE overload is required. */
public final class ExposedR2dbcRepositoryJavaConsumerFixture {

    private ExposedR2dbcRepositoryJavaConsumerFixture() {
    }

    public static Class<UserNameRecord> projectionType() {
        return UserNameRecord.class;
    }
}
