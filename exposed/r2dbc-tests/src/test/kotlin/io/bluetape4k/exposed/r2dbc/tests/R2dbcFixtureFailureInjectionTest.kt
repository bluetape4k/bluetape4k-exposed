package io.bluetape4k.exposed.r2dbc.tests

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabaseConfig
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.junit.jupiter.api.Test

class R2dbcFixtureFailureInjectionTest {
    // coroutine debug의 예외 복제 대신 최초 실패 인스턴스의 보존 여부를 검사한다.
    private class FixtureFailure(val phase: String): RuntimeException(phase)

    private fun database(configure: DatabaseConfig.Builder.() -> Unit): R2dbcDatabase =
        R2dbcDatabase.connect(databaseConfig = R2dbcDatabaseConfig {
            setUrl("r2dbc:h2:mem:///r2dbc_fixture_failure;DB_CLOSE_DELAY=-1;")
            configure()
        })

    @Test
    fun `본문과 unregister가 함께 실패해도 최초 실패와 기본 연결을 보존한다`() = runSuspendIO {
        val fixture = r2dbcTestDbFixture("body-cleanup", { database(it) })
        val primary = FixtureFailure("body")
        val cleanup = FixtureFailure("unregister")
        var temporary: R2dbcDatabase? = null
        withDb(fixture) {}
        mockkObject(TransactionManager.Companion)
        try {
            every { TransactionManager.closeAndUnregister(any()) } throws cleanup
            val actual = assertFailsWith<FixtureFailure> {
                withDb(fixture, configure = {}) {
                    temporary = db
                    throw primary
                }
            }
            actual shouldBeSameInstanceAs primary
            actual.suppressed.toList() shouldBeEqualTo listOf(cleanup)
            fixture.semaphore.availablePermits shouldBeEqualTo 1
            withDb(fixture) { db shouldBeSameInstanceAs fixture.database }
        } finally {
            unmockkObject(TransactionManager.Companion)
            temporary?.let { TransactionManager.closeAndUnregister(it) }
        }
    }

    @Test
    fun `본문 성공 뒤 unregister 실패는 호출자에게 전파하고 permit을 반환한다`() = runSuspendIO {
        val fixture = r2dbcTestDbFixture("cleanup-only", { database(it) })
        val cleanup = FixtureFailure("unregister")
        var temporary: R2dbcDatabase? = null
        withDb(fixture) {}
        mockkObject(TransactionManager.Companion)
        try {
            every { TransactionManager.closeAndUnregister(any()) } throws cleanup
            assertFailsWith<FixtureFailure> {
                withDb(fixture, configure = {}) { temporary = db }
            } shouldBeSameInstanceAs cleanup
            fixture.semaphore.availablePermits shouldBeEqualTo 1
            withDb(fixture) { db shouldBeSameInstanceAs fixture.database }
        } finally {
            unmockkObject(TransactionManager.Companion)
            temporary?.let { TransactionManager.closeAndUnregister(it) }
        }
    }

    @Test
    fun `종료 등록과 unregister 동시 실패 후에도 초기화를 재시도한다`() = runSuspendIO {
        val primary = FixtureFailure("register")
        val cleanup = FixtureFailure("unregister")
        var registrations = 0
        var created: R2dbcDatabase? = null
        val fixture = R2dbcTestDbFixture("registration", {
            database(it).also { db -> created = db }
        }, {}, {
            if (++registrations == 1) throw primary
        })
        mockkObject(TransactionManager.Companion)
        try {
            every { TransactionManager.closeAndUnregister(any()) } throws cleanup
            val actual = assertFailsWith<FixtureFailure> { withDb(fixture) {} }
            actual shouldBeSameInstanceAs primary
            actual.suppressed.toList() shouldBeEqualTo listOf(cleanup)
            fixture.database.shouldBeNull()
            fixture.semaphore.availablePermits shouldBeEqualTo 1
        } finally {
            unmockkObject(TransactionManager.Companion)
            created?.let { TransactionManager.closeAndUnregister(it) }
        }
        withDb(fixture) {}
        registrations shouldBeEqualTo 2
    }

    @Test
    fun `legacy beforeConnection은 일시 wrapper마다 한 번 실행한다`() = runSuspendIO {
        val selected = TestDB.H2
        withDb(selected) {}
        val original = selected.beforeConnection
        var calls = 0
        val counting: suspend () -> Unit = { calls++; original() }
        // enum 내부 connect는 getter 대신 필드를 읽으므로 callback 자체를 잠시 교체한다.
        val callback = TestDB::class.java.getDeclaredField("beforeConnection").apply { isAccessible = true }
        callback.set(selected, counting)
        try {
            withDb(selected, configure = {}) {}
            withDb(selected) {}
            withDb(selected, configure = {}) {}
            calls shouldBeEqualTo 2
        } finally {
            callback.set(selected, original)
        }
    }
}
