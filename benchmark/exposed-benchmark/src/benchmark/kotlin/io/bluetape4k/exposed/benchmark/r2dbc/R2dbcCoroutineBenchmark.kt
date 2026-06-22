package io.bluetape4k.exposed.benchmark.r2dbc

import io.bluetape4k.exposed.benchmark.support.BenchmarkUsers
import io.bluetape4k.exposed.benchmark.support.createR2dbcDatabase
import io.bluetape4k.exposed.benchmark.support.seedR2dbcUsers
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
class R2dbcCoroutineBenchmark {

    @Param("1000", "10000")
    var rowCount: Int = 1000

    private lateinit var database: R2dbcDatabase
    private lateinit var ids: LongArray
    private val cursor = AtomicLong()

    @Setup(Level.Trial)
    fun setup() = runBlocking {
        database = createR2dbcDatabase("r2dbc_coroutine_benchmark")
        ids = seedR2dbcUsers(database, rowCount).toLongArray()
    }

    @Benchmark
    fun suspendTransactionSelectById(): Int =
        runBlocking {
            val id = nextId()
            suspendTransaction(db = database) {
                BenchmarkUsers
                    .selectAll()
                    .where { BenchmarkUsers.id eq id }
                    .limit(1)
                    .count()
                    .toInt()
            }
        }

    private fun nextId(): Long {
        val index = Math.floorMod(cursor.getAndIncrement(), ids.size.toLong()).toInt()
        return ids[index]
    }
}
