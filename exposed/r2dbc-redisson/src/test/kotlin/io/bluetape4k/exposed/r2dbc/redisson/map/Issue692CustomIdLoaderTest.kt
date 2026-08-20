package io.bluetape4k.exposed.r2dbc.redisson.map

import io.r2dbc.spi.R2dbcTransientResourceException
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.exposed.r2dbc.tests.AbstractExposedR2dbcTest
import io.bluetape4k.exposed.r2dbc.tests.TestDB
import io.bluetape4k.exposed.r2dbc.tests.withTables
import io.bluetape4k.junit5.coroutines.runSuspendIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.core.SqlLogger
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.transactions.inTopLevelSuspendTransaction
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class Issue692CustomIdLoaderTest: AbstractExposedR2dbcTest() {
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `custom ID fallback emits ordered bounded pages`(testDB: TestDB) = runSuspendIO {
        val sqlStatements = mutableListOf<String>()
        withTables(
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

            withDefaultDatabase(testDB) {
                val loader = R2dbcExposedEntityMapLoader<Issue692CustomId, String>(
                    entityTable = Issue692CustomIdTable,
                    batchSize = 2,
                    toEntity = { this[Issue692CustomIdTable.id].value.value },
                )
                val iterator = loader.loadAllKeys()
                val ids = buildList {
                    while (iterator.hasNext().await() == true) {
                        add(iterator.next().await())
                    }
                }

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
            withTables(
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
                        runBlocking(Dispatchers.IO) {
                            suspendTransaction(db = database) {
                                maxAttempts = 1
                                Issue692CustomIdTable.deleteWhere {
                                    Issue692CustomIdTable.id eq Issue692CustomId("a03")
                                }
                                Issue692CustomIdTable.insert {
                                    it[Issue692CustomIdTable.id] = Issue692CustomId("a99")
                                    it[name] = "name-a99"
                                }
                            }
                        }
                    } catch (cause: Throwable) {
                        writerFailure.set(cause)
                    } finally {
                        writerDone.countDown()
                    }
                }

                withDefaultDatabase(testDB) {
                    val loader = R2dbcExposedEntityMapLoader<Issue692CustomId, String>(
                        entityTable = Issue692CustomIdTable,
                        batchSize = 2,
                        toEntity = { this[Issue692CustomIdTable.id].value.value },
                    )
                    val iterator = loader.loadAllKeys()
                    val ids = buildList {
                        while (iterator.hasNext().await() == true) {
                            add(iterator.next().await())
                        }
                    }

                    check(writerDone.await(5, TimeUnit.SECONDS)) { "writer did not finish" }
                    writerFailure.get()?.let { throw it }
                    ids.map(Issue692CustomId::value) shouldBeEqualTo listOf("a01", "a02", "a04", "a05", "a99")
                    ids.distinct() shouldBeEqualTo ids
                }
            }
        } finally {
            writerExecutor.shutdownNow()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `caller scope cancellation stops the next page query`(testDB: TestDB) = runSuspendIO {
        assumeTrue(testDB == TestDB.H2 || testDB == TestDB.POSTGRESQL) {
            "cancellation evidence is scoped to H2/PostgreSQL"
        }

        val sqlStatements = mutableListOf<String>()
        withTables(
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

            withDefaultDatabase(testDB) {
                val callerJob = Job()
                val scope = CoroutineScope(callerJob + Dispatchers.IO)
                try {
                    val loader = R2dbcExposedEntityMapLoader<Issue692CustomId, String>(
                        entityTable = Issue692CustomIdTable,
                        scope = scope,
                        batchSize = 2,
                        toEntity = { this[Issue692CustomIdTable.id].value.value },
                    )
                    val iterator = loader.loadAllKeys()
                    iterator.hasNext().await() shouldBeEqualTo true
                    iterator.next().await().value shouldBeEqualTo "a01"

                    callerJob.cancel()
                    withTimeout(5_000) { callerJob.join() }

                    val selects = sqlStatements.filter { it.trimStart().startsWith("SELECT", ignoreCase = true) }
                    selects.size shouldBeEqualTo 1
                } finally {
                    callerJob.cancel()
                    withTimeout(5_000) { callerJob.join() }
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `top-level producer fault is not retried or re-emitted`(testDB: TestDB) = runSuspendIO {
        withTables(testDB, Issue692CustomIdTable) {
            val attempts = AtomicInteger()
            val emitted = mutableListOf<Issue692CustomId>()
            val expectedId = Issue692CustomId("a01")
            val expectedFailure = R2dbcTransientResourceException("issue-692 producer fault")
            withDefaultDatabase(testDB) {
                val loader = R2dbcEntityMapLoader<Issue692CustomId, String>(
                    loadByIdFromDB = { _ -> null },
                    loadAllIdsFromDB = { channel ->
                        attempts.incrementAndGet()
                        channel.send(expectedId)
                        emitted += expectedId
                        throw expectedFailure
                    },
                )

                val iterator = loader.loadAllKeys()
                iterator.hasNext().await() shouldBeEqualTo true
                iterator.next().await() shouldBeEqualTo expectedId
                assertFailsWith<R2dbcTransientResourceException> {
                    iterator.hasNext().await()
                }
            }

            attempts.get() shouldBeEqualTo 1
            emitted shouldBeEqualTo listOf(expectedId)
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `ambient transaction owns retry and may replay producer emissions`(testDB: TestDB) = runSuspendIO {
        withTables(testDB, Issue692CustomIdTable) {
            val database = checkNotNull(testDB.db) { "testDB.db must be initialized" }
            val attempts = AtomicInteger()
            val emitted = mutableListOf<Issue692CustomId>()
            val expectedId = Issue692CustomId("a01")
            val expectedFailure = R2dbcTransientResourceException("issue-692 ambient retry")

            assertFailsWith<R2dbcTransientResourceException> {
                inTopLevelSuspendTransaction(db = database) {
                    maxAttempts = 2
                    minRetryDelay = 0
                    maxRetryDelay = 0

                    val loader = R2dbcEntityMapLoader<Issue692CustomId, String>(
                        loadByIdFromDB = { _ -> null },
                        loadAllIdsFromDB = { channel ->
                            attempts.incrementAndGet()
                            channel.send(expectedId)
                            emitted += expectedId
                            throw expectedFailure
                        },
                        scope = CoroutineScope(currentCoroutineContext()),
                    )
                    val iterator = loader.loadAllKeys()
                    while (iterator.hasNext().await() == true) {
                        iterator.next().await()
                    }
                }
            }

            attempts.get() shouldBeEqualTo 2
            emitted shouldBeEqualTo listOf(expectedId, expectedId)
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    @EnabledIfEnvironmentVariable(
        named = "EXPOSED_ISSUE_692_TIMEOUT_TEST",
        matches = "true",
    )
    fun `PostgreSQL whole enumeration timeout preserves its cause`(testDB: TestDB) = runSuspendIO {
        assumeTrue(testDB == TestDB.POSTGRESQL) {
            "the 60-second timeout contract is a PostgreSQL nightly check"
        }

        withTables(testDB, Issue692CustomIdTable) {
            withDefaultDatabase(testDB) {
                val loader = R2dbcEntityMapLoader<Issue692CustomId, String>(
                    loadByIdFromDB = { _ -> null },
                    loadAllIdsFromDB = {
                        delay(60_500)
                    },
                )

                val failure = assertFailsWith<TimeoutException> {
                    loader.loadAllKeys().hasNext().await()
                }
                failure.message shouldBeEqualTo "Loading all IDs exceeded 60000 ms"
            }
        }
    }

    private suspend fun <T> withDefaultDatabase(testDB: TestDB, block: suspend () -> T): T {
        val previousDefault = TransactionManager.defaultDatabase
        TransactionManager.defaultDatabase = checkNotNull(testDB.db) { "testDB.db must be initialized" }
        return try {
            block()
        } finally {
            TransactionManager.defaultDatabase = previousDefault
        }
    }
}
