@file:Suppress("TooGenericExceptionCaught")

package io.bluetape4k.exposed.tenant.jdbc

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

class TenantJdbcResourceRegistryFailureTest {

    private val fixtures = mutableListOf<FailureFixture>()

    @AfterEach
    fun releaseExposedRegistrationsAndInterruptFlag() {
        fixtures.forEach(FailureFixture::forceUnregister)
        Thread.interrupted()
    }

    @Test
    fun `connect 실패는 현재 datasource와 이전 resource를 역순 정리한다`() {
        val connectFailure = IllegalStateException("connect-secret")
        val fixture = failureFixture()

        val failure = catchThrowable {
            fixture.create(
                tenants = listOf("a", "b"),
                connectFailureFor = "b" to connectFailure,
            )
        }

        assertSame(connectFailure, failure)
        assertEquals(
            listOf("create:a", "connect:a", "create:b", "connect:b", "dispose:b", "unregister:a", "dispose:a"),
            fixture.events,
        )
    }

    @Test
    fun `factory 반환 전 실패는 현재 datasource를 정리하지 않는다`() {
        val fixture = failureFixture()
        val factoryFailure = IllegalStateException("factory-failure")

        val failure = catchThrowable {
            fixture.create(
                tenants = listOf("a", "b"),
                factoryFailureFor = "b" to factoryFailure,
            )
        }

        assertSame(factoryFailure, failure)
        assertEquals(listOf("create:a", "connect:a", "create:b", "unregister:a", "dispose:a"), fixture.events)
    }

    @Test
    fun `resource commit과 registry publish 실패는 인수한 resource를 역순 정리한다`() {
        val commitFixture = failureFixture()
        val commitFailure = IllegalStateException("commit-failure")
        val commitCalls = AtomicInteger()

        val observedCommitFailure = catchThrowable {
            commitFixture.create(
                tenants = listOf("a", "b"),
                extraHooks = TenantJdbcRegistryHooks(
                    connectDatabase = commitFixture::connect,
                    unregisterDatabase = commitFixture::unregister,
                    beforeResourceCommit = {
                        if (commitCalls.incrementAndGet() == 2) throw commitFailure
                    },
                ),
            )
        }
        assertSame(commitFailure, observedCommitFailure)
        assertEquals(
            listOf(
                "create:a", "connect:a", "create:b", "connect:b",
                "unregister:b", "dispose:b", "unregister:a", "dispose:a",
            ),
            commitFixture.events,
        )

        val publishFixture = failureFixture()
        val publishFailure = IllegalStateException("publish-failure")
        val observedPublishFailure = catchThrowable {
            publishFixture.create(
                tenants = listOf("a", "b"),
                extraHooks = TenantJdbcRegistryHooks(
                    connectDatabase = publishFixture::connect,
                    unregisterDatabase = publishFixture::unregister,
                    beforeRegistryPublish = { throw publishFailure },
                ),
            )
        }
        assertSame(publishFailure, observedPublishFailure)
        assertEquals(
            listOf(
                "create:a", "connect:a", "create:b", "connect:b",
                "unregister:b", "dispose:b", "unregister:a", "dispose:a",
            ),
            publishFixture.events,
        )
    }

    @Test
    fun `close는 ordinary failure identity와 suppressed 발생 순서를 보존한다`() {
        val fixture = failureFixture()
        val unregisterB = IllegalStateException("unregister-b")
        val disposeB = IllegalArgumentException("dispose-b")
        val unregisterA = UnsupportedOperationException("unregister-a")
        val disposeA = CancellationException("dispose-a")
        val registry = fixture.create(listOf("a", "b"))
        fixture.unregisterFailures["b"] = unregisterB
        fixture.disposeFailures["b"] = disposeB
        fixture.unregisterFailures["a"] = unregisterA
        fixture.disposeFailures["a"] = disposeA

        val first = catchThrowable { registry.close() }
        val second = catchThrowable { registry.close() }

        assertSame(unregisterB, first)
        assertSame(first, second)
        assertEquals(listOf(disposeB, unregisterA, disposeA), first.suppressed.toList())
        assertEquals(1, fixture.disposeCalls.getValue("a").get())
        assertEquals(1, fixture.disposeCalls.getValue("b").get())
    }

    @Test
    fun `cleanup fatal은 primary로 승격되고 동일 throwable은 self suppression에서 제외된다`() {
        val fixture = failureFixture()
        val ordinary = IllegalStateException("ordinary")
        val fatal = LinkageError("fatal")
        val registry = fixture.create(listOf("a", "b"))
        fixture.unregisterFailures["b"] = ordinary
        fixture.disposeFailures["b"] = fatal
        fixture.unregisterFailures["a"] = fatal

        val observed = catchThrowable { registry.close() }

        assertSame(fatal, observed)
        assertEquals(listOf(ordinary), observed.suppressed.toList())
        assertEquals(1, fixture.disposeCalls.getValue("a").get())
        assertEquals(1, fixture.disposeCalls.getValue("b").get())
        val later = assertThrows(IllegalStateException::class.java) { registry.close() }
        assertEquals("Tenant JDBC resource registry closed after a fatal cleanup failure.", later.message)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `fatal assembly failure 종류는 원형을 유지하고 best effort cleanup을 수행한다`() {
        val fatalFailures = listOf<Throwable>(
            object : VirtualMachineError("vm") {},
            ThreadDeath(),
            LinkageError("linkage"),
        )

        fatalFailures.forEachIndexed { index, fatal ->
            val fixture = failureFixture()
            val observed = catchThrowable {
                fixture.create(
                    tenants = listOf("a", "b"),
                    connectFailureFor = "b" to fatal,
                )
            }
            assertSame(fatal, observed, "fatal[$index]")
            assertTrue("unregister:a" in fixture.events, "fatal[$index]")
            assertTrue("dispose:a" in fixture.events, "fatal[$index]")
        }
    }

    @Test
    fun `InterruptedException은 construction과 close 경로에서 interrupt flag를 복원한다`() {
        assertFalse(Thread.interrupted())
        val constructionFixture = failureFixture()
        val constructionInterrupt = InterruptedException("construction")
        val constructionFailure = catchThrowable {
            constructionFixture.create(
                tenants = listOf("a"),
                extraHooks = TenantJdbcRegistryHooks(
                    connectDatabase = constructionFixture::connect,
                    unregisterDatabase = constructionFixture::unregister,
                    beforeRegistryPublish = { throw constructionInterrupt },
                ),
            )
        }
        assertSame(constructionInterrupt, constructionFailure)
        assertTrue(Thread.currentThread().isInterrupted)
        assertTrue(Thread.interrupted())

        val closeFixture = failureFixture()
        val closeInterrupt = InterruptedException("close")
        val registry = closeFixture.create(listOf("a"))
        closeFixture.unregisterFailures["a"] = closeInterrupt
        val closeFailure = catchThrowable { registry.close() }
        assertSame(closeInterrupt, closeFailure)
        assertTrue(Thread.currentThread().isInterrupted)
        assertTrue(Thread.interrupted())
    }

    @Test
    fun `callback secret은 provider log에 복제되지 않는다`() {
        val marker = "callback-secret-marker"
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        root.addAppender(appender)
        val fixture = failureFixture()
        val registry = fixture.create(listOf("a"))
        fixture.disposeFailures["a"] = IllegalStateException(marker)
        try {
            val failure = catchThrowable { registry.close() }
            assertEquals(marker, failure.message)
            assertTrue(appender.list.none { marker in it.formattedMessage })
            assertTrue(appender.list.none { it.throwableProxy?.message?.contains(marker) == true })
        } finally {
            root.detachAppender(appender)
            appender.stop()
        }
    }

    private fun failureFixture(): FailureFixture = FailureFixture().also(fixtures::add)

    private class FailureFixture {
        val events = mutableListOf<String>()
        val unregisterFailures = mutableMapOf<String, Throwable>()
        val disposeFailures = mutableMapOf<String, Throwable>()
        val disposeCalls = mutableMapOf<String, AtomicInteger>()
        private val tenantByDataSource = IdentityHashMap<DataSource, String>()
        private val tenantByDatabase = IdentityHashMap<Database, String>()

        @Suppress("ThrowsCount")
        fun create(
            tenants: List<String>,
            factoryFailureFor: Pair<String, Throwable>? = null,
            connectFailureFor: Pair<String, Throwable>? = null,
            extraHooks: TenantJdbcRegistryHooks? = null,
        ): TenantJdbcResourceRegistry<String> {
            val hooks = extraHooks ?: TenantJdbcRegistryHooks(
                connectDatabase = { source ->
                    val tenant = tenantByDataSource.getValue(source)
                    if (connectFailureFor?.first == tenant) {
                        events += "connect:$tenant"
                        throw connectFailureFor.second
                    }
                    connect(source)
                },
                unregisterDatabase = ::unregister,
            )
            return TenantJdbcResourceRegistry.createForTesting(
                tenants = tenants,
                dataSourceFactory = { tenant ->
                    events += "create:$tenant"
                    if (factoryFailureFor?.first == tenant) throw factoryFailureFor.second
                    dataSource("failure-$tenant-${System.nanoTime()}").also { tenantByDataSource[it] = tenant }
                },
                disposeDataSource = { tenant, _ ->
                    events += "dispose:$tenant"
                    disposeCalls.getOrPut(tenant) { AtomicInteger() }.incrementAndGet()
                    disposeFailures[tenant]?.let { throw it }
                },
                hooks = hooks,
            )
        }

        fun connect(source: DataSource): Database {
            val tenant = tenantByDataSource.getValue(source)
            events += "connect:$tenant"
            return Database.connect(source).also { tenantByDatabase[it] = tenant }
        }

        fun unregister(database: Database) {
            val tenant = tenantByDatabase.getValue(database)
            events += "unregister:$tenant"
            unregisterFailures[tenant]?.let { throw it }
            TransactionManager.closeAndUnregister(database)
        }

        fun forceUnregister() {
            tenantByDatabase.keys.toList().forEach { database ->
                runCatching { TransactionManager.closeAndUnregister(database) }
            }
        }
    }

    private fun catchThrowable(block: () -> Unit): Throwable =
        try {
            block()
            throw AssertionError("Expected a failure")
        } catch (failure: Throwable) {
            failure
        }
}
