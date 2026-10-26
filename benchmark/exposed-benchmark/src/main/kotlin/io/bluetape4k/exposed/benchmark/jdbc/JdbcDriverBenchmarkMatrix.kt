package io.bluetape4k.exposed.benchmark.jdbc

import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requirePositiveNumber

/** Benchmark 전용 JDBC backend이며 published API에 포함되지 않습니다. */
internal enum class JdbcBenchmarkDriver {
    POSTGRESQL,
    MYSQL_V8,
}

/** Issue #694 benchmark matrix의 검증된 driver/row/pool point입니다. */
internal data class JdbcDriverBenchmarkCase(
    val driver: JdbcBenchmarkDriver,
    val rowCount: Int,
    val poolSize: Int,
    val maxConcurrency: Int = DRIVER_BENCHMARK_MAX_CONCURRENCY,
) {
    init {
        rowCount.requirePositiveNumber("rowCount")
        poolSize.requirePositiveNumber("poolSize")
        maxConcurrency.requireEquals(DRIVER_BENCHMARK_MAX_CONCURRENCY, "maxConcurrency")
    }
}

internal data class JdbcDriverBenchmarkRange(
    val lowerInclusive: Long?,
    val upperExclusive: Long?,
)

internal fun jdbcDriverBenchmarkCases(): List<JdbcDriverBenchmarkCase> =
    JdbcBenchmarkDriver.entries.flatMap { driver ->
        ROW_COUNTS.flatMap { rowCount ->
            POOL_SIZES.map { poolSize ->
                JdbcDriverBenchmarkCase(
                    driver = driver,
                    rowCount = rowCount,
                    poolSize = poolSize,
                )
            }
        }
    }

/** 생성 ID에 대해 겹치지 않는 `[lowerInclusive, upperExclusive)` 구간을 만듭니다. */
internal fun buildDriverBenchmarkRanges(
    rowCount: Int,
    rangeCount: Int,
): List<JdbcDriverBenchmarkRange> {
    rowCount.requirePositiveNumber("rowCount")
    rangeCount.requireInRange(1, rowCount, "rowCount")

    val boundaries = List(rangeCount) { (it + 1).toLong() * rowCount / rangeCount + 1L }

    return List(rangeCount) { index ->
        JdbcDriverBenchmarkRange(
            lowerInclusive = boundaries.getOrNull(index - 1),
            upperExclusive = boundaries.getOrNull(index),
        )
    }
}

private val ROW_COUNTS = intArrayOf(1_000, 10_000)
private val POOL_SIZES = intArrayOf(1, 2, 4)
internal const val DRIVER_BENCHMARK_MAX_CONCURRENCY = 2
