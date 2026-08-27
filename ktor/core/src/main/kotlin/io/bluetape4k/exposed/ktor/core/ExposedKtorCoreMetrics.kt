package io.bluetape4k.exposed.ktor.core

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.Timer
import java.util.concurrent.TimeUnit

internal const val CORE_TRANSACTION_METER_NAME = "bluetape4k.exposed.ktor.core.transaction"
internal const val CORE_READINESS_METER_NAME = "bluetape4k.exposed.ktor.core.readiness"

private const val BACKEND_TAG = "backend"
private const val OPERATION_TAG = "operation"
private const val OUTCOME_TAG = "outcome"
private const val COMPONENT_TAG = "component"
private const val READINESS_OPERATION = "readiness"
private val READINESS_OUTCOMES = listOf("success", "error", "timeout", "cancelled")
private val EXPECTED_TAG_KEYS = setOf(BACKEND_TAG, OPERATION_TAG, OUTCOME_TAG, COMPONENT_TAG)

internal class CoreReadinessMetricBinding private constructor(
    private val timers: Map<String, Timer>,
) {
    fun record(outcome: String, elapsedNanos: Long) {
        require(outcome in READINESS_OUTCOMES) { "Unsupported readiness metric outcome." }
        timers[outcome]?.record(elapsedNanos.coerceAtLeast(0L), TimeUnit.NANOSECONDS)
    }

    companion object {
        fun withoutRegistry(): CoreReadinessMetricBinding = CoreReadinessMetricBinding(emptyMap())

        fun install(
            registry: MeterRegistry,
            probe: RegisteredProbe,
        ): CoreReadinessMetricBinding {
            validateMeterFamily(registry, CORE_READINESS_METER_NAME)
            val tags = listOf(
                Tag.of(BACKEND_TAG, probe.backend.metricValue),
                Tag.of(OPERATION_TAG, READINESS_OPERATION),
                Tag.of(COMPONENT_TAG, probe.component),
            )
            val timers = READINESS_OUTCOMES.associateWith { outcome ->
                Timer.builder(CORE_READINESS_METER_NAME)
                    .tags(tags)
                    .tag(OUTCOME_TAG, outcome)
                    .description("Exposed Ktor readiness probe duration.")
                    .register(registry)
            }
            return CoreReadinessMetricBinding(timers)
        }
    }
}

internal fun ExposedKtorReadinessBackend.toMetricValue(): String = name.lowercase()

private val ExposedKtorReadinessBackend.metricValue: String
    get() = toMetricValue()

private fun validateMeterFamily(registry: MeterRegistry, meterName: String) {
    registry.meters
        .filter { it.id.name == meterName }
        .forEach { meter ->
            require(meter is Timer) {
                "Metric family $meterName already exists with an incompatible meter type."
            }
            val keys = meter.id.tags.map(Tag::getKey).toSet()
            require(keys == EXPECTED_TAG_KEYS) {
                "Metric family $meterName already exists with incompatible tag keys."
            }
        }
}

internal fun MeterRegistry?.installCoreReadinessMetrics(
    probes: List<RegisteredProbe>,
): List<CoreReadinessMetricBinding> = when (this) {
    null -> probes.map { CoreReadinessMetricBinding.withoutRegistry() }
    else -> probes.map { CoreReadinessMetricBinding.install(this, it) }
}

internal fun MeterRegistry?.recordCoreTransaction(
    backend: ExposedKtorReadinessBackend,
    component: String,
    outcome: String,
    elapsedNanos: Long,
) {
    this ?: return
    require(outcome in READINESS_OUTCOMES) { "Unsupported transaction metric outcome." }
    validateMeterFamily(this, CORE_TRANSACTION_METER_NAME)
    Timer.builder(CORE_TRANSACTION_METER_NAME)
        .tags(
            BACKEND_TAG,
            backend.metricValue,
            OPERATION_TAG,
            "transaction",
            COMPONENT_TAG,
            component,
            OUTCOME_TAG,
            outcome,
        )
        .description("Exposed Ktor transaction duration.")
        .register(this)
        .record(elapsedNanos.coerceAtLeast(0L), TimeUnit.NANOSECONDS)
}
