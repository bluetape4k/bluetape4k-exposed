package io.bluetape4k.batch.r2dbc

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.vendors.H2Dialect
import org.jetbrains.exposed.v1.core.vendors.MysqlDialect
import org.jetbrains.exposed.v1.core.vendors.PostgreSQLDialect
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction

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
internal suspend inline fun <T> R2dbcTransaction.withLeaseDatabaseTimeout(
    timeoutSeconds: Int,
    block: suspend R2dbcTransaction.() -> T,
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
                withContext(NonCancellable) {
                    exec("SET QUERY_TIMEOUT ${previous.queryTimeoutMillis}")
                    exec("SET LOCK_TIMEOUT ${previous.lockTimeoutMillis}")
                }
            }
        }

        is MysqlDialect -> {
            val previous = readMysqlTimeouts()
            try {
                exec("SET SESSION MAX_EXECUTION_TIME = $timeoutMillis")
                exec("SET SESSION innodb_lock_wait_timeout = $timeoutSeconds")
                block()
            } finally {
                withContext(NonCancellable) {
                    exec("SET SESSION MAX_EXECUTION_TIME = ${previous.maxExecutionTimeMillis}")
                    exec("SET SESSION innodb_lock_wait_timeout = ${previous.lockTimeoutSeconds}")
                }
            }
        }

        else -> error("Unsupported database dialect for batch lease timeout: ${db.dialect.name}")
    }
}

internal suspend fun R2dbcTransaction.readLong(sql: String): Long =
    exec(sql) { row ->
        when (val value = row.get(0)) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }
    }?.firstOrNull()
        ?: error("Database timeout setting was not numeric or unavailable: $sql")

internal suspend fun R2dbcTransaction.readMysqlTimeouts(): MysqlTimeouts =
    exec(
        "SELECT @@SESSION.innodb_lock_wait_timeout, @@SESSION.max_execution_time",
    ) { row ->
        val lockTimeout = (row.get(0) as? Number)?.toLong()
            ?: row.get(0)?.toString()?.toLongOrNull()
        val maxExecutionTime = (row.get(1) as? Number)?.toLong()
            ?: row.get(1)?.toString()?.toLongOrNull()
        if (lockTimeout == null || maxExecutionTime == null) null
        else MysqlTimeouts(lockTimeout, maxExecutionTime)
    }?.firstOrNull()
        ?: error("MySQL timeout settings were not available")
