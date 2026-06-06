package io.bluetape4k.exposed.cockroachdb

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeNull
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

/**
 * Regression tests for CockroachDB transaction retry helpers.
 */
class CockroachTransactionRetryTest: AbstractCockroachDbTest() {

    @Test
    fun `retry predicate detects CockroachDB transaction retry error`() {
        retryableSqlException().isCockroachRetryableTransactionError().shouldBeTrue()
    }

    @Test
    fun `retry predicate detects wrapped CockroachDB transaction retry error`() {
        RuntimeException("wrapped", retryableSqlException())
            .isCockroachRetryableTransactionError()
            .shouldBeTrue()
    }

    @Test
    fun `retry predicate rejects wrong sql state`() {
        SQLException("restart transaction: retry txn", "23505")
            .isCockroachRetryableTransactionError()
            .shouldBeFalse()
    }

    @Test
    fun `retry predicate rejects wrong message prefix`() {
        SQLException("duplicate key value violates unique constraint", "40001")
            .isCockroachRetryableTransactionError()
            .shouldBeFalse()
    }

    @Test
    fun `retry helper succeeds after retryable failures`() {
        var attempts = 0

        val result = CockroachTransactionRetry.execute(noDelayOptions(maxAttempts = 3)) {
            attempts++
            if (attempts < 3) {
                throw retryableSqlException("restart transaction: attempt $attempts")
            }
            "completed"
        }

        result shouldBeEqualTo "completed"
        attempts shouldBeEqualTo 3
    }

    @Test
    fun `retry helper exhausts attempts and preserves suppressed failures`() {
        var attempts = 0

        val failure = assertFailsWith<SQLException> {
            CockroachTransactionRetry.execute(noDelayOptions(maxAttempts = 3)) {
                attempts++
                throw retryableSqlException("restart transaction: attempt $attempts")
            }
        }

        attempts shouldBeEqualTo 3
        failure.message shouldBeEqualTo "restart transaction: attempt 3"
        failure.suppressed shouldHaveSize 2
    }

    @Test
    fun `retry helper does not retry non retryable sql exception`() {
        var attempts = 0
        val duplicateKey = SQLException("duplicate key value violates unique constraint", "23505")

        val failure = assertFailsWith<SQLException> {
            CockroachTransactionRetry.execute(noDelayOptions(maxAttempts = 3)) {
                attempts++
                throw duplicateKey
            }
        }

        attempts shouldBeEqualTo 1
        failure shouldBeEqualTo duplicateKey
    }

    @Test
    fun `retry helper rethrows cancellation without retry`() {
        var attempts = 0
        val cancellation = CancellationException("cancelled")

        val failure = assertFailsWith<CancellationException> {
            CockroachTransactionRetry.execute(noDelayOptions(maxAttempts = 3)) {
                attempts++
                throw cancellation
            }
        }

        attempts shouldBeEqualTo 1
        failure shouldBeEqualTo cancellation
    }

    @Test
    fun `retry helper rethrows interruption while waiting before retry`() {
        var attempts = 0

        try {
            Thread.currentThread().interrupt()

            assertFailsWith<InterruptedException> {
                CockroachTransactionRetry.execute(
                    CockroachTransactionRetryOptions(
                        maxAttempts = 2,
                        minRetryDelayMillis = 1,
                        maxRetryDelayMillis = 1,
                    )
                ) {
                    attempts++
                    throw retryableSqlException()
                }
            }

            Thread.currentThread().isInterrupted.shouldBeTrue()
            attempts shouldBeEqualTo 1
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `retry options reject invalid boundaries`() {
        assertFailsWith<IllegalArgumentException> {
            CockroachTransactionRetryOptions(maxAttempts = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            CockroachTransactionRetryOptions(
                minRetryDelayMillis = 10,
                maxRetryDelayMillis = 5,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            CockroachTransactionRetryOptions(transactionIsolation = Connection.TRANSACTION_NONE)
        }
    }

    @Test
    fun `retry options calculate bounded exponential delay`() {
        val options = CockroachTransactionRetryOptions(
            maxAttempts = 5,
            minRetryDelay = 10.milliseconds,
            maxRetryDelay = 25.milliseconds,
        )

        options.retryDelayMillis(0) shouldBeEqualTo 10L
        options.retryDelayMillis(1) shouldBeEqualTo 20L
        options.retryDelayMillis(2) shouldBeEqualTo 25L
    }

    @Test
    fun `withCockroachTransaction commits normal work`() {
        recreateRetryEvents()

        withCockroachTransaction(db) {
            RetryEvents.insert {
                it[eventName] = "committed"
            }
        }

        transaction(db) {
            RetryEvents.selectAll().count() shouldBeEqualTo 1L
        }
    }

    @Test
    fun `withCockroachTransaction rolls back failed work`() {
        recreateRetryEvents()

        assertFailsWith<IllegalStateException> {
            withCockroachTransaction(db) {
                RetryEvents.insert {
                    it[eventName] = "rolled-back"
                }
                error("force rollback")
            }
        }

        transaction(db) {
            RetryEvents.selectAll().count() shouldBeEqualTo 0L
        }
    }

    @Test
    fun `withCockroachTransaction owns retry attempts and uses serializable isolation`() {
        val isolation = withCockroachTransaction(
            db = db,
            options = noDelayOptions(maxAttempts = 3),
        ) {
            maxAttempts shouldBeEqualTo 1
            queryTimeout.shouldNotBeNull() shouldBeEqualTo 3

            exec("SHOW transaction_isolation") { rs ->
                rs.next()
                rs.getString(1)
            }.shouldNotBeNull()
        }

        isolation shouldBeEqualTo "serializable"
    }

    @Test
    fun `withCockroachTransaction rejects nested Exposed transaction usage`() {
        transaction(db) {
            assertFailsWith<IllegalStateException> {
                withCockroachTransaction(db) {
                    exec("SELECT 1")
                }
            }
        }
    }

    private fun recreateRetryEvents() {
        transaction(db) {
            runCatching { SchemaUtils.drop(RetryEvents) }
            SchemaUtils.create(RetryEvents)
        }
    }

    private fun noDelayOptions(maxAttempts: Int): CockroachTransactionRetryOptions =
        CockroachTransactionRetryOptions(
            maxAttempts = maxAttempts,
            minRetryDelayMillis = 0,
            maxRetryDelayMillis = 0,
            queryTimeoutSeconds = 3,
        )

    private fun retryableSqlException(
        message: String = "restart transaction: retry txn",
    ): SQLException =
        SQLException(message, "40001")

    object RetryEvents: Table("bt4k_cockroach_retry_events") {
        val id = long("id").autoIncrement()
        val eventName = varchar("event_name", 64)

        override val primaryKey: PrimaryKey = PrimaryKey(id)
    }
}
