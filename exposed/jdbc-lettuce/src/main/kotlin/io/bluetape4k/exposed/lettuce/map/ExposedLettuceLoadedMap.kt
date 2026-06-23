package io.bluetape4k.exposed.lettuce.map

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.error
import io.bluetape4k.logging.warn
import io.bluetape4k.redis.lettuce.map.LettuceCacheConfig
import io.bluetape4k.redis.lettuce.map.MapLoader
import io.bluetape4k.redis.lettuce.map.MapWriter
import io.bluetape4k.redis.lettuce.map.WriteMode
import io.lettuce.core.RedisClient
import io.lettuce.core.ScanArgs
import io.lettuce.core.ScanCursor
import io.lettuce.core.SetArgs
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.sync.RedisCommands
import io.lettuce.core.codec.RedisCodec
import io.lettuce.core.codec.StringCodec
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class ExposedLettuceLoadedMap<K: Any, V: Any>(
    private val client: RedisClient,
    private val loader: MapLoader<K, V>? = null,
    private val writer: MapWriter<K, V>? = null,
    private val config: LettuceCacheConfig = LettuceCacheConfig.READ_WRITE_THROUGH,
    private val keySerializer: (K) -> String = { it.toString() },
    valueCodec: RedisCodec<String, V>,
): Closeable {
    companion object: KLogging() {
        private const val MAX_DEAD_LETTER_RETRY = 3
    }

    private val connection: StatefulRedisConnection<String, V> = client.connect(valueCodec)
    private val commands: RedisCommands<String, V> = connection.sync()

    private val lazyStrConnection = lazy { client.connect(StringCodec.UTF8) }
    private val strConnection: StatefulRedisConnection<String, String> by lazyStrConnection
    private val strCommands: RedisCommands<String, String> by lazy { strConnection.sync() }

    private val ttlSeconds = config.ttl.seconds

    private val writeBehindQueue: LinkedBlockingDeque<Triple<K, V, Int>>? =
        if (config.writeMode == WriteMode.WRITE_BEHIND) {
            LinkedBlockingDeque(config.writeBehindQueueCapacity)
        } else {
            null
        }

    private val scheduler: ScheduledExecutorService? =
        if (config.writeMode == WriteMode.WRITE_BEHIND) {
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "exposed-lettuce-write-behind-flusher").also { it.isDaemon = true }
            }
        } else {
            null
        }

    init {
        scheduler?.scheduleWithFixedDelay(
            ::flushWriteBehindQueue,
            config.writeBehindDelay.toMillis(),
            config.writeBehindDelay.toMillis(),
            TimeUnit.MILLISECONDS
        )
    }

    private fun redisKey(key: K): String = "${config.keyPrefix}:${keySerializer(key)}"

    operator fun get(key: K): V? {
        val redisKey = redisKey(key)
        val cached = runCatching { commands.get(redisKey) }
            .onFailure { log.warn(it) { "Redis GET failed, loader fallback: $redisKey" } }
            .getOrNull()
        if (cached != null) return cached
        val loader = loader ?: return null
        val value = loader.load(key) ?: return null
        runCatching { commands.set(redisKey, value, SetArgs().ex(ttlSeconds)) }
            .onFailure { log.warn(it) { "Redis SETEX failed: $redisKey" } }
        return value
    }

    operator fun set(key: K, value: V) {
        when (config.writeMode) {
            WriteMode.NONE          -> {
                commands.set(redisKey(key), value, SetArgs().ex(ttlSeconds))
            }
            WriteMode.WRITE_THROUGH -> {
                writer?.write(mapOf(key to value))
                commands.set(redisKey(key), value, SetArgs().ex(ttlSeconds))
            }
            WriteMode.WRITE_BEHIND  -> {
                val queue = writeBehindQueue ?: return
                if (!queue.offer(Triple(key, value, 0))) {
                    throw IllegalStateException(
                        "Write-behind queue is full (capacity=${config.writeBehindQueueCapacity})"
                    )
                }
                commands.set(redisKey(key), value, SetArgs().ex(ttlSeconds))
            }
        }
    }

    fun getAll(keys: Set<K>): Map<K, V> {
        if (keys.isEmpty()) return emptyMap()
        val keyList = keys.toList()
        val redisKeys = keyList.map { redisKey(it) }.toTypedArray()

        val mgetResult = runCatching { commands.mget(*redisKeys) }
            .onFailure { log.warn(it) { "Redis MGET failed, loader fallback: ${redisKeys.take(5)}..." } }
            .getOrNull()

        val result = mutableMapOf<K, V>()
        val missedKeys = mutableListOf<K>()

        if (mgetResult == null) {
            missedKeys.addAll(keyList)
        } else {
            mgetResult.forEachIndexed { index, value ->
                if (value != null && value.hasValue()) {
                    result[keyList[index]] = value.value
                } else {
                    missedKeys.add(keyList[index])
                }
            }
        }

        if (missedKeys.isNotEmpty() && loader != null) {
            missedKeys.forEach { key ->
                val value = loader.load(key) ?: return@forEach
                result[key] = value
                runCatching { commands.set(redisKey(key), value, SetArgs().ex(ttlSeconds)) }
                    .onFailure { log.warn(it) { "Redis SETEX failed: ${redisKey(key)}" } }
            }
        }
        return result
    }

    fun delete(key: K) {
        if (config.writeMode != WriteMode.NONE) writer?.delete(listOf(key))
        commands.del(redisKey(key))
    }

    fun deleteAll(keys: Collection<K>) {
        if (keys.isEmpty()) return
        if (config.writeMode != WriteMode.NONE) writer?.delete(keys)
        commands.unlink(*keys.map { redisKey(it) }.toTypedArray())
    }

    fun evict(key: K) {
        commands.del(redisKey(key))
    }

    fun evictAll(keys: Collection<K>) {
        if (keys.isEmpty()) return
        commands.unlink(*keys.map { redisKey(it) }.toTypedArray())
    }

    fun invalidateByPattern(patterns: String, count: Long = 100L): Long {
        val keyPattern = "${config.keyPrefix}:$patterns"
        val scanArgs = ScanArgs.Builder.matches(keyPattern).limit(count)
        var cursor: ScanCursor = ScanCursor.INITIAL
        var deleted = 0L
        do {
            val scanResult = commands.scan(cursor, scanArgs)
            if (scanResult.keys.isNotEmpty()) {
                deleted += commands.unlink(*scanResult.keys.toTypedArray())
            }
            cursor = scanResult
        } while (!cursor.isFinished)
        return deleted
    }

    fun clear() {
        val pattern = "${config.keyPrefix}:*"
        val scanArgs = ScanArgs.Builder.matches(pattern).limit(100)
        var cursor: ScanCursor = ScanCursor.INITIAL
        do {
            val scanResult = commands.scan(cursor, scanArgs)
            if (scanResult.keys.isNotEmpty()) {
                commands.unlink(*scanResult.keys.toTypedArray())
            }
            cursor = scanResult
        } while (!cursor.isFinished)
    }

    private fun flushWriteBehindQueue() {
        val queue = writeBehindQueue ?: return
        val entries = mutableListOf<Triple<K, V, Int>>()
        repeat(config.writeBehindBatchSize) {
            val entry = queue.poll() ?: return@repeat
            entries.add(entry)
        }
        if (entries.isEmpty()) return

        val batch = entries.associate { it.first to it.second }
        runCatching { writer?.write(batch) }
            .onFailure { e ->
                val retryCount = entries.first().third + 1
                log.error(e) { "Write-behind flush failed (attempt $retryCount): ${batch.keys}" }
                if (retryCount < MAX_DEAD_LETTER_RETRY) {
                    val failed = mutableListOf<Triple<K, V, Int>>()
                    entries.forEach { (key, value, _) ->
                        val offered = queue.offerFirst(Triple(key, value, retryCount))
                        if (!offered) failed.add(Triple(key, value, retryCount))
                    }
                    if (failed.isNotEmpty()) {
                        writeDeadLetter(failed.associate { it.first to it.second })
                    }
                } else {
                    writeDeadLetter(batch)
                }
            }
    }

    private fun writeDeadLetter(batch: Map<K, V>) {
        runCatching {
            val deadLetterKey = "${config.keyPrefix}:dead-letter"
            val deadLetterValuesKey = "${config.keyPrefix}:dead-letter:values"
            val valueMap = batch.entries.associate { (key, value) -> keySerializer(key) to value }
            commands.hset(deadLetterValuesKey, valueMap)
            val serializedKeys = batch.keys.map { keySerializer(it) }
            strCommands.lpush(deadLetterKey, *serializedKeys.toTypedArray())
        }.onFailure { ex -> log.error(ex) { "Dead letter write failed" } }
    }

    override fun close() {
        scheduler?.let { sched ->
            sched.shutdown()
            val deadline = System.currentTimeMillis() + config.writeBehindShutdownTimeout.toMillis()
            while (writeBehindQueue?.isNotEmpty() == true && System.currentTimeMillis() < deadline) {
                flushWriteBehindQueue()
            }
            if (writeBehindQueue?.isNotEmpty() == true) {
                log.warn { "Write-behind shutdown timed out: ${writeBehindQueue.size} entries may be lost" }
            }
            sched.awaitTermination(1, TimeUnit.SECONDS)
        }
        if (lazyStrConnection.isInitialized()) strConnection.close()
        connection.close()
    }
}
