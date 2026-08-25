package io.bluetape4k.exposed.ktor

import io.bluetape4k.assertions.should
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue

import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheFailure
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheFailureBuffer
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheOperation
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheOutcome
import io.bluetape4k.exposed.cache.snapshot.SnapshotStoreId
import io.bluetape4k.exposed.cache.snapshot.snapshotCacheFailureBuffer
import io.bluetape4k.ktor.core.Bluetape4kKtorCoreConfig
import io.bluetape4k.ktor.core.HealthResponse
import io.bluetape4k.ktor.core.installBluetape4kKtorCore
import io.bluetape4k.ktor.testing.bluetape4kJsonClient
import io.bluetape4k.ktor.testing.decodeJsonBody
import io.bluetape4k.ktor.testing.shouldHaveStatus
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.h2.jdbcx.JdbcDataSource
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabaseConfig
import org.junit.jupiter.api.Test
import java.io.PrintWriter
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Logger
import javax.sql.DataSource
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class ExposedKtorReadinessBudgetTest {

    @Test
    fun `database probes precede sequential cache contributors in deterministic order`() = runTest {
        val order = mutableListOf<String>()
        val bindings = bindings(
            contributor("first") {
                order += "cache-first"
                ExposedKtorCacheStatus.DOWN
            },
            contributor("second") {
                order += "cache-second"
                ExposedKtorCacheStatus.UP
            },
        )

        val details = aggregateExposedKtorReadiness(
            jdbcProbe = {
                order += "jdbc"
                HealthResponse.UP
            },
            r2dbcProbe = {
                order += "r2dbc"
                HealthResponse.UP
            },
            cacheBindings = bindings,
            cachePhaseTimeout = 1.seconds,
            timeSource = testScheduler.timeSource,
        )

        order shouldBeEqualTo listOf("jdbc", "r2dbc", "cache-first", "cache-second")
        details shouldBeEqualTo linkedMapOf(
                "jdbc" to HealthResponse.UP,
                "r2dbc" to HealthResponse.UP,
                "cache.first" to HealthResponse.DOWN,
                "cache.second" to HealthResponse.UP,
            )
    }

    @Test
    fun `one shared cache deadline times out active probe and skips remaining probes without timers`() = runTest {
        val registry = SimpleMeterRegistry()
        val invocations = List(3) { AtomicInteger() }
        val bindings = bindings(
            registry,
            contributor("first") {
                invocations[0].incrementAndGet()
                delay(60.milliseconds)
                ExposedKtorCacheStatus.UP
            },
            contributor("active") {
                invocations[1].incrementAndGet()
                delay(60.milliseconds)
                ExposedKtorCacheStatus.UP
            },
            contributor("skipped") {
                invocations[2].incrementAndGet()
                ExposedKtorCacheStatus.UP
            },
        )

        val details = aggregateExposedKtorReadiness(
            null,
            null,
            bindings,
            100.milliseconds,
            testScheduler.timeSource,
        )

        testScheduler.currentTime shouldBeEqualTo 100L
        details shouldBeEqualTo linkedMapOf(
                "cache.first" to HealthResponse.UP,
                "cache.active" to TIMEOUT_OUTCOME,
                "cache.skipped" to TIMEOUT_OUTCOME,
            )
        (invocations.map(AtomicInteger::get)) shouldBeEqualTo listOf(1, 1, 0)
        (timerCount(registry, "first", SUCCESS_OUTCOME)) shouldBeEqualTo 1L
        (timerCount(registry, "active", TIMEOUT_OUTCOME)) shouldBeEqualTo 1L
        (CACHE_OUTCOMES.sumOf { timerCount(registry, "skipped", it) }) shouldBeEqualTo 0L
        (bindings[1].currentSample().queueDepth.isNaN()).shouldBeTrue()
        (bindings[2].currentSample().queueDepth.isNaN()).shouldBeTrue()
    }

    @Test
    fun `all-backend virtual budget follows jdbc R plus J r2dbc R and cache R`() = runTest {
        val r = 2.seconds
        val jEffective = 1.seconds
        val registry = SimpleMeterRegistry()
        val binding = bindings(
            registry,
            contributor("cache") {
                delay(10.seconds)
                ExposedKtorCacheStatus.UP
            }
        )

        val details = aggregateExposedKtorReadiness(
            jdbcProbe = {
                delay(r + jEffective)
                HealthResponse.UP
            },
            r2dbcProbe = {
                delay(r)
                HealthResponse.UP
            },
            cacheBindings = binding,
            cachePhaseTimeout = r,
            timeSource = testScheduler.timeSource,
        )

        testScheduler.currentTime shouldBeEqualTo (r + jEffective + r + r).inWholeMilliseconds
        details shouldBeEqualTo linkedMapOf(
                "jdbc" to HealthResponse.UP,
                "r2dbc" to HealthResponse.UP,
                "cache.cache" to TIMEOUT_OUTCOME,
            )
        (timerCount(registry, "cache", TIMEOUT_OUTCOME)) shouldBeEqualTo 1L
    }

    @Test
    fun `newer request cancelled before contributor B does not suppress older B publication`() = runTest {
        repeat(5) {
            val registry = SimpleMeterRegistry()
            val aInvocations = AtomicInteger()
            val newerAtA = CompletableDeferred<Unit>()
            val olderAtB = CompletableDeferred<Unit>()
            val releaseOlderB = CompletableDeferred<Unit>()
            val a = contributor("a") {
                if (aInvocations.incrementAndGet() == 1) {
                    ExposedKtorCacheStatus.UP
                } else {
                    newerAtA.complete(Unit)
                    awaitCancellation()
                }
            }
            val b = contributor("b") {
                olderAtB.complete(Unit)
                releaseOlderB.await()
                ExposedKtorCacheStatus.UP
            }
            val bindings = bindings(registry, a, b)

            val older = launch {
                aggregateExposedKtorReadiness(null, null, bindings, 10.seconds, testScheduler.timeSource)
            }
            olderAtB.await()
            val newer = launch {
                aggregateExposedKtorReadiness(null, null, bindings, 10.seconds, testScheduler.timeSource)
            }
            newerAtA.await()
            newer.cancelAndJoin()
            releaseOlderB.complete(Unit)
            older.join()

            (bindings[1].currentSample().status) shouldBeEqualTo ExposedKtorCacheStatus.UP
            (timerCount(registry, "b", SUCCESS_OUTCOME)) shouldBeEqualTo 1L
            (timerCount(registry, "b", CANCELLED_OUTCOME)) shouldBeEqualTo 0L
        }
    }

    @Test
    fun `blocking cancellation-insensitive probe may outlive deadline and library adds no compensating worker`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val deadlinePassed = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val timer = Executors.newSingleThreadScheduledExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val binding = bindings(
                contributor("blocking") {
                    entered.countDown()
                    release.await()
                    ExposedKtorCacheStatus.UP
                }
            )
            val attempt = async(dispatcher) {
                aggregateExposedKtorReadiness(null, null, binding, 20.milliseconds)
            }
            (entered.await(2, TimeUnit.SECONDS)).shouldBeTrue()
            timer.schedule({ deadlinePassed.countDown() }, 80, TimeUnit.MILLISECONDS)
            (deadlinePassed.await(2, TimeUnit.SECONDS)).shouldBeTrue()
            attempt.isCompleted.shouldBeFalse()
            release.countDown()
            withTimeout(2.seconds) { attempt.await() }
        } finally {
            release.countDown()
            dispatcher.close()
            timer.shutdownNow()
            (executor.awaitTermination(2, TimeUnit.SECONDS)).shouldBeTrue()
            (timer.awaitTermination(2, TimeUnit.SECONDS)).shouldBeTrue()
        }
    }

    @Test
    fun `jdbc parent cancellation records exactly one cancelled outcome and no timeout`() = runBlocking {
        val connectionRequested = CountDownLatch(1)
        val connectionGate = CountDownLatch(1)
        val statementStarted = CountDownLatch(1)
        val statementGate = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val registry = SimpleMeterRegistry()
        val h2 = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:ktor-parent-cancel;DB_CLOSE_DELAY=-1")
            user = "sa"
            password = ""
        }
        val database = Database.connect(
            GatedDataSource(h2, connectionRequested, connectionGate, statementStarted, statementGate)
        )
        try {
            val attempt = async(Dispatchers.Default) {
                probeJdbcReadiness(
                    db = database,
                    blockingDispatcher = dispatcher,
                    readinessProbeTimeout = 10.seconds,
                    jdbcQueryTimeout = 1.seconds,
                    meterRegistry = registry,
                )
            }
            (connectionRequested.await(2, TimeUnit.SECONDS)).shouldBeTrue()
            attempt.cancel()
            connectionGate.countDown()
            statementGate.countDown()
            attempt.join()

            attempt.isCancelled.shouldBeTrue()
            (databaseTimerCount(registry, JDBC_BACKEND, CANCELLED_OUTCOME)) shouldBeEqualTo 1L
            (databaseTimerCount(registry, JDBC_BACKEND, TIMEOUT_OUTCOME)) shouldBeEqualTo 0L
            (databaseTimerCount(registry, JDBC_BACKEND, SUCCESS_OUTCOME)) shouldBeEqualTo 0L
            (databaseTimerCount(registry, JDBC_BACKEND, ERROR_OUTCOME)) shouldBeEqualTo 0L
        } finally {
            connectionGate.countDown()
            statementGate.countDown()
            dispatcher.close()
            (executor.awaitTermination(2, TimeUnit.SECONDS)).shouldBeTrue()
            h2.connection.use { connection ->
                connection.createStatement().use { it.execute("SHUTDOWN") }
            }
        }
    }

    @Test
    fun `snapshot producer drainer and sampler remain bounded read-only and meter-stable`() = runBlocking {
        val rounds = 100
        val buffer = snapshotCacheFailureBuffer(rounds + 1)
        val registry = SimpleMeterRegistry()
        val binding = registerExposedKtorCacheMetrics(
            registry,
            ExposedKtorCacheReadinessConfig(
                listOf(ExposedKtorCacheContributor.snapshot("snapshot", buffer))
            ),
        ).single()
        val roundBoundary = CyclicBarrier(3)
        val produced = Semaphore(0)
        val executor = Executors.newFixedThreadPool(3)
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            coroutineScope {
                val producer = async(dispatcher) {
                    repeat(rounds) {
                        roundBoundary.await(5, TimeUnit.SECONDS)
                        buffer.recordForTest()
                        buffer.recordForTest()
                        produced.release()
                        roundBoundary.await(5, TimeUnit.SECONDS)
                    }
                }
                val drainer = async(dispatcher) {
                    repeat(rounds) {
                        roundBoundary.await(5, TimeUnit.SECONDS)
                        (produced.tryAcquire(5, TimeUnit.SECONDS)).shouldBeTrue()
                        (buffer.poll()?.affectedCount) shouldBeEqualTo 1
                        roundBoundary.await(5, TimeUnit.SECONDS)
                    }
                }
                val sampler = async(dispatcher) {
                    repeat(rounds) {
                        roundBoundary.await(5, TimeUnit.SECONDS)
                        val details = aggregateExposedKtorReadiness(null, null, listOf(binding), 1.seconds)
                        (details["cache.snapshot"]) shouldBeEqualTo HealthResponse.UP
                        roundBoundary.await(5, TimeUnit.SECONDS)
                    }
                }
                producer.await()
                drainer.await()
                sampler.await()
            }

            buffer.size shouldBeEqualTo rounds
            buffer.recordForTest(affectedCount = 777)
            val retainedAffectedCounts = mutableListOf<Int>()
            val drain = buffer.drainTo(
                observer = { retainedAffectedCounts += it.affectedCount },
                maxElements = Int.MAX_VALUE,
            )

            drain.deliveredCount shouldBeEqualTo rounds + 1
            drain.observerFailedCount shouldBeEqualTo 0
            drain.remainingCount shouldBeEqualTo 0
            (retainedAffectedCounts.count { it == 1 }) shouldBeEqualTo rounds
            (retainedAffectedCounts.count { it == 777 }) shouldBeEqualTo 1
            buffer.size shouldBeEqualTo 0
            buffer.droppedCount shouldBeEqualTo 0L
            buffer.observerFailureCount shouldBeEqualTo 0L
            (timerCount(registry, "snapshot", SUCCESS_OUTCOME, kind = "snapshot")) shouldBeEqualTo rounds.toLong()
            registry.meters.size shouldBeEqualTo 8
            registry.meters.filter { it.id.type.name == "GAUGE" }.forEach { meter ->
                meter.measure().forEach { value -> (value.value.isNaN() || value.value >= 0.0).shouldBeTrue() }
            }
        } finally {
            roundBoundary.reset()
            produced.release(rounds)
            dispatcher.close()
            (executor.awaitTermination(2, TimeUnit.SECONDS)).shouldBeTrue()
        }
    }

    @Test
    fun `all-backend real smoke stays inside conservative formula and cleans controlled resources`() = testApplication {
        val readinessTimeout = 750.milliseconds
        val jdbcQueryTimeout = 1.seconds
        val connectionDelay = 550.milliseconds
        val connectionRequested = CountDownLatch(1)
        val connectionGate = CountDownLatch(1)
        val statementGate = CountDownLatch(1)
        val statementStarted = CountDownLatch(1)
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val jdbcExecutor = Executors.newSingleThreadExecutor()
        val jdbcDispatcher = jdbcExecutor.asCoroutineDispatcher()
        val meterRegistry = SimpleMeterRegistry()
        val h2 = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:ktor-budget;DB_CLOSE_DELAY=-1")
            user = "sa"
            password = ""
        }
        val database = Database.connect(
            GatedDataSource(h2, connectionRequested, connectionGate, statementStarted, statementGate)
        )
        val r2dbc = R2dbcDatabase.connect(
            databaseConfig = R2dbcDatabaseConfig {
                setUrl("r2dbc:h2:mem:///ktor-budget-r2dbc;DB_CLOSE_DELAY=-1;")
            }
        )
        try {
            application {
                installBluetape4kKtorCore(
                    Bluetape4kKtorCoreConfig(installStatusPages = false, installHealthRoutes = false)
                )
                routing {
                    bluetape4kExposedHealthRoutes(
                        jdbcDatabase = database,
                        jdbcBlockingDispatcher = jdbcDispatcher,
                        r2dbcDatabase = r2dbc,
                        readinessProbeTimeout = readinessTimeout,
                        jdbcQueryTimeout = jdbcQueryTimeout,
                        meterRegistry = meterRegistry,
                        cacheReadiness = ExposedKtorCacheReadinessConfig(
                            listOf(contributor("cache") { ExposedKtorCacheStatus.UP })
                        ),
                    )
                }
            }

            val started = TimeSource.Monotonic.markNow()
            val (response, statementHeld) = coroutineScope {
                val responseAttempt = async(Dispatchers.Default) {
                    bluetape4kJsonClient().get("/readyz/exposed")
                }
                (connectionRequested.await(2, TimeUnit.SECONDS)).shouldBeTrue()
                scheduler.schedule(
                    { connectionGate.countDown() },
                    connectionDelay.inWholeMilliseconds,
                    TimeUnit.MILLISECONDS,
                )
                (statementStarted.await(2, TimeUnit.SECONDS)).shouldBeTrue()
                val statementHoldStarted = TimeSource.Monotonic.markNow()
                scheduler.schedule(
                    { statementGate.countDown() },
                    jdbcQueryTimeout.inWholeMilliseconds,
                    TimeUnit.MILLISECONDS,
                )

                val response = responseAttempt.await().shouldHaveStatus(HttpStatusCode.ServiceUnavailable)
                response to statementHoldStarted.elapsedNow()
            }
            val elapsed = started.elapsedNow()

            (response.decodeJsonBody<HealthResponse>()) shouldBeEqualTo HealthResponse.down(
                    linkedMapOf(
                        "jdbc" to TIMEOUT_OUTCOME,
                        "r2dbc" to HealthResponse.UP,
                        "cache.cache" to HealthResponse.UP,
                    )
                )
            (statementHeld >= jdbcQueryTimeout - 150.milliseconds).should("statementHeld=$statementHeld") { it }
            (elapsed >= connectionDelay + jdbcQueryTimeout - 200.milliseconds)
                .should("elapsed=$elapsed connectionDelay=$connectionDelay jdbcQueryTimeout=$jdbcQueryTimeout") { it }
            val formula = readinessTimeout + jdbcQueryTimeout + readinessTimeout + readinessTimeout
            (elapsed < formula + 2.seconds).should("elapsed=$elapsed formula=$formula") { it }
            (databaseTimerCount(meterRegistry, JDBC_BACKEND, TIMEOUT_OUTCOME)) shouldBeEqualTo 1L
            (databaseTimerCount(meterRegistry, JDBC_BACKEND, CANCELLED_OUTCOME)) shouldBeEqualTo 0L
            (databaseTimerCount(meterRegistry, JDBC_BACKEND, SUCCESS_OUTCOME)) shouldBeEqualTo 0L
            (databaseTimerCount(meterRegistry, JDBC_BACKEND, ERROR_OUTCOME)) shouldBeEqualTo 0L
            (databaseTimerCount(meterRegistry, R2DBC_BACKEND, SUCCESS_OUTCOME)) shouldBeEqualTo 1L
            (timerCount(meterRegistry, "cache", SUCCESS_OUTCOME)) shouldBeEqualTo 1L
        } finally {
            connectionGate.countDown()
            statementGate.countDown()
            jdbcDispatcher.close()
            scheduler.shutdownNow()
            (jdbcExecutor.awaitTermination(2, TimeUnit.SECONDS)).shouldBeTrue()
            (scheduler.awaitTermination(2, TimeUnit.SECONDS)).shouldBeTrue()
            h2.connection.use { connection ->
                connection.createStatement().use { it.execute("SHUTDOWN") }
            }
        }
    }

    @Test
    fun `new public overload KDoc pins deadline security unsupported probes ownership and no resources`() {
        val source = healthRoutesSource()
        val declaration = "fun Route.bluetape4kExposedHealthRoutes("
        val second = source.indexOf(declaration, source.indexOf(declaration) + declaration.length)
        val prefix = source.substring(0, second)
        val end = prefix.lastIndexOf("*/")
        val start = prefix.lastIndexOf("/**", end)
        val kdoc = prefix.substring(start, end + 2)
            .lineSequence()
            .map { it.trim().removePrefix("/**").removePrefix("*").removeSuffix("*/").trim() }
            .joinToString(" ")

        listOf(
            "cache-only",
            "shared monotonic",
            "caller-owned",
            "authentication",
            "request concurrency",
            "blocking",
            "cancellation-insensitive",
            "backend-I/O",
            "creates or closes no",
            "cache keys",
            "SQL",
            "URLs",
            "credentials",
            "Timeout contract",
            "readinessProbeTimeout",
            "jdbcQueryTimeout",
            "defaultQueryTimeout",
            "no separate Ktor query timeout",
        ).forEach { (kdoc.contains(it)).should(it) { it } }
    }

    private fun bindings(
        vararg contributors: ExposedKtorCacheContributor,
    ): List<ExposedKtorCacheMetricBinding> = bindings(SimpleMeterRegistry(), *contributors)

    private fun bindings(
        registry: SimpleMeterRegistry,
        vararg contributors: ExposedKtorCacheContributor,
    ): List<ExposedKtorCacheMetricBinding> = registerExposedKtorCacheMetrics(
        registry,
        ExposedKtorCacheReadinessConfig(contributors.toList()),
    )

    private fun contributor(
        component: String,
        probe: suspend () -> ExposedKtorCacheStatus,
    ) = ExposedKtorCacheContributor.custom(component, probe)

    private fun timerCount(
        registry: SimpleMeterRegistry,
        component: String,
        outcome: String,
        kind: String = "custom",
    ): Long =
        registry.find(CACHE_READINESS_METER_NAME)
            .tags("component", component, "kind", kind, "operation", READINESS_OPERATION, "outcome", outcome)
            .timer()
            ?.count()
            ?: 0L

    private fun databaseTimerCount(registry: SimpleMeterRegistry, backend: String, outcome: String): Long =
        registry.find("bluetape4k.exposed.ktor.readiness")
            .tags("backend", backend, "operation", READINESS_OPERATION, "outcome", outcome)
            .timer()
            ?.count()
            ?: 0L

    private fun SnapshotCacheFailureBuffer.recordForTest(affectedCount: Int = 1) {
        val method = Class.forName("io.bluetape4k.exposed.cache.snapshot.SnapshotCacheFailureKt")
            .getMethod("recordFailure", SnapshotCacheFailureBuffer::class.java, SnapshotCacheFailure::class.java)
        method.invoke(
            null,
            this,
            SnapshotCacheFailure(
                SnapshotStoreId("test", "bounded"),
                SnapshotCacheOperation.PUT,
                SnapshotCacheOutcome.FAILED,
                affectedCount,
            ),
        )
    }

    private fun healthRoutesSource(): String {
        val relative = "ktor/exposed/src/main/kotlin/io/bluetape4k/exposed/ktor/ExposedKtorHealthRoutes.kt"
        val paths = listOf(Path.of(relative), Path.of("src/main/kotlin/io/bluetape4k/exposed/ktor/ExposedKtorHealthRoutes.kt"))
        return Files.readString(paths.first(Files::exists))
    }

    private inner class GatedDataSource(
        private val delegate: DataSource,
        private val connectionRequested: CountDownLatch,
        private val connectionGate: CountDownLatch,
        private val statementStarted: CountDownLatch,
        private val statementGate: CountDownLatch,
    ) : DataSource {
        override fun getConnection(): Connection {
            connectionRequested.countDown()
            connectionGate.await()
            return delegate.connection.gated(statementStarted, statementGate)
        }

        override fun getConnection(username: String?, password: String?): Connection {
            connectionRequested.countDown()
            connectionGate.await()
            return delegate.getConnection(username, password).gated(statementStarted, statementGate)
        }

        override fun getLogWriter(): PrintWriter? = delegate.logWriter
        override fun setLogWriter(out: PrintWriter?) { delegate.logWriter = out }
        override fun setLoginTimeout(seconds: Int) { delegate.loginTimeout = seconds }
        override fun getLoginTimeout(): Int = delegate.loginTimeout
        override fun getParentLogger(): Logger = delegate.parentLogger
        override fun <T : Any?> unwrap(iface: Class<T>?): T = delegate.unwrap(iface)
        override fun isWrapperFor(iface: Class<*>?): Boolean = delegate.isWrapperFor(iface)
    }

    private fun Connection.gated(started: CountDownLatch, release: CountDownLatch): Connection =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
            val result = invokeDelegate(this, method, args)
            when (result) {
                is PreparedStatement -> result.gated(started, release, PreparedStatement::class.java)
                is Statement -> result.gated(started, release, Statement::class.java)
                else -> result
            }
        } as Connection

    @Suppress("UNCHECKED_CAST")
    private fun <T : Statement> T.gated(
        started: CountDownLatch,
        release: CountDownLatch,
        type: Class<T>,
    ): T = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(type)) { _, method, args ->
        if (method.name.startsWith("execute")) {
            started.countDown()
            release.await()
        }
        invokeDelegate(this, method, args)
    } as T

    private fun invokeDelegate(target: Any, method: java.lang.reflect.Method, args: Array<out Any?>?): Any? =
        try {
            method.invoke(target, *(args ?: emptyArray()))
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
}
