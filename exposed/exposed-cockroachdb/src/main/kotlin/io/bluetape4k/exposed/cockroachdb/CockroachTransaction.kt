package io.bluetape4k.exposed.cockroachdb

import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.support.requireZeroOrPositiveNumber
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.Serializable
import java.sql.Connection
import java.sql.SQLException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Retry options for CockroachDB serializable transaction retry handling.
 *
 * ## Contract
 *
 * The first execution counts as an attempt. Retry delays are expressed in
 * milliseconds because this helper is a blocking JDBC helper. The default
 * transaction isolation is `SERIALIZABLE`, matching CockroachDB's default
 * isolation level.
 *
 * ```kotlin
 * val options = CockroachTransactionRetryOptions(
 *     maxAttempts = 5,
 *     minRetryDelay = 25.milliseconds,
 *     maxRetryDelay = 250.milliseconds,
 * )
 * ```
 */
data class CockroachTransactionRetryOptions(
    val maxAttempts: Int = 3,
    val minRetryDelayMillis: Long = 50L,
    val maxRetryDelayMillis: Long = 500L,
    val queryTimeoutSeconds: Int? = null,
    val transactionIsolation: Int = Connection.TRANSACTION_SERIALIZABLE,
): Serializable {

    init {
        maxAttempts.requirePositiveNumber("maxAttempts")
        minRetryDelayMillis.requireZeroOrPositiveNumber("minRetryDelayMillis")
        maxRetryDelayMillis.requireZeroOrPositiveNumber("maxRetryDelayMillis")
        require(maxRetryDelayMillis >= minRetryDelayMillis) {
            "maxRetryDelayMillis must be greater than or equal to minRetryDelayMillis."
        }
        queryTimeoutSeconds?.requirePositiveNumber("queryTimeoutSeconds")
        require(transactionIsolation in JDBC_TRANSACTION_ISOLATION_LEVELS) {
            "transactionIsolation must be a JDBC transaction isolation constant: $transactionIsolation"
        }
    }

    internal fun retryDelayMillis(retryIndex: Int): Long {
        if (maxAttempts <= 1 || maxRetryDelayMillis == 0L) {
            return 0L
        }

        var delay = minRetryDelayMillis
        repeat(retryIndex.coerceAtLeast(0)) {
            val doubled = if (delay > Long.MAX_VALUE / 2) Long.MAX_VALUE else delay * 2
            delay = doubled.coerceAtMost(maxRetryDelayMillis)
        }
        return delay
    }

    companion object {
        private const val serialVersionUID: Long = 1L

        /**
         * Creates retry options with Kotlin [Duration] values.
         */
        operator fun invoke(
            maxAttempts: Int = 3,
            minRetryDelay: Duration = 50.milliseconds,
            maxRetryDelay: Duration = 500.milliseconds,
            queryTimeout: Duration? = null,
            transactionIsolation: Int = Connection.TRANSACTION_SERIALIZABLE,
        ): CockroachTransactionRetryOptions =
            CockroachTransactionRetryOptions(
                maxAttempts = maxAttempts,
                minRetryDelayMillis = minRetryDelay.inWholeMilliseconds,
                maxRetryDelayMillis = maxRetryDelay.inWholeMilliseconds,
                queryTimeoutSeconds = queryTimeout?.inWholeSeconds?.toInt(),
                transactionIsolation = transactionIsolation,
            )
    }
}

/**
 * Returns whether this throwable represents a CockroachDB transaction retry
 * error.
 *
 * ## Contract
 *
 * CockroachDB documents retryable transaction errors as SQLSTATE `40001` with
 * a message beginning with `restart transaction`. The check walks the cause
 * chain so wrapped JDBC and Exposed exceptions keep the original SQL error
 * classification.
 */
fun Throwable.isCockroachRetryableTransactionError(): Boolean =
    causeSequence().filterIsInstance<SQLException>().any { e ->
        e.sqlState == COCKROACH_RETRY_SQLSTATE &&
                e.message?.startsWith(COCKROACH_RETRY_MESSAGE_PREFIX, ignoreCase = true) == true
    }

/**
 * Executes a top-level CockroachDB transaction with retry handling for
 * CockroachDB retryable transaction errors.
 *
 * ## Contract
 *
 * This helper restarts the whole Exposed transaction when CockroachDB reports a
 * retryable transaction error. The inner Exposed transaction is forced to one
 * attempt so retry classification stays CockroachDB-specific instead of
 * retrying every `SQLException`.
 *
 * ```kotlin
 * val orderId = withCockroachTransaction(db) {
 *     Orders.insertAndGetId {
 *         it[customerId] = customer.id
 *     }.value
 * }
 * ```
 *
 * @throws IllegalStateException if called inside an existing Exposed
 * transaction.
 * @throws SQLException when a non-retryable SQL error occurs or retry attempts
 * are exhausted.
 */
inline fun <T> withCockroachTransaction(
    db: Database? = null,
    options: CockroachTransactionRetryOptions = CockroachTransactionRetryOptions(),
    readOnly: Boolean? = null,
    crossinline statement: JdbcTransaction.() -> T,
): T {
    check(TransactionManager.currentOrNull() == null) {
        "withCockroachTransaction must be called outside an existing Exposed transaction."
    }

    return CockroachTransactionRetry.execute(options) {
        transaction(
            db = db,
            transactionIsolation = options.transactionIsolation,
            readOnly = readOnly,
        ) {
            maxAttempts = 1
            queryTimeout = options.queryTimeoutSeconds
            statement()
        }
    }
}

@PublishedApi
internal object CockroachTransactionRetry {

    @PublishedApi
    internal fun <T> execute(
        options: CockroachTransactionRetryOptions,
        block: () -> T,
    ): T {
        val failures = ArrayList<SQLException>()

        repeat(options.maxAttempts) { attemptIndex ->
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: SQLException) {
                if (!e.isCockroachRetryableTransactionError()) {
                    throw e
                }

                failures += e
                if (attemptIndex + 1 >= options.maxAttempts) {
                    failures.dropLast(1).forEach(e::addSuppressed)
                    throw e
                }

                sleepBeforeRetry(options.retryDelayMillis(attemptIndex))
            }
        }

        error("CockroachDB transaction retry loop exited without a result.")
    }

    private fun sleepBeforeRetry(delayMillis: Long) {
        if (delayMillis <= 0L) {
            return
        }

        try {
            Thread.sleep(delayMillis)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        }
    }
}

private fun Throwable.causeSequence(): Sequence<Throwable> =
    generateSequence(this) { it.cause }

private const val COCKROACH_RETRY_SQLSTATE: String = "40001"
private const val COCKROACH_RETRY_MESSAGE_PREFIX: String = "restart transaction"

private val JDBC_TRANSACTION_ISOLATION_LEVELS: Set<Int> = setOf(
    Connection.TRANSACTION_READ_UNCOMMITTED,
    Connection.TRANSACTION_READ_COMMITTED,
    Connection.TRANSACTION_REPEATABLE_READ,
    Connection.TRANSACTION_SERIALIZABLE,
)
