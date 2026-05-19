package io.bluetape4k.batch.r2dbc

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBe
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.batch.api.BatchStatus
import io.bluetape4k.batch.api.StepReport
import io.bluetape4k.batch.internal.CheckpointJson
import io.bluetape4k.batch.jdbc.tables.BatchJobExecutionTable
import io.bluetape4k.batch.jdbc.tables.BatchStepExecutionTable
import io.bluetape4k.batch.jdbc.tables.toParamsHash
import io.bluetape4k.exposed.r2dbc.tests.TestDB
import io.bluetape4k.exposed.r2dbc.tests.withTables
import io.bluetape4k.junit5.coroutines.SuspendedJobTester
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.support.requireNotNull
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * [ExposedR2dbcBatchJobRepository] R2DBC 통합 테스트.
 *
 * H2 / PostgreSQL / MySQL 각 방언에서:
 * 1. JobExecution 신규 생성
 * 2. FAILED Job 재시작 → 기존 재사용
 * 3. COMPLETED Job → 신규 생성
 * 4. StepExecution 4-case 계약 검증
 * 5. Checkpoint 저장 / 로드 round-trip
 */
class ExposedR2dbcBatchJobRepositoryTest : AbstractBatchR2dbcTest() {

    companion object {
        private const val ACTIVE_JOB_EXECUTION_UNIQUE_INDEX_SQL =
            """
            CREATE UNIQUE INDEX batch_job_exec_active_uidx
                ON batch_job_execution(job_name, params_hash)
                WHERE status IN ('RUNNING', 'FAILED', 'STOPPED')
            """
    }

    private val batchTables = arrayOf(BatchJobExecutionTable, BatchStepExecutionTable)

    private suspend fun withRepoTables(
        testDB: TestDB,
        block: suspend ExposedR2dbcBatchJobRepository.() -> Unit,
    ) {
        withTables(testDB, *batchTables) { db ->
            if (testDB == TestDB.POSTGRESQL) {
                exec(ACTIVE_JOB_EXECUTION_UNIQUE_INDEX_SQL)
            }
            commit()
            val repo = ExposedR2dbcBatchJobRepository(db.db.requireNotNull("db.db"), CheckpointJson.jackson3())
            repo.block()
        }
    }

    private suspend fun activeJobExecutionCount(jobName: String, params: Map<String, Any>): Long {
        val hash = params.toParamsHash()

        return BatchJobExecutionTable.selectAll()
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

    // ─── 1. JobExecution 신규 생성 ────────────────────────────────────────────

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `JobExecution 신규 생성 - RUNNING 상태로 반환`(testDB: TestDB) {
        runSuspendIO {
            withRepoTables(testDB) {
                val je = findOrCreateJobExecution("myJob", emptyMap())
                je.jobName shouldBeEqualTo "myJob"
                je.status shouldBe BatchStatus.RUNNING
                (je.id > 0L) shouldBe true
            }
        }
    }

    // ─── 2. FAILED Job 재시작 → 기존 재사용 ────────────────────────────────────

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `FAILED Job 재시작 - 기존 JobExecution 재사용 RUNNING으로 전환`(testDB: TestDB) {
        runSuspendIO {
            withRepoTables(testDB) {
                val je1 = findOrCreateJobExecution("failedJob", emptyMap())
                completeJobExecution(je1, BatchStatus.FAILED)

                val je2 = findOrCreateJobExecution("failedJob", emptyMap())
                je2.id shouldBeEqualTo je1.id
                je2.status shouldBe BatchStatus.RUNNING
            }
        }
    }

    // ─── 3. COMPLETED Job → 신규 생성 ────────────────────────────────────────

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `COMPLETED Job - 신규 JobExecution 생성`(testDB: TestDB) {
        runSuspendIO {
            withRepoTables(testDB) {
                val je1 = findOrCreateJobExecution("completedJob", emptyMap())
                completeJobExecution(je1, BatchStatus.COMPLETED)

                val je2 = findOrCreateJobExecution("completedJob", emptyMap())
                (je2.id > je1.id) shouldBe true
            }
        }
    }

    // ─── 4. StepExecution 4-case 계약 ────────────────────────────────────────

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `StepExecution COMPLETED - UPDATE 없이 그대로 반환`(testDB: TestDB) {
        runSuspendIO {
            withRepoTables(testDB) {
                val je = findOrCreateJobExecution("stepJob", emptyMap())
                val se = findOrCreateStepExecution(je, "step1")
                completeStepExecution(se, StepReport("step1", BatchStatus.COMPLETED, readCount = 100L, writeCount = 100L))

                val se2 = findOrCreateStepExecution(je, "step1")
                se2.status shouldBe BatchStatus.COMPLETED
                se2.readCount shouldBeEqualTo 100L
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `StepExecution FAILED - RUNNING으로 복원`(testDB: TestDB) {
        runSuspendIO {
            withRepoTables(testDB) {
                val je = findOrCreateJobExecution("failedStepJob", emptyMap())
                val se = findOrCreateStepExecution(je, "step1")
                completeStepExecution(se, StepReport("step1", BatchStatus.FAILED))

                val se2 = findOrCreateStepExecution(je, "step1")
                se2.status shouldBe BatchStatus.RUNNING
            }
        }
    }

    // ─── 5. Checkpoint round-trip ─────────────────────────────────────────────

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `Checkpoint 저장 - loadCheckpoint로 Long 값 복원`(testDB: TestDB) {
        runSuspendIO {
            withRepoTables(testDB) {
                val je = findOrCreateJobExecution("cpJob", emptyMap())
                val se = findOrCreateStepExecution(je, "step1")

                saveCheckpoint(se.id, 42L)
                val restored = loadCheckpoint(se.id)

                restored.shouldNotBeNull()
                (restored as Long) shouldBeEqualTo 42L
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `Checkpoint 미저장 - loadCheckpoint는 null 반환`(testDB: TestDB) {
        runSuspendIO {
            withRepoTables(testDB) {
                val je = findOrCreateJobExecution("noCpJob", emptyMap())
                val se = findOrCreateStepExecution(je, "step1")

                loadCheckpoint(se.id) shouldBe null
            }
        }
    }

    // ─── 6. 동일 params 다른 날짜 → 별도 실행 ─────────────────────────────────

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `동시 findOrCreateJobExecution - SuspendedJobTester는 같은 JobExecution을 반환한다`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB == TestDB.POSTGRESQL }

        runSuspendIO {
            withRepoTables(testDB) {
                val executionIds = ConcurrentLinkedQueue<Long>()

                SuspendedJobTester()
                    .rounds(1)
                    .addAll(
                        (1..2).map {
                            suspend {
                                val jobExecution = findOrCreateJobExecution(
                                    jobName = "raceJob",
                                    params = mapOf("runId" to "issue-124"),
                                )
                                executionIds += jobExecution.id
                            }
                        }
                    )
                    .run()

                executionIds.size shouldBeEqualTo 2
                executionIds.distinct().size shouldBeEqualTo 1
                activeJobExecutionCount("raceJob", mapOf("runId" to "issue-124")) shouldBeEqualTo 1L
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `다른 params - 별도 JobExecution 생성`(testDB: TestDB) {
        runSuspendIO {
            withRepoTables(testDB) {
                val je1 = findOrCreateJobExecution("paramJob", mapOf("date" to "2026-04-10"))
                val je2 = findOrCreateJobExecution("paramJob", mapOf("date" to "2026-04-11"))

                (je2.id > je1.id) shouldBe true
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `UniqueViolation 재조회 - winner row가 있으면 반환한다`(testDB: TestDB) {
        runSuspendIO {
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
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `UniqueViolation 재조회 - winner row가 없으면 맥락 있는 IllegalStateException을 던진다`(testDB: TestDB) {
        runSuspendIO {
            withRepoTables(testDB) {
                val error = assertFailsWith<IllegalStateException> {
                    requeryJobExecutionAfterUniqueViolation(
                        jobName = "missingRetryJob",
                        params = mapOf("date" to "2026-05-19"),
                    )
                }

                val message = error.message.shouldNotBeNull()
                message shouldContain "Job execution disappeared after unique-constraint violation re-query"
                message shouldContain "missingRetryJob"
                message shouldContain "date=2026-05-19"
            }
        }
    }
}
