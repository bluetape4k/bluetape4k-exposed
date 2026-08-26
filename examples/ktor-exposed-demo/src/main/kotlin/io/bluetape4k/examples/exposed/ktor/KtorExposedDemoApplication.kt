package io.bluetape4k.examples.exposed.ktor

import io.bluetape4k.examples.exposed.ktor.order.DemoDiagnostic
import io.bluetape4k.examples.exposed.ktor.order.DemoDiagnosticSink
import io.bluetape4k.examples.exposed.ktor.order.orderRoutes
import io.bluetape4k.exposed.ktor.Bluetape4kExposedKtorConfig
import io.bluetape4k.exposed.ktor.ExposedKtorCacheContributor
import io.bluetape4k.exposed.ktor.ExposedKtorCacheReadinessConfig
import io.bluetape4k.exposed.ktor.bluetape4kExposedErrors
import io.bluetape4k.exposed.ktor.exposedJdbcTransaction
import io.bluetape4k.exposed.ktor.exposedR2dbcTransaction
import io.bluetape4k.exposed.ktor.installBluetape4kExposedKtor
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.ktor.core.Bluetape4kKtorCoreConfig
import io.bluetape4k.ktor.core.bluetape4kErrorResponses
import io.bluetape4k.ktor.core.installBluetape4kKtorCore
import io.bluetape4k.logging.KotlinLogging
import io.bluetape4k.logging.error
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.selectAll as r2dbcSelectAll
import java.io.Serializable
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

fun Application.installKtorExposedDemo(
    resources: KtorExposedDemoResources,
    diagnostics: DemoDiagnosticSink,
) {
    monitor.subscribe(ApplicationStopped) {
        resources.closeReport()
    }

    installBluetape4kKtorCore(
        Bluetape4kKtorCoreConfig(
            installStatusPages = false,
            installHealthRoutes = false,
        ),
    )
    install(StatusPages) {
        bluetape4kErrorResponses()
        bluetape4kExposedErrors()
    }
    installBluetape4kExposedKtor(
        Bluetape4kExposedKtorConfig(
            jdbcDatabase = resources.jdbcDatabase,
            jdbcBlockingDispatcher = resources.jdbcDispatcher,
            r2dbcDatabase = resources.r2dbcDatabase,
            installHealthRoutes = true,
            readinessProbeTimeout = 2.seconds,
        ),
        ExposedKtorCacheReadinessConfig(
            listOf(
                ExposedKtorCacheContributor.r2dbcRepository("orders") {
                    resources.orderRepository.validateConsistency()
                },
            ),
        ),
    )
    routing {
        get("/transactions/jdbc-count") {
            val count = call.exposedJdbcTransaction<Long>(
                db = resources.jdbcDatabase,
                blockingDispatcher = resources.jdbcDispatcher,
            ) {
                DemoItems.selectAll().count()
            }
            call.respondText(count.toString())
        }
        get("/transactions/r2dbc-count") {
            val count = call.exposedR2dbcTransaction(resources.r2dbcDatabase) {
                io.bluetape4k.examples.exposed.ktor.order.DemoOrders.r2dbcSelectAll().count()
            }
            call.respondText(count.toString())
        }
        orderRoutes(resources.orderService, resources.orderRepository, diagnostics)
    }
}

internal interface DemoServer {
    fun start(wait: Boolean)
    fun stop(gracePeriodMillis: Long, timeoutMillis: Long)
}

internal fun interface DemoResourcesFactory {
    fun create(): KtorExposedDemoResources
}

internal data class DemoRunResult(
    val status: Int,
    val primaryFailure: Throwable? = null,
    val cleanupReport: DemoCleanupReport,
) : Serializable {

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

@Suppress("TooGenericExceptionCaught") // startup cleanup에서 primary와 suppressed failure를 모두 보존한다.
internal fun runKtorExposedDemo(
    resourcesFactory: DemoResourcesFactory,
    serverFactory: (KtorExposedDemoResources) -> DemoServer,
    diagnosticSink: DemoDiagnosticSink,
): DemoRunResult {
    var resources: KtorExposedDemoResources? = null
    var server: DemoServer? = null
    return try {
        resources = resourcesFactory.create()
        server = serverFactory(resources)
        server.start(wait = true)

        val cleanupReport = resources.closeReport()
        if (cleanupReport.isClean) {
            DemoRunResult(status = 0, cleanupReport = cleanupReport)
        } else {
            diagnosticSink.emit(runtimeDiagnostic("DEMO_SHUTDOWN_FAILED", phase = "shutdown"))
            DemoRunResult(status = 2, cleanupReport = cleanupReport)
        }
    } catch (primary: RuntimeException) {
        try {
            server?.stop(SHUTDOWN_GRACE_MILLIS, SHUTDOWN_TIMEOUT_MILLIS)
        } catch (stopFailure: RuntimeException) {
            if (stopFailure !== primary) primary.addSuppressed(stopFailure)
        }

        val cleanupReport = resources?.closeReport() ?: DemoCleanupReport(primary.suppressed.toList())
        cleanupReport.failures.forEach { cleanupFailure ->
            if (cleanupFailure !== primary && primary.suppressed.none { it === cleanupFailure }) {
                primary.addSuppressed(cleanupFailure)
            }
        }
        diagnosticSink.emit(runtimeDiagnostic("DEMO_STARTUP_FAILED", phase = "startup"))
        DemoRunResult(status = 1, primaryFailure = primary, cleanupReport = cleanupReport)
    }
}

private val log = KotlinLogging.logger {}

internal fun interface DemoDiagnosticLogger {
    fun error(message: () -> String)
}

private val defaultDiagnosticLogger = DemoDiagnosticLogger { message ->
    log.error { message() }
}

internal class StderrDemoDiagnosticSink(
    private val logger: DemoDiagnosticLogger = defaultDiagnosticLogger,
) : DemoDiagnosticSink {
    override fun emit(diagnostic: DemoDiagnostic) {
        val fields = buildList {
            add("code=${diagnostic.code}")
            add("correlationId=${diagnostic.correlationId}")
            add("component=${diagnostic.component}")
            diagnostic.operation?.let { add("operation=$it") }
            diagnostic.phase?.let { add("phase=$it") }
            add("outcome=${diagnostic.outcome}")
        }
        logger.error { fields.joinToString(" ") }
    }
}

fun main() {
    val diagnostics = StderrDemoDiagnosticSink()
    val result = runKtorExposedDemo(
        resourcesFactory = DemoResourcesFactory(KtorExposedDemoResources::create),
        serverFactory = { resources ->
            val server = embeddedServer(
                factory = Netty,
                configure = {
                    connectors += EngineConnectorBuilder().apply {
                        host = "127.0.0.1"
                        port = 8080
                    }
                    shutdownGracePeriod = SHUTDOWN_GRACE_MILLIS
                    shutdownTimeout = SHUTDOWN_TIMEOUT_MILLIS
                },
            ) {
                installKtorExposedDemo(resources, diagnostics)
            }
            object : DemoServer {
                override fun start(wait: Boolean) {
                    server.start(wait)
                }

                override fun stop(gracePeriodMillis: Long, timeoutMillis: Long) {
                    server.stop(gracePeriodMillis, timeoutMillis)
                }
            }
        },
        diagnosticSink = diagnostics,
    )
    exitProcess(result.status)
}

private fun runtimeDiagnostic(code: String, phase: String) = DemoDiagnostic(
    code = code,
    correlationId = Uuid.V7.nextId().toString(),
    component = "ktor-demo",
    phase = phase,
    outcome = "failed",
)

private const val SHUTDOWN_GRACE_MILLIS = 1_000L
private const val SHUTDOWN_TIMEOUT_MILLIS = 5_000L

internal fun DemoDiagnostic.toLogFields(): String = buildList {
    add("code=$code")
    add("correlationId=$correlationId")
    add("component=$component")
    operation?.let { add("operation=$it") }
    phase?.let { add("phase=$it") }
    add("outcome=$outcome")
}.joinToString(" ")
