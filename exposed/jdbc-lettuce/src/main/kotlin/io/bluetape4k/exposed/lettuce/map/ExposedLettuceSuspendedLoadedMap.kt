package io.bluetape4k.exposed.lettuce.map

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.error
import io.bluetape4k.logging.warn
import io.bluetape4k.redis.lettuce.map.LettuceCacheConfig
import io.bluetape4k.redis.lettuce.map.SuspendedMapLoader
import io.bluetape4k.redis.lettuce.map.SuspendedMapWriter
import io.bluetape4k.redis.lettuce.map.WriteMode
import io.lettuce.core.RedisClient
import io.lettuce.core.ScanArgs
import io.lettuce.core.ScanCursor
import io.lettuce.core.SetArgs
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.async.RedisAsyncCommands
import io.lettuce.core.codec.RedisCodec
import io.lettuce.core.codec.StringCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.time.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable

class ExposedLettuceSuspendedLoadedMap<K: Any, V: Any>(
    private val client: RedisClient,
    private val loader: SuspendedMapLoader<K, V>? = null,
    private val writer: SuspendedMapWriter<K, V>? = null,
    private val config: LettuceCacheConfig = LettuceCacheConfig.READ_WRITE_THROUGH,
    private val keySerializer: (K) -> String = { it.toString() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    valueCodec: RedisCodec<String, V>,
): Closeable {
    companion object: KLogging() {
        private const val MAX_DEAD_LETTER_RETRY = 3
    }

    private val connection: StatefulRedisConnection<String, V> = client.connect(valueCodec)
    private val asyncCommands: RedisAsyncCommands<String, V> = connection.async()

    private val lazyStrConnection = lazy { client.connect(StringCodec.UTF8) }
    private val strAsyncCommands by lazy { lazyStrConnection.value.async() }

    private val ttlSeconds = config.ttl.seconds

    private val ownedJob = SupervisorJob(parent = scope.coroutineContext[Job])
    private val ownedScope = CoroutineScope(scope.coroutineContext + ownedJob)

    private val writeBehindChannel: Channel<Triple<K, V, Int>>? =
        if (config.writeMode == WriteMode.WRITE_BEHIND) {
            Channel(config.writeBehindQueueCapacity)
        } else {
            null
        }

    private val writeBehindJob = writeBehindChannel?.let { ownedScope.launch { consumeWriteBehindChannel() } }

    private fun redisKey(key: K): String = "${config.keyPrefix}:${keySerializer(key)}"

    suspend fun get(key: K): V? {
        val redisKey = redisKey(key)
        val cached = try {
            asyncCommands.get(redisKey).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Redis GET failed, loader fallback: $redisKey" }
            null
        }
        if (cached != null) return cached
        val loader = loader ?: return null
        val value = loader.load(key) ?: return null
        try {
            asyncCommands.set(redisKey, value, SetArgs().ex(ttlSeconds)).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Redis SETEX failed: $redisKey" }
        }
        return value
    }

    suspend fun set(key: K, value: V) {
        when (config.writeMode) {
            WriteMode.NONE          -> {
                asyncCommands.set(redisKey(key), value, SetArgs().ex(ttlSeconds)).await()
            }
            WriteMode.WRITE_THROUGH -> {
                writer?.write(mapOf(key to value))
                asyncCommands.set(redisKey(key), value, SetArgs().ex(ttlSeconds)).await()
            }
            WriteMode.WRITE_BEHIND  -> {
                val channel = writeBehindChannel ?: return
                val result = channel.trySend(Triple(key, value, 0))
                if (result.isFailure) {
                    throw IllegalStateException(
                        "Write-behind channel is full (capacity=${config.writeBehindQueueCapacity})"
                    )
                }
                asyncCommands.set(redisKey(key), value, SetArgs().ex(ttlSeconds)).await()
            }
        }
    }

    suspend fun getAll(keys: Set<K>): Map<K, V> {
        if (keys.isEmpty()) return emptyMap()
        val keyList = keys.toList()
        val redisKeys = keyList.map { redisKey(it) }.toTypedArray()

        val mgetResult = try {
            asyncCommands.mget(*redisKeys).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Redis MGET failed, loader fallback: ${redisKeys.take(5)}..." }
            null
        }

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
            for (key in missedKeys) {
                val value = loader.load(key) ?: continue
                result[key] = value
                try {
                    asyncCommands.set(redisKey(key), value, SetArgs().ex(ttlSeconds)).await()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn(e) { "Redis SETEX failed: ${redisKey(key)}" }
                }
            }
        }
        return result
    }

    suspend fun delete(key: K) {
        if (config.writeMode != WriteMode.NONE) writer?.delete(listOf(key))
        asyncCommands.del(redisKey(key)).await()
    }

    suspend fun deleteAll(keys: Collection<K>) {
        if (keys.isEmpty()) return
        if (config.writeMode != WriteMode.NONE) writer?.delete(keys)
        asyncCommands.unlink(*keys.map { redisKey(it) }.toTypedArray()).await()
    }

    suspend fun evict(key: K) {
        asyncCommands.del(redisKey(key)).await()
    }

    suspend fun evictAll(keys: Collection<K>) {
        if (keys.isEmpty()) return
        asyncCommands.unlink(*keys.map { redisKey(it) }.toTypedArray()).await()
    }

    suspend fun invalidateByPattern(patterns: String, count: Long = 100L): Long {
        val keyPattern = "${config.keyPrefix}:$patterns"
        val scanArgs = ScanArgs.Builder.matches(keyPattern).limit(count)
        var cursor: ScanCursor = ScanCursor.INITIAL
        var deleted = 0L
        do {
            val scanResult = asyncCommands.scan(cursor, scanArgs).await()
            if (scanResult.keys.isNotEmpty()) {
                deleted += asyncCommands.unlink(*scanResult.keys.toTypedArray()).await()
            }
            cursor = scanResult
        } while (!cursor.isFinished)
        return deleted
    }

    suspend fun clear() {
        val pattern = "${config.keyPrefix}:*"
        val scanArgs = ScanArgs.Builder.matches(pattern).limit(100)
        var cursor: ScanCursor = ScanCursor.INITIAL
        do {
            val scanResult = asyncCommands.scan(cursor, scanArgs).await()
            if (scanResult.keys.isNotEmpty()) {
                asyncCommands.unlink(*scanResult.keys.toTypedArray()).await()
            }
            cursor = scanResult
        } while (!cursor.isFinished)
    }

    private suspend fun consumeWriteBehindChannel() {
        val channel = writeBehindChannel ?: return
        while (currentCoroutineContext().isActive) {
            val batch = mutableListOf<Triple<K, V, Int>>()
            val first = channel.receiveCatching().getOrNull() ?: break
            batch.add(first)
            while (batch.size < config.writeBehindBatchSize) {
                val next = channel.tryReceive().getOrNull() ?: break
                batch.add(next)
            }
            flushBatch(batch)
            delay(config.writeBehindDelay)
        }
    }

    private suspend fun writeToDeadLetter(batch: Map<K, V>) {
        try {
            val deadLetterKey = "${config.keyPrefix}:dead-letter"
            val deadLetterValuesKey = "${config.keyPrefix}:dead-letter:values"
            val valueMap = batch.entries.associate { (key, value) -> keySerializer(key) to value }
            asyncCommands.hset(deadLetterValuesKey, valueMap).await()
            val serializedKeys = batch.keys.map { keySerializer(it) }
            strAsyncCommands.lpush(deadLetterKey, *serializedKeys.toTypedArray()).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error(e) { "Dead letter write failed" }
        }
    }

    private suspend fun flushBatch(entries: List<Triple<K, V, Int>>) {
        if (entries.isEmpty()) return
        val batch = entries.associate { it.first to it.second }
        try {
            writer?.write(batch)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val retryCount = entries.first().third + 1
            log.error(e) { "Write-behind flush failed (attempt $retryCount): ${batch.keys}" }
            if (retryCount < MAX_DEAD_LETTER_RETRY) {
                val dropped = mutableMapOf<K, V>()
                entries.forEach { (key, value, _) ->
                    val result = writeBehindChannel?.trySend(Triple(key, value, retryCount))
                    if (result == null || result.isFailure) {
                        dropped[key] = value
                    }
                }
                if (dropped.isNotEmpty()) writeToDeadLetter(dropped)
            } else {
                writeToDeadLetter(batch)
            }
        }
    }

    override fun close() {
        writeBehindChannel?.close()
        try {
            writeBehindJob?.let { job ->
                runBlocking(Dispatchers.IO) {
                    withTimeout(config.writeBehindShutdownTimeout.toMillis()) {
                        job.join()
                    }
                }
            }
        } catch (e: Exception) {
            log.warn(e) { "Write-behind job drain timed out or failed during close()" }
        } finally {
            ownedJob.cancel()
            if (lazyStrConnection.isInitialized()) lazyStrConnection.value.close()
            connection.close()
        }
    }

    suspend fun suspendClose() {
        writeBehindChannel?.close()
        try {
            writeBehindJob?.let { job ->
                val drained = withTimeoutOrNull(config.writeBehindShutdownTimeout.toMillis()) {
                    job.join()
                    true
                } ?: false
                if (!drained) {
                    log.warn { "Write-behind job drain timed out during suspendClose()" }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Write-behind job drain failed during suspendClose()" }
        } finally {
            withContext(NonCancellable) {
                ownedJob.cancel()
                if (lazyStrConnection.isInitialized()) lazyStrConnection.value.close()
                connection.close()
            }
        }
    }
}
