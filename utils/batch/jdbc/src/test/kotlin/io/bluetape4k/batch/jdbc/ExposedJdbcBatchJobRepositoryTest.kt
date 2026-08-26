package io.bluetape4k.batch.jdbc

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBe
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.batch.api.BatchStatus
import io.bluetape4k.batch.api.StepReport
import io.bluetape4k.batch.CheckpointJson
import io.bluetape4k.batch.jdbc.tables.BatchJobExecutionTable
import io.bluetape4k.batch.jdbc.tables.BatchStepExecutionTable
import io.bluetape4k.batch.jdbc.tables.toParamsHash
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withTables
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.junit5.concurrency.StructuredTaskScopeTester
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.support.requireNotNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledForJreRange
import org.junit.jupiter.api.condition.JRE
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * [ExposedJdbcBatchJobRepository] 통합 테스트.
 *
 * H2 / PostgreSQL / MySQL 각 방언에서:
 * 1. JobExecution 신규 생성
 * 2. FAILED Job 재시작 → 기존 재사용
 * 3. COMPLETED Job → 신규 생성
 * 4. StepExecution 4-case 계약 검증
 * 5. Checkpoint 저장 / 로드 round-trip
 */
class ExposedJdbcBatchJobRepositoryTest : AbstractBatchJdbcTest() {

    companion object {
        private const val ACTIVE_JOB_EXECUTION_UNIQUE_INDEX_SQL =
            """
            CREATE UNIQUE INDEX batch_job_exec_active_uidx
                ON batch_job_execution(job_name, params_hash)
                WHERE status IN ('RUNNING', 'FAILED', 'STOPPED')
            """
    }

    private val batchTables = arrayOf(BatchJobExecutionTable, BatchStepExecutionTable)

    private data class UnexpectedCheckpoint(val value: Long)

    private data class TamperedCheckpointCase(
        val label: String,
        val className: String,
        val payload: String,
    ) {
        val json: String = """{"className":"$className","payload":"$payload"}"""
    }

    private val tamperedCheckpointCases = listOf(
        TamperedCheckpointCase(
            label = "unknown",
            className = "com.example.DoesNotExist",
            payload = "{}",
        ),
        TamperedCheckpointCase(
            label = "disallowed",
            className = "java.lang.Runtime",
            payload = "{}",
        ),
        TamperedCheckpointCase(
            label = "unexpected",
            className = UnexpectedCheckpoint::class.java.name,
            payload = """{\"value\":1}""",
        ),
    )

    private fun withRepoTables(testDB: TestDB, block: suspend ExposedJdbcBatchJobRepository.() -> Unit) {
        withTables(testDB, *batchTables) {
            if (testDB == TestDB.POSTGRESQL) {
                exec(ACTIVE_JOB_EXECUTION_UNIQUE_INDEX_SQL)
            }
            commit()
            val repo = ExposedJdbcBatchJobRepository(testDB.db.requireNotNull("testDB.db"), CheckpointJson.jackson3())
            runSuspendIO { repo.block() }
        }
    }

    private fun activeJobExecutionCount(testDB: TestDB, jobName: String, params: Map<String, Any>): Long {
        val hash = params.toParamsHash()

        return transaction(testDB.db.requireNotNull("testDB.db")) {
            BatchJobExecutionTable.selectAll()
                .where {
                    (BatchJobExecutionTable.jobName eq jobName) and
                        (BatchJobExecutionTable.paramsHash eq hash) and
                        (BatchJobExecutionTable.status inList listOf(
                            BatchStatus.RUNNING,
                            BatchStatus.FAILED,
                            BatchStatus.STOPPED,
                        ))
                }
                .count()
        }
    }

    private fun tamperCheckpoint(testDB: TestDB, stepExecutionId: Long, checkpoint: String) {
        transaction(testDB.db.requireNotNull("testDB.db")) {
            BatchStepExecutionTable.update({ BatchStepExecutionTable.id eq stepExecutionId }) { row ->
                row[BatchStepExecutionTable.checkpoint] = checkpoint
            }
        }
    }

    @Test
    fun `params hash codec는 정렬·UTF-8·SHA-256 hex 계약을 유지한다`() {
        val params: Map<String, Any> = linkedMapOf(
            "region" to "KR",
            "date" to "2026-04-10",
        )
        val expectedHash =
            "3befc559f29662638764e67938f8d674155a356e91286dd4e6e5e3133b74d1fc"
        val utf8Params: Map<String, Any> = linkedMapOf(
            "지역" to "대한민국",
            "date" to "2026-04-10",
        )

        params.toParamsHash() shouldBeEqualTo expectedHash
        emptyMap<String, Any>().toParamsHash() shouldBeEqualTo ""
        utf8Params.toParamsHash() shouldBeEqualTo
            "ce97c1670f55a1644de4d550391f51abf054a79dff9aebaf39e1e7a7a312ab3b"
    }

    // ─── 1. JobExecution 신규 생성 ────────────────────────────────────────────

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `JobExecution 신규 생성 - RUNNING 상태로 반환`(testDB: TestDB) {
        withRepoTables(testDB) {
            val je = findOrCreateJobExecution("myJob", emptyMap())
            je.jobName shouldBeEqualTo "myJob"
            je.status shouldBe BatchStatus.RUNNING
            (je.id > 0L) shouldBe true
        }
    }

    // ─── 2. FAILED Job 재시작 → 기존 재사용 ────────────────────────────────────

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `FAILED Job 재시작 - 기존 JobExecution 재사용 후 claim 으로 RUNNING 전환`(testDB: TestDB) {
        withRepoTables(testDB) {
            val je1 = findOrCreateJobExecution("failedJob", emptyMap())
            completeJobExecution(je1, BatchStatus.FAILED)

            val je2 = findOrCreateJobExecution("failedJob", emptyMap())
            je2.id shouldBeEqualTo je1.id
            je2.status shouldBe BatchStatus.FAILED

            val claimed = claimJobExecution(je2, "owner-1", Instant.now().plusSeconds(60))
            claimed.shouldNotBeNull()
            claimed.status shouldBe BatchStatus.RUNNING
            claimed.ownerId shouldBeEqualTo "owner-1"
        }
    }

    // ─── 3. COMPLETED Job → 신규 생성 ────────────────────────────────────────

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `COMPLETED Job - 신규 JobExecution 생성`(testDB: TestDB) {
        withRepoTables(testDB) {
            val je1 = findOrCreateJobExecution("completedJob", emptyMap())
            completeJobExecution(je1, BatchStatus.COMPLETED)

            val je2 = findOrCreateJobExecution("completedJob", emptyMap())
            (je2.id > je1.id) shouldBe true
        }
    }

    // ─── 4. StepExecution 4-case 계약 ────────────────────────────────────────

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `StepExecution COMPLETED - UPDATE 없이 그대로 반환`(testDB: TestDB) {
        withRepoTables(testDB) {
            val je = findOrCreateJobExecution("stepJob", emptyMap())
            val se = findOrCreateStepExecution(je, "step1")
            completeStepExecution(se, StepReport("step1", BatchStatus.COMPLETED, readCount = 100L, writeCount = 100L))

            val se2 = findOrCreateStepExecution(je, "step1")
            se2.status shouldBe BatchStatus.COMPLETED
            se2.readCount shouldBeEqualTo 100L
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `StepExecution FAILED - claim 으로 RUNNING 복원`(testDB: TestDB) {
        withRepoTables(testDB) {
            val je = findOrCreateJobExecution("failedStepJob", emptyMap())
            val se = findOrCreateStepExecution(je, "step1")
            completeStepExecution(se, StepReport("step1", BatchStatus.FAILED))

            val se2 = findOrCreateStepExecution(je, "step1")
            se2.status shouldBe BatchStatus.FAILED

            val claimed = claimStepExecution(se2, "owner-1", Instant.now().plusSeconds(60))
            claimed.shouldNotBeNull()
            claimed.status shouldBe BatchStatus.RUNNING
            claimed.ownerId shouldBeEqualTo "owner-1"
        }
    }

    // ─── 5. Checkpoint round-trip ─────────────────────────────────────────────

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `Checkpoint 저장 - loadCheckpoint로 Long 값 복원`(testDB: TestDB) {
        withRepoTables(testDB) {
            val je = findOrCreateJobExecution("cpJob", emptyMap())
            val se = findOrCreateStepExecution(je, "step1")

            saveCheckpoint(se.id, 42L)
            val restored = loadCheckpoint(se.id)

            restored.shouldNotBeNull()
            (restored as Long) shouldBeEqualTo 42L
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `Checkpoint 미저장 - loadCheckpoint는 null 반환`(testDB: TestDB) {
        withRepoTables(testDB) {
            val je = findOrCreateJobExecution("noCpJob", emptyMap())
            val se = findOrCreateStepExecution(je, "step1")

            loadCheckpoint(se.id) shouldBe null
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `Owner-aware checkpoint - owner와 version CAS를 fail-closed 적용`(testDB: TestDB) {
        withRepoTables(testDB) {
            val je = findOrCreateJobExecution("ownerCpJob", emptyMap())
            val se = findOrCreateStepExecution(je, "step1")

            assertFailsWith<IllegalStateException> {
                saveCheckpointAndReturn(se, 1L)
            }
            assertFailsWith<IllegalStateException> {
                saveCheckpointAndReturn(se.copy(ownerId = "   "), 1L)
            }

            val claimed = claimStepExecution(se, "owner-1", Instant.now().plusSeconds(60)).shouldNotBeNull()
            assertFailsWith<IllegalStateException> {
                saveCheckpointAndReturn(claimed.copy(ownerId = "owner-2"), 2L)
            }

            val refreshed = saveCheckpointAndReturn(claimed, 3L)
            refreshed.version shouldBeEqualTo claimed.version + 1L
            loadCheckpoint(se.id) shouldBeEqualTo 3L

            assertFailsWith<IllegalStateException> {
                saveCheckpointAndReturn(claimed, 4L)
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `Owner-aware completion - wrong-owner와 stale version을 fail-closed 적용`(testDB: TestDB) {
        withRepoTables(testDB) {
            val job = findOrCreateJobExecution("completionJob", emptyMap())
            val claimedJob = claimJobExecution(job, "owner-1", Instant.now().plusSeconds(60)).shouldNotBeNull()

            assertFailsWith<IllegalStateException> {
                completeJobExecution(claimedJob.copy(ownerId = "owner-2"), BatchStatus.COMPLETED)
            }
            assertFailsWith<IllegalStateException> {
                completeJobExecution(claimedJob.copy(version = claimedJob.version - 1), BatchStatus.COMPLETED)
            }

            val step = findOrCreateStepExecution(job, "completionStep")
            val claimedStep = claimStepExecution(step, "owner-1", Instant.now().plusSeconds(60)).shouldNotBeNull()

            assertFailsWith<IllegalStateException> {
                completeStepExecution(
                    claimedStep.copy(ownerId = "owner-2"),
                    StepReport("completionStep", BatchStatus.COMPLETED),
                )
            }
            assertFailsWith<IllegalStateException> {
                completeStepExecution(
                    claimedStep.copy(version = claimedStep.version - 1),
                    StepReport("completionStep", BatchStatus.COMPLETED),
                )
            }

            val currentStep = findOrCreateStepExecution(job, "completionStep")
            currentStep.ownerId shouldBeEqualTo "owner-1"
            currentStep.version shouldBeEqualTo claimedStep.version
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `Checkpoint 변조 - 등록되지 않은 className 은 loadCheckpoint에서 거부`(testDB: TestDB) {
        withRepoTables(testDB) {
            val je = findOrCreateJobExecution("tamperedCpJob", emptyMap())

            tamperedCheckpointCases.forEachIndexed { index, case ->
                val se = findOrCreateStepExecution(je, "tampered-step-${case.label}-$index")

                tamperCheckpoint(testDB, se.id, case.json)

                val error = assertFailsWith<IllegalArgumentException> {
                    loadCheckpoint(se.id)
                }

                error.message.shouldNotBeNull() shouldContain case.className
            }
        }
    }

    // ─── 6. 동일 params 다른 Job → 별도 실행 ─────────────────────────────────

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `다른 params - 별도 JobExecution 생성`(testDB: TestDB) {
        withRepoTables(testDB) {
            val je1 = findOrCreateJobExecution("paramJob", mapOf("date" to "2026-04-10"))
            val je2 = findOrCreateJobExecution("paramJob", mapOf("date" to "2026-04-11"))

            je2.id shouldNotBeEqualTo je1.id
            (je2.id > je1.id) shouldBe true
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `동시 findOrCreateJobExecution - MultithreadingTester는 같은 JobExecution을 반환한다`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB == TestDB.POSTGRESQL }

        withRepoTables(testDB) {
            val repository = this
            val executionIds = ConcurrentLinkedQueue<Long>()

            MultithreadingTester()
                .workers(2)
                .rounds(1)
                .add {
                    runSuspendIO {
                        val jobExecution = repository.findOrCreateJobExecution(
                            jobName = "raceJob",
                            params = mapOf("runId" to "issue-124"),
                        )
                        executionIds += jobExecution.id
                    }
                }
                .run()

            executionIds shouldHaveSize 2
            executionIds.distinct() shouldHaveSize 1
            activeJobExecutionCount(testDB, "raceJob", mapOf("runId" to "issue-124")) shouldBeEqualTo 1L
        }
    }

    @EnabledForJreRange(min = JRE.JAVA_21)
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `동시 findOrCreateJobExecution - StructuredTaskScopeTester는 같은 JobExecution을 반환한다`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB == TestDB.POSTGRESQL }

        withRepoTables(testDB) {
            val repository = this
            val executionIds = ConcurrentLinkedQueue<Long>()

            StructuredTaskScopeTester()
                .rounds(2)
                .add {
                    runSuspendIO {
                        val jobExecution = repository.findOrCreateJobExecution(
                            jobName = "structuredRaceJob",
                            params = mapOf("runId" to "issue-124"),
                        )
                        executionIds += jobExecution.id
                    }
                }
                .run()

            executionIds shouldHaveSize 2
            executionIds.distinct() shouldHaveSize 1
            activeJobExecutionCount(testDB, "structuredRaceJob", mapOf("runId" to "issue-124")) shouldBeEqualTo 1L
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `동시 findOrCreateStepExecution - SELECT 경합 후 unique winner를 재조회한다`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB == TestDB.POSTGRESQL }

        withRepoTables(testDB) {
            val repository = this
            val job = findOrCreateJobExecution("stepRaceJob", emptyMap())
            val selected = AtomicInteger()
            val bothSelected = CompletableDeferred<Unit>()
            repository.beforeStepInsertHook = {
                if (selected.incrementAndGet() == 2) {
                    bothSelected.complete(Unit)
                }
                withTimeout(5_000) { bothSelected.await() }
            }

            val executions = try {
                coroutineScope {
                    (1..2).map {
                        async { repository.findOrCreateStepExecution(job, "raceStep") }
                    }.awaitAll()
                }
            } finally {
                repository.beforeStepInsertHook = null
            }

            executions.map { it.id }.distinct() shouldHaveSize 1
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `UniqueViolation 재조회 - winner row가 있으면 반환한다`(testDB: TestDB) {
        withRepoTables(testDB) {
            val created = findOrCreateJobExecution("retryWinnerJob", mapOf("date" to "2026-05-19"))

            val retried = requeryJobExecutionAfterUniqueViolation(
                jobName = "retryWinnerJob",
                params = mapOf("date" to "2026-05-19"),
            )

            retried.id shouldBeEqualTo created.id
            retried.status shouldBe BatchStatus.RUNNING
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `UniqueViolation 재조회 - winner row가 없으면 민감한 맥락 없이 IllegalStateException을 던진다`(testDB: TestDB) {
        withRepoTables(testDB) {
            val error = assertFailsWith<IllegalStateException> {
                requeryJobExecutionAfterUniqueViolation(
                    jobName = "missingRetryJob",
                    params = mapOf("date" to "2026-05-19"),
                )
            }

            val message = error.message.shouldNotBeNull()
            message shouldContain "Job execution disappeared after unique-constraint violation re-query"
            (message.contains("missingRetryJob") || message.contains("date=2026-05-19")) shouldBeEqualTo false
        }
    }
}
