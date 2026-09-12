@file:Suppress("TooGenericExceptionCaught")

package io.bluetape4k.exposed.tenant.jdbc

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.bluetape4k.assertions.shouldBe
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.logging.KLogging
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource
import kotlin.test.assertFailsWith

class TenantJdbcResourceRegistryFailureTest {

    companion object: KLogging()

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

        failure shouldBe connectFailure
        fixture.events shouldBeEqualTo listOf(
            "create:a",
            "connect:a",
            "create:b",
            "connect:b",
            "dispose:b",
            "unregister:a",
            "dispose:a"
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

        failure shouldBe factoryFailure
        fixture.events shouldBeEqualTo listOf(
            "create:a",
            "connect:a",
            "create:b",
            "unregister:a",
            "dispose:a"
        )
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
        observedCommitFailure shouldBe commitFailure
        commitFixture.events shouldBeEqualTo listOf(
            "create:a",
            "connect:a",
            "create:b",
            "connect:b",
            "unregister:b",
            "dispose:b",
            "unregister:a",
            "dispose:a",
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

        observedPublishFailure shouldBe publishFailure
        publishFixture.events shouldBeEqualTo listOf(
            "create:a",
            "connect:a",
            "create:b",
            "connect:b",
            "unregister:b",
            "dispose:b",
            "unregister:a",
            "dispose:a",
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

        first shouldBe unregisterB
        second shouldBe first
        first.suppressed.toList() shouldBeEqualTo listOf(disposeB, unregisterA, disposeA)
        fixture.disposeCalls.getValue("a").get() shouldBeEqualTo 1
        fixture.disposeCalls.getValue("b").get() shouldBeEqualTo 1
    }

    @Test
    fun `많은 cleanup failure도 기존 suppressed identity와 발생 순서를 보존한다`() {
        val fixture = failureFixture()
        val tenants = (0 until 128).map { "tenant-$it" }
        val cleanupFailures = tenants.asReversed().flatMap { tenant ->
            val unregister = IllegalStateException("unregister-$tenant")
            val dispose = IllegalArgumentException("dispose-$tenant")
            fixture.unregisterFailures[tenant] = unregister
            fixture.disposeFailures[tenant] = dispose
            listOf(unregister, dispose)
        }
        val primary = cleanupFailures.first()
        val alreadySuppressed = cleanupFailures[100]
        primary.addSuppressed(alreadySuppressed)
        val registry = fixture.create(tenants)

        val observed = catchThrowable { registry.close() }

        observed shouldBe primary
        observed.suppressed.toList() shouldBeEqualTo listOf(alreadySuppressed) + cleanupFailures.drop(1)
            .filterNot { it === alreadySuppressed }
        fixture.disposeCalls.size shouldBeEqualTo 128
        fixture.disposeCalls.values.all { it.get() == 1 }.shouldBeTrue()
    }

    @Test
    fun `cleanup fatal은 primary로 승격되고 동일 throwable은 self suppression에서 제외된다`() {
        val fixture = failureFixture()
        val ordinary = IllegalStateException("ordinary")
        val fatal = LinkageError("fatal")
        val registry = fixture.create(listOf("a", "b"))
        val databaseA = registry.databaseFor("a")
        fixture.unregisterFailures["b"] = ordinary
        fixture.disposeFailures["b"] = fatal
        fixture.unregisterFailuresAfter["a"] = fatal

        val observed = catchThrowable { registry.close() }

        observed shouldBe fatal
        observed.suppressed.toList() shouldBeEqualTo listOf(ordinary)
        fixture.disposeCalls.getValue("a").get() shouldBeEqualTo 1
        fixture.disposeCalls.getValue("b").get() shouldBeEqualTo 1

        assertFailsWith<IllegalStateException> {
            TransactionManager.managerFor(databaseA)
        }

        val state = registry.javaClass.getDeclaredField("state")
            .run {
                isAccessible = true
                (get(registry) as AtomicReference<*>).get()
            }
        state::class.java.declaredFields.any { Throwable::class.java.isAssignableFrom(it.type) }.shouldBeFalse()

        val later = assertFailsWith<IllegalStateException> {
            registry.close()
        }
        later.message shouldBeEqualTo "Tenant JDBC resource registry closed after a fatal cleanup failure."
    }

    @Test
    @Suppress("DEPRECATION")
    fun `fatal assembly failure 종류는 원형을 유지하고 best effort cleanup을 수행한다`() {
        val fatalFailures = listOf<Throwable>(
            object: VirtualMachineError("vm") {},
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

            observed shouldBe fatal
            fixture.events shouldContain "unregister:a"
            fixture.events shouldContain "dispose:a"
        }
    }

    @Test
    fun `InterruptedException은 construction과 close 경로에서 interrupt flag를 복원한다`() {
        Thread.interrupted().shouldBeFalse()

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
        constructionFailure shouldBe constructionInterrupt
        Thread.currentThread().isInterrupted.shouldBeTrue()
        Thread.interrupted().shouldBeTrue()

        val closeFixture = failureFixture()
        val closeInterrupt = InterruptedException("close")
        val registry = closeFixture.create(listOf("a"))
        closeFixture.unregisterFailures["a"] = closeInterrupt
        val closeFailure = catchThrowable { registry.close() }

        closeFailure shouldBe closeInterrupt
        Thread.currentThread().isInterrupted.shouldBeTrue()
        Thread.interrupted().shouldBeTrue()
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
            failure.message shouldBeEqualTo marker
            appender.list.none { marker in it.formattedMessage }.shouldBeTrue()
            appender.list.none { it.throwableProxy?.message?.contains(marker) == true }.shouldBeTrue()
        } finally {
            root.detachAppender(appender)
            appender.stop()
        }
    }

    private fun failureFixture(): FailureFixture = FailureFixture().also(fixtures::add)

    private class FailureFixture {
        val events = mutableListOf<String>()
        val unregisterFailures = mutableMapOf<String, Throwable>()
        val unregisterFailuresAfter = mutableMapOf<String, Throwable>()
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
            unregisterFailuresAfter[tenant]?.let { throw it }
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
