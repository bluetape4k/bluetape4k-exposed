package io.bluetape4k.exposed.benchmark.cache

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
class CacheStrategyBenchmark {

    @Param("1000", "10000")
    var cacheSize: Int = 1000

    private lateinit var caffeine: Cache<Long, CacheEntity>
    private lateinit var nearCache: ConcurrentHashMap<Long, CacheEntity>
    private lateinit var remoteStore: ConcurrentHashMap<Long, CacheEntity>
    private val cursor = AtomicLong()

    @Setup(Level.Trial)
    fun setup() {
        caffeine = Caffeine.newBuilder()
            .maximumSize(cacheSize.toLong())
            .build()
        nearCache = ConcurrentHashMap(cacheSize)
        remoteStore = ConcurrentHashMap(cacheSize)

        repeat(cacheSize) { index ->
            val id = index.toLong()
            val entity = CacheEntity(id, "entity-$id", index)
            caffeine.put(id, entity)
            nearCache[id] = entity
            remoteStore[id] = entity
        }
    }

    @Benchmark
    fun localCaffeineHit(): CacheEntity =
        caffeine.getIfPresent(nextId()) ?: error("missing caffeine entry")

    @Benchmark
    fun nearCacheHit(): CacheEntity =
        nearCache[nextId()] ?: error("missing near-cache entry")

    @Benchmark
    fun nearCacheReadThroughMiss(): CacheEntity {
        val id = nextMissId()
        nearCache.remove(id)
        return nearCache.computeIfAbsent(id) { key ->
            remoteStore[key] ?: CacheEntity(key, "remote-$key", key.toInt())
        }
    }

    private fun nextId(): Long =
        Math.floorMod(cursor.getAndIncrement(), cacheSize.toLong())

    private fun nextMissId(): Long =
        Math.floorMod(cursor.getAndIncrement(), cacheSize.toLong())
}

data class CacheEntity(
    val id: Long,
    val name: String,
    val value: Int,
)
