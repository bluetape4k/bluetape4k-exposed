package io.bluetape4k.exposed.benchmark.jdbc

/** Benchmark-only JDBC backends; this type is not part of the published API. */
internal enum class JdbcBenchmarkDriver {
    POSTGRESQL,
    MYSQL_V8,
}

/** One validated driver/row/pool point in the Issue #694 benchmark matrix. */
internal data class JdbcDriverBenchmarkCase(
    val driver: JdbcBenchmarkDriver,
    val rowCount: Int,
    val poolSize: Int,
    val maxConcurrency: Int = 2,
) {
    init {
        require(rowCount > 0) { "rowCount must be positive: $rowCount" }
        require(poolSize > 0) { "poolSize must be positive: $poolSize" }
        require(maxConcurrency > 0) { "maxConcurrency must be positive: $maxConcurrency" }
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

/** Builds non-overlapping `[lowerInclusive, upperExclusive)` ranges for generated IDs. */
internal fun buildDriverBenchmarkRanges(
    rowCount: Int,
    rangeCount: Int,
): List<JdbcDriverBenchmarkRange> {
    require(rowCount > 0) { "rowCount must be positive: $rowCount" }
    require(rangeCount in 1..rowCount) {
        "rangeCount must be between 1 and rowCount: rangeCount=$rangeCount rowCount=$rowCount"
    }

    val boundaries = (1 until rangeCount)
        .map { index -> index.toLong() * rowCount / rangeCount + 1L }

    return List(rangeCount) { index ->
        JdbcDriverBenchmarkRange(
            lowerInclusive = boundaries.getOrNull(index - 1),
            upperExclusive = boundaries.getOrNull(index),
        )
    }
}

private val ROW_COUNTS = intArrayOf(1_000, 10_000)
private val POOL_SIZES = intArrayOf(1, 2, 4)
