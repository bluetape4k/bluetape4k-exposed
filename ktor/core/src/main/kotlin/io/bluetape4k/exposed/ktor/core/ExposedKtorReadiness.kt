package io.bluetape4k.exposed.ktor.core

import kotlin.time.Duration
import kotlin.time.DurationUnit

/** readiness probe가 나타내는 backend입니다. */
enum class ExposedKtorReadinessBackend {
    JDBC,
    R2DBC,
    CACHE,
}

/** Ktor readiness 경계를 통과할 수 있는 유한 결과입니다. */
enum class ExposedKtorReadinessOutcome {
    UP,
    DOWN,
    TIMEOUT,
}

/**
 * backend와 무관한 readiness 계약입니다.
 *
 * 구현은 Ktor event-loop를 blocking하지 않고 coroutine cancellation에 협력해야
 * 합니다. resource lifecycle은 구현을 제공한 호출자가 소유합니다.
 */
interface ExposedKtorReadinessProbe {
    val component: String
    val backend: ExposedKtorReadinessBackend

    suspend fun probe(timeout: Duration): ExposedKtorReadinessOutcome
}

/** 호출자 소유의 cancellation-cooperative readiness 계약을 선언하는 marker입니다. */
interface ExposedKtorCooperativeReadinessProbe : ExposedKtorReadinessProbe

/** 등록 시 상태를 고정해 mutable probe metadata가 tag를 바꾸지 못하게 합니다. */
internal data class RegisteredProbe(
    val component: String,
    val backend: ExposedKtorReadinessBackend,
    val delegate: ExposedKtorReadinessProbe,
)

internal sealed interface ProbeAttempt<out T> {
    data class Value<T>(val value: T) : ProbeAttempt<T>
    data object OuterTimeout : ProbeAttempt<Nothing>
}

/** 결정적인 route 계약 테스트에 사용하는 monotonic clock seam입니다. */
internal fun interface ReadinessClock {
    fun nowNanos(): Long
}

internal val SYSTEM_READINESS_CLOCK: ReadinessClock = ReadinessClock(System::nanoTime)

internal const val MAX_READINESS_PROBES: Int = 16
internal const val MAX_EXPOSED_KTOR_PATH_LENGTH: Int = 256

private val COMPONENT_PATTERN = Regex("[a-z][a-z0-9_.-]{0,62}")

internal fun validateReadinessProbes(
    probes: List<ExposedKtorReadinessProbe>,
): List<RegisteredProbe> {
    require(probes.isNotEmpty()) { "Readiness probes must not be empty." }
    require(probes.size <= MAX_READINESS_PROBES) {
        "Readiness probes must contain at most $MAX_READINESS_PROBES entries."
    }

    val components = HashSet<String>(probes.size)
    return probes.mapIndexed { index, probe ->
        require(probe is ExposedKtorCooperativeReadinessProbe) {
            "Readiness probe at index=$index must implement ExposedKtorCooperativeReadinessProbe."
        }
        validateReadinessComponent(probe.component, index)
        require(components.add(probe.component)) {
            "Readiness probe at index=$index has duplicate component=${probe.component}."
        }
        RegisteredProbe(
            component = probe.component,
            backend = probe.backend,
            delegate = probe,
        )
    }
}

internal fun validateReadinessComponent(component: String, index: Int? = null) {
    require(COMPONENT_PATTERN.matches(component)) {
        val location = index?.let { " at index=$it" }.orEmpty()
        "Invalid readiness component$location: reason=unsafe_component."
    }
}

internal fun Duration.requireFinitePositive(parameterName: String): Duration {
    require(parameterName.isNotBlank()) { "parameterName must not be blank." }
    require(isFinite() && isPositive()) {
        "$parameterName must be finite and positive."
    }
    return this
}

internal fun String.requireLiteralExposedKtorPath(parameterName: String): String {
    require(parameterName.isNotBlank()) { "parameterName must not be blank." }
    require(isNotEmpty() && startsWith("/")) {
        "$parameterName must be an absolute path."
    }
    require(length <= MAX_EXPOSED_KTOR_PATH_LENGTH) {
        "$parameterName must not exceed $MAX_EXPOSED_KTOR_PATH_LENGTH characters."
    }
    require(none { it.isISOControl() || it == '{' || it == '}' || it == '*' }) {
        "$parameterName contains a forbidden path character."
    }
    require('?' !in this && '#' !in this) {
        "$parameterName must not contain a query or fragment."
    }
    return trimEnd('/').ifEmpty { "/" }
}

internal fun requireDistinctExposedKtorCorePaths(healthPath: String, readinessPath: String) {
    require(healthPath != readinessPath) {
        "healthPath and readinessPath must be distinct after normalization."
    }
}

internal fun Duration.toTimeoutNanosSaturated(): Long = toLong(DurationUnit.NANOSECONDS)

internal fun remainingReadinessNanos(
    startedNanos: Long,
    nowNanos: Long,
    budgetNanos: Long,
): Long {
    val elapsed = if (nowNanos >= startedNanos) {
        nowNanos - startedNanos
    } else {
        0L
    }
    if (elapsed >= budgetNanos) return 0L
    return budgetNanos - elapsed
}
