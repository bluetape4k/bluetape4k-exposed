package io.bluetape4k.exposed.ktor.tenant.jdbc

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeout
import org.h2.jdbcx.JdbcDataSource
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class ExposedTenantJdbcTransactionTest {

    @Test
    fun `missing tenant context fails before resolver`() = testApplication {
        val dispatcher = newDispatcher()
        val resolverCalls = AtomicInteger()
        try {
            application {
                routing {
                    get("/missing") {
                        val failure = runCatching {
                            call.exposedTenantJdbcTransaction(
                                databaseResolver = {
                                    resolverCalls.incrementAndGet()
                                    error("resolver must not be called")
                                },
                                blockingDispatcher = dispatcher,
                            ) { "unreachable" }
                        }.exceptionOrNull()
                        call.respondText((failure is MissingTenantContextException).toString())
                    }
                }
            }

            client.get("/missing").bodyAsText() shouldBeEqualTo "true"
            resolverCalls.get() shouldBeEqualTo 0
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun `resolver exception is propagated before transaction`() = testApplication {
        val dispatcher = newDispatcher()
        val expected = IllegalStateException("tenant is unavailable")
        try {
            application {
                routing {
                    get("/resolver-error") {
                        KtorTenantContext.bindTenant(call, TenantId("unknown"))
                        val failure = runCatching {
                            call.exposedTenantJdbcTransaction(
                                databaseResolver = { throw expected },
                                blockingDispatcher = dispatcher,
                            ) { "unreachable" }
                        }.exceptionOrNull()
                        call.respondText((failure === expected).toString())
                    }
                }
            }

            client.get("/resolver-error").bodyAsText() shouldBeEqualTo "true"
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun `unknown tenant fails closed without default transaction`() = testApplication {
        val dispatcher = newDispatcher()
        val database = tenantDatabase("known")
        val databases = mapOf(TenantId("known") to database)
        val meterRegistry = SimpleMeterRegistry()
        try {
            application {
                routing {
                    get("/unknown") {
                        KtorTenantContext.bindTenant(call, TenantId("unknown"))
                        val failure = runCatching {
                            call.exposedTenantJdbcTransaction(
                                databaseResolver = databases::getValue,
                                blockingDispatcher = dispatcher,
                                meterRegistry = meterRegistry,
                            ) { "unreachable" }
                        }.exceptionOrNull()
                        val timerCount = meterRegistry.find("bluetape4k.exposed.ktor.core.transaction")
                            .tag("backend", "jdbc")
                            .tag("outcome", "success")
                            .timer()
                            ?.count() ?: 0L
                        call.respondText("${failure is NoSuchElementException}:$timerCount")
                    }
                }
            }

            client.get("/unknown").bodyAsText() shouldBeEqualTo "true:0"
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun `transaction exception keeps existing mapping and metric`() = testApplication {
        val dispatcher = newDispatcher()
        val database = tenantDatabase("error")
        val meterRegistry = SimpleMeterRegistry()
        val expected = IllegalStateException("transaction failed")
        try {
            application {
                routing {
                    get("/transaction-error") {
                        KtorTenantContext.bindTenant(call, TenantId("error"))
                        val failure = runCatching {
                            call.exposedTenantJdbcTransaction(
                                databaseResolver = { database },
                                blockingDispatcher = dispatcher,
                                meterRegistry = meterRegistry,
                            ) { throw expected }
                        }.exceptionOrNull()
                        val timerCount = meterRegistry.get("bluetape4k.exposed.ktor.core.transaction")
                            .tag("backend", "jdbc")
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
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun `tenant resolver routes each call to its database and preserves dispatcher boundary`() = testApplication {
        val tenantA = tenantDatabase("tenant-a")
        val tenantB = tenantDatabase("tenant-b")
        val databases = mapOf(TenantId("a") to tenantA, TenantId("b") to tenantB)
        val dispatcher = newDispatcher("tenant-jdbc-worker")
        val meterRegistry = SimpleMeterRegistry()
        try {
            application {
                routing {
                    get("/tenant/{id}") {
                        val tenantId = TenantId(requireNotNull(call.parameters["id"]))
                        KtorTenantContext.bindTenant(call, tenantId)
                        val result = call.exposedTenantJdbcTransaction(
                            databaseResolver = databases::getValue,
                            blockingDispatcher = dispatcher,
                            meterRegistry = meterRegistry,
                        ) {
                            Thread.sleep(20)
                            val marker = TenantMarkers.selectAll().single()[TenantMarkers.marker]
                            "$marker@${Thread.currentThread().name}"
                        }
                        call.respondText(result)
                    }
                }
            }

            client.get("/tenant/a").bodyAsText().also { response ->
                response.substringBefore('@') shouldBeEqualTo "tenant-a"
                response.substringAfter('@').contains("tenant-jdbc-worker").shouldBeTrue()
            }
            client.get("/tenant/b").bodyAsText().substringBefore('@') shouldBeEqualTo "tenant-b"

            val concurrent = coroutineScope {
                listOf("a", "b").map { id ->
                    async { client.get("/tenant/$id").bodyAsText().substringBefore('@') }
                }.awaitAll()
            }
            concurrent.toSet() shouldBeEqualTo setOf("tenant-a", "tenant-b")
            meterRegistry.get("bluetape4k.exposed.ktor.core.transaction")
                .tag("backend", "jdbc")
                .tag("outcome", "success")
                .timer()
                .count() shouldBeEqualTo 4L
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun `cancelled request does not contaminate later tenant routing`() = testApplication {
        val dispatcher = newDispatcher()
        val meterRegistry = SimpleMeterRegistry()
        val resolverInputs = CopyOnWriteArrayList<TenantId>()
        val databaseResolver = recordingDatabaseResolver(resolverInputs)
        val transactionStarted = CompletableDeferred<Unit>()
        val transactionCompleted = CompletableDeferred<Unit>()
        val requestJob = CompletableDeferred<Job>()
        try {
            application {
                routing {
                    get("/cancel/{id}") {
                        val tenantId = TenantId(requireNotNull(call.parameters["id"]))
                        KtorTenantContext.bindTenant(call, tenantId)
                        requestJob.complete(checkNotNull(currentCoroutineContext()[Job]))
                        try {
                            call.exposedTenantJdbcTransaction(
                                databaseResolver = databaseResolver,
                                blockingDispatcher = dispatcher,
                                meterRegistry = meterRegistry,
                            ) {
                                transactionStarted.complete(Unit)
                                CountDownLatch(1).await()
                            }
                        } finally {
                            transactionCompleted.complete(Unit)
                        }
                    }
                    get("/tenant/{id}") {
                        val tenantId = TenantId(requireNotNull(call.parameters["id"]))
                        KtorTenantContext.bindTenant(call, tenantId)
                        val marker = call.exposedTenantJdbcTransaction(
                            databaseResolver = databaseResolver,
                            blockingDispatcher = dispatcher,
                            meterRegistry = meterRegistry,
                        ) { TenantMarkers.selectAll().single()[TenantMarkers.marker] }
                        call.respondText(marker)
                    }
                }
            }

            coroutineScope {
                val request = async { client.get("/cancel/a") }
                withTimeout(5_000) { transactionStarted.await() }
                val serverRequest = withTimeout(5_000) { requestJob.await() }
                serverRequest.cancelAndJoin()
                serverRequest.isCancelled.shouldBeTrue()
                withTimeout(5_000) { transactionCompleted.await() }
                request.cancelAndJoin()
            }

            client.get("/tenant/b").bodyAsText() shouldBeEqualTo "tenant-b"
            client.get("/tenant/a").bodyAsText() shouldBeEqualTo "tenant-a"
            resolverInputs.toList() shouldBeEqualTo listOf(TenantId("a"), TenantId("b"), TenantId("a"))
            assertTransactionMetrics(meterRegistry)
        } finally {
            dispatcher.close()
        }
    }

    private fun assertTransactionMetrics(meterRegistry: SimpleMeterRegistry) {
        meterRegistry.get("bluetape4k.exposed.ktor.core.transaction")
            .tag("backend", "jdbc")
            .tag("outcome", "cancelled")
            .timer()
            .count() shouldBeEqualTo 1L
        meterRegistry.get("bluetape4k.exposed.ktor.core.transaction")
            .tag("backend", "jdbc")
            .tag("outcome", "success")
            .timer()
            .count() shouldBeEqualTo 2L
    }

    private fun recordingDatabaseResolver(resolverInputs: MutableList<TenantId>): (TenantId) -> Database {
        val databases = mapOf(
            TenantId("a") to tenantDatabase("tenant-a"),
            TenantId("b") to tenantDatabase("tenant-b"),
        )
        return { tenantId ->
            resolverInputs += tenantId
            databases.getValue(tenantId)
        }
    }

    private fun tenantDatabase(marker: String): Database {
        val id = DATABASE_ID.incrementAndGet()
        val dataSource = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:tenant-bridge-$id;DB_CLOSE_DELAY=-1")
            user = "sa"
            password = ""
        }
        val database = Database.connect(dataSource)
        transaction(database) {
            SchemaUtils.create(TenantMarkers)
            TenantMarkers.insert { it[TenantMarkers.marker] = marker }
        }
        return database
    }

    private fun newDispatcher(prefix: String = "tenant-jdbc"): kotlinx.coroutines.ExecutorCoroutineDispatcher =
        Executors.newFixedThreadPool(2) { runnable -> Thread(runnable, "$prefix-${THREAD_ID.incrementAndGet()}") }
            .asCoroutineDispatcher()

    private object TenantMarkers : Table("tenant_markers") {
        val marker = varchar("marker", 64)
    }

    private companion object {
        val DATABASE_ID = AtomicLong()
        val THREAD_ID = AtomicLong()
    }
}
