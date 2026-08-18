package io.bluetape4k.spring.data.exposed.r2dbc

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.exposed.r2dbc.tests.AbstractExposedR2dbcTest
import io.bluetape4k.exposed.r2dbc.tests.TestDB
import io.bluetape4k.exposed.r2dbc.tests.withTables
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.spring.data.exposed.r2dbc.domain.User
import io.bluetape4k.spring.data.exposed.r2dbc.domain.Users
import io.bluetape4k.spring.data.exposed.r2dbc.repository.UserR2dbcRepository
import io.bluetape4k.support.requireNotNull
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.IsolationLevel
import io.r2dbc.spi.R2dbcTransientResourceException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.vendors.H2Dialect
import org.jetbrains.exposed.v1.core.vendors.PostgreSQLDialect
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabaseConfig
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Retry-boundary regression tests for both `saveAll` overloads. */
class SimpleExposedR2dbcRepositoryRetryTest: AbstractExposedR2dbcRepositoryTest() {

    @Autowired
    private lateinit var userRepository: UserR2dbcRepository

    private fun retryDatabaseConfig(testDB: TestDB, maxAttempts: Int) =
        R2dbcDatabaseConfig.Builder().apply {
            setUrl(testDB.connection())
            explicitDialect = when (testDB) {
                TestDB.H2 -> H2Dialect()
                TestDB.POSTGRESQL -> PostgreSQLDialect()
                else -> error("Retry fault matrix supports H2 and PostgreSQL only: $testDB")
            }
            defaultR2dbcIsolationLevel = IsolationLevel.READ_COMMITTED
            defaultMaxAttempts = maxAttempts
            defaultMinRetryDelay = 0
            defaultMaxRetryDelay = 0
        }

    private suspend fun cleanupRetryResources(
        restoreDatabase: suspend () -> Unit,
        closeDatabase: suspend () -> Unit,
        dropTables: suspend () -> Unit,
    ): Throwable? = withContext(NonCancellable) {
        var cleanupFailure: Throwable? = null

        suspend fun cleanup(block: suspend () -> Unit) {
            try {
                block()
            } catch (cause: Throwable) {
                cleanupFailure?.addSuppressed(cause) ?: run { cleanupFailure = cause }
            }
        }

        cleanup(restoreDatabase)
        cleanup(closeDatabase)
        cleanup(dropTables)
        cleanupFailure
    }

    private suspend fun <T> withRetryFaultDatabase(
        testDB: TestDB,
        maxAttempts: Int,
        block: suspend (OneShotR2dbcFaultFactory) -> T,
    ): T {
        val previousDatabase = TransactionManager.defaultDatabase
        var retryDatabase: R2dbcDatabase? = null
        var tablesAttempted = false
        var result: Result<T>? = null
        var primaryFailure: Throwable? = null

        try {
            tablesAttempted = true
            withTables(testDB, Users, dropTables = false) {}
            val faultFactory = OneShotR2dbcFaultFactory(ConnectionFactories.get(testDB.connection()))
            retryDatabase = R2dbcDatabase.connect(faultFactory, retryDatabaseConfig(testDB, maxAttempts))
            TransactionManager.defaultDatabase = retryDatabase
            result = Result.success(block(faultFactory))
        } catch (cause: Throwable) {
            primaryFailure = cause
        } finally {
            val cleanupFailure = cleanupRetryResources(
                restoreDatabase = { TransactionManager.defaultDatabase = previousDatabase },
                closeDatabase = { retryDatabase?.let(TransactionManager::closeAndUnregister) },
                dropTables = { if (tablesAttempted) withTables(testDB, Users) {} },
            )
            if (cleanupFailure != null) {
                primaryFailure?.addSuppressed(cleanupFailure) ?: run { primaryFailure = cleanupFailure }
            }
        }

        primaryFailure?.let { throw it }
        return checkNotNull(result).getOrThrow()
    }

    @ParameterizedTest
    @MethodSource(AbstractExposedR2dbcTest.ENABLE_DIALECTS_METHOD)
    fun `top-level saveAll with Flow does not recollect a one-shot input after a transient failure`(testDB: TestDB) =
        runSuspendIO {
            Assumptions.assumeTrue { testDB in setOf(TestDB.H2, TestDB.POSTGRESQL) }

            withRetryFaultDatabase(testDB, maxAttempts = 2) { faultFactory ->
                val collectionCount = AtomicInteger(0)
                val collected = AtomicBoolean(false)
                val failure = assertFailsWith<R2dbcTransientResourceException> {
                    userRepository.saveAll(
                        flow {
                            check(collected.compareAndSet(false, true)) { "one-shot Flow was collected more than once" }
                            collectionCount.incrementAndGet()
                            emit(User(id = null, name = "Retry-Alice", email = "retry-alice@example.com", age = 30))
                            emit(User(id = null, name = "Retry-Bob", email = "retry-bob@example.com", age = 25))
                        }
                    ).toList()
                }

                failure.message shouldBeEqualTo "one-shot commit retry fault"
                faultFactory.failureCount.get() shouldBeEqualTo 1
                faultFactory.connectionCount.get() shouldBeEqualTo 1
                faultFactory.beginCount.get() shouldBeGreaterThan 0
                faultFactory.commitCount.get() shouldBeEqualTo 1
                faultFactory.rollbackCount.get() shouldBeGreaterThan 0
                faultFactory.closeCount.get() shouldBeEqualTo 1
                collectionCount.get() shouldBeEqualTo 1
                userRepository.findAll().toList() shouldHaveSize 0
                userRepository.count() shouldBeEqualTo 0L
            }
        }

    @ParameterizedTest
    @MethodSource(AbstractExposedR2dbcTest.ENABLE_DIALECTS_METHOD)
    fun `top-level saveAll with Flow does not repeat input side effects after a transient failure`(testDB: TestDB) =
        runSuspendIO {
            Assumptions.assumeTrue { testDB in setOf(TestDB.H2, TestDB.POSTGRESQL) }

            withRetryFaultDatabase(testDB, maxAttempts = 2) { faultFactory ->
                val sideEffectCount = AtomicInteger(0)
                val failure = assertFailsWith<R2dbcTransientResourceException> {
                    userRepository.saveAll(
                        flow {
                            sideEffectCount.incrementAndGet()
                            emit(User(id = null, name = "Retry-Alice", email = "retry-alice@example.com", age = 30))
                            emit(User(id = null, name = "Retry-Bob", email = "retry-bob@example.com", age = 25))
                        }
                    ).toList()
                }

                failure.message shouldBeEqualTo "one-shot commit retry fault"
                faultFactory.failureCount.get() shouldBeEqualTo 1
                faultFactory.connectionCount.get() shouldBeEqualTo 1
                faultFactory.beginCount.get() shouldBeGreaterThan 0
                faultFactory.commitCount.get() shouldBeEqualTo 1
                faultFactory.rollbackCount.get() shouldBeGreaterThan 0
                faultFactory.closeCount.get() shouldBeEqualTo 1
                sideEffectCount.get() shouldBeEqualTo 1
                userRepository.findAll().toList() shouldHaveSize 0
                userRepository.count() shouldBeEqualTo 0L
            }
        }

    @ParameterizedTest
    @MethodSource(AbstractExposedR2dbcTest.ENABLE_DIALECTS_METHOD)
    fun `top-level saveAll with hot SharedFlow does not recollect after a transient failure`(testDB: TestDB) =
        runSuspendIO {
            Assumptions.assumeTrue { testDB in setOf(TestDB.H2, TestDB.POSTGRESQL) }

            withRetryFaultDatabase(testDB, maxAttempts = 2) { faultFactory ->
                val hotInput = MutableSharedFlow<User>(replay = 2)
                check(
                    hotInput.tryEmit(
                        User(id = null, name = "Retry-Alice", email = "retry-alice@example.com", age = 30)
                    )
                )
                check(
                    hotInput.tryEmit(
                        User(id = null, name = "Retry-Bob", email = "retry-bob@example.com", age = 25)
                    )
                )
                val collectionCount = AtomicInteger(0)
                val failure = assertFailsWith<R2dbcTransientResourceException> {
                    userRepository.saveAll(
                        hotInput.asSharedFlow().onStart {
                            check(collectionCount.incrementAndGet() == 1) {
                                "hot SharedFlow was collected more than once"
                            }
                        }.take(2)
                    ).toList()
                }

                failure.message shouldBeEqualTo "one-shot commit retry fault"
                faultFactory.failureCount.get() shouldBeEqualTo 1
                faultFactory.connectionCount.get() shouldBeEqualTo 1
                faultFactory.beginCount.get() shouldBeGreaterThan 0
                faultFactory.commitCount.get() shouldBeEqualTo 1
                faultFactory.rollbackCount.get() shouldBeGreaterThan 0
                faultFactory.closeCount.get() shouldBeEqualTo 1
                collectionCount.get() shouldBeEqualTo 1
                userRepository.findAll().toList() shouldHaveSize 0
                userRepository.count() shouldBeEqualTo 0L
            }
        }

    @ParameterizedTest
    @MethodSource(AbstractExposedR2dbcTest.ENABLE_DIALECTS_METHOD)
    fun `top-level saveAll with Iterable does not re-iterate after a transient failure`(testDB: TestDB) =
        runSuspendIO {
            Assumptions.assumeTrue { testDB in setOf(TestDB.H2, TestDB.POSTGRESQL) }

            withRetryFaultDatabase(testDB, maxAttempts = 2) { faultFactory ->
                val iterationCount = AtomicInteger(0)
                val users = object : Iterable<User> {
                    override fun iterator(): Iterator<User> {
                        iterationCount.incrementAndGet()
                        return listOf(
                            User(id = null, name = "Retry-Alice", email = "retry-alice@example.com", age = 30),
                            User(id = null, name = "Retry-Bob", email = "retry-bob@example.com", age = 25),
                        ).iterator()
                    }
                }
                val failure = assertFailsWith<R2dbcTransientResourceException> {
                    userRepository.saveAll(users).toList()
                }

                failure.message shouldBeEqualTo "one-shot commit retry fault"
                faultFactory.failureCount.get() shouldBeEqualTo 1
                faultFactory.connectionCount.get() shouldBeEqualTo 1
                faultFactory.beginCount.get() shouldBeGreaterThan 0
                faultFactory.commitCount.get() shouldBeEqualTo 1
                faultFactory.rollbackCount.get() shouldBeGreaterThan 0
                faultFactory.closeCount.get() shouldBeEqualTo 1
                iterationCount.get() shouldBeEqualTo 1
                userRepository.findAll().toList() shouldHaveSize 0
                userRepository.count() shouldBeEqualTo 0L
            }
        }

    @ParameterizedTest
    @MethodSource(AbstractExposedR2dbcTest.ENABLE_DIALECTS_METHOD)
    fun `outer transaction keeps caller retry policy for saveAll Flow`(testDB: TestDB) =
        runSuspendIO {
            Assumptions.assumeTrue { testDB in setOf(TestDB.H2, TestDB.POSTGRESQL) }

            withRetryFaultDatabase(testDB, maxAttempts = 2) { faultFactory ->
                val outerAttemptCount = AtomicInteger(0)
                val collectionCount = AtomicInteger(0)
                val commitCountsAtEmission = mutableListOf<Int>()
                val saved = suspendTransaction {
                    maxAttempts = 2
                    outerAttemptCount.incrementAndGet()
                    userRepository.saveAll(
                        flow {
                            collectionCount.incrementAndGet()
                            emit(User(id = null, name = "Retry-Alice", email = "retry-alice@example.com", age = 30))
                            emit(User(id = null, name = "Retry-Bob", email = "retry-bob@example.com", age = 25))
                        }
                    ).onEach { commitCountsAtEmission += faultFactory.commitCount.get() }.toList()
                }

                faultFactory.failureCount.get() shouldBeEqualTo 1
                faultFactory.connectionCount.get() shouldBeEqualTo 2
                faultFactory.beginCount.get() shouldBeGreaterThan 1
                faultFactory.commitCount.get() shouldBeEqualTo 2
                faultFactory.rollbackCount.get() shouldBeGreaterThan 0
                faultFactory.closeCount.get() shouldBeEqualTo 2
                outerAttemptCount.get() shouldBeEqualTo 2
                collectionCount.get() shouldBeEqualTo 2
                commitCountsAtEmission shouldBeEqualTo listOf(0, 0, 1, 1)
                saved shouldHaveSize 2
                saved.map { it.name } shouldBeEqualTo listOf("Retry-Alice", "Retry-Bob")
                val savedIds = saved.map { it.id.requireNotNull("saved.id") }
                savedIds.distinct() shouldHaveSize 2
                val stored = userRepository.findAll().toList()
                stored.map { it.id.requireNotNull("stored.id") }.toSet() shouldBeEqualTo savedIds.toSet()
                userRepository.count() shouldBeEqualTo 2L
            }
        }

    @ParameterizedTest
    @MethodSource(AbstractExposedR2dbcTest.ENABLE_DIALECTS_METHOD)
    fun `outer transaction keeps caller retry policy for saveAll Iterable`(testDB: TestDB) =
        runSuspendIO {
            Assumptions.assumeTrue { testDB in setOf(TestDB.H2, TestDB.POSTGRESQL) }

            withRetryFaultDatabase(testDB, maxAttempts = 2) { faultFactory ->
                val outerAttemptCount = AtomicInteger(0)
                val iterationCount = AtomicInteger(0)
                val commitCountsAtEmission = mutableListOf<Int>()
                val users = object : Iterable<User> {
                    override fun iterator(): Iterator<User> {
                        iterationCount.incrementAndGet()
                        return listOf(
                            User(id = null, name = "Retry-Alice", email = "retry-alice@example.com", age = 30),
                            User(id = null, name = "Retry-Bob", email = "retry-bob@example.com", age = 25),
                        ).iterator()
                    }
                }
                val saved = suspendTransaction {
                    maxAttempts = 2
                    outerAttemptCount.incrementAndGet()
                    userRepository.saveAll(users)
                        .onEach { commitCountsAtEmission += faultFactory.commitCount.get() }
                        .toList()
                }

                faultFactory.failureCount.get() shouldBeEqualTo 1
                faultFactory.connectionCount.get() shouldBeEqualTo 2
                faultFactory.beginCount.get() shouldBeGreaterThan 1
                faultFactory.commitCount.get() shouldBeEqualTo 2
                faultFactory.rollbackCount.get() shouldBeGreaterThan 0
                faultFactory.closeCount.get() shouldBeEqualTo 2
                outerAttemptCount.get() shouldBeEqualTo 2
                iterationCount.get() shouldBeEqualTo 2
                commitCountsAtEmission shouldBeEqualTo listOf(0, 0, 1, 1)
                saved shouldHaveSize 2
                saved.map { it.name } shouldBeEqualTo listOf("Retry-Alice", "Retry-Bob")
                val savedIds = saved.map { it.id.requireNotNull("saved.id") }
                savedIds.distinct() shouldHaveSize 2
                val stored = userRepository.findAll().toList()
                stored.map { it.id.requireNotNull("stored.id") }.toSet() shouldBeEqualTo savedIds.toSet()
                userRepository.count() shouldBeEqualTo 2L
            }
        }
}
