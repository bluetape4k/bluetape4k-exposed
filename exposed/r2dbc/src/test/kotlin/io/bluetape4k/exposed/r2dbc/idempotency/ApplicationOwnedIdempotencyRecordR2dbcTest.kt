package io.bluetape4k.exposed.r2dbc.idempotency

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.exposed.r2dbc.tests.AbstractExposedR2dbcTest
import io.bluetape4k.exposed.r2dbc.tests.TestDB
import io.bluetape4k.exposed.r2dbc.tests.withTables
import io.bluetape4k.junit5.coroutines.runSuspendIO
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.singleOrNull
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.insertIgnore
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.inTopLevelSuspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import org.junit.jupiter.api.Test

class ApplicationOwnedIdempotencyRecordR2dbcTest : AbstractExposedR2dbcTest() {

    @Test
    fun `PostgreSQL unique constraint admits only one concurrent owner`() = runSuspendIO {
        withTables(TestDB.POSTGRESQL, IdempotencyRecords) {
            commit()
            val fixture = R2dbcIdempotencyFixture(db)

            val outcomes = coroutineScope {
                val start = CompletableDeferred<Unit>()
                val attempts = listOf("owner-a", "owner-b")
                    .map { owner ->
                        async {
                            start.await()
                            fixture.acquire(
                                scope = "order-command",
                                key = "idem-concurrent",
                                fingerprint = FINGERPRINT_A,
                                ownerToken = owner,
                                nowEpochMillis = 1_000L,
                            )
                        }
                    }
                start.complete(Unit)
                attempts.awaitAll()
            }

            outcomes.count { it is IdempotencyAcquireResult.Acquired } shouldBeEqualTo 1
            outcomes.count { it is IdempotencyAcquireResult.InFlight } shouldBeEqualTo 1
        }
    }

    @Test
    fun `same key with a different fingerprint is a deterministic conflict`() = runSuspendIO {
        withTables(TestDB.POSTGRESQL, IdempotencyRecords) {
            val fixture = R2dbcIdempotencyFixture(db)

            fixture.acquire("order-command", "idem-conflict", FINGERPRINT_A, "owner-a", 1_000L)
                .shouldBeInstanceOf<IdempotencyAcquireResult.Acquired>()
            fixture.acquire("order-command", "idem-conflict", FINGERPRINT_B, "owner-b", 1_001L)
                .shouldBeInstanceOf<IdempotencyAcquireResult.FingerprintConflict>()
        }
    }

    @Test
    fun `terminal result finalization is guarded by the in-flight owner token`() = runSuspendIO {
        withTables(TestDB.POSTGRESQL, IdempotencyRecords) {
            val fixture = R2dbcIdempotencyFixture(db)

            fixture.acquire("order-command", "idem-finalize", FINGERPRINT_A, "owner-a", 1_000L)
                .shouldBeInstanceOf<IdempotencyAcquireResult.Acquired>()
            fixture.finalize("order-command", "idem-finalize", "owner-b", "result-201", 1_100L)
                .shouldBeFalse()
            fixture.finalize("order-command", "idem-finalize", "owner-a", "result-201", 1_100L)
                .shouldBeTrue()
            fixture.finalize("order-command", "idem-finalize", "owner-a", "result-overwrite", 1_150L)
                .shouldBeFalse()

            fixture.acquire("order-command", "idem-finalize", FINGERPRINT_A, "owner-c", 1_200L)
                .shouldBeEqualTo(IdempotencyAcquireResult.Completed("result-201"))
        }
    }

    @Test
    fun `interrupted owner is diagnosed and replaced only by stale-record policy`() = runSuspendIO {
        withTables(TestDB.POSTGRESQL, IdempotencyRecords) {
            val fixture = R2dbcIdempotencyFixture(db)

            fixture.acquire("order-command", "idem-retry", FINGERPRINT_A, "owner-a", 1_000L)
                .shouldBeInstanceOf<IdempotencyAcquireResult.Acquired>()

            fixture.diagnose(
                scope = "order-command",
                key = "idem-retry",
                nowEpochMillis = 1_499L,
                staleAfterMillis = 500L,
            ).shouldBeInstanceOf<IdempotencyDiagnosis.Active>()
            fixture.retryInterrupted(
                scope = "order-command",
                key = "idem-retry",
                fingerprint = FINGERPRINT_A,
                newOwnerToken = "owner-b",
                staleBeforeEpochMillis = 999L,
                nowEpochMillis = 1_499L,
            ).shouldBeInstanceOf<IdempotencyAcquireResult.InFlight>()
            fixture.finalize("order-command", "idem-retry", "owner-b", "premature-result", 1_499L)
                .shouldBeFalse()

            val interrupted = fixture.diagnose(
                scope = "order-command",
                key = "idem-retry",
                nowEpochMillis = 1_500L,
                staleAfterMillis = 500L,
            )
            interrupted.shouldBeInstanceOf<IdempotencyDiagnosis.Interrupted>()
            interrupted.metricLabels() shouldBeEqualTo mapOf(
                "state" to "in_flight",
                "policy" to "retry_after_timeout",
            )

            fixture.retryInterrupted(
                scope = "order-command",
                key = "idem-retry",
                fingerprint = FINGERPRINT_B,
                newOwnerToken = "owner-b",
                staleBeforeEpochMillis = 1_000L,
                nowEpochMillis = 1_500L,
            ).shouldBeInstanceOf<IdempotencyAcquireResult.FingerprintConflict>()

            fixture.retryInterrupted(
                scope = "order-command",
                key = "idem-retry",
                fingerprint = FINGERPRINT_A,
                newOwnerToken = "owner-b",
                staleBeforeEpochMillis = 1_000L,
                nowEpochMillis = 1_500L,
            ).shouldBeInstanceOf<IdempotencyAcquireResult.Acquired>()

            fixture.finalize("order-command", "idem-retry", "owner-a", "stale-result", 2_100L)
                .shouldBeFalse()
            fixture.finalize("order-command", "idem-retry", "owner-b", "result-202", 2_100L)
                .shouldBeTrue()
        }
    }

    private class R2dbcIdempotencyFixture(
        private val database: R2dbcDatabase,
    ) {

        suspend fun acquire(
            scope: String,
            key: String,
            fingerprint: String,
            ownerToken: String,
            nowEpochMillis: Long,
        ): IdempotencyAcquireResult {
            val inserted = transaction {
                IdempotencyRecords.insertIgnore { row ->
                    row[IdempotencyRecords.scope] = scope
                    row[IdempotencyRecords.key] = key
                    row[IdempotencyRecords.fingerprint] = fingerprint
                    row[IdempotencyRecords.ownerToken] = ownerToken
                    row[IdempotencyRecords.state] = STATE_IN_FLIGHT
                    row[IdempotencyRecords.resultReference] = null
                    row[IdempotencyRecords.updatedAtEpochMillis] = nowEpochMillis
                }.insertedCount
            }
            return if (inserted == 1) {
                IdempotencyAcquireResult.Acquired
            } else {
                classify(requireNotNull(load(scope, key)), fingerprint)
            }
        }

        suspend fun finalize(
            scope: String,
            key: String,
            ownerToken: String,
            resultReference: String,
            nowEpochMillis: Long,
        ): Boolean =
            transaction {
                IdempotencyRecords.update(
                    where = {
                        (IdempotencyRecords.scope eq scope) and
                                (IdempotencyRecords.key eq key) and
                                (IdempotencyRecords.ownerToken eq ownerToken) and
                                (IdempotencyRecords.state eq STATE_IN_FLIGHT)
                    }
                ) { row ->
                    row[IdempotencyRecords.state] = STATE_COMPLETED
                    row[IdempotencyRecords.resultReference] = resultReference
                    row[IdempotencyRecords.updatedAtEpochMillis] = nowEpochMillis
                } == 1
            }

        suspend fun diagnose(
            scope: String,
            key: String,
            nowEpochMillis: Long,
            staleAfterMillis: Long,
        ): IdempotencyDiagnosis {
            val snapshot = load(scope, key) ?: return IdempotencyDiagnosis.Missing
            return when {
                snapshot.state == STATE_COMPLETED -> IdempotencyDiagnosis.Completed
                nowEpochMillis - snapshot.updatedAtEpochMillis >= staleAfterMillis -> IdempotencyDiagnosis.Interrupted
                else -> IdempotencyDiagnosis.Active
            }
        }

        suspend fun retryInterrupted(
            scope: String,
            key: String,
            fingerprint: String,
            newOwnerToken: String,
            staleBeforeEpochMillis: Long,
            nowEpochMillis: Long,
        ): IdempotencyAcquireResult {
            val before = load(scope, key) ?: return acquire(
                scope,
                key,
                fingerprint,
                newOwnerToken,
                nowEpochMillis,
            )
            if (before.fingerprint != fingerprint) {
                return IdempotencyAcquireResult.FingerprintConflict
            }

            val replaced = transaction {
                IdempotencyRecords.update(
                    where = {
                        (IdempotencyRecords.scope eq scope) and
                                (IdempotencyRecords.key eq key) and
                                (IdempotencyRecords.fingerprint eq fingerprint) and
                                (IdempotencyRecords.state eq STATE_IN_FLIGHT) and
                                (IdempotencyRecords.updatedAtEpochMillis lessEq staleBeforeEpochMillis)
                    }
                ) { row ->
                    row[IdempotencyRecords.ownerToken] = newOwnerToken
                    row[IdempotencyRecords.updatedAtEpochMillis] = nowEpochMillis
                } == 1
            }
            return if (replaced) {
                IdempotencyAcquireResult.Acquired
            } else {
                classify(requireNotNull(load(scope, key)), fingerprint)
            }
        }

        private suspend fun load(scope: String, key: String): IdempotencySnapshot? =
            transaction {
                IdempotencyRecords.selectAll()
                    .where {
                        (IdempotencyRecords.scope eq scope) and
                                (IdempotencyRecords.key eq key)
                    }
                    .singleOrNull()
                    ?.let { row ->
                        val updatedAtEpochMillis: Long = row[IdempotencyRecords.updatedAtEpochMillis]
                        IdempotencySnapshot(
                            fingerprint = row[IdempotencyRecords.fingerprint],
                            state = row[IdempotencyRecords.state],
                            resultReference = row[IdempotencyRecords.resultReference],
                            updatedAtEpochMillis = updatedAtEpochMillis,
                        )
                    }
            }

        private fun classify(
            snapshot: IdempotencySnapshot,
            fingerprint: String,
        ): IdempotencyAcquireResult =
            when {
                snapshot.fingerprint != fingerprint -> IdempotencyAcquireResult.FingerprintConflict
                snapshot.state == STATE_COMPLETED ->
                    IdempotencyAcquireResult.Completed(requireNotNull(snapshot.resultReference))

                else -> IdempotencyAcquireResult.InFlight
            }

        private suspend fun <T> transaction(statement: suspend R2dbcTransaction.() -> T): T =
            inTopLevelSuspendTransaction(db = database) {
                maxAttempts = 1
                statement()
            }
    }

    private sealed interface IdempotencyAcquireResult {
        data object Acquired : IdempotencyAcquireResult
        data object FingerprintConflict : IdempotencyAcquireResult
        data object InFlight : IdempotencyAcquireResult
        data class Completed(val resultReference: String) : IdempotencyAcquireResult
    }

    private sealed interface IdempotencyDiagnosis {
        data object Missing : IdempotencyDiagnosis
        data object Active : IdempotencyDiagnosis
        data object Interrupted : IdempotencyDiagnosis
        data object Completed : IdempotencyDiagnosis
    }

    private data class IdempotencySnapshot(
        val fingerprint: String,
        val state: String,
        val resultReference: String?,
        val updatedAtEpochMillis: Long,
    )

    private fun IdempotencyDiagnosis.metricLabels(): Map<String, String> =
        when (this) {
            IdempotencyDiagnosis.Interrupted -> mapOf(
                "state" to "in_flight",
                "policy" to "retry_after_timeout",
            )

            IdempotencyDiagnosis.Active -> mapOf("state" to "in_flight", "policy" to "wait")
            IdempotencyDiagnosis.Completed -> mapOf("state" to "completed", "policy" to "replay_result")
            IdempotencyDiagnosis.Missing -> mapOf("state" to "missing", "policy" to "acquire")
        }

    private object IdempotencyRecords : Table("app_idempotency_record_r2dbc") {
        val scope = varchar("scope", 80)
        val key = varchar("idempotency_key", 120)
        val fingerprint = varchar("request_fingerprint", 80)
        val ownerToken = varchar("owner_token", 80)
        val state = varchar("state", 20)
        val resultReference = varchar("result_reference", 160).nullable()
        val updatedAtEpochMillis = long("updated_at_epoch_ms")

        override val primaryKey = PrimaryKey(scope, key)
    }

    companion object {
        private const val FINGERPRINT_A =
            "sha256:5e884898da28047151d0e56f8dc6292773603d0d6aabbdd62a11ef721d1542d8"
        private const val FINGERPRINT_B =
            "sha256:2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
        private const val STATE_IN_FLIGHT = "IN_FLIGHT"
        private const val STATE_COMPLETED = "COMPLETED"
    }
}
