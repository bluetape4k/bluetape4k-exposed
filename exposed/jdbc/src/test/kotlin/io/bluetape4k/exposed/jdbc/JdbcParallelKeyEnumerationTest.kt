package io.bluetape4k.exposed.jdbc

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.exposed.tests.AbstractExposedTest
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withTables
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class JdbcParallelKeyEnumerationTest: AbstractExposedTest() {

    private object EnumerationTable: LongIdTable("jdbc_parallel_key_enumeration") {
        val name = varchar("name", 64)
    }

    @Test
    fun `disjoint ranges are merged in range order without duplicates`() {
        withTables(TestDB.H2, EnumerationTable) {
            repeat(8) { index ->
                EnumerationTable.insert { it[name] = "user-$index" }
            }
            commit()

            parallelJdbcKeyEnumeration(
                table = EnumerationTable,
                ranges = listOf(
                    JdbcKeyRange(upperExclusive = 5L),
                    JdbcKeyRange(lowerInclusive = 5L),
                ),
                options = JdbcParallelKeyEnumerationOptions(maxConcurrency = 2),
            ) shouldBeEqualTo (1L..8L).toList()
        }
    }

    @Test
    fun `sparse IDs and open outer bounds are included once`() {
        withTables(TestDB.H2, EnumerationTable) {
            repeat(6) { index ->
                EnumerationTable.insert { it[name] = "user-$index" }
            }
            EnumerationTable.deleteWhere { EnumerationTable.id eq 2L }
            commit()

            val ids =
                parallelJdbcKeyEnumeration(
                    table = EnumerationTable,
                    ranges = listOf(
                        JdbcKeyRange(upperExclusive = 4L),
                        JdbcKeyRange(lowerInclusive = 4L),
                    ),
                )

            ids shouldBeEqualTo listOf(1L, 3L, 4L, 5L, 6L)
            (ids.size == ids.distinct().size).shouldBeTrue()
        }
    }

    @Test
    fun `overlap and reverse ranges are rejected before a transaction`() {
        withTables(TestDB.H2, EnumerationTable) {
            commit()

            assertFailsWith<IllegalArgumentException> {
                parallelJdbcKeyEnumeration(
                    table = EnumerationTable,
                    ranges = listOf(
                        JdbcKeyRange(upperExclusive = 5L),
                        JdbcKeyRange(lowerInclusive = 4L),
                    ),
                )
            }
            assertFailsWith<IllegalArgumentException> {
                parallelJdbcKeyEnumeration(
                    table = EnumerationTable,
                    ranges = listOf(JdbcKeyRange(lowerInclusive = 5L, upperExclusive = 4L)),
                )
            }
        }
    }

    @Test
    fun `custom comparator is honored for range validation`() {
        withTables(TestDB.H2, EnumerationTable) {
            commit()
            val reverse = compareByDescending<Long> { it }

            assertFailsWith<IllegalArgumentException> {
                parallelJdbcKeyEnumeration(
                    table = EnumerationTable,
                    ranges = listOf(JdbcKeyRange(lowerInclusive = 1L, upperExclusive = 4L)),
                    options = JdbcParallelKeyEnumerationOptions(comparator = reverse),
                )
            }
        }
    }

    @Test
    fun `non-positive maxConcurrency is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            JdbcParallelKeyEnumerationOptions<Long>(maxConcurrency = 0)
        }
    }

    @Test
    fun `active transactions never exceed maxConcurrency`() {
        withTables(TestDB.H2, EnumerationTable) {
            commit()
            val active = AtomicInteger(0)
            val maximum = AtomicInteger(0)
            val executor = Executors.newVirtualThreadPerTaskExecutor()
            try {
                val ids =
                    parallelJdbcKeyEnumeration(
                        table = EnumerationTable,
                        ranges = List(6) { index -> JdbcKeyRange(index.toLong(), (index + 1).toLong()) },
                        options = JdbcParallelKeyEnumerationOptions(
                            maxConcurrency = 2,
                            executor = executor,
                        ),
                    ) { _, range ->
                        val current = active.incrementAndGet()
                        maximum.updateAndGet { previous -> maxOf(previous, current) }
                        try {
                            Thread.sleep(20)
                            listOf(requireNotNull(range.lowerInclusive))
                        } finally {
                            active.decrementAndGet()
                        }
                    }

                ids shouldBeEqualTo (0L..5L).toList()
                maximum.get() shouldBeEqualTo 2
            } finally {
                executor.close()
            }
        }
    }

    @Test
    fun `failed range cancels siblings and preserves the cause`() {
        withTables(TestDB.H2, EnumerationTable) {
            commit()
            val started = CountDownLatch(2)
            val interrupted = AtomicBoolean(false)
            val expected = IllegalStateException("range failure")
            val executor = Executors.newVirtualThreadPerTaskExecutor()
            try {
                val failure =
                    runCatching {
                        parallelJdbcKeyEnumeration(
                            table = EnumerationTable,
                            ranges = listOf(
                                JdbcKeyRange(upperExclusive = 1L),
                                JdbcKeyRange(lowerInclusive = 1L),
                            ),
                            options = JdbcParallelKeyEnumerationOptions(
                                maxConcurrency = 2,
                                executor = executor,
                            ),
                        ) { _, range ->
                            started.countDown()
                            if (range.upperExclusive == 1L) {
                                check(started.await(2, TimeUnit.SECONDS))
                                throw expected
                            }
                            try {
                                Thread.sleep(10_000)
                                emptyList()
                            } catch (cause: InterruptedException) {
                                interrupted.set(true)
                                throw cause
                            }
                        }
                    }.exceptionOrNull()

                failure shouldBeEqualTo expected
                interrupted.get().shouldBeTrue()
            } finally {
                executor.close()
            }
        }
    }

    @Test
    @Suppress("LongMethod")
    fun `failed range joins interrupt-ignoring siblings before returning`() {
        withTables(TestDB.H2, EnumerationTable) {
            commit()
            val started = CountDownLatch(2)
            val interruptObserved = CountDownLatch(1)
            val allFinished = CountDownLatch(1)
            val activeChildren = AtomicInteger(0)
            val expected = IllegalStateException("range failure")
            val executor = Executors.newVirtualThreadPerTaskExecutor()
            try {
                val failure =
                    runCatching {
                        parallelJdbcKeyEnumeration(
                            table = EnumerationTable,
                            ranges = listOf(
                                JdbcKeyRange(upperExclusive = 1L),
                                JdbcKeyRange(lowerInclusive = 1L, upperExclusive = 2L),
                                JdbcKeyRange(lowerInclusive = 2L),
                            ),
                            options = JdbcParallelKeyEnumerationOptions(
                                maxConcurrency = 2,
                                executor = executor,
                            ),
                        ) { _, range ->
                            activeChildren.incrementAndGet()
                            started.countDown()
                            try {
                                if (range.upperExclusive == 1L) {
                                    check(started.await(2, TimeUnit.SECONDS))
                                    throw expected
                                }

                                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250)
                                while (System.nanoTime() < deadline) {
                                    try {
                                        Thread.sleep(10)
                                    } catch (_: InterruptedException) {
                                        interruptObserved.countDown()
                                    }
                                }
                                emptyList()
                            } finally {
                                if (activeChildren.decrementAndGet() == 0) {
                                    allFinished.countDown()
                                }
                            }
                        }
                    }.exceptionOrNull()

                failure shouldBeEqualTo expected
                check(interruptObserved.await(2, TimeUnit.SECONDS)) {
                    "the sibling must observe the cancellation interrupt"
                }
                activeChildren.get() shouldBeEqualTo 0
                allFinished.count shouldBeEqualTo 0
                executor.isShutdown shouldBeEqualTo false
            } finally {
                check(allFinished.await(2, TimeUnit.SECONDS)) {
                    "interrupt-ignoring siblings must eventually finish"
                }
                executor.close()
            }
        }
    }

    @Test
    @Suppress("LongMethod")
    fun `caller interrupt joins interrupt-ignoring children before returning`() {
        withTables(TestDB.H2, EnumerationTable) {
            commit()
            val database = checkNotNull(TestDB.H2.db)
            val started = CountDownLatch(2)
            val interruptObserved = CountDownLatch(1)
            val allFinished = CountDownLatch(1)
            val callerFinished = CountDownLatch(1)
            val activeChildren = AtomicInteger(0)
            val callerFailure = AtomicReference<Throwable?>()
            val callerWasInterrupted = AtomicBoolean(false)
            val childExecutor = Executors.newVirtualThreadPerTaskExecutor()
            val caller = Thread.ofPlatform().start {
                try {
                    parallelJdbcKeyEnumeration(
                        table = EnumerationTable,
                        ranges = listOf(
                            JdbcKeyRange(upperExclusive = 1L),
                            JdbcKeyRange(lowerInclusive = 1L, upperExclusive = 2L),
                            JdbcKeyRange(lowerInclusive = 2L),
                        ),
                        options = JdbcParallelKeyEnumerationOptions(
                            maxConcurrency = 2,
                            executor = childExecutor,
                            database = database,
                        ),
                    ) { _, _ ->
                        activeChildren.incrementAndGet()
                        started.countDown()
                        try {
                            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250)
                            while (System.nanoTime() < deadline) {
                                try {
                                    Thread.sleep(10)
                                } catch (_: InterruptedException) {
                                    interruptObserved.countDown()
                                }
                            }
                            emptyList()
                        } finally {
                            if (activeChildren.decrementAndGet() == 0) {
                                allFinished.countDown()
                            }
                        }
                    }
                    error("caller interrupt must abort enumeration")
                } catch (cause: Throwable) {
                    callerFailure.set(cause)
                    callerWasInterrupted.set(Thread.currentThread().isInterrupted)
                } finally {
                    callerFinished.countDown()
                }
            }

            try {
                check(started.await(2, TimeUnit.SECONDS)) {
                    "two child transactions must start before interrupting the caller"
                }
                caller.interrupt()
                check(callerFinished.await(2, TimeUnit.SECONDS)) {
                    "caller must finish after child cleanup"
                }
                check(interruptObserved.await(2, TimeUnit.SECONDS)) {
                    "children must observe the caller cancellation interrupt"
                }
                (callerFailure.get() is InterruptedException).shouldBeTrue()
                callerWasInterrupted.get().shouldBeTrue()
                activeChildren.get() shouldBeEqualTo 0
                allFinished.count shouldBeEqualTo 0
            } finally {
                caller.interrupt()
                caller.join(TimeUnit.SECONDS.toMillis(2))
                check(allFinished.await(2, TimeUnit.SECONDS)) {
                    "interrupt-ignoring children must eventually finish"
                }
                childExecutor.close()
            }
        }
    }

    @Test
    fun `shutdown executor is rejected without opening a transaction`() {
        withTables(TestDB.H2, EnumerationTable) {
            commit()
            val executor = Executors.newVirtualThreadPerTaskExecutor()
            executor.close()

            assertFailsWith<IllegalArgumentException> {
                parallelJdbcKeyEnumeration(
                    table = EnumerationTable,
                    ranges = listOf(JdbcKeyRange(upperExclusive = 1L)),
                    options = JdbcParallelKeyEnumerationOptions(executor = executor),
                )
            }
        }
    }
}
