package io.bluetape4k.exposed.lettuce.map

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.exposed.tests.AbstractExposedTest
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withTablesSuspending
import io.bluetape4k.junit5.coroutines.runSuspendIO
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.core.SqlLogger
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.inTopLevelTransaction
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.sql.Connection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class Issue692CustomIdLoaderTest: AbstractExposedTest() {

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `custom ID fallback emits ordered bounded pages`(testDB: TestDB) = runSuspendIO {
        val sqlStatements = mutableListOf<String>()
        withTablesSuspending(
            testDB,
            Issue692CustomIdTable,
            configure = {
                sqlLogger = object : SqlLogger {
                    override fun log(context: StatementContext, transaction: Transaction) {
                        sqlStatements += context.sql(transaction)
                    }
                }
            },
        ) {
            listOf("a01", "a02", "a03", "a04", "a05").forEach { id ->
                Issue692CustomIdTable.insert {
                    it[Issue692CustomIdTable.id] = Issue692CustomId(id)
                    it[name] = "name-$id"
                }
            }
            commit()
            sqlStatements.clear()

            val loader = SuspendedExposedEntityMapLoader<Issue692CustomId, String>(
                table = Issue692CustomIdTable,
                batchSize = 2,
                toEntity = { row -> row[Issue692CustomIdTable.id].value.value },
            )

            val ids = loader.loadAllKeys()
            ids.map(Issue692CustomId::value) shouldBeEqualTo listOf("a01", "a02", "a03", "a04", "a05")
            ids shouldHaveSize 5
            ids.distinct() shouldBeEqualTo ids

            val selects = sqlStatements.filter { it.trimStart().startsWith("SELECT", ignoreCase = true) }
            selects.size shouldBeEqualTo 3
            selects.all { it.contains("LIMIT", ignoreCase = true) }.shouldBeTrue()
            selects.drop(1).all { it.contains("OFFSET", ignoreCase = true) }.shouldBeTrue()
            selects.none { it.contains(">") }.shouldBeTrue()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    @Suppress("LongMethod")
    fun `custom ID mutation preserves weak consistency`(testDB: TestDB) = runSuspendIO {
        assumeTrue(testDB == TestDB.H2 || testDB == TestDB.POSTGRESQL) {
            "page mutation contract is scoped to READ_COMMITTED H2/PostgreSQL evidence"
        }
        val sqlStatements = mutableListOf<String>()
        val firstSelectObserved = CountDownLatch(1)
        val writerDone = CountDownLatch(1)
        val writerFailure = AtomicReference<Throwable?>()
        val firstSelect = AtomicBoolean(false)
        val writerExecutor = Executors.newSingleThreadExecutor()

        try {
            withTablesSuspending(
                testDB,
                Issue692CustomIdTable,
                configure = {
                    sqlLogger = object : SqlLogger {
                        override fun log(context: StatementContext, transaction: Transaction) {
                            val sql = context.sql(transaction)
                            sqlStatements += sql
                            if (sql.trimStart().startsWith("SELECT", ignoreCase = true) &&
                                firstSelect.compareAndSet(false, true)
                            ) {
                                firstSelectObserved.countDown()
                                check(writerDone.await(5, TimeUnit.SECONDS)) {
                                    "writer did not commit before the next page"
                                }
                            }
                        }
                    }
                },
            ) {
                listOf("a01", "a02", "a03", "a04", "a05").forEach { id ->
                    Issue692CustomIdTable.insert {
                        it[Issue692CustomIdTable.id] = Issue692CustomId(id)
                        it[name] = "name-$id"
                    }
                }
                commit()
                sqlStatements.clear()
                firstSelect.set(false)

                writerExecutor.submit {
                    try {
                        check(firstSelectObserved.await(5, TimeUnit.SECONDS)) {
                            "loader did not reach the first page"
                        }
                        val database = checkNotNull(testDB.db) { "test database is not initialized" }
                        inTopLevelTransaction(
                            db = database,
                            transactionIsolation = Connection.TRANSACTION_READ_COMMITTED,
                        ) {
                            Issue692CustomIdTable.deleteWhere {
                                Issue692CustomIdTable.id eq Issue692CustomId("a03")
                            }
                            Issue692CustomIdTable.insert {
                                it[Issue692CustomIdTable.id] = Issue692CustomId("a99")
                                it[name] = "name-a99"
                            }
                        }
                    } catch (cause: Throwable) {
                        writerFailure.set(cause)
                    } finally {
                        writerDone.countDown()
                    }
                }

                val loader = SuspendedExposedEntityMapLoader<Issue692CustomId, String>(
                    table = Issue692CustomIdTable,
                    batchSize = 2,
                    toEntity = { row -> row[Issue692CustomIdTable.id].value.value },
                )
                val ids = loader.loadAllKeys()

                check(writerDone.await(5, TimeUnit.SECONDS)) { "writer did not finish" }
                writerFailure.get()?.let { throw it }
                ids.map(Issue692CustomId::value) shouldBeEqualTo listOf("a01", "a02", "a04", "a05", "a99")
                ids.distinct() shouldBeEqualTo ids
            }
        } finally {
            writerExecutor.shutdownNow()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `suspended loader cancellation closes the transaction`(testDB: TestDB) = runSuspendIO {
        assumeTrue(testDB == TestDB.H2 || testDB == TestDB.POSTGRESQL) {
            "cancellation evidence is scoped to H2/PostgreSQL"
        }

        val firstSelect = CompletableDeferred<Unit>()
        val releaseSelect = CountDownLatch(1)
        val sqlLogger = object : SqlLogger {
            override fun log(context: StatementContext, transaction: Transaction) {
                if (context.sql(transaction).trimStart().startsWith("SELECT", ignoreCase = true) &&
                    !firstSelect.isCompleted
                ) {
                    firstSelect.complete(Unit)
                    releaseSelect.await(2, TimeUnit.SECONDS)
                }
            }
        }

        withTablesSuspending(
            testDB,
            Issue692CustomIdTable,
            configure = { this.sqlLogger = sqlLogger },
        ) {
            listOf("a01", "a02", "a03", "a04", "a05").forEach { id ->
                Issue692CustomIdTable.insert {
                    it[Issue692CustomIdTable.id] = Issue692CustomId(id)
                    it[name] = "name-$id"
                }
            }
            commit()

            val loader = SuspendedExposedEntityMapLoader<Issue692CustomId, String>(
                table = Issue692CustomIdTable,
                batchSize = 2,
                toEntity = { row -> row[Issue692CustomIdTable.id].value.value },
            )
            val loadJob = launch { loader.loadAllKeys() }
            try {
                withTimeout(5_000) { firstSelect.await() }
                loadJob.cancelAndJoin()
                loadJob.isCancelled.shouldBeTrue()
            } finally {
                releaseSelect.countDown()
            }

            val recovered = loader.loadAllKeys()
            recovered.map(Issue692CustomId::value) shouldBeEqualTo listOf("a01", "a02", "a03", "a04", "a05")
        }
    }
}
