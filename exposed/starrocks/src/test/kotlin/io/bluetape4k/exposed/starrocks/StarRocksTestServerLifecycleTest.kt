package io.bluetape4k.exposed.starrocks

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test

class StarRocksTestServerLifecycleTest {

    @Test
    fun `container reuse is disabled by default`() {
        StarRocksTestServer.shouldReuseContainer(emptyMap()).shouldBeFalse()
        StarRocksTestServer.create(emptyMap()).reusable.shouldBeFalse()
    }

    @Test
    fun `container reuse requires explicit local opt in`() {
        StarRocksTestServer.shouldReuseContainer(
            mapOf(StarRocksTestServer.REUSE_ENV to "true")
        ).shouldBeTrue()
        StarRocksTestServer.create(
            mapOf(StarRocksTestServer.REUSE_ENV to "true")
        ).reusable.shouldBeTrue()
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
            val environment = ciMarker + (StarRocksTestServer.REUSE_ENV to "true")

            StarRocksTestServer.shouldReuseContainer(environment).shouldBeFalse()
            StarRocksTestServer.create(environment).reusable.shouldBeFalse()
        }
    }

    @Test
    fun `reusable container is not registered for shutdown`() {
        var registered = false
        val server = StarRocksTestServer.create(
            mapOf(StarRocksTestServer.REUSE_ENV to "true")
        )

        server.registerShutdownIfNeeded { registered = true }

        registered.shouldBeFalse()
    }

    @Test
    fun `non reusable container is registered for shutdown`() {
        var registered = false
        val server = StarRocksTestServer.create(emptyMap())

        server.registerShutdownIfNeeded { registered = true }

        registered.shouldBeTrue()
    }
}
