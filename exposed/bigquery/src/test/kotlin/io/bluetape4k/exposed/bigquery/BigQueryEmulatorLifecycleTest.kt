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
        BigQueryEmulator.shouldReuseContainer(
            mapOf(
                "CI" to "true",
                BigQueryEmulator.REUSE_ENV to "true",
            )
        ).shouldBeFalse()
        BigQueryEmulator.createContainer(
            mapOf(
                "CI" to "true",
                BigQueryEmulator.REUSE_ENV to "true",
            )
        ).isShouldBeReused.shouldBeFalse()
    }
}
