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
 * CockroachDB serializable transaction의 retry 처리 옵션입니다.
 *
 * ## 계약
 *
 * 최초 실행도 attempt 횟수에 포함됩니다. 이 helper는 blocking JDBC를 사용하므로 retry delay의
 * 단위는 millisecond입니다. 기본 transaction isolation은 CockroachDB 기본값과 같은
 * `SERIALIZABLE`입니다.
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

        /** Kotlin [Duration] 값으로 retry 옵션을 생성합니다. */
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
 * 이 throwable이 CockroachDB transaction retry error를 나타내는지 반환합니다.
 *
 * ## 계약
 *
 * CockroachDB의 retry 가능한 transaction error는 SQLSTATE `40001`이면서 message가
 * `restart transaction`으로 시작합니다. Cause chain 전체를 확인하므로 JDBC나 Exposed가
 * exception을 감싸도 원래 SQL error 분류를 유지합니다.
 */
fun Throwable.isCockroachRetryableTransactionError(): Boolean =
    causeSequence().filterIsInstance<SQLException>().any { e ->
        e.sqlState == COCKROACH_RETRY_SQLSTATE &&
                e.message?.startsWith(COCKROACH_RETRY_MESSAGE_PREFIX, ignoreCase = true) == true
    }

/**
 * CockroachDB의 retry 가능한 transaction error를 처리하면서 최상위 CockroachDB transaction을 실행합니다.
 *
 * ## 계약
 *
 * CockroachDB가 retry 가능한 transaction error를 보고하면 Exposed transaction 전체를 다시
 * 시작합니다. 내부 Exposed transaction의 attempt는 1회로 고정하여 모든 `SQLException`을
 * 재시도하지 않고 CockroachDB 전용 분류만 적용합니다.
 *
 * ```kotlin
 * val orderId = withCockroachTransaction(db) {
 *     Orders.insertAndGetId {
 *         it[customerId] = customer.id
 *     }.value
 * }
 * ```
 *
 * @throws IllegalStateException 기존 Exposed transaction 내부에서 호출한 경우
 * @throws SQLException retry할 수 없는 SQL error가 발생하거나 retry 횟수를 모두 소진한 경우
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
