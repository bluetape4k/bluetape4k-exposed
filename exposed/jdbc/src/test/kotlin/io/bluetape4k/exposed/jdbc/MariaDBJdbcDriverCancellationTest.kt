package io.bluetape4k.exposed.jdbc

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.exposed.tests.AbstractExposedTest
import io.bluetape4k.exposed.tests.Containers
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.TestDBConfig
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import java.io.PrintWriter
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource

/**
 * MariaDB Connector/J의 driver-specific query cancellation과 pool lease recovery를 고정합니다.
 *
 * MariaDB driver는 testRuntimeOnly이므로 `org.mariadb.jdbc.Connection`은 reflection으로
 * unwrap합니다. source capability를 runtime 성공으로 추론하지 않고, 실제 `SLEEP(30)`과
 * 다음 query recovery를 같은 fixture에서 확인합니다.
 */
class MariaDBJdbcDriverCancellationTest: AbstractExposedTest() {

    @Test
    @Suppress("LongMethod")
    fun `MariaDB cancelCurrentQuery interrupts an active query and recovers the lease`() {
        assumeMariaDB()

        val mariaDb = Containers.MariaDB
        val jdbcUrl = TestDB.MARIADB.connection()
        check(jdbcUrl.startsWith(mariaDb.jdbcUrl)) {
            "MariaDB fixture must use the Testcontainers URL: $jdbcUrl"
        }
        val hikari = HikariDataSource(HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            username = mariaDb.username
            password = mariaDb.password
            driverClassName = MARIADB_DRIVER
            maximumPoolSize = 2
            minimumIdle = 0
            connectionTimeout = HIKARI_TIMEOUT_MS
            poolName = "issue-707-mariadb-cancel"
        })
        val tracker = TrackingDataSource(hikari)
        val target = tracker.connection
        val observer = tracker.connection
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val statement = target.createStatement()
        try {
            target.autoCommit = false
            val connectionId = target.createStatement().use { probe ->
                probe.executeQuery("SELECT CONNECTION_ID()").use { result ->
                    check(result.next()) { "MariaDB connection id was not returned" }
                    result.getLong(1)
                }
            }
            val outcome = AtomicReference<Throwable?>()
            val elapsedNanos = AtomicLong()
            val sleepResult = AtomicReference<Int?>()
            val future = executor.submit {
                val startedAt = System.nanoTime()
                try {
                    if (statement.execute("SELECT SLEEP(30)")) {
                        statement.resultSet?.use { result ->
                            if (result.next()) {
                                sleepResult.set(result.getInt(1))
                            }
                        }
                    }
                } catch (cause: Throwable) {
                    outcome.set(cause)
                } finally {
                    elapsedNanos.set(System.nanoTime() - startedAt)
                }
            }

            try {
                awaitMariaDbSleep(observer, connectionId)
                invokeMariaDbConnectionMethod(target, "cancelCurrentQuery")
                future.get(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)

                check(elapsedNanos.get() < TimeUnit.SECONDS.toNanos(LATCH_TIMEOUT_SECONDS)) {
                    "cancelCurrentQuery did not finish the MariaDB query promptly"
                }
                outcome.get()?.let { cancellation ->
                    hasMariaDbCancellationCause(cancellation).shouldBeTrue()
                } ?: run {
                    // MariaDB의 SLEEP()은 query가 중단되면 1을 반환합니다.
                    sleepResult.get() shouldBeEqualTo 1
                }

                target.rollback()
                target.createStatement().use { recovery ->
                    recovery.executeQuery("SELECT 1").use { result ->
                        check(result.next()) { "MariaDB recovery query returned no row" }
                        result.getInt(1) shouldBeEqualTo 1
                    }
                }
            } finally {
                runCatching { invokeMariaDbConnectionMethod(target, "cancelCurrentQuery") }
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

    private fun assumeMariaDB() {
        Assumptions.assumeTrue(TestDBConfig.useTestcontainers)
    }

    private fun awaitMariaDbSleep(
        observer: Connection,
        connectionId: Long,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LATCH_TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            observer.prepareStatement(
                """
                SELECT 1
                FROM information_schema.PROCESSLIST
                WHERE ID = ? AND COMMAND = 'Query' AND INFO LIKE '%SLEEP%'
                """.trimIndent(),
            ).use { probe ->
                probe.setLong(1, connectionId)
                probe.executeQuery().use { result ->
                    if (result.next()) return
                }
            }
            Thread.sleep(25)
        }
        error("MariaDB SLEEP query did not become active before cancellation")
    }

    private fun invokeMariaDbConnectionMethod(
        connection: Connection,
        methodName: String,
    ) {
        val connectionType = Class.forName(MARIADB_CONNECTION_TYPE)
        val extension = connection.unwrap(connectionType)
        try {
            extension.javaClass.getMethod(methodName).invoke(extension)
        } catch (cause: InvocationTargetException) {
            throw cause.targetException
        }
    }

    private fun hasMariaDbCancellationCause(failure: Throwable): Boolean =
        generateSequence(failure) { it.cause }.any { cause ->
            val sqlException = cause as? SQLException
            sqlException?.errorCode == 1317 ||
                sqlException?.sqlState == "70100" ||
                cause.message.orEmpty().contains("interrupted", ignoreCase = true)
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
        private const val MARIADB_DRIVER = "org.mariadb.jdbc.Driver"
        private const val MARIADB_CONNECTION_TYPE = "org.mariadb.jdbc.Connection"
    }
}
