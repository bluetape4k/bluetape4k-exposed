package io.bluetape4k.exposed.ktor

import io.bluetape4k.codec.Base58
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.ktor.core.Bluetape4kKtorCoreConfig
import io.bluetape4k.ktor.core.HealthResponse
import io.bluetape4k.ktor.core.installBluetape4kKtorCore
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabaseConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExposedKtorTransactionsTest {

    @Test
    fun `jdbc transaction helper uses caller supplied dispatcher and commits`() = testApplication {
        val database = newJdbcDatabase()
        val dispatcher = Executors.newSingleThreadExecutor { command ->
            Thread(command, "ktor-jdbc-worker-${Base58.randomString(8)}")
        }.asCoroutineDispatcher()

        try {
            transaction(database) {
                SchemaUtils.create(JdbcTransactionItems)
            }

            application {
                routing {
                    get("/jdbc/{name}") {
                        val threadName = call.exposedJdbcTransaction(database, dispatcher) {
                            JdbcTransactionItems.insert {
                                it[name] = call.parameters["name"] ?: "missing"
                            }
                            Thread.currentThread().name
                        }
                        call.respondText(threadName)
                    }
                }
            }

            val response = client.get("/jdbc/blue")
            response.status shouldBeEqualTo HttpStatusCode.OK
            response.bodyAsText() shouldContain "ktor-jdbc-worker-"

            transaction(database) {
                JdbcTransactionItems.selectAll().single()[JdbcTransactionItems.name] shouldBeEqualTo "blue"
            }
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun `jdbc transaction helper rolls back failed user block`() = testApplication {
        val database = newJdbcDatabase()
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

        try {
            transaction(database) {
                SchemaUtils.create(JdbcTransactionItems)
            }

            application {
                installBluetape4kKtorCore(
                    Bluetape4kKtorCoreConfig(
                        installStatusPages = false,
                        installHealthRoutes = false,
                    )
                )
                routing {
                    get("/jdbc/fail") {
                        call.exposedJdbcTransaction(database, dispatcher) {
                            JdbcTransactionItems.insert {
                                it[name] = "rolled-back"
                            }
                            error("force rollback")
                        }
                    }
                }
            }

            client.get("/jdbc/fail").status shouldBeEqualTo HttpStatusCode.InternalServerError

            transaction(database) {
                JdbcTransactionItems.selectAll().count() shouldBeEqualTo 0L
            }
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun `jdbc transaction helper inherits database timeout and honors receiver override`() = testApplication {
        val database = Database.connect(
            url = "jdbc:h2:mem:ktor-tx-jdbc-timeout-${Base58.randomString(8)};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
            user = "sa",
            password = "",
            databaseConfig = DatabaseConfig {
                defaultQueryTimeout = 7
            },
        )
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

        try {
            application {
                routing {
                    get("/jdbc-timeout") {
                        val observed = call.exposedJdbcTransaction(database, dispatcher) {
                            val inherited = queryTimeout
                            queryTimeout = 3
                            inherited to queryTimeout
                        }
                        call.respondText(observed.toString())
                    }
                }
            }

            client.get("/jdbc-timeout").bodyAsText() shouldBeEqualTo "(7, 3)"
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun `r2dbc transaction helper runs suspend transaction`() = testApplication {
        val database = R2dbcDatabase.connect(
            databaseConfig = R2dbcDatabaseConfig {
                setUrl("r2dbc:h2:mem:///ktor-tx-r2dbc-${Base58.randomString(8)};DB_CLOSE_DELAY=-1;")
            }
        )

        application {
            routing {
                get("/r2dbc") {
                    val selected = call.exposedR2dbcTransaction(database) {
                        exec("SELECT 42") { row ->
                            row.get(0, Int::class.javaObjectType)
                        }?.single()
                    }
                    call.respondText(selected.toString())
                }
            }
        }

        val response = client.get("/r2dbc")
        response.status shouldBeEqualTo HttpStatusCode.OK
        response.bodyAsText() shouldBeEqualTo "42"
    }

    @Test
    fun `r2dbc transaction helper inherits database timeout and honors receiver override`() = testApplication {
        val database = R2dbcDatabase.connect(
            databaseConfig = R2dbcDatabaseConfig {
                setUrl("r2dbc:h2:mem:///ktor-tx-r2dbc-timeout-${Base58.randomString(8)};DB_CLOSE_DELAY=-1;")
                defaultQueryTimeout = 11
            }
        )

        application {
            routing {
                get("/r2dbc-timeout") {
                    val observed = call.exposedR2dbcTransaction(database) {
                        val inherited = queryTimeout
                        queryTimeout = 4
                        inherited to queryTimeout
                    }
                    call.respondText(observed.toString())
                }
            }
        }

        client.get("/r2dbc-timeout").bodyAsText() shouldBeEqualTo "(11, 4)"
    }

    @Test
    fun `jdbc readiness timeout overrides database default and truncates subsecond duration`() = runBlocking {
        val observedQueryTimeout = AtomicInteger()
        val h2 = org.h2.jdbcx.JdbcDataSource().apply {
            setURL("jdbc:h2:mem:ktor-jdbc-timeout-precedence;DB_CLOSE_DELAY=-1")
            user = "sa"
            password = ""
        }
        val database = Database.connect(
            recordingDataSource(h2, observedQueryTimeout),
            databaseConfig = DatabaseConfig {
                defaultQueryTimeout = 9
            },
        )
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()

        try {
            val result = probeJdbcReadiness(
                db = database,
                blockingDispatcher = dispatcher,
                readinessProbeTimeout = 2.seconds,
                jdbcQueryTimeout = 250.milliseconds,
                meterRegistry = null,
            )

            result shouldBeEqualTo HealthResponse.UP
            observedQueryTimeout.get() shouldBeEqualTo 1
        } finally {
            dispatcher.close()
            executor.awaitTermination(2, TimeUnit.SECONDS).shouldBeTrue()
            h2.connection.use { connection ->
                connection.createStatement().use { it.execute("SHUTDOWN") }
            }
        }

        Unit
    }

    @Test
    fun `r2dbc readiness keeps caller database default timeout without adapter override`() = runBlocking {
        val database = R2dbcDatabase.connect(
            databaseConfig = R2dbcDatabaseConfig {
                setUrl("r2dbc:h2:mem:///ktor-r2dbc-timeout-readiness;DB_CLOSE_DELAY=-1;")
                defaultQueryTimeout = 13
            },
        )

        database.config.defaultQueryTimeout shouldBeEqualTo 13
        probeR2dbcReadiness(
            db = database,
            readinessProbeTimeout = 2.seconds,
            meterRegistry = null,
        ) shouldBeEqualTo HealthResponse.UP

        val source = healthRoutesSource()
        val start = source.indexOf("internal suspend fun probeR2dbcReadiness")
        (start >= 0).shouldBeTrue()
        val body = source.substring(start)
        ("suspendTransaction(db = db)" in body).shouldBeTrue()
        ("queryTimeout =" in body).shouldBeFalse()

        Unit
    }

    private fun healthRoutesSource(): String {
        val relative = "ktor/exposed/src/main/kotlin/io/bluetape4k/exposed/ktor/ExposedKtorHealthRoutes.kt"
        val paths = listOf(
            Path.of(relative),
            Path.of("src/main/kotlin/io/bluetape4k/exposed/ktor/ExposedKtorHealthRoutes.kt"),
        )
        return Files.readString(paths.first(Files::exists))
    }

    private fun recordingDataSource(delegate: DataSource, observedQueryTimeout: AtomicInteger): DataSource =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(DataSource::class.java)) { _, method, args ->
            val result = invokeDelegate(delegate, method, args)
            if (result is Connection) result.recordQueryTimeout(observedQueryTimeout) else result
        } as DataSource

    private fun Connection.recordQueryTimeout(observedQueryTimeout: AtomicInteger): Connection {
        val delegate = this
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
            val result = invokeDelegate(delegate, method, args)
            when (result) {
                is PreparedStatement -> result.recordQueryTimeout(observedQueryTimeout, PreparedStatement::class.java)
                is Statement -> result.recordQueryTimeout(observedQueryTimeout, Statement::class.java)
                else -> result
            }
        } as Connection
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Statement> T.recordQueryTimeout(
        observedQueryTimeout: AtomicInteger,
        type: Class<T>,
    ): T {
        val delegate = this
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(type)) { _, method, args ->
            if (method.name == "setQueryTimeout") {
                observedQueryTimeout.set(args?.first() as Int)
            }
            invokeDelegate(delegate, method, args)
        } as T
    }

    private fun invokeDelegate(target: Any, method: java.lang.reflect.Method, args: Array<out Any?>?): Any? =
        try {
            method.invoke(target, *(args ?: emptyArray()))
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }

    private fun newJdbcDatabase(): Database =
        Database.connect(
            url = "jdbc:h2:mem:ktor-tx-jdbc-${Base58.randomString(8)};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
            user = "sa",
            password = "",
        )

    private object JdbcTransactionItems: Table("ktor_jdbc_transaction_items") {
        val name = varchar("name", 64)
    }
}
