package io.bluetape4k.exposed.bigquery

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test

class BigQueryEmulatorLifecycleTest {

    @Test
    fun `container reuse is disabled by default`() {
        BigQueryEmulator.shouldReuseContainer(emptyMap()).shouldBeFalse()
        BigQueryEmulator.createContainer(emptyMap()).isShouldBeReused.shouldBeFalse()
    }

    @Test
    fun `container reuse requires explicit local opt in`() {
        BigQueryEmulator.shouldReuseContainer(
            mapOf(BigQueryEmulator.REUSE_ENV to "true")
        ).shouldBeTrue()
        BigQueryEmulator.createContainer(
            mapOf(BigQueryEmulator.REUSE_ENV to "true")
        ).isShouldBeReused.shouldBeTrue()
    }

    @Test
    fun `CI always disables container reuse`() {
        listOf(
            mapOf("CI" to "true"),
            mapOf("CI" to "1"),
            mapOf("CI" to "false"),
            mapOf("GITHUB_ACTIONS" to "true"),
            mapOf("GITHUB_ACTIONS" to "false"),
            mapOf("GITHUB_ACTIONS" to ""),
        ).forEach { ciMarker ->
            val environment = ciMarker + (BigQueryEmulator.REUSE_ENV to "true")

            BigQueryEmulator.shouldReuseContainer(environment).shouldBeFalse()
            BigQueryEmulator.createContainer(environment).isShouldBeReused.shouldBeFalse()
        }
    }

    @Test
    fun `reusable container is not registered for shutdown`() {
        var registered = false
        val container = BigQueryEmulator.createContainer(
            mapOf(BigQueryEmulator.REUSE_ENV to "true")
        )

        BigQueryEmulator.registerShutdownIfNeeded(container) { registered = true }

        registered.shouldBeFalse()
    }

    @Test
    fun `non reusable container is registered for shutdown`() {
        var registered = false
        val container = BigQueryEmulator.createContainer(emptyMap())

        BigQueryEmulator.registerShutdownIfNeeded(container) { registered = true }

        registered.shouldBeTrue()
    }
}
