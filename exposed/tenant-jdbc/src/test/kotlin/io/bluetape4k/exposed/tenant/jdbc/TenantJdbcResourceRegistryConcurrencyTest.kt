@file:Suppress("TooGenericExceptionCaught")

package io.bluetape4k.exposed.tenant.jdbc

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class TenantJdbcResourceRegistryConcurrencyTest {

    @Test
    fun `동시 close caller는 한 cleanup과 같은 non fatal 결과를 관찰한다`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val waiterObservedClosing = CountDownLatch(1)
        val failure = IllegalStateException("dispose-failure")
        val disposeCalls = AtomicInteger()
        val registry = registryWithHooks(
            dispose = {
                disposeCalls.incrementAndGet()
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                throw failure
            },
            beforeCloseWait = { waiterObservedClosing.countDown() },
        )
        val ownerResult = AtomicReference<Throwable?>()
        val waiterResult = AtomicReference<Throwable?>()
        val owner = thread(name = "tenant-close-owner") { ownerResult.set(catchThrowable { registry.close() }) }
        var waiter: Thread? = null
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val startedWaiter = thread(name = "tenant-close-waiter") {
                waiterResult.set(catchThrowable { registry.close() })
            }
            waiter = startedWaiter
            assertTrue(waiterObservedClosing.await(5, TimeUnit.SECONDS))
            release.countDown()
            owner.join(Duration.ofSeconds(5))
            startedWaiter.join(Duration.ofSeconds(5))
            assertSame(failure, ownerResult.get())
            assertSame(failure, waiterResult.get())
            assertEquals(1, disposeCalls.get())
        } finally {
            release.countDown()
            owner.interrupt()
            waiter?.interrupt()
            owner.join(Duration.ofSeconds(5))
            waiter?.join(Duration.ofSeconds(5))
        }
        assertFalse(owner.isAlive)
        assertFalse(checkNotNull(waiter).isAlive)
    }

    @Test
    fun `repeated close와 cleanup owner 재진입은 cleanup을 반복하지 않는다`() {
        val disposeCalls = AtomicInteger()
        lateinit var registry: TenantJdbcResourceRegistry<String>
        registry = registryWithHooks(
            dispose = {
                disposeCalls.incrementAndGet()
                registry.close()
            },
        )

        registry.close()
        registry.close()

        assertEquals(1, disposeCalls.get())
    }

    @Test
    fun `interrupt된 waiter는 owner 결과 뒤 interrupt flag를 복원한다`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val waiting = CountDownLatch(1)
        val registry = registryWithHooks(
            dispose = {
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
            },
            beforeCloseWait = { waiting.countDown() },
        )
        val waiterInterrupted = AtomicBoolean()
        val owner = thread(name = "tenant-interrupt-owner") { registry.close() }
        var waiter: Thread? = null
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val startedWaiter = thread(name = "tenant-interrupt-waiter") {
                registry.close()
                waiterInterrupted.set(Thread.currentThread().isInterrupted)
            }
            waiter = startedWaiter
            assertTrue(waiting.await(5, TimeUnit.SECONDS))
            startedWaiter.interrupt()
            release.countDown()
            owner.join(Duration.ofSeconds(5))
            startedWaiter.join(Duration.ofSeconds(5))
        } finally {
            release.countDown()
            owner.interrupt()
            waiter?.interrupt()
            owner.join(Duration.ofSeconds(5))
            waiter?.join(Duration.ofSeconds(5))
        }
        assertFalse(owner.isAlive)
        assertFalse(checkNotNull(waiter).isAlive)
        assertTrue(waiterInterrupted.get())
    }

    @Test
    fun `fatal close의 owner는 raw throwable을 받고 waiter는 고정 상태 예외를 받는다`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val waiting = CountDownLatch(1)
        val fatal = LinkageError("fatal-secret")
        val registry = registryWithHooks(
            dispose = {
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                throw fatal
            },
            beforeCloseWait = { waiting.countDown() },
        )
        val ownerResult = AtomicReference<Throwable?>()
        val waiterResult = AtomicReference<Throwable?>()
        val owner = thread(name = "tenant-fatal-owner") { ownerResult.set(catchThrowable { registry.close() }) }
        var waiter: Thread? = null
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val startedWaiter = thread(name = "tenant-fatal-waiter") {
                waiterResult.set(catchThrowable { registry.close() })
            }
            waiter = startedWaiter
            assertTrue(waiting.await(5, TimeUnit.SECONDS))
            release.countDown()
            owner.join(Duration.ofSeconds(5))
            startedWaiter.join(Duration.ofSeconds(5))
        } finally {
            release.countDown()
            owner.interrupt()
            waiter?.interrupt()
            owner.join(Duration.ofSeconds(5))
            waiter?.join(Duration.ofSeconds(5))
        }
        assertFalse(owner.isAlive)
        assertFalse(checkNotNull(waiter).isAlive)
        assertSame(fatal, ownerResult.get())
        val waiterFailure = waiterResult.get()
        assertTrue(waiterFailure is IllegalStateException)
        assertEquals("Tenant JDBC resource registry closed after a fatal cleanup failure.", waiterFailure?.message)
    }

    @Test
    fun `OPEN을 관찰한 lookup은 close와 경합해도 시작한 map 조회를 끝낸다`() {
        val key = BlockingHashKey("a")
        val registry = TenantJdbcResourceRegistry.create(
            tenants = listOf(key),
            dataSourceFactory = { dataSource("blocking-hash") },
            disposeDataSource = { _, _ -> },
        )
        val gates = key.blockNextHash()
        val returned = AtomicReference<TenantJdbcResource?>()
        val lookup = thread(name = "tenant-lookup-race") { returned.set(registry.resourceFor(key)) }
        try {
            assertTrue(gates.entered.await(5, TimeUnit.SECONDS))
            registry.close()
            gates.release.countDown()
            lookup.join(Duration.ofSeconds(5))
            assertNotNull(returned.get())
            val failure = assertThrows(IllegalStateException::class.java) { registry.resourceFor(key) }
            assertEquals("Tenant JDBC resource registry is closed.", failure.message)
        } finally {
            gates.release.countDown()
            lookup.interrupt()
            lookup.join(Duration.ofSeconds(5))
            registry.close()
        }
        assertFalse(lookup.isAlive)
    }

    @Test
    fun `immutable lookup은 bounded concurrent stress에서 identity를 유지한다`() {
        val registry = registryOf("a", "b")
        val expected = registry.resourceFor("a")
        val threadCount = 32
        val barrier = CyclicBarrier(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)
        val errors = ConcurrentLinkedQueue<Throwable>()
        val futures = (0 until threadCount).map {
            executor.submit {
                try {
                    barrier.await(5, TimeUnit.SECONDS)
                    repeat(10_000) {
                        assertSame(expected, registry.resourceFor("a"))
                        assertSame(expected.database, registry.databaseFor("a"))
                        assertSame(expected.dataSource, registry.dataSourceFor("a"))
                    }
                } catch (failure: Throwable) {
                    errors += failure
                    throw failure
                }
            }
        }
        try {
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
            assertTrue(errors.isEmpty(), errors.joinToString { it.stackTraceToString() })
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            registry.close()
        }
    }

    private fun registryWithHooks(
        dispose: () -> Unit,
        beforeCloseWait: () -> Unit = {},
    ): TenantJdbcResourceRegistry<String> {
        val source = dataSource("concurrency-${System.nanoTime()}")
        return TenantJdbcResourceRegistry.createForTesting(
            tenants = listOf("a"),
            dataSourceFactory = { source },
            disposeDataSource = { _, _ -> dispose() },
            hooks = TenantJdbcRegistryHooks(
                connectDatabase = { Database.connect(it) },
                unregisterDatabase = { TransactionManager.closeAndUnregister(it) },
                beforeCloseWait = beforeCloseWait,
            ),
        )
    }

    private class BlockingHashKey(private val value: String) {
        private val nextGate = AtomicReference<HashGate?>()

        fun blockNextHash(): HashGate = HashGate().also {
            check(nextGate.compareAndSet(null, it))
        }

        override fun hashCode(): Int {
            nextGate.getAndSet(null)?.let { gate ->
                gate.entered.countDown()
                check(gate.release.await(5, TimeUnit.SECONDS)) { "hash gate release timed out" }
            }
            return value.hashCode()
        }

        override fun equals(other: Any?): Boolean = other is BlockingHashKey && value == other.value
    }

    private class HashGate(
        val entered: CountDownLatch = CountDownLatch(1),
        val release: CountDownLatch = CountDownLatch(1),
    )

    private fun catchThrowable(block: () -> Unit): Throwable =
        try {
            block()
            throw AssertionError("Expected a failure")
        } catch (failure: Throwable) {
            failure
        }
}
