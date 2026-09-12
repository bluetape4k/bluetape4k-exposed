package io.bluetape4k.exposed.clickhouse

/**
 * ClickHouse JDBC V2 RowBinary writer의 명시적 opt-in 설정입니다.
 *
 * [enabled]는 connection profile을 선택하는 기능만 켭니다. 실제 connection과
 * driver property는 [ClickHouseConnectionProvider]가 소유하며, 기존 Exposed
 * connection이나 pool property를 변경하지 않습니다.
 */
data class ClickHouseRowBinaryOptions(
    val enabled: Boolean = false,
    val maxRowsPerFlush: Int = DEFAULT_MAX_ROWS_PER_FLUSH,
) {

    init {
        require(maxRowsPerFlush > 0) {
            "maxRowsPerFlush는 양수여야 합니다: $maxRowsPerFlush"
        }
    }

    private companion object {
        const val DEFAULT_MAX_ROWS_PER_FLUSH = 1_024
    }
}
