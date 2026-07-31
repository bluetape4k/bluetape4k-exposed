package io.bluetape4k.batch.r2dbc

import io.bluetape4k.batch.api.BatchJobRepository
import io.bluetape4k.batch.api.BatchStatus
import io.bluetape4k.batch.api.JobExecution
import io.bluetape4k.batch.api.StepExecution
import io.bluetape4k.batch.api.StepReport
import io.bluetape4k.batch.internal.CheckpointJson
import io.bluetape4k.batch.jdbc.tables.BatchJobExecutionTable
import io.bluetape4k.batch.jdbc.tables.BatchStepExecutionTable
import io.bluetape4k.batch.jdbc.tables.toJobExecution
import io.bluetape4k.batch.jdbc.tables.toParamsHash
import io.bluetape4k.batch.jdbc.tables.toStepExecution
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requireNotBlank
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

    companion object : KLoggingChannel()

    override suspend fun findOrCreateJobExecution(
        jobName: String,
        params: Map<String, Any>,
    ): JobExecution {
        jobName.requireNotBlank("jobName")
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
            log.debug(e) { "동시 INSERT 감지 — job=$jobName, 재조회" }
            requeryJobExecutionAfterUniqueViolation(jobName, params)
        }
    }

    override suspend fun claimJobExecution(
        execution: JobExecution,
        ownerId: String,
        leaseUntil: Instant,
    ): JobExecution? {
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
            findJobExecutionById(execution.id)
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
                ?: throw IllegalStateException(
                    "Job execution disappeared after unique-constraint violation re-query. " +
                        "jobName=${jobName}, params=${params}"
                )
        }
    }

    override suspend fun completeJobExecution(execution: JobExecution, status: BatchStatus) {
        suspendTransaction(db = database) {
            val condition = if (execution.ownerId == null) {
                BatchJobExecutionTable.id eq execution.id
            } else {
                (BatchJobExecutionTable.id eq execution.id) and
                    (BatchJobExecutionTable.ownerId eq execution.ownerId)
            }
            BatchJobExecutionTable.update({ condition }) { row ->
                row[BatchJobExecutionTable.status] = status
                row[BatchJobExecutionTable.ownerId] = null
                row[BatchJobExecutionTable.leaseUntil] = null
                row[BatchJobExecutionTable.version] = execution.version + 1
                row[BatchJobExecutionTable.endTime] = Instant.now()
            }
        }
    }

    override suspend fun findOrCreateStepExecution(
        jobExecution: JobExecution,
        stepName: String,
    ): StepExecution {
        stepName.requireNotBlank("stepName")

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
            return when (existing.status) {
                BatchStatus.COMPLETED, BatchStatus.COMPLETED_WITH_SKIPS -> existing
                BatchStatus.FAILED, BatchStatus.STOPPED, BatchStatus.RUNNING -> existing

                else -> existing
            }
        }

        return suspendTransaction(db = database) {
            val now = Instant.now()
            val newId = BatchStepExecutionTable.insertAndGetId { row ->
                row[BatchStepExecutionTable.jobExecutionId] = jobExecution.id
                row[BatchStepExecutionTable.stepName] = stepName
                row[BatchStepExecutionTable.status] = BatchStatus.RUNNING
                row[BatchStepExecutionTable.startTime] = now
            }
            StepExecution(
                id = newId.value,
                jobExecutionId = jobExecution.id,
                stepName = stepName,
                status = BatchStatus.RUNNING,
                startTime = now,
            )
        }
    }

    override suspend fun claimStepExecution(
        execution: StepExecution,
        ownerId: String,
        leaseUntil: Instant,
    ): StepExecution? {
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
            findStepExecutionById(execution.id)
        } else {
            null
        }
    }

    override suspend fun completeStepExecution(execution: StepExecution, report: StepReport) {
        suspendTransaction(db = database) {
            val condition = if (execution.ownerId == null) {
                BatchStepExecutionTable.id eq execution.id
            } else {
                (BatchStepExecutionTable.id eq execution.id) and
                    (BatchStepExecutionTable.ownerId eq execution.ownerId)
            }
            BatchStepExecutionTable.update({ condition }) { row ->
                row[BatchStepExecutionTable.status] = report.status
                row[BatchStepExecutionTable.readCount] = report.readCount
                row[BatchStepExecutionTable.writeCount] = report.writeCount
                row[BatchStepExecutionTable.skipCount] = report.skipCount
                row[BatchStepExecutionTable.checkpoint] = report.checkpoint?.let { checkpointJson.write(it) }
                row[BatchStepExecutionTable.ownerId] = null
                row[BatchStepExecutionTable.leaseUntil] = null
                row[BatchStepExecutionTable.version] = execution.version + 1
                row[BatchStepExecutionTable.endTime] = Instant.now()
            }
        }
    }

    override suspend fun saveCheckpoint(stepExecutionId: Long, checkpoint: Any) {
        suspendTransaction(db = database) {
            BatchStepExecutionTable.update({ BatchStepExecutionTable.id eq stepExecutionId }) { row ->
                row[BatchStepExecutionTable.checkpoint] = checkpointJson.write(checkpoint)
            }
        }
    }

    override suspend fun saveCheckpoint(execution: StepExecution, checkpoint: Any) {
        suspendTransaction(db = database) {
            val condition = if (execution.ownerId == null) {
                BatchStepExecutionTable.id eq execution.id
            } else {
                (BatchStepExecutionTable.id eq execution.id) and
                    (BatchStepExecutionTable.ownerId eq execution.ownerId)
            }
            BatchStepExecutionTable.update({ condition }) { row ->
                row[BatchStepExecutionTable.checkpoint] = checkpointJson.write(checkpoint)
            }
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
 * UniqueViolation 예외 여부를 판별한다.
 *
 * R2DBC 드라이버마다 예외 타입이 다르므로, 메시지 문자열로 판별한다.
 * - PostgreSQL: SQLSTATE 23505
 * - MySQL/MariaDB: SQLSTATE 23000, error code 1062
 * - H2: "unique" 포함 메시지
 */
private fun Throwable.isUniqueViolation(): Boolean {
    val msg = message ?: cause?.message ?: ""
    return msg.contains("unique", ignoreCase = true) ||
        msg.contains("23505") ||
        msg.contains("1062")
}
