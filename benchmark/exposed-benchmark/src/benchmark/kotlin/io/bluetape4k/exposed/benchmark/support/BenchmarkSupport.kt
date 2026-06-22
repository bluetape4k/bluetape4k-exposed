package io.bluetape4k.exposed.benchmark.support

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabaseConfig
import org.jetbrains.exposed.v1.r2dbc.SchemaUtils as R2dbcSchemaUtils
import org.jetbrains.exposed.v1.r2dbc.batchInsert as r2dbcBatchInsert
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.util.concurrent.atomic.AtomicLong

object BenchmarkUsers : LongIdTable("benchmark_users") {
    val username = varchar("username", 128)
    val email = varchar("email", 192)
    val age = integer("age")
}

data class BenchmarkUser(
    val username: String,
    val email: String,
    val age: Int,
)

fun createJdbcDatabase(name: String, maximumPoolSize: Int): Pair<HikariDataSource, Database> {
    val config = HikariConfig().apply {
        jdbcUrl = "jdbc:h2:mem:$name;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;"
        driverClassName = "org.h2.Driver"
        username = "sa"
        password = ""
        this.maximumPoolSize = maximumPoolSize
        minimumIdle = maximumPoolSize
    }
    val dataSource = HikariDataSource(config)
    return dataSource to Database.connect(dataSource)
}

fun createR2dbcDatabase(name: String): R2dbcDatabase =
    R2dbcDatabase.connect(
        databaseConfig = R2dbcDatabaseConfig {
            setUrl("r2dbc:h2:mem:///$name;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;")
        },
    )

fun seedJdbcUsers(database: Database, rowCount: Int, sequence: AtomicLong = AtomicLong()): List<Long> =
    transaction(database) {
        SchemaUtils.drop(BenchmarkUsers)
        SchemaUtils.create(BenchmarkUsers)
        BenchmarkUsers.batchInsert(generateUsers(rowCount, sequence), shouldReturnGeneratedValues = true) { user ->
            this[BenchmarkUsers.username] = user.username
            this[BenchmarkUsers.email] = user.email
            this[BenchmarkUsers.age] = user.age
        }.map { it[BenchmarkUsers.id].value }
    }

suspend fun seedR2dbcUsers(database: R2dbcDatabase, rowCount: Int, sequence: AtomicLong = AtomicLong()): List<Long> =
    suspendTransaction(db = database) {
        R2dbcSchemaUtils.drop(BenchmarkUsers)
        R2dbcSchemaUtils.create(BenchmarkUsers)
        BenchmarkUsers.r2dbcBatchInsert(generateUsers(rowCount, sequence), shouldReturnGeneratedValues = true) { user ->
            this[BenchmarkUsers.username] = user.username
            this[BenchmarkUsers.email] = user.email
            this[BenchmarkUsers.age] = user.age
        }.map { it[BenchmarkUsers.id].value }
    }

fun generateUsers(rowCount: Int, sequence: AtomicLong = AtomicLong()): Sequence<BenchmarkUser> =
    generateSequence {
        val id = sequence.incrementAndGet()
        BenchmarkUser(
            username = "user-$id",
            email = "user-$id@example.test",
            age = 20 + (id % 50).toInt(),
        )
    }.take(rowCount)
