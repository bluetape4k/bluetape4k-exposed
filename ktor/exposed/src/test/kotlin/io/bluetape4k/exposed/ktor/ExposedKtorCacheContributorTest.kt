package io.bluetape4k.exposed.ktor

import io.bluetape4k.exposed.cache.CacheHealthReport
import io.bluetape4k.exposed.cache.CacheWorkerState
import io.bluetape4k.exposed.cache.CacheWriteMode
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheFailureBuffer
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class ExposedKtorCacheContributorTest {

    @Test
    fun `all four factories pin sanitized finite kinds`() = runTest {
        val jdbc = ExposedKtorCacheContributor.jdbcRepository("orders") { healthyReport() }
        val r2dbc = ExposedKtorCacheContributor.r2dbcRepository("orders_r2dbc") { healthyReport() }
        val snapshot = ExposedKtorCacheContributor.snapshot("snapshots", failureBuffer())
        val custom = ExposedKtorCacheContributor.custom("custom-1") { ExposedKtorCacheStatus.DOWN }

        assertEquals(ExposedKtorCacheKind.JDBC, jdbc.kind)
        assertEquals(ExposedKtorCacheStatus.UP, jdbc.probe().status)
        assertEquals(ExposedKtorCacheKind.R2DBC, r2dbc.kind)
        assertEquals(ExposedKtorCacheStatus.UP, r2dbc.probe().status)
        assertEquals(ExposedKtorCacheKind.SNAPSHOT, snapshot.kind)
        assertEquals(ExposedKtorCacheStatus.UP, snapshot.probe().status)
        assertEquals(ExposedKtorCacheKind.CUSTOM, custom.kind)
        assertEquals(ExposedKtorCacheStatus.DOWN, custom.probe().status)
    }

    @Test
    fun `repository lifecycle and flush error map to finite readiness`() = runTest {
        val expected = mapOf(
            CacheWorkerState.NOT_APPLICABLE to ExposedKtorCacheStatus.UP,
            CacheWorkerState.IDLE to ExposedKtorCacheStatus.UP,
            CacheWorkerState.RUNNING to ExposedKtorCacheStatus.UP,
            CacheWorkerState.DRAINING to ExposedKtorCacheStatus.DOWN,
            CacheWorkerState.FAILED to ExposedKtorCacheStatus.DOWN,
            CacheWorkerState.STOPPED to ExposedKtorCacheStatus.DOWN,
        )

        expected.forEach { (state, status) ->
            val contributor = ExposedKtorCacheContributor.jdbcRepository("orders") {
                healthyReport().copy(workerState = state)
            }
            assertEquals(status, contributor.probe().status)
        }

        val failed = ExposedKtorCacheContributor.jdbcRepository("orders") {
            healthyReport().copy(lastFlushError = IllegalStateException("secret"))
        }.probe()
        assertEquals(ExposedKtorCacheStatus.DOWN, failed.status)
    }

    @Test
    fun `snapshot samples each public count once and never mutates the buffer`() = runTest {
        val buffer = failureBuffer(size = 3, dropped = 5, observerFailures = 7)
        val sample = ExposedKtorCacheContributor.snapshot("snapshots", buffer).probe()

        assertEquals(3.0, sample.snapshotPending)
        assertEquals(5.0, sample.snapshotDropped)
        assertEquals(7.0, sample.snapshotObserverFailures)
        assertTrue(sample.queueDepth.isNaN())
        verify(exactly = 1) { buffer.size }
        verify(exactly = 1) { buffer.droppedCount }
        verify(exactly = 1) { buffer.observerFailureCount }
        verify(exactly = 0) { buffer.poll() }
        verify(exactly = 0) { buffer.drainTo(any(), any()) }
    }

    @Test
    fun `config defensively copies and bounds a unique non-empty contributor list`() {
        val source = mutableListOf(custom("a"))
        val config = ExposedKtorCacheReadinessConfig(source)
        source += custom("b")

        assertEquals(listOf("a"), config.contributors.map { it.component })
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (config.contributors as MutableList<ExposedKtorCacheContributor>).add(custom("c"))
        }
        val empty = assertThrows(IllegalArgumentException::class.java) {
            ExposedKtorCacheReadinessConfig(emptyList())
        }
        assertEquals(null, empty.cause)
        val duplicate = assertThrows(IllegalArgumentException::class.java) {
            ExposedKtorCacheReadinessConfig(listOf(custom("same"), custom("same")))
        }
        assertTrue(duplicate.message.orEmpty().contains("index=1"))
        assertTrue(duplicate.message.orEmpty().contains("duplicateOf=0"))
        assertFalse(duplicate.message.orEmpty().contains("same"))
        assertEquals(null, duplicate.cause)
        ExposedKtorCacheReadinessConfig((0 until 16).map { custom("cache_$it") })
        val overLimit = assertThrows(IllegalArgumentException::class.java) {
            ExposedKtorCacheReadinessConfig((0 until 17).map { custom("cache_$it") })
        }
        assertEquals(null, overLimit.cause)
    }

    @Test
    fun `component validation is exact and never echoes raw input`() {
        listOf("a", "a0_-", "z" + "0".repeat(62)).forEach { custom(it) }
        val unsafeValues = listOf(
            "A",
            "0cache",
            "has.dot",
            "has/slash",
            "a".repeat(64),
            "jdbc:h2:mem:secret?password=top-secret\nnext",
        )
        unsafeValues.forEach { raw ->
            val error = assertThrows(IllegalArgumentException::class.java) { custom(raw) }
            assertTrue(error.message.orEmpty().contains("reason=unsafe_component"))
            assertTrue(error.message.orEmpty().contains("length=${raw.length}"))
            assertFalse(error.message.orEmpty().contains(raw))
            assertFalse(error.message.orEmpty().contains("top-secret"))
            assertEquals(null, error.cause)
        }
    }

    @Test
    fun `public factory KDoc pins bounded names safe probes cancellation and isolation ownership`() {
        val source = cacheReadinessSource()
        val factories = listOf(
            "jdbcRepository" to false,
            "r2dbcRepository" to true,
            "snapshot" to false,
            "custom" to true,
        )

        factories.forEach { (factory, suspends) ->
            val kdoc = kdocImmediatelyBefore(source, "fun $factory(")
            assertCommonProbeContract(factory, kdoc)
            if (suspends) {
                assertTrue(kdoc.contains("non-blocking"), "$factory non-blocking")
                assertTrue(kdoc.contains("cooperate with coroutine cancellation"), "$factory cancellation")
            }
        }

        val configKdoc = kdocImmediatelyBefore(source, "class ExposedKtorCacheReadinessConfig(")
        assertCommonProbeContract("config", configKdoc)
        assertTrue(configKdoc.contains("non-blocking"), "config non-blocking")
        assertTrue(configKdoc.contains("cooperate with coroutine cancellation"), "config cancellation")
    }

    @Test
    fun `negative repository and snapshot measurements fail with stable redacted reasons`() = runTest {
        val repositoryError = runCatching {
            ExposedKtorCacheContributor.jdbcRepository("orders") {
                healthyReport().copy(queueDepth = -1)
            }.probe()
        }.exceptionOrNull()
        assertTrue(repositoryError is IllegalArgumentException)
        assertTrue(repositoryError?.message.orEmpty().contains("reason=negative_queue_depth"))

        val snapshotError = runCatching {
            ExposedKtorCacheContributor.snapshot(
                "snapshots",
                failureBuffer(dropped = -1),
            ).probe()
        }.exceptionOrNull()
        assertTrue(snapshotError is IllegalArgumentException)
        assertTrue(snapshotError?.message.orEmpty().contains("reason=negative_dropped"))
    }

    private fun custom(component: String) =
        ExposedKtorCacheContributor.custom(component) { ExposedKtorCacheStatus.UP }

    private fun healthyReport() = CacheHealthReport(
        mode = CacheWriteMode.WRITE_BEHIND,
        queueDepth = 2,
        workerState = CacheWorkerState.RUNNING,
        lastFlushError = null,
    )

    private fun failureBuffer(
        size: Int = 0,
        dropped: Long = 0,
        observerFailures: Long = 0,
    ): SnapshotCacheFailureBuffer = mockk(relaxed = true) {
        every { this@mockk.size } returns size
        every { droppedCount } returns dropped
        every { observerFailureCount } returns observerFailures
    }

    private fun cacheReadinessSource(): String {
        val relative = "ktor/exposed/src/main/kotlin/io/bluetape4k/exposed/ktor/ExposedKtorCacheReadiness.kt"
        val candidates = listOf(Path.of(relative), Path.of("src/main/kotlin/io/bluetape4k/exposed/ktor/ExposedKtorCacheReadiness.kt"))
        return Files.readString(candidates.first(Files::exists))
    }

    private fun kdocImmediatelyBefore(source: String, declaration: String): String {
        val declarationOffset = source.indexOf(declaration)
        assertTrue(declarationOffset >= 0, declaration)
        val prefix = source.substring(0, declarationOffset)
        val end = prefix.lastIndexOf("*/")
        val start = prefix.lastIndexOf("/**", end)
        assertTrue(start >= 0 && end >= start, "$declaration KDoc")
        assertTrue(prefix.substring(end + 2).isBlank(), "$declaration must immediately follow its KDoc")
        return prefix.substring(start, end + 2)
    }

    private fun assertCommonProbeContract(scope: String, kdoc: String) {
        listOf(
            "[a-z][a-z0-9_-]{0,62}" to "regex",
            "tenant" to "tenant",
            "key" to "key",
            "URL" to "URL",
            "namespace" to "namespace",
            "data-bearing" to "data-bearing",
            "side-effect-free O(1)" to "side-effect-free O(1)",
            "in-memory" to "in-memory",
            "backend I/O" to "backend I/O",
            "no isolation thread" to "no isolation thread",
            "dispatcher" to "dispatcher",
            "scope" to "scope",
        ).forEach { (contract, label) ->
            assertTrue(kdoc.contains(contract), "$scope $label")
        }
    }
}
