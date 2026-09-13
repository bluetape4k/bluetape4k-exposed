package io.bluetape4k.exposed.clickhouse

import java.sql.SQLException

/**
 * options 기반 ClickHouse 연결 실패를 민감정보 없이 전달하는 예외입니다.
 *
 * 원본 driver 예외는 cause 또는 suppressed graph에 연결하지 않으며 SQLState와
 * vendor code만 보존합니다.
 */
class ClickHouseConnectionException internal constructor(
    message: String,
    sqlState: String?,
    vendorCode: Int,
    causeSummary: String,
): SQLException("${ClickHouseV2Redaction.sanitizeMessage(message)} [$causeSummary]", sqlState, vendorCode) {

    /** 원본 예외 대신 보존한 안전한 타입 요약입니다. */
    val sanitizedCause: String = causeSummary
}

/** 연결 정리 실패에서 안전하게 보존할 수 있는 최소 진단 정보입니다. */
internal class SanitizedCleanupException internal constructor(
    val reasonCode: String,
    sqlState: String?,
    vendorCode: Int,
): SQLException("ClickHouse connection cleanup failed: $reasonCode", sqlState, vendorCode)

internal fun Throwable.toClickHouseConnectionException(jdbcUrl: String): ClickHouseConnectionException {
    val sqlException = this as? SQLException
    val sqlState = sqlException?.sqlState
    val vendorCode = sqlException?.errorCode ?: 0
    val causeSummary = ClickHouseV2Redaction.sanitizeThrowable(this).message.orEmpty()
    return ClickHouseConnectionException(
        message = ClickHouseV2Redaction.redactJdbcUrl(jdbcUrl),
        sqlState = sqlState,
        vendorCode = vendorCode,
        causeSummary = causeSummary,
    )
}

internal fun Throwable.toSanitizedCleanupException(): SanitizedCleanupException {
    val sqlException = this as? SQLException
    return SanitizedCleanupException(
        reasonCode = "connection-close-failed",
        sqlState = sqlException?.sqlState,
        vendorCode = sqlException?.errorCode ?: 0,
    )
}
