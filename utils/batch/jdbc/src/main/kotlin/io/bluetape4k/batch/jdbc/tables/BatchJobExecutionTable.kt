package io.bluetape4k.batch.jdbc.tables

import io.bluetape4k.batch.BatchParameterHash
import io.bluetape4k.batch.api.BatchStatus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.javatime.timestamp

internal const val BATCH_ACTIVE_KEY = "ACTIVE"
private val BATCH_ACTIVE_STATUSES = listOf(
    BatchStatus.STARTING,
    BatchStatus.RUNNING,
    BatchStatus.FAILED,
    BatchStatus.STOPPED,
)
private val BATCH_COMPLETED_STATUSES = listOf(
    BatchStatus.COMPLETED,
    BatchStatus.COMPLETED_WITH_SKIPS,
)

/**
 * Job 실행 이력 테이블.
 *
 * ## 재시작 단일성
 * `(job_name, params_hash, active_key)` 조합으로 재시작 대상을 식별한다.
 * `STARTING`, `RUNNING`, `FAILED`, `STOPPED` row의 `active_key`는 `ACTIVE`이고
 * 완료 row는 null이다. 필수 인덱스:
 * ```sql
 * CREATE UNIQUE INDEX batch_job_execution_active_uidx
 *   ON batch_job_execution(job_name, params_hash, active_key);
 * ```
 * 동시 INSERT 경쟁은 `UNIQUE constraint violation` catch 후 재조회로 처리한다.
 * `SELECT ... FOR UPDATE`는 사용하지 않는다 (빈 결과 시 무의미).
 */
object BatchJobExecutionTable : LongIdTable("batch_job_execution") {
    /** Job 이름 — 인덱스 포함 */
    val jobName = varchar("job_name", 100).index()

    /** Job 파라미터의 SHA-256 해시 (재시작 식별 키) */
    val paramsHash = varchar("params_hash", 64)

    /** 재사용 가능한 실행은 `ACTIVE`, 완료된 실행 이력은 null이다. */
    val activeKey = varchar("active_key", 16).nullable()

    /** 현재 실행 상태 */
    val status = enumerationByName<BatchStatus>("status", 20)

    /** 현재 실행 소유자 ID. null이면 아직 claim되지 않았거나 terminal 상태이다. */
    val ownerId = varchar("owner_id", 128).nullable()

    /** 실행 소유권 lease 만료 시각. */
    val leaseUntil = timestamp("lease_until").nullable()

    /** claim CAS를 위한 낙관적 버전. */
    val version = long("version").default(0L)

    /** Job 파라미터 JSON 문자열 */
    val params = text("params").nullable()

    /** 실행 시작 시각 (UTC) */
    val startTime = timestamp("start_time")

    /** 실행 종료 시각 (UTC), 실행 중이면 null */
    val endTime = timestamp("end_time").nullable()

    init {
        uniqueIndex("batch_job_execution_active_uidx", jobName, paramsHash, activeKey)
        check("batch_job_exec_status_active_key_chk") {
            ((status inList BATCH_ACTIVE_STATUSES) and activeKey.isNotNull() and (activeKey eq BATCH_ACTIVE_KEY)) or
                ((status inList BATCH_COMPLETED_STATUSES) and activeKey.isNull())
        }
    }
}

/**
 * Job 파라미터 Map을 shared [BatchParameterHash]로 변환한다.
 *
 * key/value 바이트 길이와 value type을 포함한 `v2` canonical encoding의 SHA-256
 * lowercase hex를 반환한다. 빈 Map이면 기존 저장 계약을 위해 빈 문자열을 반환한다.
 *
 * ```kotlin
 * val hash = mapOf("date" to "2026-04-10", "region" to "KR").toParamsHash()
 * // → canonical `v2` encoding의 SHA-256 lowercase hex
 * ```
 */
internal fun Map<String, Any>.toParamsHash(): String = BatchParameterHash.hash(this)
