package io.bluetape4k.exposed.clickhouse

/** RowBinary 또는 JDBC fallback 배치의 보존된 결과입니다. */
data class ClickHouseRowBinaryResult(
    val updateCounts: List<Int>,
    val acceptedCount: Int,
    val path: ClickHouseBatchPath,
    val acceptedCountMayBeIncomplete: Boolean,
)

/** 배치가 실제로 사용한 writer 경로입니다. */
enum class ClickHouseBatchPath {
    ROW_BINARY,
    JDBC_FALLBACK,
}
