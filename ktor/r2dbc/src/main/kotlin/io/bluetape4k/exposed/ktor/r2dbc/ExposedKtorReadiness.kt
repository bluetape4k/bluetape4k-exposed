package io.bluetape4k.exposed.ktor.r2dbc

import io.bluetape4k.exposed.ktor.core.ExposedKtorCooperativeReadinessProbe
import io.bluetape4k.exposed.ktor.core.ExposedKtorReadinessBackend
import io.bluetape4k.exposed.ktor.core.ExposedKtorReadinessOutcome
import io.bluetape4k.exposed.ktor.core.ExposedKtorReadinessProbe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/** blocking dispatcher 없이 cooperative R2DBC readiness probe를 생성합니다. */
fun exposedKtorR2dbcReadinessProbe(
    db: R2dbcDatabase,
    component: String = DEFAULT_R2DBC_COMPONENT,
): ExposedKtorReadinessProbe {
    validateR2dbcComponent(component)
    return R2dbcReadinessProbe(db, component)
}

private class R2dbcReadinessProbe(
    private val db: R2dbcDatabase,
    override val component: String,
) : ExposedKtorCooperativeReadinessProbe {
    override val backend: ExposedKtorReadinessBackend = ExposedKtorReadinessBackend.R2DBC

    @Suppress("TooGenericExceptionCaught")
    override suspend fun probe(timeout: kotlin.time.Duration): ExposedKtorReadinessOutcome {
        require(timeout.isFinite() && timeout.isPositive()) { "timeout must be finite and positive." }
        return try {
            suspendTransaction(db = db) {
                exec("SELECT 1")
            }
            ExposedKtorReadinessOutcome.UP
        } catch (cancellation: CancellationException) {
            if (!currentCoroutineContext().isActive) throw cancellation
            ExposedKtorReadinessOutcome.DOWN
        } catch (failure: Error) {
            throw failure
        } catch (_: Exception) {
            ExposedKtorReadinessOutcome.DOWN
        }
    }
}

private val COMPONENT_PATTERN = Regex("[a-z][a-z0-9_.-]{0,62}")
private const val DEFAULT_R2DBC_COMPONENT = "r2dbc"

private fun validateR2dbcComponent(component: String) {
    require(COMPONENT_PATTERN.matches(component)) {
        "Invalid R2DBC readiness component: reason=unsafe_component."
    }
}
