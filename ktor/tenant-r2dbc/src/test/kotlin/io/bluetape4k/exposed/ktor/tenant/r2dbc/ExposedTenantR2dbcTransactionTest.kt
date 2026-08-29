package io.bluetape4k.exposed.ktor.tenant.r2dbc

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.exposed.ktor.core.ExposedKtorTransactionException
import io.bluetape4k.ktor.tenant.KtorTenantContext
import io.bluetape4k.tenant.MissingTenantContextException
import io.bluetape4k.tenant.TenantId
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.single
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabaseConfig
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class ExposedTenantR2dbcTransactionTest {

    @Test
    fun `missing tenant context fails before resolver`() = testApplication {
        val resolverCalls = AtomicInteger()
        application {
            routing {
                get("/missing") {
                    val failure = runCatching {
                        call.exposedTenantR2dbcTransaction(
                            databaseResolver = {
                                resolverCalls.incrementAndGet()
                                error("resolver must not be called")
                            },
                        ) { "unreachable" }
                    }.exceptionOrNull()
                    call.respondText((failure is MissingTenantContextException).toString())
                }
            }
        }

        client.get("/missing").bodyAsText() shouldBeEqualTo "true"
        resolverCalls.get() shouldBeEqualTo 0
    }

    @Test
    fun `resolver exception is propagated before transaction`() = testApplication {
        val expected = IllegalStateException("tenant is unavailable")
        application {
            routing {
                get("/resolver-error") {
                    KtorTenantContext.bindTenant(call, TenantId("unknown"))
                    val failure = runCatching {
                        call.exposedTenantR2dbcTransaction(
                            databaseResolver = { throw expected },
                        ) { "unreachable" }
                    }.exceptionOrNull()
                    call.respondText((failure === expected).toString())
                }
            }
        }

        client.get("/resolver-error").bodyAsText() shouldBeEqualTo "true"
    }

    @Test
    fun `unknown tenant fails closed without default transaction`() = testApplication {
        val database = tenantDatabase("known")
        val databases = mapOf(TenantId("known") to database)
        val meterRegistry = SimpleMeterRegistry()
        application {
            routing {
                get("/unknown") {
                    KtorTenantContext.bindTenant(call, TenantId("unknown"))
                    val failure = runCatching {
                        call.exposedTenantR2dbcTransaction(
                            databaseResolver = databases::getValue,
                            meterRegistry = meterRegistry,
                        ) { "unreachable" }
                    }.exceptionOrNull()
                    val timerCount = meterRegistry.find("bluetape4k.exposed.ktor.core.transaction")
                        .tag("backend", "r2dbc")
                        .tag("outcome", "success")
                        .timer()
                        ?.count() ?: 0L
                    call.respondText("${failure is NoSuchElementException}:$timerCount")
                }
            }
        }

        client.get("/unknown").bodyAsText() shouldBeEqualTo "true:0"
    }

    @Test
    fun `transaction exception keeps existing mapping and metric`() = testApplication {
        val database = tenantDatabase("error")
        val meterRegistry = SimpleMeterRegistry()
        val expected = IllegalStateException("transaction failed")
        application {
            routing {
                get("/transaction-error") {
                    KtorTenantContext.bindTenant(call, TenantId("error"))
                    val failure = runCatching {
                        call.exposedTenantR2dbcTransaction(
                            databaseResolver = { database },
                            meterRegistry = meterRegistry,
                        ) { throw expected }
                    }.exceptionOrNull()
                    val timerCount = meterRegistry.get("bluetape4k.exposed.ktor.core.transaction")
                        .tag("backend", "r2dbc")
                        .tag("outcome", "error")
                        .timer()
                        .count()
                    call.respondText(
                        "${failure is ExposedKtorTransactionException}:$timerCount",
                    )
                }
            }
        }

        client.get("/transaction-error").bodyAsText() shouldBeEqualTo "true:1"
    }

    @Test
    fun `tenant resolver routes each call to its database and preserves call isolation`() = testApplication {
        val databaseA = tenantDatabase("tenant-a")
        val databaseB = tenantDatabase("tenant-b")
        val databases = mapOf(TenantId("a") to databaseA, TenantId("b") to databaseB)
        val meterRegistry = SimpleMeterRegistry()
        application {
            routing {
                get("/tenant/{id}") {
                    val tenantId = TenantId(requireNotNull(call.parameters["id"]))
                    KtorTenantContext.bindTenant(call, tenantId)
                    val result = call.exposedTenantR2dbcTransaction(
                        databaseResolver = databases::getValue,
                        meterRegistry = meterRegistry,
                    ) {
                        delay(20)
                        checkNotNull(exec("SELECT marker FROM tenant_marker") { row ->
                            row.get(0, String::class.java)
                        }!!.single())
                    }
                    call.respondText(result)
                }
            }
        }

        client.get("/tenant/a").bodyAsText() shouldBeEqualTo "tenant-a"
        client.get("/tenant/b").bodyAsText() shouldBeEqualTo "tenant-b"

        val concurrent = coroutineScope {
            listOf("a", "b").map { id ->
                async { client.get("/tenant/$id").bodyAsText() }
            }.awaitAll()
        }
        concurrent.toSet() shouldBeEqualTo setOf("tenant-a", "tenant-b")
        meterRegistry.get("bluetape4k.exposed.ktor.core.transaction")
            .tag("backend", "r2dbc")
            .tag("outcome", "success")
            .timer()
            .count() shouldBeEqualTo 4L
    }

    @Test
    fun `cancellation is rethrown and recorded by existing helper`() = testApplication {
        val database = tenantDatabase("cancel")
        val meterRegistry = SimpleMeterRegistry()
        val cancellation = CancellationException("request cancelled")
        application {
            routing {
                get("/cancel") {
                    KtorTenantContext.bindTenant(call, TenantId("a"))
                    val failure = runCatching {
                        call.exposedTenantR2dbcTransaction(
                            databaseResolver = { database },
                            meterRegistry = meterRegistry,
                        ) { throw cancellation }
                    }.exceptionOrNull()
                    val timerCount = meterRegistry.get("bluetape4k.exposed.ktor.core.transaction")
                        .tag("backend", "r2dbc")
                        .tag("outcome", "cancelled")
                        .timer()
                        .count()
                    call.respondText("${failure is CancellationException}:$timerCount")
                }
            }
        }

        client.get("/cancel").bodyAsText() shouldBeEqualTo "true:1"
    }

    private suspend fun tenantDatabase(marker: String): R2dbcDatabase {
        val id = DATABASE_ID.incrementAndGet()
        val database = R2dbcDatabase.connect(
            databaseConfig = R2dbcDatabaseConfig {
                setUrl("r2dbc:h2:mem:///tenant-bridge-$id-$marker;DB_CLOSE_DELAY=-1;")
            },
        )
        suspendTransaction(database) {
            exec("CREATE TABLE tenant_marker (marker VARCHAR(64))")
            exec("INSERT INTO tenant_marker (marker) VALUES ('$marker')")
        }
        return database
    }

    private companion object {
        val DATABASE_ID = AtomicLong()
    }
}
