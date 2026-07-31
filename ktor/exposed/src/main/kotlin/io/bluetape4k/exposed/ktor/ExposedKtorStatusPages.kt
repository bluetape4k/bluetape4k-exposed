package io.bluetape4k.exposed.ktor

import io.bluetape4k.ktor.core.respondApiError
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.r2dbc.spi.R2dbcException
import kotlinx.coroutines.CancellationException
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import java.sql.SQLException

/** client에 안전한 Exposed error response를 등록합니다. */
fun StatusPagesConfig.bluetape4kExposedErrors() {
    exception<CancellationException> { _, cause ->
        throw cause
    }
    exception<ExposedKtorReadinessTimeoutException> { call, _ ->
        call.respondApiError(
            status = HttpStatusCode.ServiceUnavailable,
            error = "EXPOSED_READINESS_TIMEOUT",
            message = "Exposed readiness probe timed out",
        )
    }
    exception<ExposedKtorTransactionException> { call, _ ->
        call.respondApiError(
            status = HttpStatusCode.InternalServerError,
            error = "EXPOSED_TRANSACTION_FAILED",
            message = "Exposed transaction failed",
        )
    }
    exception<ExposedSQLException> { call, _ ->
        call.respondDatabaseUnavailable()
    }
    exception<SQLException> { call, _ ->
        call.respondDatabaseUnavailable()
    }
    exception<R2dbcException> { call, _ ->
        call.respondDatabaseUnavailable()
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondDatabaseUnavailable() {
    respondApiError(
        status = HttpStatusCode.ServiceUnavailable,
        error = "EXPOSED_DATABASE_UNAVAILABLE",
        message = "Exposed database operation failed",
    )
}

class ExposedKtorTransactionException(
    cause: Throwable,
): RuntimeException("Exposed transaction failed", cause)

class ExposedKtorReadinessTimeoutException(
    backend: String,
): RuntimeException("Exposed $backend readiness probe timed out")
