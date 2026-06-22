package io.bluetape4k.exposed.benchmark.cache

import io.lettuce.core.RedisClient
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.redisson.Redisson
import org.redisson.api.RedissonClient
import org.redisson.config.Config
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
class RedisCacheBenchmark {

    @Param("1000")
    var cacheSize: Int = 1000

    @Param("redis://127.0.0.1:6379")
    lateinit var redisUri: String

    private lateinit var lettuceClient: RedisClient
    private lateinit var lettuceConnection: io.lettuce.core.api.StatefulRedisConnection<String, String>
    private lateinit var redissonClient: RedissonClient
    private val cursor = AtomicLong()

    @Setup(Level.Trial)
    fun setup() {
        lettuceClient = RedisClient.create(redisUri)
        lettuceConnection = lettuceClient.connect()
        redissonClient = Redisson.create(
            Config().apply {
                useSingleServer().address = redisUri
            },
        )

        val sync = lettuceConnection.sync()
        repeat(cacheSize) { index ->
            val key = key(index.toLong())
            val value = "entity-$index"
            sync.set(key, value)
            redissonClient.getBucket<String>(key).set(value)
        }
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        if (::redissonClient.isInitialized) {
            redissonClient.shutdown()
        }
        if (::lettuceConnection.isInitialized) {
            lettuceConnection.close()
        }
        if (::lettuceClient.isInitialized) {
            lettuceClient.shutdown()
        }
    }

    @Benchmark
    fun lettuceGet(): String =
        lettuceConnection.sync().get(key(nextId()))

    @Benchmark
    fun redissonGet(): String =
        redissonClient.getBucket<String>(key(nextId())).get()

    private fun nextId(): Long =
        Math.floorMod(cursor.getAndIncrement(), cacheSize.toLong())

    private fun key(id: Long): String =
        "exposed-benchmark:cache:$id"
}
