@file:OptIn(InternalSnapshotCacheApi::class)

package io.bluetape4k.exposed.benchmark.cache

import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.exposed.benchmark.support.createJdbcDatabase
import io.bluetape4k.exposed.cache.snapshot.AsyncSnapshotInvalidationStore
import io.bluetape4k.exposed.cache.snapshot.CacheSnapshot
import io.bluetape4k.exposed.cache.snapshot.CacheSnapshotValueValidator
import io.bluetape4k.exposed.cache.snapshot.CaffeineSnapshotCacheConfig
import io.bluetape4k.exposed.cache.snapshot.ClaimedSnapshotMiss
import io.bluetape4k.exposed.cache.snapshot.InternalSnapshotCacheApi
import io.bluetape4k.exposed.cache.snapshot.MeasuredInvalidation
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheApplyReport
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheConfig
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheDeadline
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheFailureBuffer
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheFailureObserver
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheLimits
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheLookup
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheMiss
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheMutation
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheOperation
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheOperationResult
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheOutcome
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheStore
import io.bluetape4k.exposed.cache.snapshot.SnapshotLocalFenceRegistry
import io.bluetape4k.exposed.cache.snapshot.SnapshotMissCapabilityRegistry
import io.bluetape4k.exposed.cache.snapshot.SnapshotStoreId
import io.bluetape4k.exposed.cache.snapshot.SnapshotTransactionBridge
import io.bluetape4k.exposed.cache.snapshot.snapshotCacheFailureBuffer
import io.bluetape4k.exposed.cache.snapshot.stageInvalidationMutation
import io.bluetape4k.exposed.cache.snapshot.stageSnapshotMutation
import io.bluetape4k.exposed.jdbc.caffeine.snapshot.JdbcCaffeineSnapshotCache
import io.bluetape4k.exposed.jdbc.caffeine.snapshot.jdbcCaffeineSnapshotCache
import io.bluetape4k.exposed.redisson.snapshot.longSnapshotIdentifierPolicy
import io.bluetape4k.exposed.redisson.snapshot.snapshotRedissonCodec
import org.jetbrains.exposed.v1.core.statements.StatementInterceptor
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.currentOrNull
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Threads
import org.redisson.client.codec.StringCodec
import java.io.Serializable
import java.lang.reflect.Array as ReflectArray
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.jvm.javaObjectType

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
class SnapshotCacheBenchmark {

    private lateinit var dataSource: HikariDataSource
    private lateinit var database: Database
    private lateinit var hitCache: JdbcCaffeineSnapshotCache<Long, BenchmarkSnapshotValue>
    private lateinit var localStore: BoundedLocalSnapshotStore
    private lateinit var secondLocalStore: BoundedLocalSnapshotStore
    private lateinit var phaseAsyncStore: CompletedAsyncSnapshotStore
    private lateinit var canonicalAsyncStore: CanonicalChunkingAsyncSnapshotStore
    private lateinit var failingStores: List<FailingAsyncSnapshotStore>
    private lateinit var outageStore: NeverCompletingAsyncSnapshotStore
    private lateinit var failureBuffer: SnapshotCacheFailureBuffer
    private val fences = SnapshotLocalFenceRegistry<Long>(FENCE_STRIPES)
    private val sequence = AtomicLong(10_000L)

    @Setup(Level.Trial)
    fun setupTrial() {
        val (createdDataSource, createdDatabase) = createJdbcDatabase("snapshot_cache_benchmark", 1)
        dataSource = createdDataSource
        database = createdDatabase
        failureBuffer = snapshotCacheFailureBuffer(FAILURE_CAPACITY)

        val limits = SnapshotCacheLimits(
            maxStagedMutations = MAX_STAGED_MUTATIONS,
            maxParticipatingStores = FAILURE_STORE_COUNT + 2,
            maxStagedWeight = MAX_STAGED_WEIGHT,
            localDrainBudget = Duration.ofSeconds(1),
        )
        localStore = BoundedLocalSnapshotStore("local-a:v1", limits, failureBuffer)
        secondLocalStore = BoundedLocalSnapshotStore("local-b:v1", limits, failureBuffer)
        phaseAsyncStore = CompletedAsyncSnapshotStore("async-phase:v1", limits, failureBuffer)
        canonicalAsyncStore = CanonicalChunkingAsyncSnapshotStore(limits, failureBuffer)
        failingStores = List(FAILURE_STORE_COUNT) { index ->
            FailingAsyncSnapshotStore("failure-$index:v1", limits, failureBuffer)
        }
        outageStore = NeverCompletingAsyncSnapshotStore(limits, failureBuffer)

        hitCache = jdbcCaffeineSnapshotCache(
            config = CaffeineSnapshotCacheConfig(
                snapshot = SnapshotCacheConfig("benchmark-hit:v1", "v1"),
                maximumSize = 16,
                maxOutstandingMissTokens = 16,
            ),
            validator = ACCEPT_BENCHMARK_VALUE,
        )
        val miss = requireNotNull(hitCache.lookup(HIT_ID).miss)
        val prepared = hitCache.claimMiss(miss).prepare(snapshot(HIT_ID))
        hitCache.applySnapshots(listOf(prepared), NEVER_EXPIRES)
    }

    @TearDown(Level.Trial)
    fun tearDownTrial() {
        outageStore.releaseOutstanding()
        dataSource.close()
    }

    @Benchmark
    fun localHit(): Any =
        requireNotNull(hitCache.lookup(HIT_ID).snapshot)

    @Benchmark
    fun missCapabilityRegisterAndClaim(): Any {
        val id = nextId()
        val miss = requireNotNull(localStore.lookup(id).miss)
        return localStore.claimMiss(miss).prepare(snapshot(id))
    }

    @Benchmark
    fun oneKeyStagingBuffer(): Long {
        val before = localStore.invalidatedCount.get()
        inSingleAttemptTransaction {
            stageInvalidationMutation(this, BenchmarkJdbcTransactionBridge, localStore, nextId())
        }
        return localStore.invalidatedCount.get() - before
    }

    @Benchmark
    fun maximumCountAndWeightStagingBuffer(): Long {
        val before = localStore.snapshotCount.get()
        inSingleAttemptTransaction {
            repeat(MAX_STAGED_MUTATIONS) {
                val id = nextId()
                val miss = requireNotNull(localStore.lookup(id).miss)
                stageSnapshotMutation(
                    this,
                    BenchmarkJdbcTransactionBridge,
                    localStore,
                    miss,
                    snapshot(id),
                    ACCEPT_BENCHMARK_VALUE,
                )
            }
        }
        return localStore.snapshotCount.get() - before
    }

    @Benchmark
    fun repeatedKeyCoalescing(): Long {
        val before = localStore.invalidatedCount.get()
        val id = nextId()
        inSingleAttemptTransaction {
            repeat(MAX_STAGED_MUTATIONS) {
                stageInvalidationMutation(this, BenchmarkJdbcTransactionBridge, localStore, id)
            }
        }
        return localStore.invalidatedCount.get() - before
    }

    @Benchmark
    fun multiStorePhasePartitioning(): Long {
        val localBefore = localStore.invalidatedCount.get()
        val secondBefore = secondLocalStore.invalidatedCount.get()
        val asyncBefore = phaseAsyncStore.submittedCount.get()
        inSingleAttemptTransaction {
            stageInvalidationMutation(this, BenchmarkJdbcTransactionBridge, localStore, nextId())
            stageInvalidationMutation(this, BenchmarkJdbcTransactionBridge, secondLocalStore, nextId())
            stageInvalidationMutation(this, BenchmarkJdbcTransactionBridge, phaseAsyncStore, nextId())
        }
        return localStore.invalidatedCount.get() - localBefore +
                (secondLocalStore.invalidatedCount.get() - secondBefore) +
                (phaseAsyncStore.submittedCount.get() - asyncBefore)
    }

    @Benchmark
    fun peakDrainAllocation(): Long {
        val before = localStore.invalidatedCount.get()
        inSingleAttemptTransaction {
            repeat(MAX_STAGED_MUTATIONS) {
                stageInvalidationMutation(this, BenchmarkJdbcTransactionBridge, localStore, nextId())
            }
        }
        return localStore.invalidatedCount.get() - before
    }

    @Benchmark
    fun commitDrain(): Long {
        val snapshotsBefore = localStore.snapshotCount.get()
        val invalidationsBefore = localStore.invalidatedCount.get()
        val snapshotId = nextId()
        val miss = requireNotNull(localStore.lookup(snapshotId).miss)
        inSingleAttemptTransaction {
            stageSnapshotMutation(
                this,
                BenchmarkJdbcTransactionBridge,
                localStore,
                miss,
                snapshot(snapshotId),
                ACCEPT_BENCHMARK_VALUE,
            )
            stageInvalidationMutation(this, BenchmarkJdbcTransactionBridge, localStore, nextId())
        }
        return localStore.snapshotCount.get() - snapshotsBefore +
                (localStore.invalidatedCount.get() - invalidationsBefore)
    }

    @Benchmark
    @Threads(4)
    fun stripedLookupFenceContention(): Boolean {
        val id = Math.floorMod(sequence.getAndIncrement(), CONTENTION_KEYS.toLong())
        val fence = fences.capture(id)
        return fences.putIfCurrent(id, fence) {}
    }

    @Benchmark
    fun redissonCanonicalKeyEncodingAndChunkSubmission(): Long {
        val encodingsBefore = canonicalAsyncStore.encodingCount()
        val typedArraysBefore = canonicalAsyncStore.typedArrayCount()
        val chunksBefore = canonicalAsyncStore.chunkSubmissionCount()
        val submittedBefore = canonicalAsyncStore.submittedCount.get()
        val measured = ArrayList<MeasuredInvalidation<Long>>(CANONICAL_BATCH_SIZE)
        repeat(CANONICAL_BATCH_SIZE) {
            measured += canonicalAsyncStore.measure(nextId())
        }
        canonicalAsyncStore.submitInvalidation(measured)
        check(canonicalAsyncStore.encodingCount() - encodingsBefore == CANONICAL_BATCH_SIZE * 2L) {
            "Every canonical identifier must be encoded once for measurement and once for chunk verification."
        }
        val expectedChunks = measured.sumOf { it.encodedBytes }
            .let { totalBytes -> (totalBytes + CANONICAL_MAX_CHUNK_BYTES - 1) / CANONICAL_MAX_CHUNK_BYTES }
        check(
            canonicalAsyncStore.typedArrayCount() - typedArraysBefore ==
                    expectedChunks.toLong(),
        ) {
            "Every submitted chunk must materialize one typed boxed-Long identifier array."
        }
        check(canonicalAsyncStore.chunkSubmissionCount() - chunksBefore == expectedChunks.toLong()) {
            "Chunk submission accounting must follow encoded-byte partitioning."
        }
        return canonicalAsyncStore.submittedCount.get() - submittedBefore
    }

    @Benchmark
    fun failureBufferSaturation(): Int {
        failureBuffer.drainTo(NOOP_FAILURE_OBSERVER, failureBuffer.capacity)
        return try {
            inSingleAttemptTransaction {
                failingStores.forEach { store ->
                    stageInvalidationMutation(this, BenchmarkJdbcTransactionBridge, store, nextId())
                }
            }
            failureBuffer.size.also { retained ->
                check(retained == FAILURE_CAPACITY) { "The bounded failure buffer did not reach saturation." }
            }
        } finally {
            failureBuffer.drainTo(NOOP_FAILURE_OBSERVER, failureBuffer.capacity)
        }
    }

    @Benchmark
    fun neverCompletingOutageConnectionHold(): Int {
        outageStore.releaseOutstanding()
        return try {
            inSingleAttemptTransaction {
                stageInvalidationMutation(this, BenchmarkJdbcTransactionBridge, outageStore, nextId())
            }
            val activeConnections = dataSource.hikariPoolMXBean.activeConnections
            check(activeConnections == 0) { "A database connection remained active while invalidation was outstanding." }
            outageStore.outstandingCount() + activeConnections
        } finally {
            outageStore.releaseOutstanding()
        }
    }

    private inline fun <T> inSingleAttemptTransaction(crossinline block: JdbcTransaction.() -> T): T =
        transaction(database) {
            maxAttempts = 1
            block()
        }

    private fun nextId(): Long = sequence.incrementAndGet()

    private fun snapshot(id: Long): CacheSnapshot<BenchmarkSnapshotValue> =
        CacheSnapshot(BenchmarkSnapshotValue(id, "snapshot-$id"))

    private companion object {
        private const val HIT_ID = 1L
        private const val MAX_STAGED_MUTATIONS = 128
        private const val ESTIMATED_WEIGHT = 16L
        private const val MAX_STAGED_WEIGHT = MAX_STAGED_MUTATIONS * ESTIMATED_WEIGHT
        private const val FENCE_STRIPES = 64
        private const val CONTENTION_KEYS = 8
        private const val FAILURE_CAPACITY = 8
        private const val FAILURE_STORE_COUNT = FAILURE_CAPACITY * 2
        private const val CANONICAL_BATCH_SIZE = 32
        private const val CANONICAL_MAX_CHUNK_BYTES = 64

        private val ACCEPT_BENCHMARK_VALUE = CacheSnapshotValueValidator<BenchmarkSnapshotValue> {}
        private val NOOP_FAILURE_OBSERVER = SnapshotCacheFailureObserver {}
        private val NEVER_EXPIRES = object : SnapshotCacheDeadline {
            override fun remaining(): Duration = Duration.ofNanos(Long.MAX_VALUE)
            override val isExpired: Boolean = false
        }
    }
}

private object BenchmarkJdbcTransactionBridge : SnapshotTransactionBridge<JdbcTransaction> {
    override fun isRoot(transaction: JdbcTransaction): Boolean = transaction.outerTransaction == null

    override fun isCurrent(transaction: JdbcTransaction): Boolean =
        transaction.transactionManager.currentOrNull() === transaction

    override fun maxAttempts(transaction: JdbcTransaction): Int = transaction.maxAttempts

    override fun registerInterceptor(transaction: JdbcTransaction, interceptor: StatementInterceptor) {
        transaction.registerInterceptor(interceptor)
    }
}

private class BoundedLocalSnapshotStore(
    namespace: String,
    override val limits: SnapshotCacheLimits,
    override val failureBuffer: SnapshotCacheFailureBuffer,
) : SnapshotCacheStore<Long, BenchmarkSnapshotValue> {
    override val storeId = SnapshotStoreId("benchmark-local", namespace)
    override val storeInstanceToken: Any = Any()
    override val compatibilityFingerprint: String = "benchmark-local-v1"
    val snapshotCount = AtomicLong()
    val invalidatedCount = AtomicLong()
    private val fences = SnapshotLocalFenceRegistry<Long>(64)
    private val misses = SnapshotMissCapabilityRegistry<Long, BenchmarkSnapshotValue>(MAX_MISS_TOKENS)

    fun lookup(id: Long): SnapshotCacheLookup<Long, BenchmarkSnapshotValue> = misses.register(id, fences.capture(id))

    override fun claimMiss(
        miss: SnapshotCacheMiss<Long, BenchmarkSnapshotValue>,
    ): ClaimedSnapshotMiss<Long, BenchmarkSnapshotValue> {
        val claimed = misses.claim(miss)
        return ClaimedSnapshotMiss { snapshot ->
            claimed.prepare(snapshot).copy(estimatedWeight = ESTIMATED_WEIGHT)
        }
    }

    override fun applySnapshots(
        snapshots: List<SnapshotCacheMutation.Put<Long, BenchmarkSnapshotValue>>,
        deadline: SnapshotCacheDeadline,
    ): SnapshotCacheApplyReport {
        snapshotCount.addAndGet(snapshots.size.toLong())
        return successful(SnapshotCacheOperation.PUT, snapshots.size)
    }

    override fun applyInvalidations(
        ids: List<Long>,
        deadline: SnapshotCacheDeadline,
    ): SnapshotCacheApplyReport {
        invalidatedCount.addAndGet(ids.size.toLong())
        return successful(SnapshotCacheOperation.INVALIDATE, ids.size)
    }

    private companion object {
        private const val MAX_MISS_TOKENS = 256
        private const val ESTIMATED_WEIGHT = 16L
    }
}

private open class CompletedAsyncSnapshotStore(
    namespace: String,
    override val limits: SnapshotCacheLimits,
    override val failureBuffer: SnapshotCacheFailureBuffer,
) : AsyncSnapshotInvalidationStore<Long> {
    override val storeId = SnapshotStoreId("benchmark-async", namespace)
    override val storeInstanceToken: Any = Any()
    override val compatibilityFingerprint: String = "benchmark-async-v1"
    val submittedCount = AtomicLong()

    override fun measure(id: Long): MeasuredInvalidation<Long> =
        MeasuredInvalidation(id, Long.SIZE_BYTES, FIXED_SHA_256)

    override fun submitInvalidation(
        batch: List<MeasuredInvalidation<Long>>,
    ): CompletionStage<SnapshotCacheApplyReport> {
        submittedCount.addAndGet(batch.size.toLong())
        return CompletableFuture.completedFuture(successful(SnapshotCacheOperation.INVALIDATE, batch.size))
    }

}

private class CanonicalChunkingAsyncSnapshotStore(
    limits: SnapshotCacheLimits,
    failureBuffer: SnapshotCacheFailureBuffer,
) : CompletedAsyncSnapshotStore("canonical:v1", limits, failureBuffer) {
    private val codec = snapshotRedissonCodec(StringCodec(), "benchmark-v1", longSnapshotIdentifierPolicy())
    private val encodings = AtomicLong()
    private val typedArrays = AtomicLong()
    private val chunkSubmissions = AtomicLong()

    override fun measure(id: Long): MeasuredInvalidation<Long> {
        val bytes = encodeCanonical(id)
        return MeasuredInvalidation(id, bytes.size, bytes.sha256())
    }

    override fun submitInvalidation(
        batch: List<MeasuredInvalidation<Long>>,
    ): CompletionStage<SnapshotCacheApplyReport> {
        check(batch.size <= MAX_BATCH_ENTRIES) { "Benchmark invalidation batch entry bound exceeded." }
        val chunks = batch.partitionByEncodedBytes(MAX_CHUNK_BYTES)
        check(chunks.size <= MAX_OUTSTANDING_CHUNKS) { "Benchmark outstanding chunk bound exceeded." }
        chunks.forEach { chunk ->
            verifyChunk(chunk)
            val identifiers = chunk.toTypedIdentifierArray()
            identifiers.forEachIndexed { index, id ->
                check(id == chunk[index].id) { "Typed identifier array changed submission order." }
            }
            typedArrays.incrementAndGet()
            chunkSubmissions.incrementAndGet()
            submittedCount.addAndGet(identifiers.size.toLong())
        }
        return CompletableFuture.completedFuture(successful(SnapshotCacheOperation.INVALIDATE, batch.size))
    }

    fun encodingCount(): Long = encodings.get()

    fun typedArrayCount(): Long = typedArrays.get()

    fun chunkSubmissionCount(): Long = chunkSubmissions.get()

    private fun verifyChunk(chunk: List<MeasuredInvalidation<Long>>) {
        var totalBytes = 0
        chunk.forEach { measured ->
            val encoded = encodeCanonical(measured.id)
            check(encoded.size == measured.encodedBytes && encoded.sha256() == measured.encodedSha256) {
                "Measured canonical identifier changed before bounded chunk submission."
            }
            totalBytes += encoded.size
        }
        check(totalBytes <= MAX_CHUNK_BYTES) { "Benchmark chunk byte bound exceeded." }
    }

    @Suppress("UNCHECKED_CAST")
    private fun List<MeasuredInvalidation<Long>>.toTypedIdentifierArray(): Array<Long> {
        val identifiers = ReflectArray.newInstance(Long::class.javaObjectType, size) as Array<Long>
        forEachIndexed { index, measured -> ReflectArray.set(identifiers, index, measured.id) }
        return identifiers
    }

    private fun encodeCanonical(id: Long): ByteArray {
        encodings.incrementAndGet()
        val buffer = codec.mapKeyEncoder.encode(id)
        return try {
            ByteArray(buffer.readableBytes()).also(buffer::readBytes)
        } finally {
            buffer.release()
        }
    }

    private companion object {
        private const val MAX_BATCH_ENTRIES = 32
        private const val MAX_CHUNK_BYTES = 64
        private const val MAX_OUTSTANDING_CHUNKS = 8
    }
}

private class FailingAsyncSnapshotStore(
    namespace: String,
    limits: SnapshotCacheLimits,
    failureBuffer: SnapshotCacheFailureBuffer,
) : CompletedAsyncSnapshotStore(namespace, limits, failureBuffer) {
    override fun submitInvalidation(
        batch: List<MeasuredInvalidation<Long>>,
    ): CompletionStage<SnapshotCacheApplyReport> =
        CompletableFuture.failedFuture(IllegalStateException("simulated bounded benchmark failure"))
}

private class NeverCompletingAsyncSnapshotStore(
    limits: SnapshotCacheLimits,
    failureBuffer: SnapshotCacheFailureBuffer,
) : CompletedAsyncSnapshotStore("outage:v1", limits, failureBuffer) {
    private val outstanding = AtomicReference<CompletableFuture<SnapshotCacheApplyReport>?>()

    override fun submitInvalidation(
        batch: List<MeasuredInvalidation<Long>>,
    ): CompletionStage<SnapshotCacheApplyReport> {
        val future = CompletableFuture<SnapshotCacheApplyReport>()
        check(outstanding.compareAndSet(null, future)) { "Only one benchmark outage future may be retained." }
        return future
    }

    fun outstandingCount(): Int = if (outstanding.get() == null) 0 else 1

    fun releaseOutstanding() {
        outstanding.getAndSet(null)?.complete(successful(SnapshotCacheOperation.INVALIDATE, 1))
    }
}

private data class BenchmarkSnapshotValue(
    val id: Long,
    val payload: String,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

private fun successful(operation: SnapshotCacheOperation, count: Int): SnapshotCacheApplyReport =
    SnapshotCacheApplyReport(
        if (count == 0) {
            emptyList()
        } else {
            listOf(SnapshotCacheOperationResult(operation, SnapshotCacheOutcome.SUCCESS, count))
        },
    )

private fun List<MeasuredInvalidation<Long>>.partitionByEncodedBytes(
    maxChunkBytes: Int,
): List<List<MeasuredInvalidation<Long>>> {
    if (isEmpty()) return emptyList()
    val chunks = ArrayList<List<MeasuredInvalidation<Long>>>()
    var current = ArrayList<MeasuredInvalidation<Long>>()
    var currentBytes = 0
    forEach { measured ->
        check(measured.encodedBytes <= maxChunkBytes) { "One encoded identifier exceeds the chunk byte bound." }
        if (current.isNotEmpty() && currentBytes + measured.encodedBytes > maxChunkBytes) {
            chunks += current
            current = ArrayList()
            currentBytes = 0
        }
        current += measured
        currentBytes += measured.encodedBytes
    }
    if (current.isNotEmpty()) chunks += current
    return chunks
}

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .toLowerHex()

private fun ByteArray.toLowerHex(): String {
    val encoded = CharArray(size * 2)
    forEachIndexed { index, byte ->
        val value = byte.toInt() and 0xff
        encoded[index * 2] = HEX_DIGITS[value ushr 4]
        encoded[index * 2 + 1] = HEX_DIGITS[value and 0x0f]
    }
    return encoded.concatToString()
}

private const val FIXED_SHA_256 = "0000000000000000000000000000000000000000000000000000000000000000"
private const val HEX_DIGITS = "0123456789abcdef"
