package io.bluetape4k.exposed.benchmark.jdbc

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.exposed.benchmark.support.BenchmarkUsers
import io.bluetape4k.exposed.benchmark.support.seedJdbcUsers
import io.bluetape4k.exposed.tests.Containers
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.TestDBConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.CallableStatement
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * Issue #694 benchmark 전용 database fixture입니다. shared container는 이 타입이 중지하지
 * 않으며, fixture가 소유한 Hikari datasource/schema만 닫습니다.
 */
internal class DriverBenchmarkFixture private constructor(
    val driver: JdbcBenchmarkDriver,
    val rowCount: Int,
    val poolSize: Int,
    val database: Database,
    val expectedIds: List<Long>,
    val tracker: TrackingDataSource,
) : AutoCloseable {

    override fun close() {
        var primary: Throwable? = null
        try {
            check(tracker.active.get() == 0) {
                "fixture teardown found ${tracker.active.get()} active JDBC connections"
            }
            transaction(database) {
                SchemaUtils.drop(BenchmarkUsers)
            }
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
        fun open(case: JdbcDriverBenchmarkCase): DriverBenchmarkFixture {
            check(TestDBConfig.useTestcontainers) {
                "Issue #694 driver benchmark requires Testcontainers"
            }
            val connectionInfo = connectionInfo(case.driver)
            var hikari: HikariDataSource? = null
            var tracker: TrackingDataSource? = null
            var database: Database? = null
            try {
                hikari = HikariDataSource(HikariConfig().apply {
                    jdbcUrl = connectionInfo.jdbcUrl
                    username = connectionInfo.username
                    password = connectionInfo.password
                    driverClassName = connectionInfo.driverClass
                    maximumPoolSize = case.poolSize
                    minimumIdle = 0
                    connectionTimeout = DEFAULT_CONNECTION_TIMEOUT_MS
                    poolName = "issue-694-${case.driver.name.lowercase()}-${case.poolSize}"
                })
                tracker = TrackingDataSource(hikari)
                database = Database.connect(tracker)
                val expectedIds = seedJdbcUsers(database, case.rowCount)
                check(tracker.active.get() == 0) {
                    "fixture setup leaked ${tracker.active.get()} JDBC connections"
                }
                tracker.resetCounters()
                return DriverBenchmarkFixture(
                    driver = case.driver,
                    rowCount = case.rowCount,
                    poolSize = case.poolSize,
                    database = database,
                    expectedIds = expectedIds,
                    tracker = tracker,
                )
            } catch (cause: Throwable) {
                cleanupPartialSetup(database, tracker, hikari, cause)
                throw cause
            }
        }

        private fun connectionInfo(driver: JdbcBenchmarkDriver): ConnectionInfo =
            when (driver) {
                JdbcBenchmarkDriver.POSTGRESQL -> {
                    val container = Containers.Postgres
                    val jdbcUrl = TestDB.POSTGRESQL.connection()
                    check(jdbcUrl.startsWith(container.jdbcUrl)) {
                        "PostgreSQL benchmark did not resolve the shared Testcontainers URL"
                    }
                    ConnectionInfo(
                        jdbcUrl = jdbcUrl,
                        username = requireNotNull(container.username) {
                            "PostgreSQL Testcontainers username is unavailable"
                        },
                        password = requireNotNull(container.password) {
                            "PostgreSQL Testcontainers password is unavailable"
                        },
                        driverClass = TestDB.POSTGRESQL.driver,
                    )
                }

                JdbcBenchmarkDriver.MYSQL_V8 -> {
                    val container = Containers.MySQL8
                    val jdbcUrl = TestDB.MYSQL_V8.connection()
                    check(jdbcUrl.startsWith(container.jdbcUrl)) {
                        "MySQL benchmark did not resolve the shared Testcontainers URL"
                    }
                    ConnectionInfo(
                        jdbcUrl = jdbcUrl,
                        username = requireNotNull(container.username) {
                            "MySQL Testcontainers username is unavailable"
                        },
                        password = requireNotNull(container.password) {
                            "MySQL Testcontainers password is unavailable"
                        },
                        driverClass = TestDB.MYSQL_V8.driver,
                    )
                }
            }

        private fun cleanupPartialSetup(
            database: Database?,
            tracker: TrackingDataSource?,
            hikari: HikariDataSource?,
            primary: Throwable,
        ) {
            if (database != null) {
                try {
                    transaction(database) {
                        SchemaUtils.drop(BenchmarkUsers)
                    }
                } catch (cause: Throwable) {
                    primary.addSuppressed(cause)
                }
            }
            try {
                tracker?.close() ?: hikari?.close()
            } catch (cause: Throwable) {
                primary.addSuppressed(cause)
            }
        }

        private data class ConnectionInfo(
            val jdbcUrl: String,
            val username: String,
            val password: String,
            val driverClass: String,
        )
    }
}

internal class TrackingDataSource(
    private val delegate: HikariDataSource,
) : DataSource {
    val active = AtomicInteger()
    val peak = AtomicInteger()
    val connectionRequests = AtomicInteger()
    val statementExecutions = AtomicInteger()

    override fun getConnection(): Connection = trackConnection(delegate.connection)

    override fun getConnection(username: String?, password: String?): Connection =
        trackConnection(delegate.getConnection(username, password))

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

    fun resetCounters() {
        check(active.get() == 0) { "cannot reset tracker with active connections" }
        peak.set(0)
        connectionRequests.set(0)
        statementExecutions.set(0)
    }

    fun snapshot(): TrackingSnapshot = TrackingSnapshot(
        connectionRequests = connectionRequests.get(),
        statementExecutions = statementExecutions.get(),
        peak = peak.get(),
        active = active.get(),
    )

    fun close() {
        delegate.close()
    }

    private fun trackConnection(connection: Connection): Connection {
        connectionRequests.incrementAndGet()
        val current = active.incrementAndGet()
        peak.updateAndGet { previous -> maxOf(previous, current) }
        val returned = AtomicBoolean()
        val handler = InvocationHandler { _, method, args ->
            if (method.name == "close" && returned.compareAndSet(false, true)) {
                // Hikari releases the physical lease inside delegate.close(). Mark the
                // logical lease returned before invoking it so a waiting borrower cannot
                // observe a transient peak above the configured pool size.
                active.decrementAndGet()
            }
            val result = invokeDelegate(connection, method, args)
            if (result is Statement && Statement::class.java.isAssignableFrom(method.returnType)) {
                trackStatement(result, method.returnType)
            } else {
                result
            }
        }
        return Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
            handler,
        ) as Connection
    }

    private fun trackStatement(statement: Statement, returnType: Class<*>): Any {
        val interfaces = linkedSetOf<Class<*>>().apply {
            if (returnType.isInterface) add(returnType)
            add(Statement::class.java)
            when {
                statement is PreparedStatement -> add(PreparedStatement::class.java)
                statement is CallableStatement -> add(CallableStatement::class.java)
            }
        }
        val handler = InvocationHandler { _, method, args ->
            if (method.name.startsWith("execute")) {
                statementExecutions.incrementAndGet()
            }
            invokeDelegate(statement, method, args)
        }
        return Proxy.newProxyInstance(
            Statement::class.java.classLoader,
            interfaces.toTypedArray(),
            handler,
        )
    }

    private fun invokeDelegate(target: Any, method: Method, args: Array<out Any?>?): Any? =
        try {
            method.invoke(target, *(args ?: emptyArray()))
        } catch (cause: InvocationTargetException) {
            throw cause.targetException
        }
}

internal data class TrackingSnapshot(
    val connectionRequests: Int,
    val statementExecutions: Int,
    val peak: Int,
    val active: Int,
)

private const val DEFAULT_CONNECTION_TIMEOUT_MS = 5_000L
