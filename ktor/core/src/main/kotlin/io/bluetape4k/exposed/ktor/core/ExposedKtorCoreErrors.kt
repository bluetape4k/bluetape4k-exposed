@file:JvmName("ExposedKtorCoreStatusPagesKt")

package io.bluetape4k.exposed.ktor.core

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.respond
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmName

/** Ktor Exposed core가 소유하는 path 없는 고정 JSON 오류 응답입니다. */
@Serializable
data class ExposedKtorCoreErrorResponse(
    val error: String,
    val message: String,
    val status: Int,
)

/** backend-neutral Ktor 경계가 노출하는 안정적인 오류 분류입니다. */
enum class ExposedKtorCoreErrorCode {
    TRANSACTION,
    READINESS_TIMEOUT,
    DATABASE_UNAVAILABLE,
    INTERNAL,
}

/** raw cause와 detail을 공개 계약에 포함하지 않는 고정 문구 transaction 오류입니다. */
class ExposedKtorTransactionException : RuntimeException("Exposed transaction failed")

/** backend, SQL, request detail을 포함하지 않는 고정 문구 readiness timeout입니다. */
class ExposedKtorReadinessTimeoutException : RuntimeException("Exposed readiness probe timed out")

private data class ErrorCatalogEntry(
    val status: HttpStatusCode,
    val error: String,
    val message: String,
)

private val ERROR_CATALOG = mapOf(
    ExposedKtorCoreErrorCode.TRANSACTION to ErrorCatalogEntry(
        HttpStatusCode.InternalServerError,
        "EXPOSED_TRANSACTION_FAILED",
        "Exposed transaction failed",
    ),
    ExposedKtorCoreErrorCode.READINESS_TIMEOUT to ErrorCatalogEntry(
        HttpStatusCode.ServiceUnavailable,
        "EXPOSED_READINESS_TIMEOUT",
        "Exposed readiness probe timed out",
    ),
    ExposedKtorCoreErrorCode.DATABASE_UNAVAILABLE to ErrorCatalogEntry(
        HttpStatusCode.ServiceUnavailable,
        "EXPOSED_DATABASE_UNAVAILABLE",
        "Exposed database operation failed",
    ),
    ExposedKtorCoreErrorCode.INTERNAL to ErrorCatalogEntry(
        HttpStatusCode.InternalServerError,
        "EXPOSED_INTERNAL_ERROR",
        "Exposed operation failed",
    ),
)

/** [error]의 고정 catalog를 응답하며 request path를 포함하지 않습니다. */
suspend fun ApplicationCall.respondExposedKtorCoreError(
    error: ExposedKtorCoreErrorCode,
) {
    val entry = requireNotNull(ERROR_CATALOG[error]) { "Unsupported core error code: $error" }
    respond(
        status = entry.status,
        message = ExposedKtorCoreErrorResponse(
            error = entry.error,
            message = entry.message,
            status = entry.status.value,
        ),
    )
}

/** core가 소유한 cancellation과 고정 오류 mapping만 설치합니다. */
fun StatusPagesConfig.bluetape4kExposedCoreErrors() {
    exception<CancellationException> { _, cause -> throw cause }
    exception<ExposedKtorTransactionException> { call, _ ->
        call.respondExposedKtorCoreError(ExposedKtorCoreErrorCode.TRANSACTION)
    }
    exception<ExposedKtorReadinessTimeoutException> { call, _ ->
        call.respondExposedKtorCoreError(ExposedKtorCoreErrorCode.READINESS_TIMEOUT)
    }
}
