package io.bluetape4k.examples.exposed.ktor

import io.bluetape4k.exposed.ktor.Bluetape4kExposedKtorConfig
import io.bluetape4k.exposed.ktor.bluetape4kExposedErrors
import io.bluetape4k.exposed.ktor.exposedJdbcTransaction
import io.bluetape4k.exposed.ktor.installBluetape4kExposedKtor
import io.bluetape4k.ktor.core.Bluetape4kKtorCoreConfig
import io.bluetape4k.ktor.core.bluetape4kErrorResponses
import io.bluetape4k.ktor.core.installBluetape4kKtorCore
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.time.Duration.Companion.seconds

fun Application.installKtorExposedDemo(resources: KtorExposedDemoResources) {
    monitor.subscribe(ApplicationStopped) {
        resources.close()
    }

    installBluetape4kKtorCore(
        Bluetape4kKtorCoreConfig(
            installStatusPages = false,
            installHealthRoutes = false,
        )
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
        )
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
    }
}

fun main() {
    val resources = KtorExposedDemoResources.create()
    embeddedServer(Netty, port = 8080) {
        installKtorExposedDemo(resources)
    }.start(wait = true)
}
