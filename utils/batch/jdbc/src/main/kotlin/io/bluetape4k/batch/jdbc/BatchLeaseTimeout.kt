package io.bluetape4k.batch.jdbc

import org.jetbrains.exposed.v1.core.vendors.H2Dialect
import org.jetbrains.exposed.v1.core.vendors.MysqlDialect
import org.jetbrains.exposed.v1.core.vendors.PostgreSQLDialect
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction

private const val MILLIS_PER_SECOND = 1_000L

internal data class H2Timeouts(
    val lockTimeoutMillis: Long,
    val queryTimeoutMillis: Long,
)

internal data class MysqlTimeouts(
    val lockTimeoutSeconds: Long,
    val maxExecutionTimeMillis: Long,
)

/** lease transaction이 DB lock/query 대기에서 무한정 머물지 않도록 dialect별 timeout을 적용한다. */
internal inline fun <T> JdbcTransaction.withLeaseDatabaseTimeout(
    timeoutSeconds: Int,
    block: JdbcTransaction.() -> T,
): T {
    check(timeoutSeconds > 0) { "timeoutSeconds must be positive" }
    maxAttempts = 1
    queryTimeout = timeoutSeconds
    val timeoutMillis = timeoutSeconds.toLong() * MILLIS_PER_SECOND

    return when (db.dialect) {
        is PostgreSQLDialect -> {
            exec("SET LOCAL statement_timeout = ${timeoutMillis}")
            exec("SET LOCAL lock_timeout = ${timeoutMillis}")
            block()
        }

        is H2Dialect -> {
            val previous = H2Timeouts(
                lockTimeoutMillis = readLong("SELECT LOCK_TIMEOUT()"),
                queryTimeoutMillis = readLong(
                    "SELECT SETTING_VALUE FROM INFORMATION_SCHEMA.SETTINGS " +
                        "WHERE SETTING_NAME = 'QUERY_TIMEOUT'",
                ),
            )
            try {
                exec("SET QUERY_TIMEOUT $timeoutMillis")
                exec("SET LOCK_TIMEOUT $timeoutMillis")
                block()
            } finally {
                exec("SET QUERY_TIMEOUT ${previous.queryTimeoutMillis}")
                exec("SET LOCK_TIMEOUT ${previous.lockTimeoutMillis}")
            }
        }

        is MysqlDialect -> {
            val previous = readMysqlTimeouts()
            try {
                exec("SET SESSION MAX_EXECUTION_TIME = $timeoutMillis")
                exec("SET SESSION innodb_lock_wait_timeout = $timeoutSeconds")
                block()
            } finally {
                exec("SET SESSION MAX_EXECUTION_TIME = ${previous.maxExecutionTimeMillis}")
                exec("SET SESSION innodb_lock_wait_timeout = ${previous.lockTimeoutSeconds}")
            }
        }

        else -> error("Unsupported database dialect for batch lease timeout: ${db.dialect.name}")
    }
}

internal fun JdbcTransaction.readLong(sql: String): Long =
    exec(sql) { resultSet ->
        check(resultSet.next()) { "Database timeout setting was not available: $sql" }
        resultSet.getString(1).toLongOrNull()
            ?: error("Database timeout setting was not numeric: $sql")
    } ?: error("Database timeout setting query returned no result: $sql")

internal fun JdbcTransaction.readMysqlTimeouts(): MysqlTimeouts =
    exec(
        "SELECT @@SESSION.innodb_lock_wait_timeout, @@SESSION.max_execution_time",
    ) { resultSet ->
        check(resultSet.next()) { "MySQL timeout settings were not available" }
        MysqlTimeouts(
            lockTimeoutSeconds = resultSet.getLong(1),
            maxExecutionTimeMillis = resultSet.getLong(2),
        )
    } ?: error("MySQL timeout settings query returned no result")
