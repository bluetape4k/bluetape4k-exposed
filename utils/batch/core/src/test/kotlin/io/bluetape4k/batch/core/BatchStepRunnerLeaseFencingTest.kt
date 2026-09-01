package io.bluetape4k.batch.core

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.batch.api.BatchExecutionLeaseSnapshot
import io.bluetape4k.batch.api.BatchInfrastructureFailureException
import io.bluetape4k.batch.api.BatchJobRepository
import io.bluetape4k.batch.api.BatchReader
import io.bluetape4k.batch.api.BatchStatus
import io.bluetape4k.batch.api.BatchWriter
import io.bluetape4k.batch.api.JobExecution
import io.bluetape4k.batch.api.StepExecution
import io.bluetape4k.junit5.coroutines.runSuspendIO
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** lease가 사라진 뒤 writer를 시작하지 않는 runner fencing 회귀 테스트. */
class BatchStepRunnerLeaseFencingTest {

    @Test
    fun `write 직전 renewal null이면 writer를 호출하지 않는다`() = runSuspendIO {
        val wallClock = MutableBatchClock(Instant.parse("2026-08-31T00:00:00Z"))
        val monotonic = MutableBatchMonotonicClock()
        val delegate = InMemoryBatchJobRepository(wallClock)
        val repository = RenewalRejectingRepository(delegate)
        val writer = RecordingWriter()
        val reader = AdvancingReader(monotonic)
        val step = BatchStep(
            name = "fencedStep",
            chunkSize = 1,
            reader = reader,
            writer = writer,
        )

        val report = BatchStepRunner(
            step = step,
            jobExecution = JobExecution(
                id = 1L,
                jobName = "fencedJob",
                status = BatchStatus.RUNNING,
            ),
            repository = repository,
            leaseDuration = Duration.ofSeconds(30),
            monotonicClock = monotonic,
        ).run()

        report.status shouldBeEqualTo BatchStatus.FAILED
        report.error shouldBeInstanceOf BatchInfrastructureFailureException::class
        val diagnostic = report.error as BatchInfrastructureFailureException
        diagnostic.category shouldBeEqualTo BatchInfrastructureFailureException.LEASE_LOST
        diagnostic.cause.shouldBeNull()
        writer.calls shouldBeEqualTo 0
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

    private class AdvancingReader(
        private val monotonic: MutableBatchMonotonicClock,
    ) : BatchReader<String> {
        private var first = true

        override suspend fun read(): String? = if (first) {
            first = false
            monotonic.nowNanos = 20_000_000_000L
            "item"
        } else {
            null
        }
    }

    private class RecordingWriter : BatchWriter<String> {
        var calls = 0

        override suspend fun write(items: List<String>) {
            calls++
        }
    }

    private class MutableBatchClock(
        private var current: Instant,
    ) : Clock() {
        override fun instant(): Instant = current

        override fun getZone(): ZoneId = ZoneId.of("UTC")

        override fun withZone(zone: ZoneId): Clock = this
    }

    private class MutableBatchMonotonicClock(
        var nowNanos: Long = 0L,
    ) : BatchMonotonicClock {
        override fun nowNanos(): Long = nowNanos
    }
}
