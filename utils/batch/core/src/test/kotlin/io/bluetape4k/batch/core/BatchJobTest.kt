package io.bluetape4k.batch.core

import io.bluetape4k.batch.api.BatchProcessor
import io.bluetape4k.batch.api.BatchInfrastructureFailureException
import io.bluetape4k.batch.api.BatchJobRepository
import io.bluetape4k.batch.api.BatchReader
import io.bluetape4k.batch.api.BatchReport
import io.bluetape4k.batch.api.BatchStatus
import io.bluetape4k.batch.api.SkipPolicy
import io.bluetape4k.batch.core.BatchStepRunnerTest.CollectingWriter
import io.bluetape4k.batch.core.BatchStepRunnerTest.ListBatchReader
import io.bluetape4k.batch.core.dsl.batchJob
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.workflow.api.WorkContext
import io.bluetape4k.assertions.shouldBe
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldHaveSize
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test

/**
 * [BatchJob]의 통합 테스트.
 *
 * ## 검증 항목
 * - 단일 Step 성공 → BatchReport.Success
 * - 다중 Step 순차 실행
 * - Step FAILED → BatchReport.Failure (후속 Step 미실행)
 * - Skip 발생 → BatchReport.PartiallyCompleted
 * - CancellationException 전파
 * - SuspendWork.execute() 매핑
 */
class BatchJobTest {

    private fun simpleStep(
        name: String,
        items: List<String>,
        writer: CollectingWriter<String> = CollectingWriter(),
        skipPolicy: SkipPolicy = SkipPolicy.NONE,
    ): BatchStep<String, String> = BatchStep(
        name = name,
        chunkSize = 10,
        reader = ListBatchReader(items),
        writer = writer,
        skipPolicy = skipPolicy,
    )

    private suspend fun runSingleStepJob(name: String, step: BatchStep<*, *>): BatchReport =
        batchJob(name) { addStep(step) }.run()

    private fun singleStepJob(name: String, step: BatchStep<*, *>): BatchJob =
        batchJob(name) { addStep(step) }

    private fun failingReader(message: String = "실패"): BatchReader<String> =
        object : BatchReader<String> {
            override suspend fun read(): String? = throw RuntimeException(message)
        }

    private fun failStep(
        name: String = "failStep",
        message: String = "실패",
    ): BatchStep<String, String> = BatchStep(
        name = name,
        chunkSize = 1,
        reader = failingReader(message),
        writer = CollectingWriter(),
    )

    // ─── 단일 Step 성공 ───────────────────────────────────────────────────────

    @Test
    fun `단일 Step 성공 - BatchReport Success 반환`() = runSuspendIO {
        val writer = CollectingWriter<String>()
        val step = simpleStep("step1", listOf("a", "b", "c"), writer)

        val report = runSingleStepJob("testJob", step)

        report shouldBeInstanceOf BatchReport.Success::class
        report.stepReports shouldHaveSize 1
        report.stepReports[0].status shouldBe BatchStatus.COMPLETED
        report.stepReports[0].writeCount shouldBeEqualTo 3L
        writer.collected shouldBeEqualTo listOf("a", "b", "c")
    }

    // ─── 다중 Step 순차 실행 ─────────────────────────────────────────────────

    @Test
    fun `다중 Step 순차 실행 - 모두 성공 → Success`() = runSuspendIO {
        val writer1 = CollectingWriter<String>()
        val writer2 = CollectingWriter<String>()
        val step1 = simpleStep("step1", listOf("a", "b"), writer1)
        val step2 = simpleStep("step2", listOf("c", "d"), writer2)

        val job = batchJob("multiStepJob") {
            addStep(step1)
            addStep(step2)
        }

        val report = job.run()

        report shouldBeInstanceOf BatchReport.Success::class
        report.stepReports shouldHaveSize 2
        report.stepReports[0].stepName shouldBeEqualTo "step1"
        report.stepReports[1].stepName shouldBeEqualTo "step2"
        writer1.collected shouldBeEqualTo listOf("a", "b")
        writer2.collected shouldBeEqualTo listOf("c", "d")
    }

    @Test
    fun `Job claim 실패는 raw cause 없이 category를 보존한 Failure로 매핑`() = runSuspendIO {
        val delegate = InMemoryBatchJobRepository()
        val repository = object : BatchJobRepository by delegate {
            override suspend fun claimJobExecution(
                execution: io.bluetape4k.batch.api.JobExecution,
                ownerId: String,
                leaseDuration: java.time.Duration,
            ): io.bluetape4k.batch.api.JobExecution? {
                throw IllegalStateException("database password leaked")
            }
        }
        val writer = CollectingWriter<String>()
        val job = BatchJob(
            name = "claim-failure-job",
            steps = listOf(simpleStep("step", listOf("never"), writer)),
            repository = repository,
        )

        val report = job.run()

        report shouldBeInstanceOf BatchReport.Failure::class
        val failure = report as BatchReport.Failure
        failure.error shouldBeInstanceOf BatchInfrastructureFailureException::class
        val diagnostic = failure.error as BatchInfrastructureFailureException
        diagnostic.category shouldBeEqualTo BatchInfrastructureFailureException.REPOSITORY_FAILURE
        diagnostic.cause shouldBe null
        diagnostic.suppressed shouldHaveSize 0
        diagnostic.correlationId.length shouldBeEqualTo 16
        failure.jobExecution.params shouldBeEqualTo emptyMap()
        failure.jobExecution.ownerId shouldBe null
        failure.jobExecution.leaseUntil shouldBe null
        writer.collected.isEmpty().shouldBeTrue()
    }

    @Test
    fun `lease renewal 미지원 repository는 첫 persistence 전에 Failure로 종료`() = runSuspendIO {
        val delegate = InMemoryBatchJobRepository()
        var findCalls = 0
        val repository = object : BatchJobRepository by delegate {
            override val supportsLeaseRenewal: Boolean = false

            override suspend fun findOrCreateJobExecution(
                jobName: String,
                params: Map<String, Any>,
            ): io.bluetape4k.batch.api.JobExecution {
                findCalls++
                return delegate.findOrCreateJobExecution(jobName, params)
            }
        }

        val report = BatchJob(
            name = "unsupported-lease-job",
            steps = listOf(simpleStep("step", listOf("never"))),
            repository = repository,
        ).run()

        report shouldBeInstanceOf BatchReport.Failure::class
        val failure = report as BatchReport.Failure
        failure.error shouldBeInstanceOf BatchInfrastructureFailureException::class
        failure.jobExecution.id shouldBeEqualTo 0L
        failure.jobExecution.params shouldBeEqualTo emptyMap()
        findCalls shouldBeEqualTo 0
    }

    // ─── Step FAILED → 후속 미실행 ───────────────────────────────────────────

    @Test
    fun `Step FAILED - BatchReport Failure 반환, 후속 Step 미실행`() = runSuspendIO {
        val writer2 = CollectingWriter<String>()
        val step1 = failStep("failStep", "step 1 read 실패")
        val step2 = simpleStep("step2", listOf("x"), writer2)

        val job = batchJob("failJob") {
            addStep(step1)
            addStep(step2)
        }

        val report = job.run()

        report shouldBeInstanceOf BatchReport.Failure::class
        val failure = report as BatchReport.Failure
        failure.error.shouldNotBeNull()
        failure.stepReports shouldHaveSize 1  // step2 미실행
        failure.stepReports[0].stepName shouldBeEqualTo "failStep"
        writer2.collected.isEmpty().shouldBeTrue()  // step2 미실행
    }

    // ─── Skip 발생 → PartiallyCompleted ─────────────────────────────────────

    @Test
    fun `Skip 발생 - BatchReport PartiallyCompleted 반환`() = runSuspendIO {
        val writer = CollectingWriter<String>()
        val step = BatchStep(
            name = "skipStep",
            chunkSize = 5,
            reader = ListBatchReader(listOf("ok", "bad", "ok2")),
            processor = BatchProcessor<String, String> { item ->
                if (item == "bad") throw IllegalArgumentException("skip me") else item
            },
            writer = writer,
            skipPolicy = SkipPolicy.ALL,
        )

        val report = runSingleStepJob("skipJob", step)

        report shouldBeInstanceOf BatchReport.PartiallyCompleted::class
        report.stepReports[0].skipCount shouldBeEqualTo 1L
    }

    // ─── CancellationException 전파 ──────────────────────────────────────────

    @Test
    fun `CancellationException - STOPPED 영속화 후 재던짐`() = runSuspendIO {
        val cancellingReader = object : BatchReader<String> {
            override suspend fun read(): String = throw kotlinx.coroutines.CancellationException("외부 취소")
        }
        val step = BatchStep(
            name = "step1",
            chunkSize = 1,
            reader = cancellingReader,
            writer = CollectingWriter(),
        )

        val job = singleStepJob("cancelJob", step)

        var thrown: Throwable? = null
        try {
            job.run()
        } catch (e: kotlinx.coroutines.CancellationException) {
            thrown = e
        }

        thrown.shouldNotBeNull()
        thrown shouldBeInstanceOf kotlinx.coroutines.CancellationException::class
    }

    @Test
    fun `heartbeat cleanup timeout은 재진입하지 않고 sanitized lease loss를 반환한다`() = runTest {
        val pauseStarted = CompletableDeferred<Unit>()
        val releasePause = CompletableDeferred<Unit>()
        var read = false
        val reader = object : BatchReader<String> {
            override suspend fun read(): String? {
                if (read) return null
                pauseStarted.await()
                read = true
                return "item"
            }
        }
        val repository = InMemoryBatchJobRepository()
        val job = BatchJob(
            name = "cleanup-timeout-job",
            params = mapOf("secret" to "must-not-leak"),
            steps = listOf(
                BatchStep(
                    name = "step",
                    chunkSize = 1,
                    reader = reader,
                    writer = CollectingWriter(),
                ),
            ),
            repository = repository,
            executionLease = java.time.Duration.ofSeconds(30),
        )
        job.heartbeatPause = {
            pauseStarted.complete(Unit)
            withContext(NonCancellable) {
                releasePause.await()
            }
        }

        try {
            val report = job.run()

            report shouldBeInstanceOf BatchReport.Failure::class
            val failure = report as BatchReport.Failure
            failure.error shouldBeInstanceOf BatchInfrastructureFailureException::class
            val diagnostic = failure.error as BatchInfrastructureFailureException
            diagnostic.category shouldBeEqualTo BatchInfrastructureFailureException.LEASE_LOST
            diagnostic.cause shouldBe null
            failure.jobExecution.params shouldBeEqualTo emptyMap()
            failure.jobExecution.ownerId shouldBe null
            failure.jobExecution.leaseUntil shouldBe null
        } finally {
            releasePause.complete(Unit)
        }
    }

    // ─── 재시작: FAILED 잡 재시작 시 COMPLETED Step skip ────────────────────

    @Test
    fun `재시작 - FAILED 잡 재시작 시 COMPLETED Step은 skip되고 나머지만 실행`() = runSuspendIO {
        val writer1 = CollectingWriter<String>()
        val repo = InMemoryBatchJobRepository()

        // 1차 실행: step1 완료, step2 실패 → BatchReport.Failure
        val job1 = batchJob("restartJob") {
            repository(repo)
            addStep(simpleStep("step1", listOf("a", "b"), writer1))
            addStep(failStep("step2", "step2 강제 실패"))
        }
        val report1 = job1.run()
        report1 shouldBeInstanceOf BatchReport.Failure::class
        writer1.collected shouldBeEqualTo listOf("a", "b")  // step1은 성공

        // 2차 실행: FAILED JobExecution 재사용 → step1 COMPLETED(skip), step2 재실행
        val writer2b = CollectingWriter<String>()
        val job2 = batchJob("restartJob") {
            repository(repo)
            addStep(simpleStep("step1", listOf("x", "y"), writer1))  // step1 skip → writer1에 추가 없음
            addStep(simpleStep("step2", listOf("c", "d"), writer2b))
        }
        val report2 = job2.run()

        report2 shouldBeInstanceOf BatchReport.Success::class
        writer1.collected shouldBeEqualTo listOf("a", "b")  // step1 재실행 안 됨 (skip)
        writer2b.collected shouldBeEqualTo listOf("c", "d")  // step2 정상 실행
    }

    // ─── SuspendWork.execute() 매핑 ─────────────────────────────────────────

    @Test
    fun `execute - Success → WorkReport success`() = runSuspendIO {
        val step = simpleStep("step1", listOf("a"))
        val job = singleStepJob("workJob", step)

        val context = WorkContext()
        job.execute(context).status shouldBe io.bluetape4k.workflow.api.WorkStatus.COMPLETED
        context.contains("batch.workJob.report").shouldBeTrue()
    }

    @Test
    fun `execute - Failure → WorkReport failure`() = runSuspendIO {
        val failingStep = failStep(name = "step1")

        val job = singleStepJob("failWorkJob", failingStep)

        val context = WorkContext()
        job.execute(context).status shouldBe io.bluetape4k.workflow.api.WorkStatus.FAILED
    }

    @Test
    fun `execute - PartiallyCompleted → WorkReport success`() = runSuspendIO {
        val step = BatchStep(
            name = "step1",
            chunkSize = 5,
            reader = ListBatchReader(listOf("ok", "bad")),
            processor = BatchProcessor<String, String> { item ->
                if (item == "bad") throw IllegalArgumentException() else item
            },
            writer = CollectingWriter(),
            skipPolicy = SkipPolicy.ALL,
        )

        val job = singleStepJob("partialJob", step)

        val context = WorkContext()
        job.execute(context).status shouldBe io.bluetape4k.workflow.api.WorkStatus.COMPLETED
        context.get<Long>("batch.partialJob.skipCount") shouldBeEqualTo 1L
    }
}
