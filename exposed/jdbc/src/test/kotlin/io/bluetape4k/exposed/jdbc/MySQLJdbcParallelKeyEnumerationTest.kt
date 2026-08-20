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
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import java.io.PrintWriter
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLIntegrityConstraintViolationException
import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource

/**
 * MySQL 8 Connector/J와 Hikari pool에서 parallel JDBC key enumeration의 계약을 고정합니다.
 *
 * 이 파일의 두-SELECT isolation fixture는 내부 `rangeReader` 주입 경로 전용이며,
 * public overload가 하나의 기준 데이터를 공유한다는 계약을 추가하지 않습니다.
 * Docker/Testcontainers가 없는 H2 실행에서는 skip하지만, 컨테이너 기동·driver·schema
 * 오류는 가정으로 숨기지 않고 테스트 실패로 남깁니다.
 */
class MySQLJdbcParallelKeyEnumerationTest: AbstractExposedTest() {

    @Test
    fun `MySQL sparse IDs keep sequential and parallel ordering`() {
        assumeMySQL8()

        MySQLFixture.create(newEnumerationTable()).use { fixture ->
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
    fun `MySQL rejects overlapping and reverse ranges before acquiring a connection`() {
        assumeMySQL8()

        MySQLFixture.create(newEnumerationTable()).use { fixture ->
            fixture.tracker.resetCounters()
            val executor = Executors.newVirtualThreadPerTaskExecutor()
            try {
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
                assertFailsWith<IllegalArgumentException> {
                    parallelJdbcKeyEnumeration(
                        table = fixture.table,
                        ranges = listOf(JdbcKeyRange(lowerInclusive = 5L, upperExclusive = 4L)),
                        options = JdbcParallelKeyEnumerationOptions(
                            executor = executor,
                            database = fixture.database,
                        ),
                    )
                }
                fixture.tracker.requests.get() shouldBeEqualTo 0
                fixture.tracker.active.get() shouldBeEqualTo 0
            } finally {
                executor.close()
            }
        }
    }

    @Test
    fun `MySQL empty ranges return without acquiring a connection`() {
        assumeMySQL8()

        MySQLFixture.create(newEnumerationTable()).use { fixture ->
            fixture.tracker.resetCounters()
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
                fixture.tracker.requests.get() shouldBeEqualTo 0
                fixture.tracker.active.get() shouldBeEqualTo 0
                fixture.tracker.peak.get() shouldBeEqualTo 0
                executor.isShutdown.shouldBeEqualTo(false)
            } finally {
                executor.close()
            }
        }
    }

    @Test
    fun `MySQL pool lease peak reaches the configured bounded concurrency`() {
        assumeMySQL8()

        listOf(1, 2, 4).forEach { poolSize ->
            MySQLFixture.create(newEnumerationTable(), poolSize = poolSize).use { fixture ->
                val expectedPeak = minOf(poolSize, 2)
                val acquired = CountDownLatch(expectedPeak)
                val release = CountDownLatch(1)
                val readerFailure = AtomicReference<Throwable?>()
                val executor = Executors.newVirtualThreadPerTaskExecutor()
                var future: Future<*>? = null
                try {
                    future = executor.submit {
                        runCatching {
                            parallelJdbcKeyEnumeration(
                                table = fixture.table,
                                ranges = List(4) { index ->
                                    JdbcKeyRange(index.toLong(), (index + 1).toLong())
                                },
                                options = JdbcParallelKeyEnumerationOptions(
                                    maxConcurrency = 2,
                                    executor = executor,
                                    database = fixture.database,
                                ),
                            ) { _, _ ->
                                fixture.table.selectAll().toList()
                                acquired.countDown()
                                check(release.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                                    "release barrier timed out"
                                }
                                emptyList()
                            }
                        }.onFailure(readerFailure::set)
                    }

                    check(acquired.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        "expected $expectedPeak MySQL leases, observed ${fixture.tracker.peak.get()}"
                    }
                    release.countDown()
                    future.get(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    readerFailure.get()?.let { throw it }
                    check(fixture.tracker.peak.get() == expectedPeak) {
                        "expected peak=$expectedPeak, actual=${fixture.tracker.peak.get()}, " +
                            "requests=${fixture.tracker.requests.get()}, active=${fixture.tracker.active.get()}"
                    }
                    fixture.tracker.active.get() shouldBeEqualTo 0
                } finally {
                    release.countDown()
                    future?.cancel(true)
                    runCatching { future?.get(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
                    executor.close()
                }
            }
        }
    }

    @Test
    fun `MySQL READ_COMMITTED observes a committed mutation between statements`() {
        assumeMySQL8()

        assertMutationObservation(Connection.TRANSACTION_READ_COMMITTED)
    }

    @Test
    fun `MySQL REPEATABLE_READ keeps the first read boundary`() {
        assumeMySQL8()

        assertMutationObservation(Connection.TRANSACTION_REPEATABLE_READ)
    }

    @Test
    fun `MySQL lease retry preserves cause and request count`() {
        assumeMySQL8()

        val config = DatabaseConfig {
            defaultMaxAttempts = 2
            defaultMinRetryDelay = 0
            defaultMaxRetryDelay = 0
        }
        MySQLFixture.create(
            table = newEnumerationTable(),
            databaseConfig = config,
            failEveryLease = true,
        ).use { fixture ->
            val executor = Executors.newVirtualThreadPerTaskExecutor()
            try {
                val failure = runCatching {
                    parallelJdbcKeyEnumeration(
                        table = fixture.table,
                        ranges = listOf(JdbcKeyRange(upperExclusive = 5L)),
                        options = JdbcParallelKeyEnumerationOptions(
                            maxConcurrency = 1,
                            executor = executor,
                            database = fixture.database,
                        ),
                    )
                }.exceptionOrNull() ?: error("lease failure must preserve a JDBC failure")

                hasConnectionTimeoutCause(failure).shouldBeTrue()
                fixture.tracker.requests.get() shouldBeEqualTo 2
                fixture.tracker.active.get() shouldBeEqualTo 0
                executor.isShutdown.shouldBeEqualTo(false)
            } finally {
                executor.close()
            }
        }
    }

    @Test
    fun `MySQL statement failure rolls back a marker and preserves SQLState`() {
        assumeMySQL8()

        MySQLFixture.create(
            table = newEnumerationTable(),
            databaseConfig = DatabaseConfig { defaultMaxAttempts = 1 },
        ).use { fixture ->
            val executor = Executors.newVirtualThreadPerTaskExecutor()
            try {
                val failure = runCatching {
                    parallelJdbcKeyEnumeration(
                        table = fixture.table,
                        ranges = listOf(JdbcKeyRange(upperExclusive = 5L)),
                        options = JdbcParallelKeyEnumerationOptions(
                            maxConcurrency = 1,
                            executor = executor,
                            database = fixture.database,
                            readOnly = false,
                        ),
                    ) { _, _ ->
                        fixture.table.insert { it[fixture.table.payload] = "marker" }
                        fixture.table.insert { it[fixture.table.payload] = "row-0" }
                        emptyList()
                    }
                }.exceptionOrNull() ?: error("duplicate insert must fail")

                hasIntegrityConstraintCause(failure).shouldBeTrue()
                transaction(fixture.database) {
                    fixture.table.selectAll().count() shouldBeEqualTo 6
                    fixture.table.selectAll()
                        .count { it[fixture.table.payload] == "marker" } shouldBeEqualTo 0
                }
                fixture.tracker.active.get() shouldBeEqualTo 0
            } finally {
                executor.close()
            }
        }
    }

    @Test
    fun `MySQL cleanup preserves primary and suppressed failures`() {
        assumeMySQL8()

        val fixture = MySQLFixture.create(
            table = newEnumerationTable(),
            dropSchemaAction = { error("schema drop failed") },
            failOnDelegateClose = true,
        )
        val failure = runCatching { fixture.close() }.exceptionOrNull()
            ?: error("cleanup must preserve schema-drop failure")
        failure.message shouldContain "schema drop failed"
        failure.suppressed.any { it.message.orEmpty().contains("delegate close failed") }.shouldBeTrue()
        fixture.tracker.delegateClosed.shouldBeTrue()

        val connectionFixture = MySQLFixture.create(
            table = newEnumerationTable(),
            failOnConnectionClose = true,
        )
        try {
            val connection = connectionFixture.tracker.acquireForTest()
            val connectionFailure = runCatching { connection.close() }.exceptionOrNull()
                ?: error("connection close failure must be preserved")
            connectionFailure.message shouldContain "delegate connection close failed"
            (connectionFixture.tracker.active.get() > 0).shouldBeTrue()
            connectionFixture.tracker.failOnConnectionClose = false
            connection.close()
        } finally {
            connectionFixture.tracker.failOnConnectionClose = false
            connectionFixture.close()
        }
    }

    @Test
    fun `MySQL helper keeps the caller executor alive`() {
        assumeMySQL8()

        MySQLFixture.create(newEnumerationTable()).use { fixture ->
            val executor = Executors.newVirtualThreadPerTaskExecutor()
            try {
                parallelJdbcKeyEnumeration(
                    table = fixture.table,
                    ranges = listOf(JdbcKeyRange(upperExclusive = 5L)),
                    options = JdbcParallelKeyEnumerationOptions(
                        maxConcurrency = 1,
                        executor = executor,
                        database = fixture.database,
                    ),
                ) shouldBeEqualTo listOf(1L, 3L, 4L)
                executor.isShutdown.shouldBeEqualTo(false)
                val probe = executor.submit(java.util.concurrent.Callable { 1 })
                    .get(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                probe shouldBeEqualTo 1
            } finally {
                executor.close()
            }
        }
    }

    private fun assertMutationObservation(isolation: Int) {
        MySQLFixture.create(newEnumerationTable(), poolSize = 4).use { fixture ->
            val outcome = runMutation(fixture, isolation)
            assertMutationResult(fixture, isolation, outcome)
        }
    }

    private fun runMutation(
        fixture: MySQLFixture,
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
        fixture: MySQLFixture,
        isolation: Int,
        outcome: Result<List<Long>>,
    ) {
        val ids = outcome.getOrThrow()
        (ids.size == ids.distinct().size).shouldBeTrue()
        if (isolation == Connection.TRANSACTION_READ_COMMITTED) {
            ids shouldContain 9L
        } else {
            ids shouldNotContain 9L
        }
        fixture.tracker.active.get() shouldBeEqualTo 0
    }

    private fun assumeMySQL8() {
        Assumptions.assumeTrue(TestDBConfig.useTestcontainers)
        Assumptions.assumeTrue(TestDB.MYSQL_V8 in TestDB.enabledDialects())
        // Accessing the shared container is intentionally not wrapped: startup errors are failures.
        Containers.MySQL8
    }

    private fun hasConnectionTimeoutCause(failure: Throwable): Boolean =
        generateSequence(failure) { it.cause }.any { cause ->
            cause is SQLTransientConnectionException ||
                cause.message.orEmpty().contains("Connection is not available", ignoreCase = true)
        }

    private fun hasIntegrityConstraintCause(failure: Throwable): Boolean =
        generateSequence(failure) { it.cause }.any { cause ->
            cause is SQLIntegrityConstraintViolationException ||
                (cause as? SQLException)?.sqlState?.startsWith("23") == true
        }

    private class EnumerationTable(name: String): LongIdTable(name) {
        val payload = varchar("payload", 64).uniqueIndex()
    }

    private class MySQLFixture private constructor(
        val table: EnumerationTable,
        val tracker: TrackingDataSource,
        val database: Database,
        private val dropSchemaAction: () -> Unit,
    ): AutoCloseable {
        private val closed = AtomicBoolean()

        override fun close() {
            if (!closed.compareAndSet(false, true)) return

            tracker.failEveryLease = false
            var primary: Throwable? = null
            try {
                dropSchemaAction()
            } catch (cause: Throwable) {
                primary = cause
            }
            try {
                tracker.close()
            } catch (cause: Throwable) {
                val existing = primary
                if (existing == null) primary = cause else existing.addSuppressed(cause)
            }
            primary?.let { throw it }
        }

        companion object {
            fun create(
                table: EnumerationTable,
                poolSize: Int = 4,
                databaseConfig: DatabaseConfig = DatabaseConfig {},
                failEveryLease: Boolean = false,
                dropSchemaAction: (() -> Unit)? = null,
                failOnDelegateClose: Boolean = false,
                failOnConnectionClose: Boolean = false,
            ): MySQLFixture {
                val mysql = Containers.MySQL8
                val jdbcUrl = TestDB.MYSQL_V8.connection()
                check(jdbcUrl.startsWith(mysql.jdbcUrl)) {
                    "MySQL fixture must use the Testcontainers URL: $jdbcUrl"
                }
                val hikari = HikariDataSource(HikariConfig().apply {
                    this.jdbcUrl = jdbcUrl
                    username = mysql.username
                    password = mysql.password
                    driverClassName = MYSQL_DRIVER
                    maximumPoolSize = poolSize
                    minimumIdle = 0
                    connectionTimeout = DEFAULT_HIKARI_TIMEOUT_MS
                    poolName = "issue-698-mysql-$poolSize"
                })
                val tracker = TrackingDataSource(hikari).apply {
                    this.failOnDelegateClose = false
                }
                return try {
                    val database = Database.connect(tracker, databaseConfig = databaseConfig)
                    transaction(database) {
                        SchemaUtils.create(table)
                        repeat(8) { index ->
                            table.insert { it[table.payload] = "row-$index" }
                        }
                        table.deleteWhere { table.id eq 2L }
                        table.deleteWhere { table.id eq 6L }
                    }
                    check(tracker.active.get() == 0) {
                        "fixture setup leaked ${tracker.active.get()} JDBC connections"
                    }
                    tracker.resetCounters()
                    tracker.failEveryLease = failEveryLease
                    tracker.failOnDelegateClose = failOnDelegateClose
                    tracker.failOnConnectionClose = failOnConnectionClose
                    MySQLFixture(
                        table = table,
                        tracker = tracker,
                        database = database,
                        dropSchemaAction = dropSchemaAction ?: { transaction(database) { SchemaUtils.drop(table) } },
                    )
                } catch (cause: Throwable) {
                    runCatching { hikari.close() }.onFailure(cause::addSuppressed)
                    throw cause
                }
            }
        }
    }

    private class TrackingDataSource(
        private val delegate: HikariDataSource,
    ): DataSource {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val requests = AtomicInteger()
        var failEveryLease: Boolean = false
        var failOnDelegateClose: Boolean = false
        var failOnConnectionClose: Boolean = false
        var delegateClosed: Boolean = false
            private set

        override fun getConnection(): Connection {
            leaseRequested()
            return track(delegate.connection)
        }

        override fun getConnection(username: String?, password: String?): Connection {
            leaseRequested()
            return track(delegate.getConnection(username, password))
        }

        override fun getLogWriter(): PrintWriter? = delegate.logWriter

        override fun setLogWriter(out: PrintWriter?) {
            delegate.logWriter = out
        }

        override fun setLoginTimeout(seconds: Int) {
            delegate.loginTimeout = seconds
        }

        override fun getLoginTimeout(): Int = delegate.loginTimeout

        override fun getParentLogger() = delegate.parentLogger

        override fun <T: Any?> unwrap(iface: Class<T>?): T = delegate.unwrap(iface)

        override fun isWrapperFor(iface: Class<*>?): Boolean = delegate.isWrapperFor(iface)

        fun resetCounters() {
            peak.set(0)
            requests.set(0)
        }

        fun acquireForTest(): Connection = getConnection()

        fun close() {
            delegateClosed = true
            delegate.close()
            if (failOnDelegateClose) {
                throw IllegalStateException("delegate close failed")
            }
        }

        private fun leaseRequested() {
            requests.incrementAndGet()
            if (failEveryLease) {
                throw SQLTransientConnectionException("test-injected MySQL pool lease exhaustion")
            }
        }

        private fun track(connection: Connection): Connection {
            active.incrementAndGet()
            peak.updateAndGet { previous -> maxOf(previous, active.get()) }
            val returned = AtomicBoolean()
            val handler = InvocationHandler { _, method, args ->
                if (method.name == "close" && failOnConnectionClose) {
                    invokeDelegate(connection, method, args)
                    throw IllegalStateException("delegate connection close failed")
                }
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
        private const val MYSQL_DRIVER = "com.mysql.cj.jdbc.Driver"
        private val tableSequence = AtomicLong()

        private fun newEnumerationTable(): EnumerationTable =
            EnumerationTable("jdbc_parallel_mysql_698_${tableSequence.incrementAndGet()}")
    }
}
