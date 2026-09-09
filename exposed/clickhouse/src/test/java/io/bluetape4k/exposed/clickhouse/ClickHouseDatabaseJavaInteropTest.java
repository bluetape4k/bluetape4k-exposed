package io.bluetape4k.exposed.clickhouse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.jetbrains.exposed.v1.jdbc.Database;
import org.junit.jupiter.api.Test;

class ClickHouseDatabaseJavaInteropTest {

    @Test
    void optionsOverloadsRemainDiscoverableFromJava() throws NoSuchMethodException {
        Method hostOverload = ClickHouseDatabase.class.getMethod(
            "connect",
            String.class,
            int.class,
            String.class,
            String.class,
            String.class,
            ClickHouseV2Options.class
        );
        Method jdbcUrlOverload = ClickHouseDatabase.class.getMethod(
            "connect",
            String.class,
            String.class,
            String.class,
            ClickHouseV2Options.class
        );
        Method existingHostOverload = ClickHouseDatabase.class.getMethod(
            "connect",
            String.class,
            int.class,
            String.class,
            String.class,
            String.class
        );
        Method existingJdbcUrlOverload = ClickHouseDatabase.class.getMethod(
            "connect",
            String.class,
            String.class,
            String.class
        );

        assertEquals(Database.class, hostOverload.getReturnType());
        assertEquals(Database.class, jdbcUrlOverload.getReturnType());
        assertEquals(Database.class, existingHostOverload.getReturnType());
        assertEquals(Database.class, existingJdbcUrlOverload.getReturnType());
        assertTrue(Modifier.isPublic(hostOverload.getModifiers()));
        assertTrue(Modifier.isPublic(jdbcUrlOverload.getModifiers()));
        assertTrue(Modifier.isPublic(existingHostOverload.getModifiers()));
        assertTrue(Modifier.isPublic(existingJdbcUrlOverload.getModifiers()));
    }
}
