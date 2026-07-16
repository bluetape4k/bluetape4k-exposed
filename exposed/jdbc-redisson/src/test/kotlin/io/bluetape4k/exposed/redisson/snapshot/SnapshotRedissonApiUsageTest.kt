package io.bluetape4k.exposed.redisson.snapshot

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.exposed.redisson.snapshot.readme.RedissonOrderSnapshot
import io.bluetape4k.exposed.redisson.snapshot.readme.orderSnapshotCodec
import org.junit.jupiter.api.Test
import org.redisson.client.codec.StringCodec
import org.redisson.client.handler.State
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class SnapshotRedissonApiUsageTest {

    @Test
    fun `canonical Redisson codec round trips the documented map value`() {
        val codec = orderSnapshotCodec()
        val expected = RedissonOrderSnapshot(7L, "ready")
        val encoded = codec.mapValueEncoder.encode(expected)
        try {
            val actual = codec.mapValueDecoder.decode(encoded, State())
            actual shouldBeEqualTo expected
        } finally {
            encoded.release()
        }
    }

    @Test
    fun `canonical Redisson README blocks equal the compiled fixture`() {
        val fixture = projectFile(
            "exposed/jdbc-redisson/src/test/kotlin/io/bluetape4k/exposed/redisson/snapshot/" +
                    "SnapshotRedissonReadmeFixture.kt",
        )
        Files.exists(fixture).shouldBeTrue()
        val expected = extractMarkedBlock(
            Files.readString(fixture),
            "// README-CANONICAL-REDISSON-BEGIN",
            "// README-CANONICAL-REDISSON-END",
        )

        listOf("exposed/jdbc-redisson/README.md", "exposed/jdbc-redisson/README.ko.md").forEach { readme ->
            val actual = extractMarkedBlock(
                Files.readString(projectFile(readme)),
                "<!-- README-CANONICAL-REDISSON-BEGIN -->",
                "<!-- README-CANONICAL-REDISSON-END -->",
            ).removePrefix("```kotlin\n").removeSuffix("\n```")

            actual shouldBeEqualTo expected
        }
    }

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

    @Test
    fun `public codec factory exposes only the exact three-argument signature`() {
        val factoryMethods = Class.forName(
            "io.bluetape4k.exposed.redisson.snapshot.SnapshotRedissonCodecKt",
        ).declaredMethods.filter { Modifier.isPublic(it.modifiers) && it.name == "snapshotRedissonCodec" }

        factoryMethods.size shouldBeEqualTo 1
        factoryMethods.single().parameterTypes.toList() shouldBeEqualTo listOf(
            org.redisson.client.codec.Codec::class.java,
            String::class.java,
            SnapshotIdentifierPolicy::class.java,
        )
    }

    private fun projectFile(relativePath: String): Path {
        val rootCandidate = Path.of(relativePath)
        if (Files.exists(rootCandidate)) return rootCandidate
        return Path.of("../..", relativePath).normalize()
    }

    private fun extractMarkedBlock(source: String, begin: String, end: String): String {
        val start = source.indexOf(begin)
        val finish = source.indexOf(end, startIndex = start + begin.length)
        check(start >= 0 && finish > start) { "Missing canonical README markers: $begin .. $end" }
        return source.substring(start + begin.length, finish).trim('\n', '\r')
    }
}
