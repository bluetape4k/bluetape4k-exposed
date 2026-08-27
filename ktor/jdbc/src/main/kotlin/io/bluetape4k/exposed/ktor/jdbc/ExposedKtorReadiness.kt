package io.bluetape4k.exposed.ktor.jdbc

import io.bluetape4k.exposed.ktor.core.ExposedKtorCooperativeReadinessProbe
import io.bluetape4k.exposed.ktor.core.ExposedKtorReadinessBackend
import io.bluetape4k.exposed.ktor.core.ExposedKtorReadinessOutcome
import io.bluetape4k.exposed.ktor.core.ExposedKtorReadinessProbe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * cooperative JDBC readiness probe를 생성합니다.
 *
 * blocking 작업은 [blockingDispatcher]에서 `runInterruptible`로만 실행합니다.
 * 호출자는 statement timeout과 interruption을 지원하는 driver를 제공해야 합니다.
 */
fun exposedKtorJdbcReadinessProbe(
    db: Database,
    blockingDispatcher: CoroutineDispatcher,
    jdbcQueryTimeout: Duration = 1.seconds,
    component: String = DEFAULT_JDBC_COMPONENT,
): ExposedKtorReadinessProbe {
    jdbcQueryTimeout.requireFinitePositive("jdbcQueryTimeout")
    validateJdbcComponent(component)
    return JdbcReadinessProbe(db, blockingDispatcher, jdbcQueryTimeout, component)
}

private class JdbcReadinessProbe(
    private val db: Database,
    private val blockingDispatcher: CoroutineDispatcher,
    private val jdbcQueryTimeout: Duration,
    override val component: String,
) : ExposedKtorCooperativeReadinessProbe {
    override val backend: ExposedKtorReadinessBackend = ExposedKtorReadinessBackend.JDBC

    @Suppress("TooGenericExceptionCaught")
    override suspend fun probe(timeout: Duration): ExposedKtorReadinessOutcome {
        timeout.requireFinitePositive("timeout")
        val started = TimeSource.Monotonic.markNow()
        return try {
            val completed = runInterruptible(blockingDispatcher) {
                transaction(db = db) {
                    // Dispatcher queueing도 호출자 예산을 사용하므로 statement 직전에
                    // entry timeout을 재사용하지 않고 remaining을 다시 계산합니다.
                    val remaining = timeout - started.elapsedNow()
                    if (remaining <= ZERO) {
                        false
                    } else {
                        queryTimeout = statementTimeoutSeconds(jdbcQueryTimeout, remaining)
                        exec("SELECT 1") { resultSet -> resultSet.next() }
                        true
                    }
                }
            }
            if (completed) ExposedKtorReadinessOutcome.UP else ExposedKtorReadinessOutcome.TIMEOUT
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

private fun statementTimeoutSeconds(
    configured: Duration,
    remaining: Duration,
): Int = minOf(configured, remaining)
    .inWholeSeconds
    .coerceAtLeast(1L)
    .coerceAtMost(Int.MAX_VALUE.toLong())
    .toInt()

private fun Duration.requireFinitePositive(parameterName: String): Duration {
    require(parameterName.isNotBlank()) { "parameterName must not be blank." }
    require(isFinite() && isPositive()) { "$parameterName must be finite and positive." }
    return this
}

private val COMPONENT_PATTERN = Regex("[a-z][a-z0-9_.-]{0,62}")
private const val DEFAULT_JDBC_COMPONENT = "jdbc"

private fun validateJdbcComponent(component: String) {
    require(COMPONENT_PATTERN.matches(component)) {
        "Invalid JDBC readiness component: reason=unsafe_component."
    }
}
