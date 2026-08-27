package io.bluetape4k.batch.core

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBe
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.batch.api.BatchJobRepository
import io.bluetape4k.batch.api.BatchReader
import io.bluetape4k.batch.api.BatchReport
import io.bluetape4k.batch.api.BatchStatus
import io.bluetape4k.batch.api.BatchWriter
import io.bluetape4k.batch.api.JobExecution
import io.bluetape4k.batch.api.StepExecution
import io.bluetape4k.batch.api.StepReport
import io.bluetape4k.batch.CheckpointJson
import io.bluetape4k.batch.jdbc.ExposedJdbcBatchJobRepository
import io.bluetape4k.batch.jdbc.tables.BatchJobExecutionTable
import io.bluetape4k.batch.jdbc.tables.BatchStepExecutionTable
import io.bluetape4k.batch.r2dbc.ExposedR2dbcBatchJobRepository
import io.bluetape4k.exposed.r2dbc.tests.TestDB as R2dbcTestDB
import io.bluetape4k.exposed.r2dbc.tests.withTables as withR2dbcTables
import io.bluetape4k.exposed.tests.TestDB as JdbcTestDB
import io.bluetape4k.exposed.tests.withTables as withJdbcTables
import io.bluetape4k.junit5.coroutines.runSuspendIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger

/**
 * FAILED/STOPPED 상태 저장 실패가 실행 원인과 취소 전파에서 사라지지 않는지 검증한다.
 *
 * 자동 재시도나 outbox는 이 모듈의 저장소 계약에 포함하지 않는다. 실행기는 저장 실패를
 * 원인 예외에 suppressed cause로 연결하고 error 로그를 남기며, 재시도·알림은 호출자와
 * 저장소 운영 정책이 결정한다.
 */
class BatchFailurePersistenceTest {

    @Test
    fun `step FAILED 저장 실패는 원인 예외에 suppressed 된다`() = runSuspendIO {
        val primaryFailure = IllegalStateException("reader failed")
        val persistenceFailure = IllegalStateException("step state write failed")
        val repository = FailingCompletionRepository(stepFailure = persistenceFailure)

        val report = job(repository, FailingReader(primaryFailure)).run()

        report shouldBeInstanceOf BatchReport.Failure::class
        val failure = report as BatchReport.Failure
        failure.error shouldBeSameInstanceAs primaryFailure
        failure.error.suppressed.single() shouldBeSameInstanceAs persistenceFailure

        val storedJob = repository.findOrCreateJobExecution("failure-persistence")
        val storedStep = repository.findOrCreateStepExecution(storedJob, "step")
        storedStep.status shouldBe BatchStatus.RUNNING
        repository.claimStepExecution(
            storedStep,
            ownerId = "replacement-owner",
            leaseUntil = checkNotNull(storedStep.leaseUntil),
        ) shouldBe null
    }

    @Test
    fun `job FAILED 저장 실패는 원인 예외에 suppressed 된다`() = runSuspendIO {
        val primaryFailure = IllegalStateException("reader failed")
        val persistenceFailure = IllegalStateException("job state write failed")
        val repository = FailingCompletionRepository(jobFailure = persistenceFailure)

        val report = job(repository, FailingReader(primaryFailure)).run()

        report shouldBeInstanceOf BatchReport.Failure::class
        val failure = report as BatchReport.Failure
        failure.error shouldBeSameInstanceAs primaryFailure
        failure.error.suppressed.single() shouldBeSameInstanceAs persistenceFailure

        val storedJob = repository.findOrCreateJobExecution("failure-persistence")
        storedJob.status shouldBe BatchStatus.RUNNING
        val storedStep = repository.findOrCreateStepExecution(storedJob, "step")
        storedStep.status shouldBe BatchStatus.FAILED
        repository.claimJobExecution(
            storedJob,
            ownerId = "replacement-owner",
            leaseUntil = checkNotNull(storedJob.leaseUntil),
        ) shouldBe null
    }

    @Test
    fun `step STOPPED 저장 실패도 CancellationException에 suppressed 된다`() = runSuspendIO {
        val cancellation = CancellationException("external cancellation")
        val persistenceFailure = IllegalStateException("step stopped state write failed")
        val repository = FailingCompletionRepository(stepFailure = persistenceFailure)
        val execution = repository.findOrCreateJobExecution("failure-persistence")
        val step = step(FailingReader(cancellation))

        val thrown = assertFailsWith<CancellationException> {
            BatchStepRunner(step, execution, repository).run()
        }

        thrown shouldBeSameInstanceAs cancellation
        thrown.suppressed.single() shouldBeSameInstanceAs persistenceFailure
        val storedStep = repository.findOrCreateStepExecution(execution, "step")
        storedStep.status shouldBe BatchStatus.RUNNING
    }

    @Test
    fun `실제 Job 취소도 NonCancellable checkpoint와 STOPPED 저장을 보존한다`() = runSuspendIO {
        val repository = InMemoryBatchJobRepository()
        val execution = repository.findOrCreateJobExecution("actual-cancellation")
        val readerEntered = CompletableDeferred<Unit>()
        val reader = CancellationAwareReader(readerEntered)
        coroutineScope {
            val request = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                BatchStepRunner(step(reader), execution, repository).run()
            }

            readerEntered.await()
            request.cancel(CancellationException("external cancellation"))

            assertFailsWith<CancellationException> { request.await() }
            reader.checkpointCalls.get() shouldBeEqualTo 1
            val storedStep = repository.findOrCreateStepExecution(execution, "step")
            storedStep.status shouldBe BatchStatus.STOPPED
            storedStep.checkpoint shouldBe "checkpoint"
        }
    }

    @Test
    fun `checkpoint 커밋 직후 취소도 InMemory STOPPED 저장과 재claim을 보존한다`() = runSuspendIO {
        assertCheckpointCommitCancellationRecovery(InMemoryBatchJobRepository())
    }

    @Test
    fun `checkpoint 커밋 직후 취소도 JDBC STOPPED 저장과 재claim을 보존한다`() = withJdbcBatchTables {
        assertCheckpointCommitCancellationRecovery(
            ExposedJdbcBatchJobRepository(checkNotNull(it.db), CheckpointJson.jackson3()),
        )
    }

    @Test
    fun `checkpoint 커밋 직후 취소도 R2DBC STOPPED 저장과 재claim을 보존한다`() = runSuspendIO {
        withR2dbcBatchTables {
            assertCheckpointCommitCancellationRecovery(
                ExposedR2dbcBatchJobRepository(checkNotNull(it.db), CheckpointJson.jackson3()),
            )
        }
    }

    @Test
    fun `FAILED Step 저장 중 CancellationException은 원래 실패와 함께 전파된다`() = runSuspendIO {
        val primaryFailure = IllegalStateException("reader failed")
        val persistenceCancellation = CancellationException("step completion cancelled")
        val repository = FailingCompletionRepository(stepFailure = persistenceCancellation)

        val thrown = assertFailsWith<CancellationException> {
            job(repository, FailingReader(primaryFailure)).run()
        }

        thrown shouldBeSameInstanceAs persistenceCancellation
        thrown.suppressed.single() shouldBeSameInstanceAs primaryFailure
    }

    @Test
    fun `Job FAILED 저장 중 CancellationException은 원래 실패와 함께 전파된다`() = runSuspendIO {
        val primaryFailure = IllegalStateException("reader failed")
        val persistenceCancellation = CancellationException("job completion cancelled")
        val repository = FailingCompletionRepository(jobFailure = persistenceCancellation)

        val thrown = assertFailsWith<CancellationException> {
            job(repository, FailingReader(primaryFailure)).run()
        }

        thrown.message shouldBe persistenceCancellation.message
        persistenceCancellation.suppressed.single() shouldBeSameInstanceAs primaryFailure
    }

    @Test
    fun `job STOPPED 저장 실패도 원래 CancellationException을 재던진다`() = runSuspendIO {
        val cancellation = CancellationException("external cancellation")
        val persistenceFailure = IllegalStateException("job stopped state write failed")
        val repository = FailingCompletionRepository(jobFailure = persistenceFailure)

        val thrown = assertFailsWith<CancellationException> {
            job(repository, FailingReader(cancellation)).run()
        }

        thrown shouldBeSameInstanceAs cancellation
        thrown.suppressed.single() shouldBeSameInstanceAs persistenceFailure
        val storedJob = repository.findOrCreateJobExecution("failure-persistence")
        storedJob.status shouldBe BatchStatus.RUNNING
        val storedStep = repository.findOrCreateStepExecution(storedJob, "step")
        storedStep.status shouldBe BatchStatus.STOPPED
    }

    @Test
    fun `상태 저장 실패 예외는 error logger에 연결된다`() = runSuspendIO {
        val persistenceFailure = IllegalStateException("step state write failed")
        val repository = FailingCompletionRepository(stepFailure = persistenceFailure)
        val appender = RecordingFailureLogAppender()

        try {
            job(repository, FailingReader(IllegalStateException("reader failed"))).run()
            appender.failureMessages shouldContain "FAILED 상태 저장 실패"
            appender.throwableMessages shouldBeEqualTo listOf("step state write failed")
            appender.errorMessages shouldBeEqualTo listOf("step state write failed")
        } finally {
            appender.close()
        }
    }

    @Test
    fun `JDBC repository도 FAILED 저장 실패 원인 보존과 lease 보호 계약을 따른다`() = withJdbcBatchTables {
        val delegate = ExposedJdbcBatchJobRepository(
            checkNotNull(it.db),
            CheckpointJson.jackson3(),
        )
        val primaryFailure = IllegalStateException("jdbc reader failed")
        val persistenceFailure = IllegalStateException("jdbc job state write failed")
        val repository = FailingCompletionRepository(delegate, jobFailure = persistenceFailure)

        val report = job(repository, FailingReader(primaryFailure)).run()

        report shouldBeInstanceOf BatchReport.Failure::class
        val failure = report as BatchReport.Failure
        failure.error shouldBeSameInstanceAs primaryFailure
        failure.error.suppressed.single() shouldBeSameInstanceAs persistenceFailure

        val storedJob = repository.reloadJobExecution()
        storedJob.status shouldBe BatchStatus.RUNNING
        repository.claimJobExecution(
            storedJob,
            ownerId = "jdbc-replacement-owner",
            leaseUntil = checkNotNull(storedJob.leaseUntil),
        ) shouldBe null
        repository.reloadStepExecution(storedJob).status shouldBe BatchStatus.FAILED
    }

    @Test
    fun `JDBC repository의 STOPPED Job 저장 실패도 원인과 lease를 보존한다`() = withJdbcBatchTables {
        assertJobStoppedPersistenceFailure(
            ExposedJdbcBatchJobRepository(checkNotNull(it.db), CheckpointJson.jackson3()),
        )
    }

    @Test
    fun `JDBC repository의 FAILED Step 저장 실패도 원인과 lease를 보존한다`() = withJdbcBatchTables {
        assertStepFailedPersistenceFailure(
            ExposedJdbcBatchJobRepository(checkNotNull(it.db), CheckpointJson.jackson3()),
        )
    }

    @Test
    fun `JDBC repository의 STOPPED Step 저장 실패도 cancellation을 보존한다`() = withJdbcBatchTables {
        assertStepStoppedPersistenceFailure(
            ExposedJdbcBatchJobRepository(checkNotNull(it.db), CheckpointJson.jackson3()),
        )
    }

    @Test
    fun `R2DBC repository도 FAILED 저장 실패 원인 보존과 lease 보호 계약을 따른다`() = runSuspendIO {
        withR2dbcBatchTables {
            val delegate = ExposedR2dbcBatchJobRepository(
                checkNotNull(it.db),
                CheckpointJson.jackson3(),
            )
            val primaryFailure = IllegalStateException("r2dbc reader failed")
            val persistenceFailure = IllegalStateException("r2dbc job state write failed")
            val repository = FailingCompletionRepository(delegate, jobFailure = persistenceFailure)

            val report = job(repository, FailingReader(primaryFailure)).run()

            report shouldBeInstanceOf BatchReport.Failure::class
            val failure = report as BatchReport.Failure
            failure.error shouldBeSameInstanceAs primaryFailure
            failure.error.suppressed.single() shouldBeSameInstanceAs persistenceFailure

            val storedJob = repository.reloadJobExecution()
            storedJob.status shouldBe BatchStatus.RUNNING
            repository.claimJobExecution(
                storedJob,
                ownerId = "r2dbc-replacement-owner",
                leaseUntil = checkNotNull(storedJob.leaseUntil),
            ) shouldBe null
            repository.reloadStepExecution(storedJob).status shouldBe BatchStatus.FAILED
        }
    }

    @Test
    fun `R2DBC repository의 STOPPED Job 저장 실패도 원인과 lease를 보존한다`() = runSuspendIO {
        withR2dbcBatchTables {
            assertJobStoppedPersistenceFailure(
                ExposedR2dbcBatchJobRepository(checkNotNull(it.db), CheckpointJson.jackson3()),
            )
        }
    }

    @Test
    fun `R2DBC repository의 FAILED Step 저장 실패도 원인과 lease를 보존한다`() = runSuspendIO {
        withR2dbcBatchTables {
            assertStepFailedPersistenceFailure(
                ExposedR2dbcBatchJobRepository(checkNotNull(it.db), CheckpointJson.jackson3()),
            )
        }
    }

    @Test
    fun `R2DBC repository의 STOPPED Step 저장 실패도 cancellation을 보존한다`() = runSuspendIO {
        withR2dbcBatchTables {
            assertStepStoppedPersistenceFailure(
                ExposedR2dbcBatchJobRepository(checkNotNull(it.db), CheckpointJson.jackson3()),
            )
        }
    }

    @Test
    fun `인메모리 FAILED Step은 마지막 성공 checkpoint로 재시작한다`() = runSuspendIO {
        assertFailedCheckpointRestart(InMemoryBatchJobRepository())
    }

    @Test
    fun `JDBC FAILED Step은 마지막 성공 checkpoint로 재시작한다`() = withJdbcBatchTables {
        assertFailedCheckpointRestart(
            ExposedJdbcBatchJobRepository(checkNotNull(it.db), CheckpointJson.jackson3()),
        )
    }

    @Test
    fun `R2DBC FAILED Step은 마지막 성공 checkpoint로 재시작한다`() = runSuspendIO {
        withR2dbcBatchTables {
            assertFailedCheckpointRestart(
                ExposedR2dbcBatchJobRepository(checkNotNull(it.db), CheckpointJson.jackson3()),
            )
        }
    }

    @Test
    fun `인메모리 FAILED completion의 null checkpoint는 기존 값을 보존한다`() = runSuspendIO {
        assertNullFailureCheckpointPreserved(InMemoryBatchJobRepository())
    }

    @Test
    fun `FAILED checkpoint 조회 중 CancellationException은 STOPPED 저장 후 전파된다`() = runSuspendIO {
        val primaryFailure = IllegalStateException("reader failed")
        val cancellation = CancellationException("checkpoint lookup cancelled")
        val repository = InMemoryBatchJobRepository()
        val execution = repository.findOrCreateJobExecution("checkpoint-cancellation")

        val thrown = assertFailsWith<CancellationException> {
            BatchStepRunner(
                step(CheckpointFailingReader(primaryFailure, cancellation)),
                execution,
                repository,
            ).run()
        }

        thrown shouldBeSameInstanceAs cancellation
        thrown.suppressed.single() shouldBeSameInstanceAs primaryFailure

        val storedStep = repository.findOrCreateStepExecution(execution, "step")
        storedStep.status shouldBe BatchStatus.STOPPED
        storedStep.ownerId shouldBe null
        storedStep.leaseUntil shouldBe null
        repository.claimStepExecution(
            storedStep,
            ownerId = "replacement-owner",
            leaseUntil = java.time.Instant.now().plusSeconds(60),
        ).shouldNotBeNull()
    }

    @Test
    fun `JDBC FAILED completion의 null checkpoint는 기존 값을 보존한다`() = withJdbcBatchTables {
        assertNullFailureCheckpointPreserved(
            ExposedJdbcBatchJobRepository(checkNotNull(it.db), CheckpointJson.jackson3()),
        )
    }

    @Test
    fun `R2DBC FAILED completion의 null checkpoint는 기존 값을 보존한다`() = runSuspendIO {
        withR2dbcBatchTables {
            assertNullFailureCheckpointPreserved(
                ExposedR2dbcBatchJobRepository(checkNotNull(it.db), CheckpointJson.jackson3()),
            )
        }
    }

    private suspend fun assertFailedCheckpointRestart(repository: BatchJobRepository) {
        val firstReader = FailingAfterCheckpointReader()
        val firstExecution = repository.findOrCreateJobExecution("failed-checkpoint")

        val firstReport = BatchStepRunner(
            step(firstReader, RecordingWriter()),
            firstExecution,
            repository,
        ).run()

        firstReport.status shouldBe BatchStatus.FAILED
        firstReport.checkpoint shouldBeEqualTo "checkpoint-1"
        repository.findOrCreateStepExecution(firstExecution, "step").checkpoint shouldBeEqualTo "checkpoint-1"

        val restartReader = ResumingReader()
        val restartWriter = RecordingWriter()
        val restartExecution = repository.findOrCreateJobExecution("failed-checkpoint")
        val restartReport = BatchStepRunner(
            step(restartReader, restartWriter),
            restartExecution,
            repository,
        ).run()

        restartReport.status shouldBe BatchStatus.COMPLETED
        restartReader.restoredFromValue shouldBeEqualTo "checkpoint-1"
        restartWriter.items shouldBeEqualTo listOf("second")
    }

    private suspend fun assertNullFailureCheckpointPreserved(repository: BatchJobRepository) {
        val jobExecution = repository.findOrCreateJobExecution("null-failure-checkpoint")
        val stepExecution = repository.findOrCreateStepExecution(jobExecution, "step")
        val claimed = repository.claimStepExecution(
            stepExecution,
            ownerId = "checkpoint-owner",
            leaseUntil = java.time.Instant.now().plusSeconds(60),
        ).shouldNotBeNull()
        val checkpointed = repository.saveCheckpointAndReturn(claimed, "checkpoint-1")

        repository.completeStepExecution(
            checkpointed,
            StepReport("step", BatchStatus.FAILED),
        )

        repository.loadCheckpoint(stepExecution.id) shouldBeEqualTo "checkpoint-1"
        repository.findOrCreateStepExecution(jobExecution, "step").checkpoint shouldBeEqualTo "checkpoint-1"
    }

    private suspend fun assertCheckpointCommitCancellationRecovery(delegate: BatchJobRepository) {
        val checkpointPersisted = CompletableDeferred<Unit>()
        val releaseCheckpointReturn = CompletableDeferred<Unit>()
        val repository = CommitReturnSuspendingRepository(
            delegate = delegate,
            checkpointPersisted = checkpointPersisted,
            releaseCheckpointReturn = releaseCheckpointReturn,
        )
        val execution = repository.findOrCreateJobExecution("checkpoint-commit-cancellation")
        val cancellation = CancellationException("cancelled after checkpoint commit")

        coroutineScope {
            val request = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                BatchStepRunner(
                    step(CommitThenCancellationReader()),
                    execution,
                    repository,
                ).run()
            }

            checkpointPersisted.await()
            request.cancel(cancellation)
            releaseCheckpointReturn.complete(Unit)

            val thrown = assertFailsWith<CancellationException> { request.await() }
            thrown.message shouldBeEqualTo cancellation.message
        }

        val storedStep = repository.findOrCreateStepExecution(execution, "step")
        storedStep.status shouldBe BatchStatus.STOPPED
        storedStep.ownerId shouldBe null
        storedStep.leaseUntil shouldBe null
        storedStep.checkpoint shouldBeEqualTo "checkpoint-1"
        repository.claimStepExecution(
            storedStep,
            ownerId = "replacement-owner",
            leaseUntil = java.time.Instant.now().plusSeconds(60),
        ).shouldNotBeNull()
    }

    private suspend fun assertJobStoppedPersistenceFailure(delegate: BatchJobRepository) {
        val cancellation = CancellationException("job external cancellation")
        val persistenceFailure = IllegalStateException("job stopped state write failed")
        val repository = FailingCompletionRepository(delegate, jobFailure = persistenceFailure)

        val thrown = assertFailsWith<CancellationException> {
            job(repository, FailingReader(cancellation)).run()
        }

        thrown shouldBeSameInstanceAs cancellation
        thrown.suppressed.single() shouldBeSameInstanceAs persistenceFailure
        val storedJob = repository.reloadJobExecution()
        storedJob.status shouldBe BatchStatus.RUNNING
        repository.claimJobExecution(
            storedJob,
            ownerId = "replacement-owner",
            leaseUntil = checkNotNull(storedJob.leaseUntil),
        ) shouldBe null
        repository.reloadStepExecution(storedJob).status shouldBe BatchStatus.STOPPED
    }

    private suspend fun assertStepFailedPersistenceFailure(delegate: BatchJobRepository) {
        val primaryFailure = IllegalStateException("reader failed")
        val persistenceFailure = IllegalStateException("step state write failed")
        val repository = FailingCompletionRepository(delegate, stepFailure = persistenceFailure)

        val report = job(repository, FailingReader(primaryFailure)).run()

        report shouldBeInstanceOf BatchReport.Failure::class
        val failure = report as BatchReport.Failure
        failure.error shouldBeSameInstanceAs primaryFailure
        failure.error.suppressed.single() shouldBeSameInstanceAs persistenceFailure
        val storedJob = repository.reloadJobExecution()
        val storedStep = repository.reloadStepExecution(storedJob)
        storedStep.status shouldBe BatchStatus.RUNNING
        repository.claimStepExecution(
            storedStep,
            ownerId = "replacement-owner",
            leaseUntil = checkNotNull(storedStep.leaseUntil),
        ) shouldBe null
    }

    private suspend fun assertStepStoppedPersistenceFailure(delegate: BatchJobRepository) {
        val cancellation = CancellationException("step external cancellation")
        val persistenceFailure = IllegalStateException("step stopped state write failed")
        val repository = FailingCompletionRepository(delegate, stepFailure = persistenceFailure)
        val execution = repository.findOrCreateJobExecution("failure-persistence")

        val thrown = assertFailsWith<CancellationException> {
            BatchStepRunner(step(FailingReader(cancellation)), execution, repository).run()
        }

        thrown shouldBeSameInstanceAs cancellation
        thrown.suppressed.single() shouldBeSameInstanceAs persistenceFailure
        repository.reloadStepExecution(execution).status shouldBe BatchStatus.RUNNING
    }

    private fun job(
        repository: BatchJobRepository,
        reader: BatchReader<String>,
    ): BatchJob = BatchJob(
        name = "failure-persistence",
        steps = listOf(step(reader)),
        repository = repository,
    )

    private fun step(
        reader: BatchReader<String>,
        writer: BatchWriter<String> = NoopWriter(),
    ): BatchStep<String, String> = BatchStep(
        name = "step",
        chunkSize = 1,
        reader = reader,
        writer = writer,
    )

    private class FailingReader(
        private val failure: Throwable,
    ) : BatchReader<String> {
        override suspend fun read(): String? = throw failure
    }

    private class CheckpointFailingReader(
        private val readFailure: Throwable,
        private val checkpointFailure: CancellationException,
    ) : BatchReader<String> {
        override suspend fun read(): String? = throw readFailure

        override suspend fun checkpoint(): Any? = throw checkpointFailure
    }

    private class NoopWriter : BatchWriter<String> {
        override suspend fun write(items: List<String>) = Unit
    }

    private class RecordingWriter : BatchWriter<String> {
        val items = mutableListOf<String>()

        override suspend fun write(items: List<String>) {
            this.items += items
        }
    }

    private class FailingAfterCheckpointReader : BatchReader<String> {
        private var readIndex = 0
        private var chunkCommitted = false

        override suspend fun read(): String? = when (readIndex++) {
            0 -> "first"
            1 -> throw IllegalStateException("reader failed after successful chunk")
            else -> null
        }

        override suspend fun checkpoint(): Any? = "checkpoint-1".takeIf { chunkCommitted }

        override suspend fun onChunkCommitted() {
            chunkCommitted = true
        }
    }

    private class ResumingReader : BatchReader<String> {
        private var read = false
        private var chunkCommitted = false
        var restoredFromValue: Any? = null
            private set

        override suspend fun restoreFrom(checkpoint: Any) {
            restoredFromValue = checkpoint
        }

        override suspend fun read(): String? = if (read) null else "second".also { read = true }

        override suspend fun checkpoint(): Any? = "checkpoint-2".takeIf { chunkCommitted }

        override suspend fun onChunkCommitted() {
            chunkCommitted = true
        }
    }

    private class CommitThenCancellationReader : BatchReader<String> {
        private var readIndex = 0

        override suspend fun read(): String? = when (readIndex++) {
            0 -> "first"
            else -> awaitCancellation()
        }

        override suspend fun checkpoint(): Any = "checkpoint-1"
    }

    private class RecordingFailureLogAppender : AppenderBase<ILoggingEvent>(), AutoCloseable {
        private val logger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        private val capturedEvents = ConcurrentLinkedDeque<ILoggingEvent>()

        val failureMessages: String
            get() = failureEvents.joinToString("\n") { it.formattedMessage }

        val throwableMessages: List<String>
            get() = failureEvents.mapNotNull { it.throwableProxy?.message }

        val errorMessages: List<String>
            get() = failureEvents
                .filter { it.level == Level.ERROR }
                .mapNotNull { it.throwableProxy?.message }

        private val failureEvents: List<ILoggingEvent>
            get() = capturedEvents.filter { it.formattedMessage.contains("FAILED 상태 저장 실패") }

        init {
            start()
            logger.addAppender(this)
        }

        override fun append(eventObject: ILoggingEvent?) {
            eventObject?.let(capturedEvents::addLast)
        }

        override fun close() {
            logger.detachAppender(this)
            capturedEvents.clear()
            stop()
        }
    }

    private class CancellationAwareReader(
        private val entered: CompletableDeferred<Unit>,
    ) : BatchReader<String> {
        val checkpointCalls = AtomicInteger()

        override suspend fun read(): String? {
            entered.complete(Unit)
            awaitCancellation()
        }

        override suspend fun checkpoint(): Any {
            checkpointCalls.incrementAndGet()
            return "checkpoint"
        }
    }

    private class FailingCompletionRepository(
        private val delegate: BatchJobRepository = InMemoryBatchJobRepository(),
        private val jobFailure: Throwable? = null,
        private val stepFailure: Throwable? = null,
    ) : BatchJobRepository by delegate {

        override suspend fun completeJobExecution(execution: JobExecution, status: BatchStatus) {
            jobFailure?.let { throw it }
            delegate.completeJobExecution(execution, status)
        }

        override suspend fun completeStepExecution(execution: StepExecution, report: StepReport) {
            stepFailure?.let { throw it }
            delegate.completeStepExecution(execution, report)
        }

        suspend fun reloadJobExecution(): JobExecution =
            delegate.findOrCreateJobExecution("failure-persistence")

        suspend fun reloadStepExecution(jobExecution: JobExecution): StepExecution =
            delegate.findOrCreateStepExecution(jobExecution, "step")
    }

    private class CommitReturnSuspendingRepository(
        private val delegate: BatchJobRepository,
        private val checkpointPersisted: CompletableDeferred<Unit>,
        private val releaseCheckpointReturn: CompletableDeferred<Unit>,
    ) : BatchJobRepository by delegate {

        override suspend fun saveCheckpointAndReturn(
            execution: StepExecution,
            checkpoint: Any,
        ): StepExecution {
            val updated = delegate.saveCheckpointAndReturn(execution, checkpoint)
            checkpointPersisted.complete(Unit)
            releaseCheckpointReturn.await()
            return updated
        }
    }

    private fun withJdbcBatchTables(block: suspend (JdbcTestDB) -> Unit) {
        val testDB = JdbcTestDB.H2
        withJdbcTables(testDB, BatchJobExecutionTable, BatchStepExecutionTable) {
            runSuspendIO { block(testDB) }
        }
    }

    private suspend fun withR2dbcBatchTables(block: suspend (R2dbcTestDB) -> Unit) {
        val testDB = R2dbcTestDB.H2
        withR2dbcTables(testDB, BatchJobExecutionTable, BatchStepExecutionTable) {
            block(testDB)
        }
    }

}
