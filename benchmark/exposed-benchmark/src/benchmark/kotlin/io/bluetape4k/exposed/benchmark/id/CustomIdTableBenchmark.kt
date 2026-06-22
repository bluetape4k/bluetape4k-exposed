package io.bluetape4k.exposed.benchmark.id

import io.bluetape4k.exposed.core.dao.id.KsuidMillisTable
import io.bluetape4k.exposed.core.dao.id.KsuidTable
import io.bluetape4k.exposed.core.dao.id.SnowflakeIdTable
import io.bluetape4k.exposed.core.dao.id.TimebasedUUIDBase62Table
import io.bluetape4k.exposed.core.dao.id.TimebasedUUIDTable
import io.bluetape4k.exposed.core.dao.id.UlidTable
import io.bluetape4k.exposed.benchmark.support.createJdbcDatabase
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.batchInsert
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
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
class CustomIdTableBenchmark {

    @Param("1000", "10000")
    var rowCount: Int = 1000

    private lateinit var dataSource: com.zaxxer.hikari.HikariDataSource
    private lateinit var database: Database
    private val sequence = AtomicLong()
    private val lookupCursor = AtomicLong()
    private val tables: Array<Table> = arrayOf(
        UuidUsers,
        TimebasedUuidUsers,
        UlidUsers,
        Base62Users,
        SnowflakeUsers,
        KsuidUsers,
        KsuidMillisUsers,
    )

    @Setup(Level.Trial)
    fun setup() {
        val (ds, db) = createJdbcDatabase("custom_id_table_benchmark", maximumPoolSize = 4)
        dataSource = ds
        database = db
        transaction(database) {
            SchemaUtils.drop(*tables)
            SchemaUtils.create(*tables)
            seedRows(UuidUsers, UuidUsers.name, UuidUsers.score)
            seedRows(TimebasedUuidUsers, TimebasedUuidUsers.name, TimebasedUuidUsers.score)
            seedRows(UlidUsers, UlidUsers.name, UlidUsers.score)
            seedRows(Base62Users, Base62Users.name, Base62Users.score)
            seedRows(SnowflakeUsers, SnowflakeUsers.name, SnowflakeUsers.score)
            seedRows(KsuidUsers, KsuidUsers.name, KsuidUsers.score)
            seedRows(KsuidMillisUsers, KsuidMillisUsers.name, KsuidMillisUsers.score)
        }
    }

    @Setup(Level.Iteration)
    fun resetTables() {
        transaction(database) {
            SchemaUtils.drop(*tables)
            SchemaUtils.create(*tables)
        }
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        dataSource.close()
    }

    @Benchmark
    fun uuidTableBatchInsert(): Long = insertRows(UuidUsers, UuidUsers.name, UuidUsers.score)

    @Benchmark
    fun timebasedUuidTableBatchInsert(): Long =
        insertRows(TimebasedUuidUsers, TimebasedUuidUsers.name, TimebasedUuidUsers.score)

    @Benchmark
    fun ulidTableBatchInsert(): Long = insertRows(UlidUsers, UlidUsers.name, UlidUsers.score)

    @Benchmark
    fun base62TableBatchInsert(): Long = insertRows(Base62Users, Base62Users.name, Base62Users.score)

    @Benchmark
    fun snowflakeTableBatchInsert(): Long = insertRows(SnowflakeUsers, SnowflakeUsers.name, SnowflakeUsers.score)

    @Benchmark
    fun ksuidTableBatchInsert(): Long = insertRows(KsuidUsers, KsuidUsers.name, KsuidUsers.score)

    @Benchmark
    fun ksuidMillisTableBatchInsert(): Long =
        insertRows(KsuidMillisUsers, KsuidMillisUsers.name, KsuidMillisUsers.score)

    @Benchmark
    fun uuidTableSelectByName(): Long = selectByName(UuidUsers, UuidUsers.name)

    @Benchmark
    fun timebasedUuidTableSelectByName(): Long = selectByName(TimebasedUuidUsers, TimebasedUuidUsers.name)

    @Benchmark
    fun ulidTableSelectByName(): Long = selectByName(UlidUsers, UlidUsers.name)

    @Benchmark
    fun base62TableSelectByName(): Long = selectByName(Base62Users, Base62Users.name)

    @Benchmark
    fun snowflakeTableSelectByName(): Long = selectByName(SnowflakeUsers, SnowflakeUsers.name)

    @Benchmark
    fun ksuidTableSelectByName(): Long = selectByName(KsuidUsers, KsuidUsers.name)

    @Benchmark
    fun ksuidMillisTableSelectByName(): Long = selectByName(KsuidMillisUsers, KsuidMillisUsers.name)

    private fun seedRows(table: IdTable<*>, nameColumn: Column<String>, scoreColumn: Column<Int>) {
        val rows = (0 until rowCount).asSequence().map { index ->
            seedName(table, index) to index
        }
        table.batchInsert(rows, shouldReturnGeneratedValues = false) { (name, score) ->
            this[nameColumn] = name
            this[scoreColumn] = score
        }
    }

    private fun insertRows(table: IdTable<*>, nameColumn: Column<String>, scoreColumn: Column<Int>): Long =
        transaction(database) {
            val batch = generateSequence {
                val id = sequence.incrementAndGet()
                "entity-$id" to (id % 100).toInt()
            }.take(rowCount)
            table.batchInsert(batch, shouldReturnGeneratedValues = false) { (name, score) ->
                this[nameColumn] = name
                this[scoreColumn] = score
            }
            table.selectAll().count()
        }

    private fun selectByName(table: IdTable<*>, nameColumn: Column<String>): Long =
        transaction(database) {
            val index = Math.floorMod(lookupCursor.getAndIncrement(), rowCount.toLong()).toInt()
            table.selectAll()
                .where { nameColumn eq seedName(table, index) }
                .count()
        }

    private fun seedName(table: IdTable<*>, index: Int): String =
        "seed-${table.tableName}-$index"

    object UuidUsers : IdTable<UUID>("benchmark_uuid_users") {
        override val id: Column<EntityID<UUID>> =
            javaUUID("id").clientDefault { UUID.randomUUID() }.entityId()
        override val primaryKey = PrimaryKey(id)
        val name = varchar("name", 128)
        val score = integer("score")
    }

    object TimebasedUuidUsers : TimebasedUUIDTable("benchmark_timebased_uuid_users") {
        val name = varchar("name", 128)
        val score = integer("score")
    }

    object UlidUsers : UlidTable("benchmark_ulid_users") {
        val name = varchar("name", 128)
        val score = integer("score")
    }

    object Base62Users : TimebasedUUIDBase62Table("benchmark_base62_users") {
        val name = varchar("name", 128)
        val score = integer("score")
    }

    object SnowflakeUsers : SnowflakeIdTable("benchmark_snowflake_users") {
        val name = varchar("name", 128)
        val score = integer("score")
    }

    object KsuidUsers : KsuidTable("benchmark_ksuid_users") {
        val name = varchar("name", 128)
        val score = integer("score")
    }

    object KsuidMillisUsers : KsuidMillisTable("benchmark_ksuid_millis_users") {
        val name = varchar("name", 128)
        val score = integer("score")
    }
}
