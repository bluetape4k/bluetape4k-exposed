package io.bluetape4k.batch.core

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.javatimes.nanos
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.junit5.coroutines.runSuspendIO
import org.junit.jupiter.api.Test

/** lease heartbeat, timeout, safe-margin 계산의 millisecond 계약을 검증한다. */
class BatchLeaseTimingTest {

    @Test
    fun `최소 lease의 scheduling 경계`() = runSuspendIO {
        val timing = BatchLeaseCalculator.calculate(30.seconds())

        timing.leaseMillis shouldBeEqualTo 30_000L
        timing.repositoryTimeoutMillis shouldBeEqualTo 5_000L
        timing.safeMarginMillis shouldBeEqualTo 10_000L
        timing.heartbeatIntervalMillis shouldBeEqualTo 10_000L
        timing.latencyAlertThresholdMillis shouldBeEqualTo 2_500L
    }

    @Test
    fun `fractional millisecond는 scheduling 값에서 내림한다`() = runSuspendIO {
        val timing = BatchLeaseCalculator.calculate(31.seconds() + 999_999.nanos())

        timing.leaseMillis shouldBeEqualTo 31_000L
        timing.repositoryTimeoutMillis shouldBeEqualTo 5_166L
        timing.safeMarginMillis shouldBeEqualTo 10_333L
        timing.heartbeatIntervalMillis shouldBeEqualTo 10_333L
        timing.latencyAlertThresholdMillis shouldBeEqualTo 2_583L
    }
}
