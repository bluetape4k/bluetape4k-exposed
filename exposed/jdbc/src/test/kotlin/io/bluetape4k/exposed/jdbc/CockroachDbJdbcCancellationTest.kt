package io.bluetape4k.exposed.jdbc

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.exposed.tests.AbstractExposedTest
import io.bluetape4k.exposed.tests.TestDBConfig
import io.bluetape4k.testcontainers.database.CockroachServer
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import java.io.PrintWriter
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource

/**
 * CockroachDB의 PostgreSQL-wire `PGConnection.cancelQuery()` 경로를 별도 runtime row로
 * 검증합니다. PostgreSQL fixture의 결과를 상속하지 않고 CockroachDB server-side query
 * 관찰, cancel acknowledgement, rollback, 다음 query recovery, Hikari lease를 함께 봅니다.
 */
class CockroachDbJdbcCancellationTest: AbstractExposedTest() {

    @Test
    @Suppress("LongMethod")
    fun `CockroachDB PGConnection cancelQuery interrupts an active query and recovers the lease`() {
        assumeCockroach()

        val cockroach = CockroachServer.Launcher.cockroach
        val hikari = HikariDataSource(HikariConfig().apply {
            jdbcUrl = cockroach.url
            username = cockroach.username ?: CockroachServer.USERNAME
            password = cockroach.password ?: CockroachServer.PASSWORD
            driverClassName = CockroachServer.DRIVER_CLASS_NAME
            maximumPoolSize = 2
            minimumIdle = 0
            connectionTimeout = HIKARI_TIMEOUT_MS
            poolName = "issue-707-cockroach-cancel"
        })
        val tracker = TrackingDataSource(hikari)
        val target = tracker.connection
        val observer = tracker.connection
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val statement = target.createStatement()
        try {
            target.autoCommit = false
            val outcome = AtomicReference<Throwable?>()
            val elapsedNanos = AtomicLong()
            val future = executor.submit {
                val startedAt = System.nanoTime()
                try {
                    statement.execute("SELECT pg_sleep(30)")
                } catch (cause: Throwable) {
                    outcome.set(cause)
                } finally {
                    elapsedNanos.set(System.nanoTime() - startedAt)
                }
            }

            try {
                awaitCockroachSleep(observer)
                invokePostgreSqlConnectionMethod(target, "cancelQuery")
                future.get(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)

                check(elapsedNanos.get() < TimeUnit.SECONDS.toNanos(LATCH_TIMEOUT_SECONDS)) {
                    "PGConnection.cancelQuery did not finish the CockroachDB query promptly"
                }
                val cancellation = outcome.get() ?: error("cancelQuery must interrupt pg_sleep")
                hasCockroachCancellationCause(cancellation).shouldBeTrue()

                target.rollback()
                target.createStatement().use { recovery ->
                    recovery.executeQuery("SELECT 1").use { result ->
                        check(result.next()) { "CockroachDB recovery query returned no row" }
                        result.getInt(1) shouldBeEqualTo 1
                    }
                }
            } finally {
                runCatching { invokePostgreSqlConnectionMethod(target, "cancelQuery") }
                runCatching { target.close() }
                runCatching { observer.close() }
                runCatching { future.get(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            }
        } finally {
            runCatching { statement.close() }
            executor.close()
            tracker.close()
        }
        tracker.active.get() shouldBeEqualTo 0
    }

    private fun assumeCockroach() {
        Assumptions.assumeTrue(TestDBConfig.useTestcontainers)
    }

    @Suppress("NestedBlockDepth")
    private fun awaitCockroachSleep(observer: Connection) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LATCH_TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            observer.createStatement().use { probe ->
                probe.executeQuery("SHOW QUERIES").use { result ->
                    val queryColumn = (1..result.metaData.columnCount)
                        .firstOrNull { column ->
                            result.metaData.getColumnLabel(column).equals("query", ignoreCase = true) ||
                                result.metaData.getColumnName(column).equals("query", ignoreCase = true)
                        }
                    if (queryColumn != null) {
                        while (result.next()) {
                            if (result.getString(queryColumn).contains("pg_sleep", ignoreCase = true)) {
                                return
                            }
                        }
                    }
                }
            }
            Thread.sleep(25)
        }
        error("CockroachDB pg_sleep query did not become visible in SHOW QUERIES")
    }

    private fun invokePostgreSqlConnectionMethod(
        connection: Connection,
        methodName: String,
    ) {
        val connectionType = Class.forName(POSTGRESQL_CONNECTION_TYPE)
        val extension = connection.unwrap(connectionType)
        try {
            extension.javaClass.getMethod(methodName).invoke(extension)
        } catch (cause: InvocationTargetException) {
            throw cause.targetException
        }
    }

    private fun hasCockroachCancellationCause(failure: Throwable): Boolean =
        generateSequence(failure) { it.cause }.any { cause ->
            val sqlException = cause as? SQLException
            sqlException?.sqlState == "57014" ||
                cause.message.orEmpty().contains("canceling statement", ignoreCase = true)
        }

    private class TrackingDataSource(
        private val delegate: HikariDataSource,
    ): DataSource {
        val active = AtomicInteger()

        override fun getConnection(): Connection = track(delegate.connection)

        override fun getConnection(username: String?, password: String?): Connection =
            track(delegate.getConnection(username, password))

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

        fun close() {
            delegate.close()
        }

        private fun track(connection: Connection): Connection {
            active.incrementAndGet()
            val returned = AtomicBoolean()
            val handler = InvocationHandler { _, method, args ->
                try {
                    method.invoke(connection, *(args ?: emptyArray()))
                } catch (cause: InvocationTargetException) {
                    throw cause.targetException
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
    }

    companion object {
        private const val HIKARI_TIMEOUT_MS = 5_000L
        private const val LATCH_TIMEOUT_SECONDS = 5L
        private const val POSTGRESQL_CONNECTION_TYPE = "org.postgresql.PGConnection"
    }
}
