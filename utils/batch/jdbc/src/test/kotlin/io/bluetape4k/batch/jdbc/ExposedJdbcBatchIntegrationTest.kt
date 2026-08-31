package io.bluetape4k.batch.jdbc

import io.bluetape4k.batch.BatchSourceTable
import io.bluetape4k.batch.BatchTargetTable
import io.bluetape4k.batch.SourceRecord
import io.bluetape4k.batch.TargetRecord
import io.bluetape4k.batch.api.BatchInfrastructureFailureException
import io.bluetape4k.batch.api.BatchJobRepository
import io.bluetape4k.batch.api.BatchReader
import io.bluetape4k.batch.api.BatchReport
import io.bluetape4k.batch.api.BatchStatus
import io.bluetape4k.batch.api.BatchWriter
import io.bluetape4k.batch.api.SkipPolicy
import io.bluetape4k.batch.api.StepExecution
import io.bluetape4k.batch.api.StepReport
import io.bluetape4k.batch.core.dsl.batchJob
import io.bluetape4k.batch.CheckpointJson
import io.bluetape4k.batch.jdbc.tables.BatchJobExecutionTable
import io.bluetape4k.batch.jdbc.tables.BatchStepExecutionTable
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withTables
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.assertions.shouldBe
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFailsWith

/**
 * JDBC 배치 엔드투엔드 통합 테스트.
 *
 * [ExposedJdbcBatchJobRepository] + [ExposedJdbcBatchReader] + [ExposedJdbcBatchWriter]를
 * [batchJob] DSL로 조합하여 전체 파이프라인 실행을 검증한다.
 *
 * H2 / PostgreSQL / MySQL 각 방언에서:
 * 1. 정상 실행 → COMPLETED
 * 2. FAILED 재시작 → step1 skip + step2 재실행
 * 3. Processor 변환 + skip 처리 → COMPLETED_WITH_SKIPS
 */
class ExposedJdbcBatchIntegrationTest : AbstractBatchJdbcTest() {

    private val allTables = arrayOf(
        BatchJobExecutionTable, BatchStepExecutionTable,
        BatchSourceTable, BatchTargetTable,
    )

    private fun withAllTables(testDB: TestDB, block: suspend () -> Unit) {
        withTables(testDB, *allTables) {
            runSuspendIO { block() }
        }
    }

    private fun insertSourceData(testDB: TestDB, count: Int) {
        transaction(testDB.db!!) {
            BatchSourceTable.batchInsert((1..count).toList()) { i ->
                this[BatchSourceTable.name] = "item-$i"
                this[BatchSourceTable.value] = i
            }
        }
    }

    private fun countTarget(testDB: TestDB): Long =
        transaction(testDB.db!!) { BatchTargetTable.selectAll().count() }

    private fun makeJob(testDB: TestDB, chunkSize: Int = 10) = batchJob("integrationJob") {
        repository(ExposedJdbcBatchJobRepository(testDB.db!!, CheckpointJson.jackson3()))
        step<SourceRecord, TargetRecord>("readAndWrite") {
            reader(ExposedJdbcBatchReader(
                database = testDB.db!!,
                table = BatchSourceTable,
                keyColumn = BatchSourceTable.id,
                pageSize = chunkSize,
                rowMapper = { row ->
                    SourceRecord(
                        id = row[BatchSourceTable.id],
                        name = row[BatchSourceTable.name],
                        value = row[BatchSourceTable.value],
                    )
                },
                keyExtractor = { it.id },
            ))
            processor { src -> TargetRecord(src.name.uppercase(), src.value * 2) }
            writer(ExposedJdbcBatchWriter(testDB.db!!, BatchTargetTable) { record ->
                this[BatchTargetTable.sourceName] = record.sourceName
                this[BatchTargetTable.transformedValue] = record.transformedValue
            })
            chunkSize(chunkSize)
        }
    }

    private fun makeAtomicClaimJob(
        repository: BatchJobRepository,
        writer: SlowCountingWriter,
    ) = batchJob("atomicClaimJob") {
        repository(repository)
        step<Int, Int>("slowStep") {
            reader(SlowListReader(listOf(1, 2, 3)))
            writer(writer)
            chunkSize(1)
        }
    }

    private fun makeCheckpointJob(
        testDB: TestDB,
        reader: BatchReader<String>,
        writer: BatchWriter<String>,
        repository: BatchJobRepository = ExposedJdbcBatchJobRepository(testDB.db!!, CheckpointJson.jackson3()),
    ) = batchJob("failedCheckpointJob") {
        repository(repository)
        params("partition" to "test")
        step<String, String>("checkpointStep") {
            reader(reader)
            writer(writer)
            chunkSize(1)
        }
    }

    // ─── 1. 정상 실행 → COMPLETED ─────────────────────────────────────────────

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `정상 실행 - 100개 레코드 전부 변환 저장 COMPLETED`(testDB: TestDB) {
        withAllTables(testDB) {
            insertSourceData(testDB, 100)
            val report = makeJob(testDB, chunkSize = 20).run()

            report shouldBeInstanceOf BatchReport.Success::class
            report.stepReports[0].readCount shouldBeEqualTo 100L
            report.stepReports[0].writeCount shouldBeEqualTo 100L
            report.stepReports[0].skipCount shouldBeEqualTo 0L
            countTarget(testDB) shouldBeEqualTo 100L
        }
    }

    // ─── 2. 빈 소스 → COMPLETED, 0개 ──────────────────────────────────────────

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `빈 소스 - 읽기 아이템 없음 COMPLETED 0건`(testDB: TestDB) {
        withAllTables(testDB) {
            val report = makeJob(testDB).run()

            report shouldBeInstanceOf BatchReport.Success::class
            report.stepReports[0].writeCount shouldBeEqualTo 0L
        }
    }

    // ─── 3. FAILED 재시작 → 기존 Job 재사용 ──────────────────────────────────

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `FAILED 재시작 - 동일 JobExecution 재사용 완료`(testDB: TestDB) {
        withAllTables(testDB) {
            insertSourceData(testDB, 50)
            val repo = ExposedJdbcBatchJobRepository(testDB.db!!, CheckpointJson.jackson3())

            // 1차 실행 후 FAILED 시뮬레이션 (직접 repo 조작)
            val je = repo.findOrCreateJobExecution("integrationJob", emptyMap())
            repo.completeJobExecution(je, BatchStatus.FAILED)

            // 2차 실행 — FAILED JobExecution 재사용
            val report = makeJob(testDB, chunkSize = 10).run()

            report shouldBeInstanceOf BatchReport.Success::class
            countTarget(testDB) shouldBeEqualTo 50L
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `FAILED 청크 뒤 마지막 checkpoint를 보존하고 재시작한다`(testDB: TestDB) {
        withAllTables(testDB) {
            val firstWriter = RecordingWriter()
            val firstReport = makeCheckpointJob(
                testDB,
                FailingAfterCheckpointReader(),
                firstWriter,
            ).run()

            firstReport shouldBeInstanceOf BatchReport.Failure::class
            firstReport.stepReports.single().checkpoint shouldBeEqualTo "checkpoint-1"
            firstReport.stepReports.single().writeCount shouldBeEqualTo 1L
            firstWriter.items shouldBeEqualTo listOf("first")

            val repository = ExposedJdbcBatchJobRepository(testDB.db!!, CheckpointJson.jackson3())
            val execution = repository.findOrCreateJobExecution(
                "failedCheckpointJob",
                mapOf("partition" to "test"),
            )
            val storedStep = repository.findOrCreateStepExecution(execution, "checkpointStep")
            storedStep.checkpoint shouldBeEqualTo "checkpoint-1"

            val restartReader = ResumingReader()
            val restartWriter = RecordingWriter()
            val restartReport = makeCheckpointJob(testDB, restartReader, restartWriter).run()

            restartReport shouldBeInstanceOf BatchReport.Success::class
            restartReader.restoredFromValue shouldBeEqualTo "checkpoint-1"
            restartWriter.items shouldBeEqualTo listOf("second")
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `checkpoint 저장 실패는 raw cause를 숨기고 성공 writer counter를 보존한다`(testDB: TestDB) {
        withAllTables(testDB) {
            val delegate = ExposedJdbcBatchJobRepository(testDB.db!!, CheckpointJson.jackson3())
            val firstWriter = RecordingWriter()
            val report = makeCheckpointJob(
                testDB = testDB,
                reader = CheckpointingReader(),
                writer = firstWriter,
                repository = FailingCheckpointRepository(delegate),
            ).run()

            report shouldBeInstanceOf BatchReport.Failure::class
            report.stepReports.single().status shouldBe BatchStatus.FAILED
            report.stepReports.single().checkpoint shouldBe null
            report.stepReports.single().writeCount shouldBeEqualTo 1L
            firstWriter.items shouldBeEqualTo listOf("first")

            val execution = delegate.findOrCreateJobExecution(
                "failedCheckpointJob",
                mapOf("partition" to "test"),
            )
            val storedStep = delegate.findOrCreateStepExecution(execution, "checkpointStep")
            storedStep.status shouldBe BatchStatus.RUNNING
            storedStep.checkpoint shouldBe null
            storedStep.writeCount shouldBeEqualTo 0L
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `동시 실행 - 이미 claim된 JobExecution은 두 번째 runner가 실행하지 않음`(testDB: TestDB) {
        withAllTables(testDB) {
            val repo = ExposedJdbcBatchJobRepository(testDB.db!!, CheckpointJson.jackson3())
            val writer = SlowCountingWriter()

            val reports = coroutineScope {
                val first = async { makeAtomicClaimJob(repo, writer).run() }
                writer.opened.await()
                val second = async { makeAtomicClaimJob(repo, writer).run() }

                awaitAll(first, second)
            }

            reports.count { it is BatchReport.Success } shouldBeEqualTo 1
            reports.count { it is BatchReport.Failure } shouldBeEqualTo 1
            val failure = reports.filterIsInstance<BatchReport.Failure>().single()
            failure.error shouldBeInstanceOf BatchInfrastructureFailureException::class
            val diagnostic = failure.error as BatchInfrastructureFailureException
            diagnostic.category shouldBe BatchInfrastructureFailureException.EXECUTION_ALREADY_CLAIMED
            diagnostic.cause shouldBe null
            writer.openCount.get() shouldBeEqualTo 1
            writer.writeCount.get() shouldBeEqualTo 3
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `실행 lease 갱신은 Job과 Step 버전을 함께 증가시킨다`(testDB: TestDB) {
        withAllTables(testDB) {
            val repository = ExposedJdbcBatchJobRepository(testDB.db!!, CheckpointJson.jackson3())
            val initialJob = repository.findOrCreateJobExecution("leaseRenewalJob", emptyMap())
            val claimedJob = checkNotNull(
                repository.claimJobExecution(initialJob, "lease-owner", Duration.ofMinutes(1)),
            )
            val initialStep = repository.findOrCreateStepExecution(claimedJob, "leaseStep")
            val claimedStep = checkNotNull(
                repository.claimStepExecution(initialStep, "lease-owner", Duration.ofMinutes(1)),
            )

            val renewed = checkNotNull(
                repository.renewExecutionLeases(claimedJob, claimedStep, Duration.ofMinutes(1)),
            )

            renewed.jobExecution.version shouldBeEqualTo claimedJob.version + 1
            renewed.jobExecution.ownerId shouldBeEqualTo "lease-owner"
            renewed.stepExecution?.version shouldBeEqualTo claimedStep.version + 1
            renewed.stepExecution?.ownerId shouldBeEqualTo "lease-owner"
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    @Suppress("DEPRECATION")
    fun `lease 만료 takeover 뒤 stale owner의 외부 write와 상태 저장을 차단한다`(testDB: TestDB) {
        withAllTables(testDB) {
            val repository = ExposedJdbcBatchJobRepository(testDB.db!!, CheckpointJson.jackson3())
            val initialJob = repository.findOrCreateJobExecution("leaseTakeoverJob", emptyMap())
            val staleJob = checkNotNull(repository.claimJobExecution(initialJob, "owner-a", Instant.EPOCH))
            val initialStep = repository.findOrCreateStepExecution(staleJob, "leaseStep")
            val staleStep = checkNotNull(repository.claimStepExecution(initialStep, "owner-a", Instant.EPOCH))
            val currentJob = checkNotNull(
                repository.claimJobExecution(staleJob, "owner-b", Duration.ofMinutes(1)),
            )
            val currentStep = checkNotNull(
                repository.claimStepExecution(staleStep, "owner-b", Duration.ofMinutes(1)),
            )
            val externalWriteCount = AtomicInteger()

            if (repository.renewExecutionLeases(staleJob, staleStep, Duration.ofMinutes(1)) != null) {
                externalWriteCount.incrementAndGet()
            }
            checkNotNull(repository.renewExecutionLeases(currentJob, currentStep, Duration.ofMinutes(1)))
            externalWriteCount.incrementAndGet()

            externalWriteCount.get() shouldBeEqualTo 1
            assertFailsWith<IllegalStateException> {
                repository.saveCheckpointAndReturn(staleStep, "stale-checkpoint")
            }
            assertFailsWith<IllegalStateException> {
                repository.completeStepExecution(
                    staleStep,
                    StepReport("leaseStep", BatchStatus.COMPLETED),
                )
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `두 BatchJob runner의 lease takeover 뒤 stale runner는 다음 write를 시작하지 않는다`(testDB: TestDB) {
        withAllTables(testDB) {
            val repository = ExposedJdbcBatchJobRepository(testDB.db!!, CheckpointJson.jackson3())
            val staleReader = BarrierCheckpointReader(listOf(1, 2))
            val staleWriter = BarrierCountingWriter()
            val currentWriter = CountingWriter()

            coroutineScope {
                val staleRun = async {
                    makeTakeoverJob(repository, staleReader, staleWriter).run()
                }
                staleWriter.firstWriteStarted.await()

                transaction(testDB.db!!) {
                    BatchJobExecutionTable.update {
                        it[leaseUntil] = Instant.EPOCH
                    }
                    BatchStepExecutionTable.update {
                        it[leaseUntil] = Instant.EPOCH
                    }
                }

                val currentReport = withTimeout(10_000L) {
                    makeTakeoverJob(repository, SlowListReader(listOf(1, 2)), currentWriter).run()
                }
                staleWriter.releaseFirstWrite.complete(Unit)
                val staleReport = withTimeout(10_000L) { staleRun.await() }

                currentReport shouldBeInstanceOf BatchReport.Success::class
                staleReport shouldBeInstanceOf BatchReport.Failure::class
                currentWriter.writeCount.get() shouldBeEqualTo 2
                staleWriter.writeCount.get() shouldBeEqualTo 1
            }
        }
    }

    // ─── 4. skip 있음 → COMPLETED_WITH_SKIPS ────────────────────────────────

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `skip 있음 - 일부 아이템 skip → COMPLETED_WITH_SKIPS`(testDB: TestDB) {
        withAllTables(testDB) {
            insertSourceData(testDB, 10)
            val repo = ExposedJdbcBatchJobRepository(testDB.db!!, CheckpointJson.jackson3())

            val job = batchJob("skipJob") {
                repository(repo)
                step<SourceRecord, TargetRecord>("readAndWrite") {
                    reader(ExposedJdbcBatchReader(
                        database = testDB.db!!,
                        table = BatchSourceTable,
                        keyColumn = BatchSourceTable.id,
                        pageSize = 10,
                        rowMapper = { row ->
                            SourceRecord(
                                id = row[BatchSourceTable.id],
                                name = row[BatchSourceTable.name],
                                value = row[BatchSourceTable.value],
                            )
                        },
                        keyExtractor = { it.id },
                    ))
                    // value가 짝수인 아이템 processor에서 예외 → skip
                    processor { src ->
                        if (src.value % 2 == 0) throw IllegalArgumentException("even skip")
                        TargetRecord(src.name, src.value)
                    }
                    writer(ExposedJdbcBatchWriter(testDB.db!!, BatchTargetTable) { record ->
                        this[BatchTargetTable.sourceName] = record.sourceName
                        this[BatchTargetTable.transformedValue] = record.transformedValue
                    })
                    chunkSize(10)
                    skipPolicy(SkipPolicy.ALL)
                }
            }

            val report = job.run()

            report shouldBeInstanceOf BatchReport.PartiallyCompleted::class
            val stepReport = report.stepReports[0]
            stepReport.status shouldBe BatchStatus.COMPLETED_WITH_SKIPS
            stepReport.skipCount shouldBeEqualTo 5L   // 짝수 5개 skip
            stepReport.writeCount shouldBeEqualTo 5L  // 홀수 5개 저장
        }
    }

    private class SlowListReader(
        items: List<Int>,
    ): BatchReader<Int> {
        private val queue = ArrayDeque(items)

        override suspend fun read(): Int? = queue.removeFirstOrNull()
    }

    private class SlowCountingWriter: BatchWriter<Int> {
        val openCount = AtomicInteger()
        val writeCount = AtomicInteger()
        val opened = CompletableDeferred<Unit>()

        override suspend fun open() {
            openCount.incrementAndGet()
            opened.complete(Unit)
        }

        override suspend fun write(items: List<Int>) {
            delay(200)
            writeCount.addAndGet(items.size)
        }
    }

    private fun makeTakeoverJob(
        repository: BatchJobRepository,
        reader: BatchReader<Int>,
        writer: BatchWriter<Int>,
    ) = batchJob("runnerTakeoverJob") {
        repository(repository)
        executionLease(Duration.ofSeconds(30))
        step<Int, Int>("takeoverStep") {
            reader(reader)
            writer(writer)
            chunkSize(1)
        }
    }

    private class BarrierCheckpointReader(
        items: List<Int>,
    ) : BatchReader<Int> {
        private val queue = ArrayDeque(items)
        private var committedCount = 0

        override suspend fun read(): Int? = queue.removeFirstOrNull()

        override suspend fun onChunkCommitted() {
            committedCount++
        }

        override suspend fun checkpoint(): Any? = committedCount.takeIf { it > 0 }
    }

    private class BarrierCountingWriter : BatchWriter<Int> {
        val firstWriteStarted = CompletableDeferred<Unit>()
        val releaseFirstWrite = CompletableDeferred<Unit>()
        val writeCount = AtomicInteger()

        override suspend fun write(items: List<Int>) {
            if (writeCount.get() == 0) {
                firstWriteStarted.complete(Unit)
                releaseFirstWrite.await()
            }
            writeCount.addAndGet(items.size)
        }
    }

    private class CountingWriter : BatchWriter<Int> {
        val writeCount = AtomicInteger()

        override suspend fun write(items: List<Int>) {
            writeCount.addAndGet(items.size)
        }
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

    private class CheckpointingReader : BatchReader<String> {
        private var read = false
        private var chunkCommitted = false

        override suspend fun read(): String? = if (read) null else "first".also { read = true }

        override suspend fun checkpoint(): Any? = "checkpoint-1".takeIf { chunkCommitted }

        override suspend fun onChunkCommitted() {
            chunkCommitted = true
        }
    }

    private class FailingCheckpointRepository(
        private val delegate: BatchJobRepository,
    ) : BatchJobRepository by delegate {
        override suspend fun saveCheckpointAndReturn(
            execution: StepExecution,
            checkpoint: Any,
        ): StepExecution = throw IllegalStateException("checkpoint save failed")
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
}
