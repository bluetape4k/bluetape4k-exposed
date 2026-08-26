package issue731.consumer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConsumerTest {
    @Test
    fun runsWithoutJackson() {
        assertEquals("without-jackson", runtimeProbe())
    }

    @Test
    fun keepsOptionalAndBackendDependenciesOffRuntimeClasspath() {
        listOf(
            "tools.jackson.databind.json.JsonMapper",
            "com.fasterxml.jackson.databind.ObjectMapper",
            "org.jetbrains.exposed.sql.Database",
            "io.r2dbc.spi.ConnectionFactory",
        ).forEach { className ->
            assertFailsWith<ClassNotFoundException> { Class.forName(className) }
        }
    }
}
