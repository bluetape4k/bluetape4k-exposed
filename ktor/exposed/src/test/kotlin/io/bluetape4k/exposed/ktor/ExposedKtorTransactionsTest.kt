package io.bluetape4k.exposed.ktor

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.ktor.core.Bluetape4kKtorCoreConfig
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
import java.util.UUID
import java.util.concurrent.Executors

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExposedKtorTransactionsTest {

    @Test
    fun `jdbc transaction helper uses caller supplied dispatcher and commits`() = testApplication {
        val database = newJdbcDatabase()
        val dispatcher = Executors.newSingleThreadExecutor { command ->
            Thread(command, "ktor-jdbc-worker-${UUID.randomUUID()}")
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
    fun `r2dbc transaction helper runs suspend transaction`() = testApplication {
        val database = R2dbcDatabase.connect(
            databaseConfig = R2dbcDatabaseConfig {
                setUrl("r2dbc:h2:mem:///ktor-tx-r2dbc-${UUID.randomUUID()};DB_CLOSE_DELAY=-1;")
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

    private fun newJdbcDatabase(): Database =
        Database.connect(
            url = "jdbc:h2:mem:ktor-tx-jdbc-${UUID.randomUUID()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
            user = "sa",
            password = "",
        )

    private object JdbcTransactionItems: Table("ktor_jdbc_transaction_items") {
        val name = varchar("name", 64)
    }
}
