package io.bluetape4k.batch.r2dbc

import io.bluetape4k.batch.api.BatchJobRepository
import io.bluetape4k.batch.api.BatchStatus
import io.bluetape4k.batch.api.JobExecution
import io.bluetape4k.batch.api.StepExecution
import io.bluetape4k.batch.api.StepReport
import io.bluetape4k.batch.CheckpointJson
import io.bluetape4k.batch.api.requireValidBatchName
import io.bluetape4k.batch.r2dbc.tables.BatchJobExecutionTable
import io.bluetape4k.batch.r2dbc.tables.BatchStepExecutionTable
import io.bluetape4k.batch.r2dbc.tables.toJobExecution
import io.bluetape4k.batch.r2dbc.tables.toParamsHash
import io.bluetape4k.batch.r2dbc.tables.toStepExecution
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.r2dbc.spi.R2dbcException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.insertAndGetId
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import java.time.Instant

private const val MYSQL_DUPLICATE_KEY_ERROR_CODE = 1062

/**
 * Exposed R2DBC 기반 [BatchJobRepository] 구현 — 네이티브 suspend.
 *
 * 모든 DB 접근은 `suspendTransaction(db = database) { ... }`로 감싼다.
 * `Dispatchers.VT` / `withContext`가 필요 없다 (R2DBC는 네이티브 suspend).
 *
 * ## 재시작 시나리오
 * ```
 * findOrCreateJobExecution("importOrders", params)
 *   → RUNNING/FAILED/STOPPED 기존 실행 재사용, or 신규 INSERT
 *   → 동시 INSERT 충돌 시 UniqueViolation catch → 재조회
 * ```
 *
 * @param database Exposed R2DBC Database
 * @param checkpointJson Checkpoint 직렬화 전략 (CheckpointJson.jackson3() 등)
 */
class ExposedR2dbcBatchJobRepository(
    private val database: R2dbcDatabase,
    private val checkpointJson: CheckpointJson,
) : BatchJobRepository {

    /** SELECT와 INSERT 사이의 경합을 통합 테스트에서 결정적으로 재현하기 위한 내부 훅입니다. */
    @Volatile
    internal var beforeStepInsertHook: (suspend () -> Unit)? = null

    /**
     * 이전 `internal.CheckpointJson` JVM descriptor를 사용하는 소비자를 위한
     * 한 minor line 호환 생성자입니다.
     */
    @Deprecated(
        message = "Use io.bluetape4k.batch.CheckpointJson",
        replaceWith = ReplaceWith("io.bluetape4k.batch.CheckpointJson"),
    )
    constructor(
        database: R2dbcDatabase,
        checkpointJson: io.bluetape4k.batch.internal.CheckpointJson,
    ) : this(database, checkpointJson as CheckpointJson)

    companion object : KLoggingChannel()

    override suspend fun findOrCreateJobExecution(
        jobName: String,
        params: Map<String, Any>,
    ): JobExecution {
        jobName.requireValidBatchName("jobName")
        val hash = params.toParamsHash()

        val existing = suspendTransaction(db = database) {
            BatchJobExecutionTable.selectAll()
                .where {
                    (BatchJobExecutionTable.jobName eq jobName) and
                        (BatchJobExecutionTable.paramsHash eq hash) and
                        (BatchJobExecutionTable.status inList listOf(
                            BatchStatus.RUNNING, BatchStatus.FAILED, BatchStatus.STOPPED,
                        ))
                }
                .orderBy(BatchJobExecutionTable.id, SortOrder.DESC)
                .limit(1)
                .map { it.toJobExecution(checkpointJson) }
                .firstOrNull()
        }

        if (existing != null) {
            return existing
        }

        return try {
            suspendTransaction(db = database) {
                val now = Instant.now()
                val newId = BatchJobExecutionTable.insertAndGetId { row ->
                    row[BatchJobExecutionTable.jobName] = jobName
                    row[BatchJobExecutionTable.paramsHash] = hash  // "" for empty params (consistent with SELECT eq hash)
                    row[BatchJobExecutionTable.status] = BatchStatus.RUNNING
                    row[BatchJobExecutionTable.params] = if (params.isEmpty()) null
                    else checkpointJson.write(params)
                    row[BatchJobExecutionTable.startTime] = now
                }
                JobExecution(
                    id = newId.value,
                    jobName = jobName,
                    params = params,
                    status = BatchStatus.RUNNING,
                    startTime = now,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (!e.isUniqueViolation()) throw e
            log.debug { "동시 INSERT 감지 — JobExecution 재조회" }
            requeryJobExecutionAfterUniqueViolation(jobName, params)
        }
    }

    override suspend fun claimJobExecution(
        execution: JobExecution,
        ownerId: String,
        leaseUntil: Instant,
    ): JobExecution? {
        ownerId.requireValidBatchName("ownerId")
        val now = Instant.now()
        val updatedRows = suspendTransaction(db = database) {
            BatchJobExecutionTable.update({
                (BatchJobExecutionTable.id eq execution.id) and
                    (BatchJobExecutionTable.version eq execution.version) and
                    (
                        (BatchJobExecutionTable.status inList listOf(BatchStatus.FAILED, BatchStatus.STOPPED)) or
                            (
                                (BatchJobExecutionTable.status eq BatchStatus.RUNNING) and
                                    (
                                        BatchJobExecutionTable.ownerId.isNull() or
                                            BatchJobExecutionTable.leaseUntil.isNull() or
                                            (BatchJobExecutionTable.leaseUntil less now)
                                        )
                                )
                        )
            }) { row ->
                row[BatchJobExecutionTable.status] = BatchStatus.RUNNING
                row[BatchJobExecutionTable.ownerId] = ownerId
                row[BatchJobExecutionTable.leaseUntil] = leaseUntil
                row[BatchJobExecutionTable.version] = execution.version + 1
                row[BatchJobExecutionTable.endTime] = null
            }
        }

        return if (updatedRows == 1) {
            checkNotNull(findJobExecutionById(execution.id)) {
                "JobExecution disappeared after a successful claim: id=${execution.id}"
            }
        } else {
            null
        }
    }

    /**
     * 동시 insert가 unique-index 경합에서 실패한 뒤, 활성 상태인 승자 row를 다시 조회합니다.
     */
    internal suspend fun requeryJobExecutionAfterUniqueViolation(
        jobName: String,
        params: Map<String, Any>,
    ): JobExecution {
        val hash = params.toParamsHash()

        return suspendTransaction(db = database) {
            BatchJobExecutionTable.selectAll()
                .where {
                    (BatchJobExecutionTable.jobName eq jobName) and
                        (BatchJobExecutionTable.paramsHash eq hash) and
                        (BatchJobExecutionTable.status inList listOf(
                            BatchStatus.RUNNING, BatchStatus.FAILED, BatchStatus.STOPPED,
                        ))
                }
                .orderBy(BatchJobExecutionTable.id, SortOrder.DESC)
                .limit(1)
                .map { it.toJobExecution(checkpointJson) }
                .firstOrNull()
                ?: error("Job execution disappeared after unique-constraint violation re-query.")
        }
    }

    override suspend fun completeJobExecution(execution: JobExecution, status: BatchStatus) {
        suspendTransaction(db = database) {
            val condition = if (execution.ownerId == null) {
                (BatchJobExecutionTable.id eq execution.id) and
                    BatchJobExecutionTable.ownerId.isNull() and
                    (BatchJobExecutionTable.version eq execution.version)
            } else {
                (BatchJobExecutionTable.id eq execution.id) and
                    (BatchJobExecutionTable.ownerId eq execution.ownerId) and
                    (BatchJobExecutionTable.version eq execution.version)
            }
            val updatedRows = BatchJobExecutionTable.update({ condition }) { row ->
                row[BatchJobExecutionTable.status] = status
                row[BatchJobExecutionTable.ownerId] = null
                row[BatchJobExecutionTable.leaseUntil] = null
                row[BatchJobExecutionTable.version] = execution.version + 1
                row[BatchJobExecutionTable.endTime] = Instant.now()
            }
            check(updatedRows == 1) {
                "JobExecution completion rejected: id=${execution.id}"
            }
        }
    }

    override suspend fun findOrCreateStepExecution(
        jobExecution: JobExecution,
        stepName: String,
    ): StepExecution {
        stepName.requireValidBatchName("stepName")

        val existing = suspendTransaction(db = database) {
            BatchStepExecutionTable.selectAll()
                .where {
                    (BatchStepExecutionTable.jobExecutionId eq jobExecution.id) and
                        (BatchStepExecutionTable.stepName eq stepName)
                }
                .limit(1)
                .map { it.toStepExecution(checkpointJson) }
                .firstOrNull()
        }

        if (existing != null) {
            return existing
        }

        return createStepExecutionOrRequery(jobExecution.id, stepName)
    }

    private suspend fun createStepExecutionOrRequery(
        jobExecutionId: Long,
        stepName: String,
    ): StepExecution {
        beforeStepInsertHook?.invoke()

        return try {
            suspendTransaction(db = database) {
                val now = Instant.now()
                val newId = BatchStepExecutionTable.insertAndGetId { row ->
                    row[BatchStepExecutionTable.jobExecutionId] = jobExecutionId
                    row[BatchStepExecutionTable.stepName] = stepName
                    row[BatchStepExecutionTable.status] = BatchStatus.RUNNING
                    row[BatchStepExecutionTable.startTime] = now
                }
                StepExecution(
                    id = newId.value,
                    jobExecutionId = jobExecutionId,
                    stepName = stepName,
                    status = BatchStatus.RUNNING,
                    startTime = now,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (!e.isUniqueViolation()) throw e
            log.debug { "동시 INSERT 감지 — StepExecution 재조회" }
            requeryStepExecutionAfterUniqueViolation(jobExecutionId, stepName)
        }
    }

    /** 동시 Step INSERT가 unique index에서 패배한 뒤 승자 row를 다시 조회합니다. */
    internal suspend fun requeryStepExecutionAfterUniqueViolation(
        jobExecutionId: Long,
        stepName: String,
    ): StepExecution {
        return suspendTransaction(db = database) {
            BatchStepExecutionTable.selectAll()
                .where {
                    (BatchStepExecutionTable.jobExecutionId eq jobExecutionId) and
                        (BatchStepExecutionTable.stepName eq stepName)
                }
                .limit(1)
                .map { it.toStepExecution(checkpointJson) }
                .firstOrNull()
                ?: error("Step execution disappeared after unique-constraint violation re-query.")
        }
    }

    override suspend fun claimStepExecution(
        execution: StepExecution,
        ownerId: String,
        leaseUntil: Instant,
    ): StepExecution? {
        ownerId.requireValidBatchName("ownerId")
        val now = Instant.now()
        val updatedRows = suspendTransaction(db = database) {
            BatchStepExecutionTable.update({
                (BatchStepExecutionTable.id eq execution.id) and
                    (BatchStepExecutionTable.version eq execution.version) and
                    (
                        (BatchStepExecutionTable.status inList listOf(BatchStatus.FAILED, BatchStatus.STOPPED)) or
                            (
                                (BatchStepExecutionTable.status eq BatchStatus.RUNNING) and
                                    (
                                        BatchStepExecutionTable.ownerId.isNull() or
                                            BatchStepExecutionTable.leaseUntil.isNull() or
                                            (BatchStepExecutionTable.leaseUntil less now)
                                        )
                                )
                        )
            }) { row ->
                row[BatchStepExecutionTable.status] = BatchStatus.RUNNING
                row[BatchStepExecutionTable.ownerId] = ownerId
                row[BatchStepExecutionTable.leaseUntil] = leaseUntil
                row[BatchStepExecutionTable.version] = execution.version + 1
                row[BatchStepExecutionTable.endTime] = null
            }
        }

        return if (updatedRows == 1) {
            checkNotNull(findStepExecutionById(execution.id)) {
                "StepExecution disappeared after a successful claim: id=${execution.id}"
            }
        } else {
            null
        }
    }

    override suspend fun completeStepExecution(execution: StepExecution, report: StepReport) {
        suspendTransaction(db = database) {
            val condition = if (execution.ownerId == null) {
                (BatchStepExecutionTable.id eq execution.id) and
                    BatchStepExecutionTable.ownerId.isNull() and
                    (BatchStepExecutionTable.version eq execution.version)
            } else {
                (BatchStepExecutionTable.id eq execution.id) and
                    (BatchStepExecutionTable.ownerId eq execution.ownerId) and
                    (BatchStepExecutionTable.version eq execution.version)
            }
            val updatedRows = BatchStepExecutionTable.update({ condition }) { row ->
                row[BatchStepExecutionTable.status] = report.status
                row[BatchStepExecutionTable.readCount] = report.readCount
                row[BatchStepExecutionTable.writeCount] = report.writeCount
                row[BatchStepExecutionTable.skipCount] = report.skipCount
                report.checkpoint?.let { checkpoint ->
                    row[BatchStepExecutionTable.checkpoint] = checkpointJson.write(checkpoint)
                }
                row[BatchStepExecutionTable.ownerId] = null
                row[BatchStepExecutionTable.leaseUntil] = null
                row[BatchStepExecutionTable.version] = execution.version + 1
                row[BatchStepExecutionTable.endTime] = Instant.now()
            }
            check(updatedRows == 1) {
                "StepExecution completion rejected: id=${execution.id}"
            }
        }
    }

    override suspend fun saveCheckpoint(stepExecutionId: Long, checkpoint: Any) {
        suspendTransaction(db = database) {
            val updatedRows = BatchStepExecutionTable.update({ BatchStepExecutionTable.id eq stepExecutionId }) { row ->
                row[BatchStepExecutionTable.checkpoint] = checkpointJson.write(checkpoint)
            }
            check(updatedRows == 1) {
                "StepExecution checkpoint update rejected: id=$stepExecutionId"
            }
        }
    }

    override suspend fun saveCheckpoint(execution: StepExecution, checkpoint: Any) {
        saveCheckpointAndReturn(execution, checkpoint)
    }

    override suspend fun saveCheckpointAndReturn(
        execution: StepExecution,
        checkpoint: Any,
    ): StepExecution {
        val ownerId = checkNotNull(execution.ownerId) {
            "Owner-aware checkpoint update requires ownerId"
        }.also {
            check(it.isNotBlank()) { "Owner-aware checkpoint update requires ownerId" }
        }.requireValidBatchName("ownerId")
        return suspendTransaction(db = database) {
            val json = checkpointJson.write(checkpoint)
            val updatedRows = BatchStepExecutionTable.update({
                (BatchStepExecutionTable.id eq execution.id) and
                    (BatchStepExecutionTable.ownerId eq ownerId) and
                    (BatchStepExecutionTable.version eq execution.version)
            }) { row ->
                row[BatchStepExecutionTable.checkpoint] = json
                row[BatchStepExecutionTable.version] = execution.version + 1
            }
            check(updatedRows == 1) {
                "Owner-aware checkpoint update rejected: id=${execution.id}"
            }
            checkNotNull(
                BatchStepExecutionTable.selectAll()
                    .where { BatchStepExecutionTable.id eq execution.id }
                    .limit(1)
                    .map { it.toStepExecution(checkpointJson) }
                    .firstOrNull(),
            ) { "StepExecution disappeared after checkpoint update: id=${execution.id}" }
        }
    }

    override suspend fun loadCheckpoint(stepExecutionId: Long): Any? {
        return suspendTransaction(db = database) {
            BatchStepExecutionTable.selectAll()
                .where { BatchStepExecutionTable.id eq stepExecutionId }
                .limit(1)
                .map { it[BatchStepExecutionTable.checkpoint] }
                .firstOrNull()
                ?.let { checkpointJson.read(it) }
        }
    }

    private suspend fun findJobExecutionById(executionId: Long): JobExecution? =
        suspendTransaction(db = database) {
            BatchJobExecutionTable.selectAll()
                .where { BatchJobExecutionTable.id eq executionId }
                .limit(1)
                .map { it.toJobExecution(checkpointJson) }
                .firstOrNull()
        }

    private suspend fun findStepExecutionById(executionId: Long): StepExecution? =
        suspendTransaction(db = database) {
            BatchStepExecutionTable.selectAll()
                .where { BatchStepExecutionTable.id eq executionId }
                .limit(1)
                .map { it.toStepExecution(checkpointJson) }
                .firstOrNull()
        }
}

/**
 * R2DBC 드라이버가 제공하는 구조화된 식별자만 사용해 unique violation을 판별한다.
 *
 * - PostgreSQL/H2: SQLSTATE 23505
 * - MySQL/MariaDB: error code 1062 (SQLSTATE 23000만으로는 충분하지 않음)
 *
 * 메시지 문자열은 일반 애플리케이션 예외에도 포함될 수 있으므로 판별 근거로
 * 사용하지 않는다. 원인을 감싼 예외가 있더라도 R2DBC 예외만 검사한다.
 */
internal fun Throwable.isUniqueViolation(): Boolean =
    generateSequence(this) { it.cause }
        .filterIsInstance<R2dbcException>()
        .any { it.hasUniqueSqlState() || it.errorCode == MYSQL_DUPLICATE_KEY_ERROR_CODE }

private fun R2dbcException.hasUniqueSqlState(): Boolean = sqlState == "23505"
