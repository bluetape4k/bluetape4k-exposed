package io.bluetape4k.exposed.r2dbc.lettuce.map

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.error
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requirePositiveNumber
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.time.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable

class ExposedR2dbcLettuceSuspendedLoadedMap<K: Any, V: Any>(
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

    private val bulkConnection = lazy { client.connect(valueCodec) }
    private val bulkMutex = Mutex()

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
            log.warn { "Redis GET failed, loader fallback: errorType=${e::class.simpleName}" }
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
            log.warn { "Redis SETEX failed: errorType=${e::class.simpleName}" }
        }
        return value
    }

    suspend fun set(key: K, value: V) {
        when (config.writeMode) {
            WriteMode.NONE          -> asyncCommands.set(redisKey(key), value, SetArgs().ex(ttlSeconds)).await()
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

    /** 여러 엔트리를 [batchSize] 단위로 writer와 Redis pipeline에 저장한다. */
    suspend fun putAll(entries: Map<K, V>, batchSize: Int) {
        batchSize.requirePositiveNumber("batchSize")
        if (entries.isEmpty()) return

        for (chunkEntries in entries.entries.chunked(batchSize)) {
            val chunk = chunkEntries.associate { it.key to it.value }
            when (config.writeMode) {
                WriteMode.NONE          -> Unit
                WriteMode.WRITE_THROUGH -> writer?.write(chunk)
                WriteMode.WRITE_BEHIND  -> enqueueWriteBehind(chunk)
            }
            putCacheOnly(chunk)
        }
    }

    /** DB writer를 호출하지 않고 여러 엔트리를 [batchSize] 단위 Redis pipeline으로 적재한다. */
    suspend fun warmAll(entries: Map<K, V>, batchSize: Int) {
        batchSize.requirePositiveNumber("batchSize")
        if (entries.isEmpty()) return
        for (chunkEntries in entries.entries.chunked(batchSize)) {
            putCacheOnly(chunkEntries.associate { it.key to it.value })
        }
    }

    private fun enqueueWriteBehind(entries: Map<K, V>) {
        val channel = writeBehindChannel ?: return
        entries.forEach { (key, value) ->
            val result = channel.trySend(Triple(key, value, 0))
            if (result.isFailure) {
                throw IllegalStateException(
                    "Write-behind channel is full (capacity=${config.writeBehindQueueCapacity})"
                )
            }
        }
    }

    private suspend fun putCacheOnly(entries: Map<K, V>) {
        if (entries.isEmpty()) return
        bulkMutex.withLock {
            val bulk = bulkConnection.value
            val bulkCommands = bulk.async()
            bulk.setAutoFlushCommands(false)
            try {
                val futures = entries.map { (key, value) ->
                    bulkCommands.set(redisKey(key), value, SetArgs().ex(ttlSeconds))
                }
                bulk.flushCommands()
                futures.forEach { it.await() }
            } finally {
                bulk.setAutoFlushCommands(true)
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
            log.warn {
                "Redis MGET failed, loader fallback: requested=${keys.size}, errorType=${e::class.simpleName}"
            }
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
                    log.warn { "Redis SETEX failed: errorType=${e::class.simpleName}" }
                }
            }
        }
        return result
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
            log.error { "Dead letter write failed: errorType=${e::class.simpleName}" }
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
            log.error {
                "Write-behind flush failed: entries=${batch.size}, " +
                    "errorType=${e::class.simpleName}"
            }
            val dropped = mutableMapOf<K, V>()
            entries.forEach { (key, value, retryCount) ->
                val nextRetryCount = retryCount + 1
                if (nextRetryCount < MAX_DEAD_LETTER_RETRY) {
                    val result = writeBehindChannel?.trySend(Triple(key, value, nextRetryCount))
                    if (result == null || result.isFailure) dropped[key] = value
                } else {
                    dropped[key] = value
                }
            }
            if (dropped.isNotEmpty()) writeToDeadLetter(dropped)
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
            log.warn { "Write-behind job drain timed out or failed during close(): errorType=${e::class.simpleName}" }
        } finally {
            ownedJob.cancel()
            if (lazyStrConnection.isInitialized()) lazyStrConnection.value.close()
            if (bulkConnection.isInitialized()) bulkConnection.value.close()
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
                if (!drained) log.warn { "Write-behind job drain timed out during suspendClose()" }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn { "Write-behind job drain failed during suspendClose(): errorType=${e::class.simpleName}" }
        } finally {
            withContext(NonCancellable) {
                ownedJob.cancel()
                if (lazyStrConnection.isInitialized()) lazyStrConnection.value.close()
                if (bulkConnection.isInitialized()) bulkConnection.value.close()
                connection.close()
            }
        }
    }
}
