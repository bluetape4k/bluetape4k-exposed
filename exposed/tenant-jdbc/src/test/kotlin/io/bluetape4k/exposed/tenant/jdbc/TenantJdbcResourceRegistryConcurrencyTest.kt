@file:Suppress("TooGenericExceptionCaught")

package io.bluetape4k.exposed.tenant.jdbc

import io.bluetape4k.assertions.shouldBe
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
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
import kotlin.test.assertFailsWith

class TenantJdbcResourceRegistryConcurrencyTest {

    companion object: KLogging()

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
                release.await(5, TimeUnit.SECONDS).shouldBeTrue()
                throw failure
            },
            beforeCloseWait = { waiterObservedClosing.countDown() },
        )

        val ownerResult = AtomicReference<Throwable?>()
        val waiterResult = AtomicReference<Throwable?>()
        val owner = thread(name = "tenant-close-owner") { ownerResult.set(catchThrowable { registry.close() }) }
        var waiter: Thread? = null

        try {
            entered.await(5, TimeUnit.SECONDS).shouldBeTrue()
            val startedWaiter = thread(name = "tenant-close-waiter") {
                waiterResult.set(catchThrowable { registry.close() })
            }

            waiter = startedWaiter
            waiterObservedClosing.await(5, TimeUnit.SECONDS).shouldBeTrue()

            release.countDown()
            owner.join(Duration.ofSeconds(5))
            startedWaiter.join(Duration.ofSeconds(5))
            ownerResult.get() shouldBe failure
            waiterResult.get() shouldBe failure
            disposeCalls.get() shouldBeEqualTo 1
        } finally {
            release.countDown()
            owner.interrupt()
            waiter?.interrupt()
            owner.join(Duration.ofSeconds(5))
            waiter?.join(Duration.ofSeconds(5))
        }
        owner.isAlive.shouldBeFalse()
        waiter.isAlive.shouldBeFalse()
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

        disposeCalls.get() shouldBeEqualTo 1
    }

    @Test
    fun `interrupt된 waiter는 owner 결과 뒤 interrupt flag를 복원한다`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val waiting = CountDownLatch(1)

        val registry = registryWithHooks(
            dispose = {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS).shouldBeTrue()
            },
            beforeCloseWait = { waiting.countDown() },
        )
        val waiterInterrupted = AtomicBoolean()
        val owner = thread(name = "tenant-interrupt-owner") { registry.close() }
        var waiter: Thread? = null

        try {
            entered.await(5, TimeUnit.SECONDS).shouldBeTrue()

            val startedWaiter = thread(name = "tenant-interrupt-waiter") {
                registry.close()
                waiterInterrupted.set(Thread.currentThread().isInterrupted)
            }
            waiter = startedWaiter
            waiting.await(5, TimeUnit.SECONDS).shouldBeTrue()

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

        owner.isAlive.shouldBeFalse()
        waiter.isAlive.shouldBeFalse()
        waiterInterrupted.get().shouldBeTrue()
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
                release.await(5, TimeUnit.SECONDS).shouldBeTrue()
                throw fatal
            },
            beforeCloseWait = { waiting.countDown() },
        )
        val ownerResult = AtomicReference<Throwable?>()
        val waiterResult = AtomicReference<Throwable?>()
        val owner = thread(name = "tenant-fatal-owner") { ownerResult.set(catchThrowable { registry.close() }) }
        var waiter: Thread? = null

        try {
            entered.await(5, TimeUnit.SECONDS).shouldBeTrue()
            val startedWaiter = thread(name = "tenant-fatal-waiter") {
                waiterResult.set(catchThrowable { registry.close() })
            }
            waiter = startedWaiter
            waiting.await(5, TimeUnit.SECONDS).shouldBeTrue()
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

        owner.isAlive.shouldBeFalse()
        waiter.isAlive.shouldBeFalse()
        ownerResult.get() shouldBe fatal

        val waiterFailure = waiterResult.get()
        waiterFailure.shouldBeInstanceOf<IllegalStateException>()
        waiterFailure.message shouldBeEqualTo "Tenant JDBC resource registry closed after a fatal cleanup failure."
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

        val lookup = thread(name = "tenant-lookup-race") {
            returned.set(registry.resourceFor(key))
        }

        try {
            gates.entered.await(5, TimeUnit.SECONDS).shouldBeTrue()
            registry.close()
            gates.release.countDown()
            lookup.join(Duration.ofSeconds(5))

            returned.get().shouldNotBeNull()
            val failure = assertFailsWith<IllegalStateException> {
                registry.resourceFor(key)
            }
            failure.message shouldBeEqualTo "Tenant JDBC resource registry is closed."
        } finally {
            gates.release.countDown()
            lookup.interrupt()
            lookup.join(Duration.ofSeconds(5))
            registry.close()
        }
        lookup.isAlive.shouldBeFalse()
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
                        registry.resourceFor("a") shouldBe expected
                        registry.databaseFor("a") shouldBe expected.database
                        registry.dataSourceFor("a") shouldBe expected.dataSource
                    }
                } catch (failure: Throwable) {
                    errors += failure
                    throw failure
                }
            }
        }

        try {
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
            errors.shouldBeEmpty()
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS).shouldBeTrue()
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
