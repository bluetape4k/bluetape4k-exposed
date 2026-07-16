@file:OptIn(InternalSnapshotCacheApi::class)

package io.bluetape4k.exposed.cache.snapshot

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.Serializable

class SnapshotCacheApiContractTest {

    @Test
    fun `internal annotation is an error level opt in contract`() {
        val classBytes = InternalSnapshotCacheApi::class.java.getResourceAsStream("InternalSnapshotCacheApi.class")
            .shouldNotBeNull()
            .use { it.readBytes().decodeToString() }

        classBytes.contains("RequiresOptIn").shouldBeEqualTo(true)
        classBytes.contains("ERROR").shouldBeEqualTo(true)
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

        signatures.contains("JdbcTransaction").shouldBeFalse()
        signatures.contains("R2dbcTransaction").shouldBeFalse()
        facade.declaredMethods.count { it.name == "stageInvalidationMutation" } shouldBeEqualTo 2
    }

    private data class Payload(val text: String) : Serializable
}
