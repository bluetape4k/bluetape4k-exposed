package io.bluetape4k.exposed.cache

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.ObjectStreamClass
import java.io.Serializable
import kotlin.reflect.full.memberProperties

class CacheHealthReportTest {

    @Test
    fun `worker states have the exact public order`() {
        CacheWorkerState.entries.map { it.name } shouldBeEqualTo listOf(
            "NOT_APPLICABLE",
            "IDLE",
            "RUNNING",
            "DRAINING",
            "FAILED",
            "STOPPED",
        )
    }

    @Test
    fun `health report preserves every worker state through Java serialization`() {
        CacheWorkerState.entries.forEachIndexed { index, workerState ->
            val report = CacheHealthReport(
                mode = CacheWriteMode.WRITE_BEHIND,
                queueDepth = index,
                workerState = workerState,
                lastFlushError = IllegalStateException("flush-$workerState"),
            )

            val restored = serializeRoundTrip(report)

            restored.mode shouldBeEqualTo report.mode
            restored.queueDepth shouldBeEqualTo report.queueDepth
            restored.workerState shouldBeEqualTo workerState
            restored.lastFlushError?.javaClass shouldBeEqualTo IllegalStateException::class.java
            restored.lastFlushError?.message shouldBeEqualTo "flush-$workerState"
        }
    }

    @Test
    fun `health report no longer exposes isFlushJobRunning`() {
        CacheHealthReport::class.memberProperties
            .none { it.name == "isFlushJobRunning" }
            .shouldBeTrue()
        CacheHealthReport::class.java.methods
            .none { it.name == "isFlushJobRunning" }
            .shouldBeTrue()
    }

    @Test
    fun `health report declares the new serial version UID`() {
        ObjectStreamClass.lookup(CacheHealthReport::class.java).serialVersionUID shouldBeEqualTo
            -1428853048381429257L
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Serializable> serializeRoundTrip(value: T): T {
        val bytes = ByteArrayOutputStream().use { output ->
            ObjectOutputStream(output).use { it.writeObject(value) }
            output.toByteArray()
        }
        return ByteArrayInputStream(bytes).use { input ->
            ObjectInputStream(input).use { it.readObject() as T }
        }
    }
}
