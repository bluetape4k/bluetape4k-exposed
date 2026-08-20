package io.bluetape4k.exposed.redisson.map

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.exposed.tests.AbstractExposedTest
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withTables
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
    fun `custom ID fallback emits ordered bounded pages`(testDB: TestDB) {
        val sqlStatements = mutableListOf<String>()
        withTables(
            testDB,
            Issue692CustomIdTable,
        ) {
            listOf("a01", "a02", "a03", "a04", "a05").forEach { id ->
                Issue692CustomIdTable.insert {
                    it[Issue692CustomIdTable.id] = Issue692CustomId(id)
                    it[name] = "name-$id"
                }
            }

            addLogger(object : SqlLogger {
                override fun log(context: StatementContext, transaction: Transaction) {
                    sqlStatements += context.sql(transaction)
                }
            })
            sqlStatements.clear()

            val loader = ExposedEntityMapLoader<Issue692CustomId, String>(
                entityTable = Issue692CustomIdTable,
                batchSize = 2,
                toEntity = { this[Issue692CustomIdTable.id].value.value },
            )

            val ids = requireNotNull(loader.loadAllKeys()).toList()
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
    fun `custom ID mutation preserves weak consistency`(testDB: TestDB) {
        assumeTrue(testDB == TestDB.H2 || testDB == TestDB.POSTGRESQL) {
            "page mutation contract is scoped to READ_COMMITTED H2/PostgreSQL evidence"
        }

        val sqlStatements = mutableListOf<String>()
        val firstSelectObserved = CountDownLatch(1)
        val writerDone = CountDownLatch(1)
        val writerFailure = AtomicReference<Throwable?>()
        val firstSelect = AtomicBoolean(false)
        val writerExecutor = Executors.newSingleThreadExecutor()
        val loaderExecutor = Executors.newSingleThreadExecutor()

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

                val loader = ExposedEntityMapLoader<Issue692CustomId, String>(
                    entityTable = Issue692CustomIdTable,
                    batchSize = 2,
                    toEntity = { this[Issue692CustomIdTable.id].value.value },
                )
                val ids = loaderExecutor.submit<List<Issue692CustomId>> {
                    requireNotNull(loader.loadAllKeys()).toList()
                }.get(10, TimeUnit.SECONDS)

                check(writerDone.await(5, TimeUnit.SECONDS)) { "writer did not finish" }
                writerFailure.get()?.let { throw it }
                ids.map(Issue692CustomId::value) shouldBeEqualTo listOf("a01", "a02", "a04", "a05", "a99")
                ids.distinct() shouldBeEqualTo ids
            }
        } finally {
            writerExecutor.shutdownNow()
            loaderExecutor.shutdownNow()
        }
    }
}
