package io.bluetape4k.batch.core

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.junit5.coroutines.runSuspendIO
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * 실행 lease 갱신 계약을 고정하는 RED→GREEN 테스트.
 *
 * Job과 Step은 하나의 갱신 경계에서 함께 전진해야 하며, 잘못된 owner/version
 * 입력은 어느 행도 변경하지 않고 거부되어야 한다.
 */
class BatchLeaseRenewalTest {

    @Test
    fun `lease 갱신은 Job과 Step의 version을 함께 증가시킨다`() = runSuspendIO {
        val repository = InMemoryBatchJobRepository()
        val job = repository.findOrCreateJobExecution("leaseJob")
        val step = repository.findOrCreateStepExecution(job, "leaseStep")

        repository.supportsLeaseRenewal.shouldBeTrue()

        val claimedJob = repository.claimJobExecution(job, "owner-1", Duration.ofSeconds(30))
        val claimedStep = repository.claimStepExecution(step, "owner-1", Duration.ofSeconds(30))
        claimedJob.shouldNotBeNull()
        claimedStep.shouldNotBeNull()

        val renewed = repository.renewExecutionLeases(
            claimedJob,
            claimedStep,
            Duration.ofSeconds(30),
        )

        renewed.shouldNotBeNull()
        renewed.jobExecution.ownerId shouldBeEqualTo "owner-1"
        renewed.jobExecution.version shouldBeEqualTo claimedJob.version + 1L
        renewed.stepExecution.shouldNotBeNull()
        renewed.stepExecution.ownerId shouldBeEqualTo "owner-1"
        renewed.stepExecution.version shouldBeEqualTo claimedStep.version + 1L
        val renewedJobLease = renewed.jobExecution.leaseUntil.shouldNotBeNull()
        val claimedJobLease = claimedJob.leaseUntil.shouldNotBeNull()
        val renewedStepLease = renewed.stepExecution.leaseUntil.shouldNotBeNull()
        val claimedStepLease = claimedStep.leaseUntil.shouldNotBeNull()
        (renewedJobLease > claimedJobLease).shouldBeTrue()
        (renewedStepLease > claimedStepLease).shouldBeTrue()
    }

    @Test
    fun `owner 불일치 갱신은 두 실행을 모두 변경하지 않는다`() = runSuspendIO {
        val repository = InMemoryBatchJobRepository()
        val job = repository.findOrCreateJobExecution("leaseJob")
        val step = repository.findOrCreateStepExecution(job, "leaseStep")
        val claimedJob = repository.claimJobExecution(job, "owner-1", Duration.ofSeconds(30))
        val claimedStep = repository.claimStepExecution(step, "owner-1", Duration.ofSeconds(30))
        claimedJob.shouldNotBeNull()
        claimedStep.shouldNotBeNull()

        repository.renewExecutionLeases(
            claimedJob.copy(ownerId = "owner-2"),
            claimedStep,
            Duration.ofSeconds(30),
        ).shouldBeNull()

        val currentJob = repository.findOrCreateJobExecution("leaseJob")
        val currentStep = repository.findOrCreateStepExecution(currentJob, "leaseStep")
        currentJob.version shouldBeEqualTo claimedJob.version
        currentStep.version shouldBeEqualTo claimedStep.version
        currentJob.ownerId shouldBeEqualTo "owner-1"
        currentStep.ownerId shouldBeEqualTo "owner-1"
        currentJob.leaseUntil shouldBeEqualTo claimedJob.leaseUntil
        currentStep.leaseUntil shouldBeEqualTo claimedStep.leaseUntil
    }

    @Test
    fun `갱신 duration은 30초 이상 24시간 이하만 허용한다`() = runSuspendIO {
        val repository = InMemoryBatchJobRepository()
        val job = repository.findOrCreateJobExecution("leaseJob")

        assertFailsWith<IllegalArgumentException> {
            repository.claimJobExecution(job, "owner-1", Duration.ofSeconds(29))
        }
        assertFailsWith<IllegalArgumentException> {
            repository.claimJobExecution(job, "owner-1", Duration.ofHours(24).plusMillis(1))
        }
    }
}
