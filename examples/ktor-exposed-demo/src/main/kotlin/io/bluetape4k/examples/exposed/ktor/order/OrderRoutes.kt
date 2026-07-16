package io.bluetape4k.examples.exposed.ktor.order

import io.bluetape4k.exposed.r2dbc.caffeine.repository.R2dbcCaffeineRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class OrderResponse(
    val orderId: String,
    val status: String,
    val updatedAt: String,
) : java.io.Serializable {

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

@Serializable
data class OrderConfirmationResponse(
    val orderId: String,
    val status: String,
    val updatedAt: String,
    val eventPublished: Boolean,
) : java.io.Serializable {

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

@Serializable
data class DemoErrorResponse(
    val code: String,
    val message: String,
    val correlationId: String? = null,
) : java.io.Serializable {

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

data class DemoDiagnostic(
    val code: String,
    val correlationId: String,
    val component: String,
    val operation: String? = null,
    val phase: String? = null,
    val outcome: String,
) : java.io.Serializable {

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

fun interface DemoDiagnosticSink {
    fun emit(diagnostic: DemoDiagnostic)
}

fun Route.orderRoutes(
    service: OrderCommandService,
    repository: R2dbcCaffeineRepository<UUID, OrderRecord>,
    diagnostics: DemoDiagnosticSink,
) {
    post("/orders/{orderId}/confirm") {
        if (call.request.header(DEMO_COMMAND_HEADER) != DEMO_COMMAND_VALUE) {
            call.respond(
                HttpStatusCode.Forbidden,
                DemoErrorResponse(
                    code = "DEMO_COMMAND_REQUIRED",
                    message = "Required demo command header is missing or invalid.",
                ),
            )
            return@post
        }

        val id = call.parameters["orderId"].toCanonicalOrderIdOrNull()
        if (id == null) {
            call.respond(
                HttpStatusCode.BadRequest,
                DemoErrorResponse(
                    code = "INVALID_ORDER_ID",
                    message = "Order id must be a canonical non-nil UUID.",
                ),
            )
            return@post
        }

        try {
            call.respond(service.confirm(id).toResponse())
        } catch (e: OrderPersistenceException) {
            call.respondServiceUnavailable(
                "ORDER_PERSISTENCE_FAILED",
                "Order could not be stored.",
                "confirm",
                diagnostics,
            )
        } catch (e: OrderEventHandoffException) {
            call.respondServiceUnavailable(
                "ORDER_EVENT_HANDOFF_FAILED",
                "Order was stored but its event was not handed off.",
                "confirm",
                diagnostics,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            call.respondServiceUnavailable(
                "ORDER_CONFIRMATION_FAILED",
                "Order confirmation failed.",
                "confirm",
                diagnostics,
            )
        }
    }

    get("/orders/{orderId}") {
        val id = call.parameters["orderId"].toCanonicalOrderIdOrNull()
        if (id == null) {
            call.respond(
                HttpStatusCode.BadRequest,
                DemoErrorResponse(
                    code = "INVALID_ORDER_ID",
                    message = "Order id must be a canonical non-nil UUID.",
                ),
            )
            return@get
        }

        try {
            val record = repository.get(id)
            if (record == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    DemoErrorResponse("ORDER_NOT_FOUND", "Order was not found."),
                )
            } else {
                call.respond(record.toResponse())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            call.respondServiceUnavailable(
                "ORDER_READ_FAILED",
                "Order could not be loaded.",
                "read",
                diagnostics,
            )
        }
    }
}

private fun String?.toCanonicalOrderIdOrNull(): UUID? {
    val raw = this ?: return null
    if (raw.length != UUID_TEXT_LENGTH) return null
    val parsed = runCatching { UUID.fromString(raw) }.getOrNull() ?: return null
    if (parsed == NIL_UUID || parsed.toString() != raw) return null
    return parsed
}

private fun OrderConfirmationResult.toResponse() = OrderConfirmationResponse(
    orderId = record.id.toString(),
    status = record.status.name,
    updatedAt = record.updatedAt.toString(),
    eventPublished = eventPublished,
)

private fun OrderRecord.toResponse() = OrderResponse(
    orderId = id.toString(),
    status = status.name,
    updatedAt = updatedAt.toString(),
)

private suspend fun ApplicationCall.respondServiceUnavailable(
    code: String,
    message: String,
    operation: String,
    diagnostics: DemoDiagnosticSink,
) {
    val correlationId = UUID.randomUUID().toString()
    diagnostics.emit(
        DemoDiagnostic(
            code = code,
            correlationId = correlationId,
            component = "order-command",
            operation = operation,
            outcome = "failed",
        ),
    )
    respond(
        HttpStatusCode.ServiceUnavailable,
        DemoErrorResponse(code, message, correlationId),
    )
}

private const val DEMO_COMMAND_HEADER = "X-Demo-Command"
private const val DEMO_COMMAND_VALUE = "confirm-order"
private const val UUID_TEXT_LENGTH = 36
private val NIL_UUID = UUID(0L, 0L)
