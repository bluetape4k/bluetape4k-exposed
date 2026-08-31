package io.bluetape4k.batch.core

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.batch.api.BatchExecutionLeaseSnapshot
import io.bluetape4k.batch.api.BatchJobRepository
import io.bluetape4k.batch.api.JobExecution
import io.bluetape4k.batch.api.StepExecution
import io.bluetape4k.junit5.coroutines.runSuspendIO
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** combined guard의 monotonic margin, atomic renewal, write fencing 계약을 검증한다. */
class BatchLeaseGuardTest {

    @Test
    fun `safe margin에 진입하면 최신 Job과 Step을 함께 갱신한다`() = runSuspendIO {
        val wallClock = MutableBatchClock(Instant.parse("2026-08-31T00:00:00Z"))
        val monotonic = MutableBatchMonotonicClock()
        val repository = InMemoryBatchJobRepository(wallClock)
        val job = repository.findOrCreateJobExecution("guardJob")
        val step = repository.findOrCreateStepExecution(job, "guardStep")
        val claimedJob = repository.claimJobExecution(job, "owner-1", Duration.ofSeconds(30))
        val claimedStep = repository.claimStepExecution(step, "owner-1", Duration.ofSeconds(30))
        claimedJob.shouldNotBeNull()
        claimedStep.shouldNotBeNull()

        val guard = BatchLeaseGuard(
            repository = repository,
            ownerId = "owner-1",
            executionLease = Duration.ofSeconds(30),
            initialJobExecution = claimedJob,
            initialStepExecution = claimedStep,
            monotonicClock = monotonic,
        )
        monotonic.nowNanos = 20_000_000_000L
        wallClock.advance(Duration.ofSeconds(1))

        val snapshot = guard.checkBothAndMaybeRenew()

        snapshot.jobExecution.version shouldBeEqualTo claimedJob.version + 1L
        snapshot.stepExecution.shouldNotBeNull()
        snapshot.stepExecution.version shouldBeEqualTo claimedStep.version + 1L
    }

    @Test
    fun `heartbeat로 Step version이 증가해도 checkpoint는 최신 snapshot으로 CAS한다`() = runSuspendIO {
        val wallClock = MutableBatchClock(Instant.parse("2026-08-31T00:00:00Z"))
        val monotonic = MutableBatchMonotonicClock()
        val repository = InMemoryBatchJobRepository(wallClock)
        val job = repository.findOrCreateJobExecution("checkpointRaceJob")
        val step = repository.findOrCreateStepExecution(job, "checkpointRaceStep")
        val claimedJob = repository.claimJobExecution(job, "owner-1", Duration.ofSeconds(30)).shouldNotBeNull()
        val claimedStep = repository.claimStepExecution(step, "owner-1", Duration.ofSeconds(30)).shouldNotBeNull()
        val guard = BatchLeaseGuard(
            repository = repository,
            ownerId = "owner-1",
            executionLease = Duration.ofSeconds(30),
            initialJobExecution = claimedJob,
            initialStepExecution = claimedStep,
            monotonicClock = monotonic,
        )
        monotonic.nowNanos = 20_000_000_000L
        wallClock.advance(Duration.ofSeconds(1))
        guard.checkBothAndMaybeRenew()

        val checkpointed = guard.saveCheckpoint("checkpoint-1")

        checkpointed.version shouldBeEqualTo claimedStep.version + 2L
        repository.loadCheckpoint(claimedStep.id) shouldBeEqualTo "checkpoint-1"
        guard.latestSnapshot().stepExecution.shouldNotBeNull().version shouldBeEqualTo checkpointed.version
    }

    @Test
    fun `renewal null이면 write block을 호출하지 않고 lease loss를 기록한다`() = runSuspendIO {
        val wallClock = MutableBatchClock(Instant.parse("2026-08-31T00:00:00Z"))
        val monotonic = MutableBatchMonotonicClock()
        val delegate = InMemoryBatchJobRepository(wallClock)
        val job = delegate.findOrCreateJobExecution("guardJob")
        val step = delegate.findOrCreateStepExecution(job, "guardStep")
        val claimedJob = delegate.claimJobExecution(job, "owner-1", Duration.ofSeconds(30))
        val claimedStep = delegate.claimStepExecution(step, "owner-1", Duration.ofSeconds(30))
        claimedJob.shouldNotBeNull()
        claimedStep.shouldNotBeNull()
        val repository = RenewalRejectingRepository(delegate)
        val guard = BatchLeaseGuard(
            repository = repository,
            ownerId = "owner-1",
            executionLease = Duration.ofSeconds(30),
            initialJobExecution = claimedJob,
            initialStepExecution = claimedStep,
            monotonicClock = monotonic,
        )
        monotonic.nowNanos = 20_000_000_000L

        var writes = 0
        assertFailsWith<LeaseLostException> {
            guard.withWritePermit {
                writes++
            }
        }

        writes shouldBeEqualTo 0
        assertFailsWith<LeaseLostException> {
            guard.latestSnapshot()
        }
    }

    @Test
    fun `외부 write가 대기하는 동안 heartbeat renewal은 mutex에 차단되지 않는다`() = runSuspendIO {
        val wallClock = MutableBatchClock(Instant.parse("2026-08-31T00:00:00Z"))
        val delegate = InMemoryBatchJobRepository(wallClock)
        val job = delegate.findOrCreateJobExecution("longWriteJob")
        val step = delegate.findOrCreateStepExecution(job, "longWriteStep")
        val claimedJob = delegate.claimJobExecution(job, "owner-1", Duration.ofSeconds(30)).shouldNotBeNull()
        val claimedStep = delegate.claimStepExecution(step, "owner-1", Duration.ofSeconds(30)).shouldNotBeNull()
        val renewalObserved = CompletableDeferred<Unit>()
        val repository = RenewalObservingRepository(delegate, renewalObserved)
        val heartbeatPaused = CompletableDeferred<Unit>()
        val continueHeartbeat = CompletableDeferred<Unit>()
        val writeStarted = CompletableDeferred<Unit>()
        val finishWrite = CompletableDeferred<Unit>()
        val guard = BatchLeaseGuard(
            repository = repository,
            ownerId = "owner-1",
            executionLease = Duration.ofSeconds(30),
            initialJobExecution = claimedJob,
            initialStepExecution = claimedStep,
            pause = {
                heartbeatPaused.complete(Unit)
                continueHeartbeat.await()
            },
        )

        coroutineScope {
            guard.startHeartbeat(this)
            heartbeatPaused.await()
            val write = async {
                guard.withWritePermit {
                    writeStarted.complete(Unit)
                    finishWrite.await()
                }
            }
            writeStarted.await()
            wallClock.advance(Duration.ofSeconds(1))
            continueHeartbeat.complete(Unit)

            withTimeout(1_000L) { renewalObserved.await() }

            finishWrite.complete(Unit)
            write.await()
            guard.stopHeartbeat()
        }
    }

    @Test
    fun `heartbeat의 lease loss는 parent를 즉시 취소하고 guard의 canonical failure로 남긴다`() = runSuspendIO {
        val wallClock = MutableBatchClock(Instant.parse("2026-08-31T00:00:00Z"))
        val delegate = InMemoryBatchJobRepository(wallClock)
        val job = delegate.findOrCreateJobExecution("heartbeatLeaseLossJob")
        val step = delegate.findOrCreateStepExecution(job, "heartbeatLeaseLossStep")
        val claimedJob = delegate.claimJobExecution(job, "owner-1", Duration.ofSeconds(30)).shouldNotBeNull()
        val claimedStep = delegate.claimStepExecution(step, "owner-1", Duration.ofSeconds(30)).shouldNotBeNull()
        val guard = BatchLeaseGuard(
            repository = RenewalRejectingRepository(delegate),
            ownerId = "owner-1",
            executionLease = Duration.ofSeconds(30),
            initialJobExecution = claimedJob,
            initialStepExecution = claimedStep,
            pause = {},
        )

        val heartbeatFailure = assertFailsWith<LeaseLostException> {
            coroutineScope {
                guard.startHeartbeat(this).join()
            }
        }
        val snapshotFailure = assertFailsWith<LeaseLostException> {
            guard.latestSnapshot()
        }
        val canonicalFailure = heartbeatFailure.cause as? LeaseLostException ?: heartbeatFailure
        snapshotFailure shouldBeSameInstanceAs canonicalFailure
    }

    @Test
    fun `heartbeat cleanup 자체 timeout은 lease loss로 기록한다`() = runTest {
        val repository = InMemoryBatchJobRepository()
        val job = repository.findOrCreateJobExecution("cleanupTimeoutJob")
        val claimedJob = repository.claimJobExecution(job, "owner-1", Duration.ofSeconds(30)).shouldNotBeNull()
        val pauseStarted = CompletableDeferred<Unit>()
        val releasePause = CompletableDeferred<Unit>()
        val guard = BatchLeaseGuard(
            repository = repository,
            ownerId = "owner-1",
            executionLease = Duration.ofSeconds(30),
            initialJobExecution = claimedJob,
            initialStepExecution = null,
            pause = {
                pauseStarted.complete(Unit)
                withContext(NonCancellable) {
                    releasePause.await()
                }
            },
        )
        val heartbeat = guard.startHeartbeat(this)
        pauseStarted.await()

        try {
            assertFailsWith<LeaseLostException> {
                guard.stopHeartbeat()
            }
            guard.hasLostLease() shouldBeEqualTo true
        } finally {
            releasePause.complete(Unit)
            heartbeat.join()
        }
    }

    @Test
    fun `상위 timeout은 lease loss로 변환하지 않고 그대로 전파한다`() = runTest {
        val delegate = InMemoryBatchJobRepository()
        val job = delegate.findOrCreateJobExecution("outerTimeoutJob")
        val step = delegate.findOrCreateStepExecution(job, "outerTimeoutStep")
        val claimedJob = delegate.claimJobExecution(job, "owner-1", Duration.ofSeconds(30)).shouldNotBeNull()
        val claimedStep = delegate.claimStepExecution(step, "owner-1", Duration.ofSeconds(30)).shouldNotBeNull()
        val repository = object : BatchJobRepository by delegate {
            override suspend fun saveCheckpointAndReturn(
                execution: StepExecution,
                checkpoint: Any,
            ): StepExecution = awaitCancellation()
        }
        val guard = BatchLeaseGuard(
            repository = repository,
            ownerId = "owner-1",
            executionLease = Duration.ofSeconds(30),
            initialJobExecution = claimedJob,
            initialStepExecution = claimedStep,
        )

        assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> {
            withTimeout(100L) {
                guard.saveCheckpoint("checkpoint")
            }
        }

        guard.hasLostLease() shouldBeEqualTo false
        guard.latestSnapshot().stepExecution.shouldNotBeNull() shouldBeEqualTo claimedStep
    }

    private class RenewalRejectingRepository(
        private val delegate: BatchJobRepository,
    ) : BatchJobRepository by delegate {
        override val supportsLeaseRenewal: Boolean = true

        override suspend fun renewExecutionLeases(
            jobExecution: JobExecution,
            stepExecution: StepExecution?,
            leaseDuration: Duration,
        ): BatchExecutionLeaseSnapshot? = null
    }

    private class RenewalObservingRepository(
        private val delegate: BatchJobRepository,
        private val renewalObserved: CompletableDeferred<Unit>,
    ) : BatchJobRepository by delegate {
        override val supportsLeaseRenewal: Boolean = true

        override suspend fun renewExecutionLeases(
            jobExecution: JobExecution,
            stepExecution: StepExecution?,
            leaseDuration: Duration,
        ): BatchExecutionLeaseSnapshot {
            renewalObserved.complete(Unit)
            return BatchExecutionLeaseSnapshot(
                jobExecution = jobExecution.copy(
                    leaseUntil = jobExecution.leaseUntil?.plus(leaseDuration),
                    version = jobExecution.version + 1,
                ),
                stepExecution = stepExecution?.copy(
                    leaseUntil = stepExecution.leaseUntil?.plus(leaseDuration),
                    version = stepExecution.version + 1,
                ),
            )
        }
    }

    private class MutableBatchClock(
        @Volatile
        private var current: Instant,
    ) : Clock() {
        override fun instant(): Instant = current

        override fun getZone(): ZoneId = ZoneId.of("UTC")

        override fun withZone(zone: ZoneId): Clock = this

        fun advance(duration: Duration) {
            current = current.plus(duration)
        }
    }

    private class MutableBatchMonotonicClock(
        var nowNanos: Long = 0L,
    ) : BatchMonotonicClock {
        override fun nowNanos(): Long = nowNanos
    }
}
