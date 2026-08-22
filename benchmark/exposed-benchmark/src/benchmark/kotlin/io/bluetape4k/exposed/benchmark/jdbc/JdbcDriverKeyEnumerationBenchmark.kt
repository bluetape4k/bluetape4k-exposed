package io.bluetape4k.exposed.benchmark.jdbc

import io.bluetape4k.exposed.benchmark.support.BenchmarkUsers
import io.bluetape4k.exposed.jdbc.JdbcKeyRange
import io.bluetape4k.exposed.jdbc.JdbcParallelKeyEnumerationOptions
import io.bluetape4k.exposed.lettuce.map.ExposedEntityMapLoader
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.openjdk.jmh.annotations.AuxCounters
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Threads
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * PostgreSQL/MySQL JDBC에서 sequential keyset paging과 opt-in parallel range
 * enumeration을 같은 seeded fixture로 비교합니다.
 *
 * 이 benchmark는 benchmark worker가 소유한 Testcontainers datasource/schema와 executor만
 * 정리합니다. shared container lifecycle은 jdbc-tests가 소유합니다.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Threads(1)
@Fork(1)
@State(Scope.Benchmark)
class JdbcDriverKeyEnumerationBenchmark {

    @Param("POSTGRESQL", "MYSQL_V8")
    var driver: String = JdbcBenchmarkDriver.POSTGRESQL.name

    @Param("1000", "10000")
    var rowCount: Int = 1_000

    @Param("1", "2", "4")
    var poolSize: Int = 1

    private lateinit var fixture: DriverBenchmarkFixture
    private lateinit var loader: ExposedEntityMapLoader<Long, Long>
    private lateinit var executor: ExecutorService
    private lateinit var ranges: List<JdbcKeyRange<Long>>

    @Setup(Level.Trial)
    fun setupTrial() {
        val benchmarkCase = JdbcDriverBenchmarkCase(
            driver = JdbcBenchmarkDriver.valueOf(driver),
            rowCount = rowCount,
            poolSize = poolSize,
        )
        var ownedFixture: DriverBenchmarkFixture? = null
        var ownedExecutor: ExecutorService? = null
        try {
            ownedFixture = DriverBenchmarkFixture.open(benchmarkCase)
            ownedExecutor = Executors.newVirtualThreadPerTaskExecutor()
            val ownedLoader = ExposedEntityMapLoader<Long, Long>(
                table = BenchmarkUsers,
                toEntity = { row -> row[BenchmarkUsers.id].value },
            )
            val ownedRanges = buildDriverBenchmarkRanges(rowCount, RANGE_COUNT).map { range ->
                JdbcKeyRange<Long>(
                    lowerInclusive = range.lowerInclusive,
                    upperExclusive = range.upperExclusive,
                )
            }

            preflight(
                fixture = ownedFixture,
                loader = ownedLoader,
                ranges = ownedRanges,
                executor = ownedExecutor,
                benchmarkCase = benchmarkCase,
            )
            ownedFixture.tracker.resetCounters()

            fixture = ownedFixture
            loader = ownedLoader
            executor = ownedExecutor
            ranges = ownedRanges
        } catch (cause: Throwable) {
            shutdownExecutor(ownedExecutor, cause)
            closeFixture(ownedFixture, cause)
            throw cause
        }
    }

    @Setup(Level.Iteration)
    fun setupIteration() {
        check(fixture.tracker.active.get() == 0) {
            "iteration setup found ${fixture.tracker.active.get()} active JDBC connections"
        }
        fixture.tracker.resetCounters()
    }

    @TearDown(Level.Trial)
    fun tearDownTrial() {
        var primary: Throwable? = null
        if (::executor.isInitialized) {
            try {
                shutdownExecutor(executor, null)
            } catch (cause: Throwable) {
                primary = cause
            }
        }
        if (::fixture.isInitialized) {
            try {
                closeFixture(fixture, primary)
            } catch (cause: Throwable) {
                val existing = primary
                if (existing == null) primary = cause else existing.addSuppressed(cause)
            }
        }
        primary?.let { throw it }
    }

    @Benchmark
    fun sequentialKeysetPaging(counters: DriverBenchmarkAuxCounters): Int {
        val before = fixture.tracker.snapshot()
        val count = transaction(fixture.database) {
            loader.loadAllKeys().count()
        }
        check(count == fixture.rowCount) {
            "sequential enumeration returned $count rows; expected ${fixture.rowCount}"
        }
        record(counters, before)
        return count
    }

    @Benchmark
    fun parallelKeyEnumeration(counters: DriverBenchmarkAuxCounters): Int {
        val before = fixture.tracker.snapshot()
        val count = loader
            .loadAllKeysInParallel(
                ranges = ranges,
                options = JdbcParallelKeyEnumerationOptions(
                    maxConcurrency = 2,
                    executor = executor,
                    database = fixture.database,
                ),
            ).also { ids ->
                check(ids.size == fixture.rowCount) {
                    "parallel enumeration returned ${ids.size} rows; expected ${fixture.rowCount}"
                }
                check(ids == fixture.expectedIds) {
                    "parallel enumeration returned unexpected or unordered IDs"
                }
            }.size
        record(counters, before)
        return count
    }

    private fun preflight(
        fixture: DriverBenchmarkFixture,
        loader: ExposedEntityMapLoader<Long, Long>,
        ranges: List<JdbcKeyRange<Long>>,
        executor: ExecutorService,
        benchmarkCase: JdbcDriverBenchmarkCase,
    ) {
        val sequentialIds = transaction(fixture.database) {
            loader.loadAllKeys().toList()
        }
        check(sequentialIds == fixture.expectedIds) {
            "sequential preflight returned unexpected or unordered IDs for ${benchmarkCase.driver}"
        }
        val parallelIds = loader.loadAllKeysInParallel(
            ranges = ranges,
            options = JdbcParallelKeyEnumerationOptions(
                maxConcurrency = benchmarkCase.maxConcurrency,
                executor = executor,
                database = fixture.database,
            ),
        )
        check(parallelIds == fixture.expectedIds) {
            "parallel preflight returned unexpected or unordered IDs for ${benchmarkCase.driver}"
        }
        check(fixture.tracker.active.get() == 0) {
            "preflight leaked ${fixture.tracker.active.get()} JDBC connections"
        }
    }

    private fun record(counters: DriverBenchmarkAuxCounters, before: TrackingSnapshot) {
        val after = fixture.tracker.snapshot()
        check(after.active == 0) {
            "benchmark invocation ended with ${after.active} active JDBC connections"
        }
        check(after.peak <= fixture.poolSize) {
            "benchmark invocation exceeded pool size: peak=${after.peak} pool=${fixture.poolSize}"
        }
        counters.connectionRequests += (after.connectionRequests - before.connectionRequests).toLong()
        counters.statementExecutions += (after.statementExecutions - before.statementExecutions).toLong()
        counters.peakActiveLeases = maxOf(counters.peakActiveLeases, after.peak.toLong())
        counters.activeAtEnd = after.active.toLong()
    }

    private companion object {
        const val RANGE_COUNT = 4
        const val SHUTDOWN_TIMEOUT_SECONDS = 5L

        fun shutdownExecutor(executor: ExecutorService?, primary: Throwable?) {
            if (executor == null) return
            executor.shutdown()
            try {
                if (executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) return
                executor.shutdownNow()
                if (executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) return
                val cause = IllegalStateException("benchmark executor did not terminate")
                if (primary == null) throw cause else primary.addSuppressed(cause)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                executor.shutdownNow()
                if (primary == null) throw interrupted else primary.addSuppressed(interrupted)
            }
        }

        fun closeFixture(fixture: DriverBenchmarkFixture?, primary: Throwable?) {
            if (fixture == null) return
            try {
                fixture.close()
            } catch (cause: Throwable) {
                if (primary == null) throw cause else primary.addSuppressed(cause)
            }
        }
    }
}

/** Secondary JMH metrics; raw primary output remains one row per benchmark invocation. */
@AuxCounters(AuxCounters.Type.EVENTS)
@State(Scope.Thread)
class DriverBenchmarkAuxCounters {
    @JvmField
    final var connectionRequests: Long = 0

    @JvmField
    final var statementExecutions: Long = 0

    @JvmField
    final var peakActiveLeases: Long = 0

    @JvmField
    final var activeAtEnd: Long = 0
}
