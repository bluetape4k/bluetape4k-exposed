package io.bluetape4k.exposed.benchmark.clickhouse

import io.bluetape4k.exposed.clickhouse.ClickHouseDatabase
import io.bluetape4k.exposed.clickhouse.queryFlow
import io.bluetape4k.exposed.clickhouse.queryList
import io.bluetape4k.testcontainers.database.ClickHouseServer
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import java.util.concurrent.TimeUnit

internal object Numbers: Table("system.numbers") {
    val number = long("number")
}

internal fun clickHouseDatabase(): Database {
    val server = ClickHouseServer.Launcher.clickhouse
    return ClickHouseDatabase.connect(
        jdbcUrl = "jdbc:clickhouse://${server.host}:${server.port}/default?socket_timeout=10000&connection_timeout=10000",
        user = server.username ?: "test",
        password = server.password ?: "test",
    )
}

/** 동일한 SQL의 전체 수집과 스트리밍 처리량을 비교합니다. GC 계측은 별도 프로파일에서 실행합니다. */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
class ClickHouseQueryBenchmark {
    @Param("100000", "1000000")
    var rowCount: Int = 100000

    private lateinit var database: Database

    @Setup
    fun setup() {
        database = clickHouseDatabase()
    }

    @Benchmark
    fun collected(): Long = runBlocking {
        queryList(database) { Numbers.selectAll().limit(rowCount).map { it[Numbers.number] } }.sum()
    }

    @Benchmark
    fun streamed(): Long = runBlocking {
        queryFlow(database, query = { Numbers.selectAll().limit(rowCount) }, mapper = {
            it[Numbers.number]
        }).fold(0L, Long::plus)
    }
}
