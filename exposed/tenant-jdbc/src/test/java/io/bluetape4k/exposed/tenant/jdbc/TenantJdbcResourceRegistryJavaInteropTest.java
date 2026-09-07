package io.bluetape4k.exposed.tenant.jdbc;

import kotlin.Unit;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.functions.Function2;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TenantJdbcResourceRegistryJavaInteropTest {

    @Test
    void rejectsNullTenantBeforeCallingFactory() {
        AtomicInteger calls = new AtomicInteger();
        IllegalArgumentException failure = assertThrows(
            IllegalArgumentException.class,
            () -> TenantJdbcResourceRegistry.create(
                Arrays.asList("a", null),
                tenant -> {
                    calls.incrementAndGet();
                    return TenantJdbcTestFixtures.dataSource("java-null-key");
                },
                (tenant, dataSource) -> Unit.INSTANCE
            )
        );
        assertEquals("Tenant key must not be null.", failure.getMessage());
        assertEquals(0, calls.get());
    }

    @Test
    void rejectsNullFactoryResultAndNullLookup() {
        Function1<String, DataSource> nullFactory = tenant -> null;
        IllegalArgumentException factoryFailure = assertThrows(
            IllegalArgumentException.class,
            () -> TenantJdbcResourceRegistry.create(
                List.of("a"),
                nullFactory,
                (Function2<String, DataSource, Unit>) (tenant, dataSource) -> Unit.INSTANCE
            )
        );
        assertEquals("dataSourceFactory returned null.", factoryFailure.getMessage());

        TenantJdbcResourceRegistry<String> registry = TenantJdbcResourceRegistry.create(
            List.of("a"),
            tenant -> TenantJdbcTestFixtures.dataSource("java-null-lookup"),
            (tenant, dataSource) -> Unit.INSTANCE
        );
        try (registry) {
            assertThrows(NullPointerException.class, () -> registry.resourceFor(null));
        }
    }
}
