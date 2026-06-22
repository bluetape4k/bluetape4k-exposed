package io.bluetape4k.exposed.benchmark.jdbc

import io.bluetape4k.exposed.benchmark.support.BenchmarkUsers
import io.bluetape4k.exposed.benchmark.support.createJdbcDatabase
import io.bluetape4k.exposed.benchmark.support.seedJdbcUsers
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
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
import java.util.concurrent.atomic.AtomicLong

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
class JdbcThreadingBenchmark {

    @Param("1000", "10000")
    var rowCount: Int = 1000

    @Param("10")
    var poolSize: Int = 10

    private lateinit var dataSource: com.zaxxer.hikari.HikariDataSource
    private lateinit var database: org.jetbrains.exposed.v1.jdbc.Database
    private lateinit var virtualThreadExecutor: ExecutorService
    private lateinit var ids: LongArray
    private val cursor = AtomicLong()

    @Setup(Level.Trial)
    fun setup() {
        val (ds, db) = createJdbcDatabase("jdbc_threading_benchmark", poolSize)
        dataSource = ds
        database = db
        virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor()
        ids = seedJdbcUsers(database, rowCount).toLongArray()
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        virtualThreadExecutor.close()
        dataSource.close()
    }

    @Benchmark
    fun platformThreadSelectById(): Int =
        selectOne(nextId())

    @Benchmark
    fun virtualThreadSelectById(): Int =
        virtualThreadExecutor.submit<Int> { selectOne(nextId()) }.get()

    private fun nextId(): Long {
        val index = Math.floorMod(cursor.getAndIncrement(), ids.size.toLong()).toInt()
        return ids[index]
    }

    private fun selectOne(id: Long): Int =
        transaction(database) {
            BenchmarkUsers
                .selectAll()
                .where { BenchmarkUsers.id eq id }
                .limit(1)
                .count()
                .toInt()
        }
}
