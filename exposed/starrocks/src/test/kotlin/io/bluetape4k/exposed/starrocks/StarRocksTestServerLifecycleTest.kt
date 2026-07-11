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
        StarRocksTestServer.shouldReuseContainer(
            mapOf(
                "CI" to "true",
                StarRocksTestServer.REUSE_ENV to "true",
            )
        ).shouldBeFalse()
        StarRocksTestServer.create(
            mapOf(
                "CI" to "true",
                StarRocksTestServer.REUSE_ENV to "true",
            )
        ).reusable.shouldBeFalse()
    }
}
