package io.bluetape4k.exposed.redisson.snapshot

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import org.redisson.client.codec.StringCodec
import java.lang.reflect.Modifier
import java.util.UUID

class SnapshotRedissonApiUsageTest {

    @Test
    fun `Long and UUID scalar policy source usage compiles`() {
        val longPolicy: SnapshotIdentifierPolicy<Long> = longSnapshotIdentifierPolicy()
        val uuidPolicy: SnapshotIdentifierPolicy<UUID> = uuidSnapshotIdentifierPolicy()
        val longCodec: SnapshotRedissonCodec<Long> = snapshotRedissonCodec(StringCodec(), "json-v1", longPolicy)
        val uuidCodec: SnapshotRedissonCodec<UUID> = snapshotRedissonCodec(StringCodec(), "json-v1", uuidPolicy)

        longCodec.codecVersion shouldBeEqualTo "json-v1"
        uuidCodec.codecVersion shouldBeEqualTo "json-v1"
    }

    @Test
    fun `public API provides no String identifier policy or factory`() {
        val policyMethods = Class.forName(
            "io.bluetape4k.exposed.redisson.snapshot.SnapshotIdentifierPolicyKt",
        ).declaredMethods.filter { Modifier.isPublic(it.modifiers) }
        val signatures = policyMethods.joinToString("\n") { it.toGenericString() }

        policyMethods.count { it.name == "longSnapshotIdentifierPolicy" } shouldBeEqualTo 1
        policyMethods.count { it.name == "uuidSnapshotIdentifierPolicy" } shouldBeEqualTo 1
        signatures.contains("String").shouldBeFalse()
        SnapshotIdentifierPolicy::class.isSealed.shouldBeTrue()
    }
}
