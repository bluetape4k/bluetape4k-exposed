package io.bluetape4k.exposed.clickhouse

/** RowBinary bytes가 전송되기 전에 선택된 fallback을 관찰하기 위한 internal event입니다. */
internal data class ClickHouseRowBinaryFallbackEvent(
    val reasonCode: String,
    val beforeFirstByte: Boolean,
)

/** 테스트·진단 fixture가 pre-byte fallback을 수집하는 internal sink입니다. */
internal fun interface ClickHouseRowBinaryFallbackRecorder {
    fun record(event: ClickHouseRowBinaryFallbackEvent)
}
