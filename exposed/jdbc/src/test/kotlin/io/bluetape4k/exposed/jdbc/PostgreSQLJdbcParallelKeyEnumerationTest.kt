package io.bluetape4k.exposed.jdbc

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.exposed.tests.AbstractExposedTest
import io.bluetape4k.exposed.tests.Containers
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.TestDBConfig
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLTransientConnectionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource

/**
 * PostgreSQL JDBC driver에서 bounded Virtual Thread key enumeration의 실제 driver·pool·isolation
 * 계약을 검증합니다.
 *
 * Docker/Testcontainers가 없는 H2 실행에서는 skip합니다. 이 테스트는 production API를
 * 변경하지 않으며, Hikari connection lease 계측은 test-only decorator로 한정합니다.
 */
class PostgreSQLJdbcParallelKeyEnumerationTest: AbstractExposedTest() {

    @Test
    fun `PostgreSQL sparse IDs keep sequential and parallel ordering`() {
        assumePostgreSQL()

        PostgreSQLFixture(newEnumerationTable()).use { fixture ->
            val expected = transaction(fixture.database) {
                fixture.table.selectAll()
                    .orderBy(fixture.table.id)
                    .map { it[fixture.table.id].value }
            }
            val executor = Executors.newVirtualThreadPerTaskExecutor()
            try {
                val actual = parallelJdbcKeyEnumeration(
                    table = fixture.table,
                    ranges = listOf(
                        JdbcKeyRange(upperExclusive = 5L),
                        JdbcKeyRange(lowerInclusive = 5L),
                    ),
                    options = JdbcParallelKeyEnumerationOptions(
                        maxConcurrency = 2,
                        executor = executor,
                        database = fixture.database,
                    ),
                )

                actual shouldBeEqualTo expected
                (actual.size == actual.distinct().size).shouldBeTrue()
                executor.isShutdown.shouldBeEqualTo(false)
            } finally {
                executor.close()
            }
            fixture.tracker.active.get() shouldBeEqualTo 0
        }
    }

    @Test
    fun `PostgreSQL lease peak is bounded for pool smaller equal and larger than concurrency`() {
        assumePostgreSQL()

        listOf(1, 2, 4).forEach { poolSize ->
            PostgreSQLFixture(newEnumerationTable(), poolSize = poolSize).use { fixture ->
                val executor = Executors.newVirtualThreadPerTaskExecutor()
                try {
                    val result = parallelJdbcKeyEnumeration(
                        table = fixture.table,
                        ranges = List(4) { index ->
                            JdbcKeyRange(index.toLong(), (index + 1).toLong())
                        },
                        options = JdbcParallelKeyEnumerationOptions(
                            maxConcurrency = 2,
                            executor = executor,
                            database = fixture.database,
                        ),
                    ) { table, _ ->
                        table.selectAll().toList().size
                        Thread.sleep(40)
                        emptyList()
                    }

                    result shouldBeEqualTo emptyList()
                    (fixture.tracker.peak.get() <= minOf(poolSize, 2)).shouldBeTrue()
                    fixture.tracker.active.get() shouldBeEqualTo 0
                    executor.isShutdown.shouldBeEqualTo(false)
                } finally {
                    executor.close()
                }
            }
        }
    }

    @Test
    fun `PostgreSQL injected lease failure preserves JDBC cause and releases leases`() {
        assumePostgreSQL()

        PostgreSQLFixture(
            table = newEnumerationTable(),
            poolSize = 1,
            failEveryLease = true,
        ).use { fixture ->
            val executor = Executors.newVirtualThreadPerTaskExecutor()
            try {
                val failure = runCatching {
                    parallelJdbcKeyEnumeration(
                        table = fixture.table,
                        ranges = listOf(
                            JdbcKeyRange(upperExclusive = 5L),
                            JdbcKeyRange(lowerInclusive = 5L),
                        ),
                        options = JdbcParallelKeyEnumerationOptions(
                            maxConcurrency = 2,
                            executor = executor,
                            database = fixture.database,
                        ),
                    ) { table, range ->
                        table.selectAll().toList()
                        if (range.upperExclusive == 5L) Thread.sleep(1_000)
                        emptyList()
                    }
                }.exceptionOrNull() ?: error("lease failure must preserve a JDBC failure")

                hasConnectionTimeoutCause(failure).shouldBeTrue()
                fixture.tracker.active.get() shouldBeEqualTo 0
                executor.isShutdown.shouldBeEqualTo(false)
            } finally {
                executor.close()
            }
        }
    }

    @Test
    @Suppress("LongMethod")
    fun `PostgreSQL failed range waits for interrupt-ignoring sibling lease cleanup`() {
        assumePostgreSQL()

        PostgreSQLFixture(newEnumerationTable(), poolSize = 2).use { fixture ->
            val started = CountDownLatch(2)
            val interruptObserved = CountDownLatch(1)
            val allFinished = CountDownLatch(1)
            val activeChildren = AtomicInteger(0)
            val expected = IllegalStateException("PostgreSQL range failure")
            val executor = Executors.newVirtualThreadPerTaskExecutor()
            try {
                val failure = runCatching {
                    parallelJdbcKeyEnumeration(
                        table = fixture.table,
                        ranges = listOf(
                            JdbcKeyRange(upperExclusive = 1L),
                            JdbcKeyRange(lowerInclusive = 1L, upperExclusive = 2L),
                            JdbcKeyRange(lowerInclusive = 2L),
                        ),
                        options = JdbcParallelKeyEnumerationOptions(
                            maxConcurrency = 2,
                            executor = executor,
                            database = fixture.database,
                        ),
                    ) { table, range ->
                        activeChildren.incrementAndGet()
                        try {
                            table.selectAll().toList()
                            started.countDown()
                            if (range.upperExclusive == 1L) {
                                check(started.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                                throw expected
                            }

                            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250)
                            while (System.nanoTime() < deadline) {
                                try {
                                    Thread.sleep(10)
                                } catch (_: InterruptedException) {
                                    interruptObserved.countDown()
                                }
                            }
                            emptyList()
                        } finally {
                            if (activeChildren.decrementAndGet() == 0) {
                                allFinished.countDown()
                            }
                        }
                    }
                }.exceptionOrNull() ?: error("PostgreSQL range failure must be preserved")

                failure shouldBeEqualTo expected
                check(interruptObserved.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    "PostgreSQL sibling must observe cancellation"
                }
                fixture.tracker.active.get() shouldBeEqualTo 0
                activeChildren.get() shouldBeEqualTo 0
                executor.isShutdown.shouldBeEqualTo(false)
            } finally {
                check(allFinished.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    "PostgreSQL interrupt-ignoring sibling must finish"
                }
                executor.close()
            }
        }
    }

    @Test
    fun `PostgreSQL READ_COMMITTED observes a committed mutation between statements`() {
        assumePostgreSQL()

        assertMutationObservation(Connection.TRANSACTION_READ_COMMITTED)
    }

    @Test
    fun `PostgreSQL SERIALIZABLE keeps weak consistency without duplicate IDs`() {
        assumePostgreSQL()

        assertMutationObservation(Connection.TRANSACTION_SERIALIZABLE)
    }

    @Test
    fun `PostgreSQL empty ranges do not acquire a connection`() {
        assumePostgreSQL()

        PostgreSQLFixture(newEnumerationTable()).use { fixture ->
            fixture.tracker.reset()
            val executor = Executors.newVirtualThreadPerTaskExecutor()
            try {
                val result = parallelJdbcKeyEnumeration(
                    table = fixture.table,
                    ranges = emptyList(),
                    options = JdbcParallelKeyEnumerationOptions(
                        executor = executor,
                        database = fixture.database,
                    ),
                )

                result shouldBeEqualTo emptyList()
                fixture.tracker.active.get() shouldBeEqualTo 0
                fixture.tracker.peak.get() shouldBeEqualTo 0
                executor.isShutdown.shouldBeEqualTo(false)

                assertFailsWith<IllegalArgumentException> {
                    parallelJdbcKeyEnumeration(
                        table = fixture.table,
                        ranges = listOf(
                            JdbcKeyRange(upperExclusive = 5L),
                            JdbcKeyRange(lowerInclusive = 4L),
                        ),
                        options = JdbcParallelKeyEnumerationOptions(
                            executor = executor,
                            database = fixture.database,
                        ),
                    )
                }
                fixture.tracker.active.get() shouldBeEqualTo 0
            } finally {
                executor.close()
            }
        }
    }

    private fun assertMutationObservation(isolation: Int) {
        PostgreSQLFixture(newEnumerationTable(), poolSize = 4).use { fixture ->
            val outcome = runMutation(fixture, isolation)
            assertMutationResult(fixture, isolation, outcome)
        }
    }

    private fun runMutation(
        fixture: PostgreSQLFixture,
        isolation: Int,
    ): Result<List<Long>> {
        val readerReady = CountDownLatch(1)
        val writerDone = CountDownLatch(1)
        val writerFailure = AtomicReference<Throwable?>()
        val writerExecutor = Executors.newVirtualThreadPerTaskExecutor()
        val readerExecutor = Executors.newVirtualThreadPerTaskExecutor()
        return try {
            writerExecutor.submit {
                try {
                    check(readerReady.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        "reader did not reach the mutation barrier"
                    }
                    transaction(
                        db = fixture.database,
                        transactionIsolation = Connection.TRANSACTION_READ_COMMITTED,
                    ) {
                        fixture.table.insert { it[fixture.table.payload] = "mutation" }
                    }
                } catch (cause: Throwable) {
                    writerFailure.set(cause)
                } finally {
                    writerDone.countDown()
                }
            }

            val outcome = runCatching {
                parallelJdbcKeyEnumeration(
                    table = fixture.table,
                    ranges = listOf(JdbcKeyRange(lowerInclusive = 1L, upperExclusive = 20L)),
                    options = JdbcParallelKeyEnumerationOptions(
                        maxConcurrency = 1,
                        executor = readerExecutor,
                        database = fixture.database,
                        transactionIsolation = isolation,
                        readOnly = true,
                    ),
                ) { table, _ ->
                    val before = table.selectAll()
                        .orderBy(table.id)
                        .map { it[table.id].value }
                    readerReady.countDown()
                    check(writerDone.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        "writer did not commit before the second statement"
                    }
                    val after = table.selectAll()
                        .orderBy(table.id)
                        .map { it[table.id].value }
                    if (isolation == Connection.TRANSACTION_READ_COMMITTED) after else before
                }
            }
            writerFailure.get()?.let { throw it }
            outcome
        } finally {
            readerExecutor.close()
            writerExecutor.close()
        }
    }

    private fun assertMutationResult(
        fixture: PostgreSQLFixture,
        isolation: Int,
        outcome: Result<List<Long>>,
    ) {
        if (outcome.isSuccess) {
            val ids = outcome.getOrThrow()
            (ids.size == ids.distinct().size).shouldBeTrue()
            if (isolation == Connection.TRANSACTION_READ_COMMITTED) {
                ids shouldContain 9L
            } else {
                ids shouldNotContain 9L
            }
        } else {
            val failure = outcome.exceptionOrNull() ?: error("missing isolation failure")
            hasSerializationFailureCause(failure).shouldBeTrue()
        }
        fixture.tracker.active.get() shouldBeEqualTo 0
    }

    private fun assumePostgreSQL() {
        Assumptions.assumeTrue(TestDBConfig.useTestcontainers)
        Assumptions.assumeTrue(TestDB.POSTGRESQL in TestDB.enabledDialects())
    }

    private fun hasConnectionTimeoutCause(failure: Throwable): Boolean =
        generateSequence(failure) { it.cause }.any { cause ->
            cause is SQLTransientConnectionException ||
                cause.message.orEmpty().contains("Connection is not available", ignoreCase = true)
        }

    private fun hasSerializationFailureCause(failure: Throwable): Boolean =
        generateSequence(failure) { it.cause }.any { cause ->
            cause.message.orEmpty().contains("40001") ||
                cause::class.simpleName.orEmpty().contains("Serialization", ignoreCase = true)
        }

    private class EnumerationTable(name: String): LongIdTable(name) {
        val payload = varchar("payload", 64)
    }

    private class PostgreSQLFixture(
        val table: EnumerationTable,
        poolSize: Int = 4,
        connectionTimeout: Long = DEFAULT_HIKARI_TIMEOUT_MS,
        failEveryLease: Boolean = false,
    ): AutoCloseable {
        val tracker: TrackingDataSource
        val database: Database

        init {
            val postgres = Containers.Postgres
            val hikari = HikariDataSource(HikariConfig().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                driverClassName = POSTGRESQL_DRIVER
                maximumPoolSize = poolSize
                minimumIdle = 0
                this.connectionTimeout = connectionTimeout
                poolName = "issue-694-postgresql-$poolSize"
            })
            tracker = TrackingDataSource(hikari)
            database = Database.connect(tracker)
            transaction(database) {
                SchemaUtils.create(table)
                repeat(8) { index ->
                    table.insert { it[table.payload] = "row-$index" }
                }
                table.deleteWhere { table.id eq 2L }
                table.deleteWhere { table.id eq 6L }
            }
            tracker.reset()
            tracker.failEveryRequest = failEveryLease
        }

        override fun close() {
            tracker.failEveryRequest = false
            transaction(database) {
                SchemaUtils.drop(table)
            }
            tracker.close()
        }
    }

    private class TrackingDataSource(
        private val delegate: HikariDataSource,
    ): DataSource {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        var failEveryRequest: Boolean = false
        private val requests = AtomicInteger()

        override fun getConnection(): Connection {
            failIfRequested()
            return track(delegate.connection)
        }

        override fun getConnection(username: String?, password: String?): Connection {
            failIfRequested()
            return track(delegate.getConnection(username, password))
        }

        override fun getLogWriter() = delegate.logWriter

        override fun setLogWriter(out: java.io.PrintWriter?) {
            delegate.logWriter = out
        }

        override fun setLoginTimeout(seconds: Int) {
            delegate.loginTimeout = seconds
        }

        override fun getLoginTimeout(): Int = delegate.loginTimeout

        override fun getParentLogger() = delegate.parentLogger

        override fun <T: Any?> unwrap(iface: Class<T>?): T = delegate.unwrap(iface)

        override fun isWrapperFor(iface: Class<*>?): Boolean = delegate.isWrapperFor(iface)

        fun reset() {
            active.set(0)
            peak.set(0)
            requests.set(0)
        }

        fun close() {
            delegate.close()
        }

        private fun track(connection: Connection): Connection {
            val current = active.incrementAndGet()
            peak.updateAndGet { previous -> maxOf(previous, active.get()) }
            val returned = AtomicBoolean()
            val handler = InvocationHandler { _, method, args ->
                try {
                    invokeDelegate(connection, method, args)
                } finally {
                    if (method.name == "close" && returned.compareAndSet(false, true)) {
                        active.decrementAndGet()
                    }
                }
            }
            return Proxy.newProxyInstance(
                Connection::class.java.classLoader,
                arrayOf(Connection::class.java),
                handler,
            ) as Connection
        }

        private fun failIfRequested() {
            requests.incrementAndGet()
            if (failEveryRequest) {
                throw SQLTransientConnectionException("test-injected PostgreSQL pool lease exhaustion")
            }
        }

        private fun invokeDelegate(connection: Connection, method: Method, args: Array<out Any?>?): Any? =
            try {
                method.invoke(connection, *(args ?: emptyArray()))
            } catch (cause: InvocationTargetException) {
                throw cause.targetException
            }
    }

    companion object {
        private const val DEFAULT_HIKARI_TIMEOUT_MS = 5_000L
        private const val LATCH_TIMEOUT_SECONDS = 5L
        private const val POSTGRESQL_DRIVER = "org.postgresql.Driver"
        private val tableSequence = AtomicLong()

        private fun newEnumerationTable(): EnumerationTable =
            EnumerationTable("jdbc_parallel_pg_694_${tableSequence.incrementAndGet()}")
    }
}
