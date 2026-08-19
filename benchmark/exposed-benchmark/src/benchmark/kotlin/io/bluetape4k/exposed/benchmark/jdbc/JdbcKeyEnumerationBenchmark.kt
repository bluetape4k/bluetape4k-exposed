package io.bluetape4k.exposed.benchmark.jdbc

import io.bluetape4k.exposed.benchmark.support.BenchmarkUsers
import io.bluetape4k.exposed.benchmark.support.createJdbcDatabase
import io.bluetape4k.exposed.benchmark.support.seedJdbcUsers
import io.bluetape4k.exposed.jdbc.JdbcKeyRange
import io.bluetape4k.exposed.jdbc.JdbcParallelKeyEnumerationOptions
import io.bluetape4k.exposed.lettuce.map.ExposedEntityMapLoader
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 기존 lazy keyset paging과 opt-in Virtual Thread range enumeration을 같은 H2 fixture에서 비교합니다.
 *
 * 결과는 처리한 전체 keyset 수를 반환해 benchmark invocation이 실제 DB 작업을 생략하지 않도록 합니다.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
class JdbcKeyEnumerationBenchmark {

    @Param("1000", "10000")
    var rowCount: Int = 1000

    @Param("10")
    var poolSize: Int = 10

    @Param("4")
    var rangeCount: Int = 4

    private lateinit var dataSource: com.zaxxer.hikari.HikariDataSource
    private lateinit var database: org.jetbrains.exposed.v1.jdbc.Database
    private lateinit var loader: ExposedEntityMapLoader<Long, Long>
    private lateinit var virtualThreadExecutor: ExecutorService
    private lateinit var ranges: List<JdbcKeyRange<Long>>

    @Setup(Level.Trial)
    fun setup() {
        require(rangeCount in 1..rowCount) {
            "rangeCount는 rowCount 이하여야 합니다. rangeCount=$rangeCount rowCount=$rowCount"
        }
        val (ds, db) = createJdbcDatabase("jdbc_key_enumeration_benchmark", poolSize)
        dataSource = ds
        database = db
        seedJdbcUsers(database, rowCount)
        loader = ExposedEntityMapLoader(
            table = BenchmarkUsers,
            toEntity = { row -> row[BenchmarkUsers.id].value },
        )
        virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor()
        ranges = buildRanges(rowCount, rangeCount)
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        virtualThreadExecutor.close()
        dataSource.close()
    }

    @Benchmark
    fun sequentialKeysetPaging(): Int = loader.loadAllKeys().count()

    @Benchmark
    fun parallelKeyEnumeration(): Int =
        loader
            .loadAllKeysInParallel(
                ranges = ranges,
                options = JdbcParallelKeyEnumerationOptions(
                    maxConcurrency = rangeCount,
                    executor = virtualThreadExecutor,
                    database = database,
                ),
            ).size

    private companion object {
        fun buildRanges(
            rowCount: Int,
            rangeCount: Int,
        ): List<JdbcKeyRange<Long>> {
            val boundaries = (1 until rangeCount).map { index -> index.toLong() * rowCount / rangeCount + 1L }
            return List(rangeCount) { index ->
                JdbcKeyRange(
                    lowerInclusive = boundaries.getOrNull(index - 1),
                    upperExclusive = boundaries.getOrNull(index),
                )
            }
        }
    }
}
