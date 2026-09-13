@file:OptIn(InternalSnapshotCacheApi::class)

package io.bluetape4k.exposed.cache.snapshot

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import java.io.Serializable

class SnapshotCacheApiContractTest {

    companion object: KLogging()

    @Test
    fun `internal annotation is an error level opt in contract`() {
        val classBytes = InternalSnapshotCacheApi::class.java.getResourceAsStream("InternalSnapshotCacheApi.class")
            .shouldNotBeNull()
            .use { it.readBytes().decodeToString() }

        classBytes shouldContain "RequiresOptIn"
        classBytes shouldContain "ERROR"
    }

    @Test
    fun `lookup factories remain usable by opted in adapters`() {
        val hit = SnapshotCacheLookup.hit<Long, Payload>(CacheSnapshot(Payload("one")))
        val miss = SnapshotCacheLookup.miss<Long, Payload>()

        hit.snapshot?.value shouldBeEqualTo Payload("one")
        miss.miss.shouldNotBeNull()
    }

    @Test
    fun `common coordinator API does not expose backend transaction classes`() {
        val facade = Class.forName("io.bluetape4k.exposed.cache.snapshot.SnapshotTransactionCoordinatorKt")
        val signatures = facade.declaredMethods.joinToString("\n") { it.toGenericString() }

        signatures shouldNotContain "JdbcTransaction"
        signatures shouldNotContain "R2dbcTransaction"
        facade.declaredMethods.count { it.name == "stageInvalidationMutation" } shouldBeEqualTo 2
    }

    private data class Payload(val text: String): Serializable
}
