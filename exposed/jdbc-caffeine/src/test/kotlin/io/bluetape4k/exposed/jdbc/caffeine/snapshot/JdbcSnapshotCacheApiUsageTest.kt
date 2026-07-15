package io.bluetape4k.exposed.jdbc.caffeine.snapshot

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.exposed.cache.snapshot.CacheSnapshot
import io.bluetape4k.exposed.cache.snapshot.CacheSnapshotMapper
import io.bluetape4k.exposed.cache.snapshot.CaffeineSnapshotCacheConfig
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheConfig
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.lang.reflect.Modifier

class JdbcSnapshotCacheApiUsageTest {

    @Test
    fun `README equivalent JDBC source compiles against the final surface`() {
        val cache = jdbcCaffeineSnapshotCache<Long, Payload>(
            CaffeineSnapshotCacheConfig(SnapshotCacheConfig("api-usage:v1", "payload-v1")),
        )
        val miss = requireNotNull(cache.lookup(1L).miss)
        val direct: JdbcTransaction.(CacheSnapshot<Payload>) -> CacheSnapshot<Payload> = { snapshot ->
            stageSnapshot(cache, miss, snapshot)
        }
        val mapped: JdbcTransaction.(String) -> CacheSnapshot<Payload> = { source ->
            stageSnapshot(cache, miss, source, CacheSnapshotMapper { CacheSnapshot(Payload(it)) })
        }
        val invalidate: JdbcTransaction.(Long) -> Unit = { id -> stageInvalidation(cache, id) }

        direct shouldBeEqualTo direct
        mapped shouldBeEqualTo mapped
        invalidate shouldBeEqualTo invalidate
    }

    @Test
    fun `public facade and extension signatures expose JDBC only`() {
        val facadeClass = Class.forName(
            "io.bluetape4k.exposed.jdbc.caffeine.snapshot.JdbcCaffeineSnapshotCache",
        )
        val factoryClass = Class.forName(
            "io.bluetape4k.exposed.jdbc.caffeine.snapshot.JdbcCaffeineSnapshotCacheKt",
        )
        val transactionClass = Class.forName(
            "io.bluetape4k.exposed.jdbc.caffeine.snapshot.JdbcSnapshotTransactionKt",
        )
        val publicConstructors = facadeClass.declaredConstructors.filter {
            Modifier.isPublic(it.modifiers) && !it.isSynthetic
        }
        val factorySignatures = factoryClass.declaredMethods.joinToString("\n") { it.toGenericString() }
        val transactionMethods = transactionClass.declaredMethods.filter { Modifier.isPublic(it.modifiers) }
        val transactionSignatures = transactionMethods.joinToString("\n") { it.toGenericString() }

        publicConstructors.isEmpty() shouldBeEqualTo true
        factoryClass.declaredMethods.count { it.name == "jdbcCaffeineSnapshotCache" } shouldBeEqualTo 2
        transactionMethods.count { it.name == "stageSnapshot" } shouldBeEqualTo 2
        transactionMethods.count { it.name == "stageInvalidation" } shouldBeEqualTo 1
        factorySignatures.contains("kotlin.reflect.KClass").shouldBeTrue()
        transactionSignatures.contains("JdbcTransaction").shouldBeTrue()
        transactionSignatures.contains("R2dbcTransaction").shouldBeFalse()
        transactionSignatures.contains("LocalCacheConfig").shouldBeFalse()
    }

    private data class Payload(val value: String) : Serializable
}
