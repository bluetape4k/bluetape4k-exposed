package io.bluetape4k.exposed.tests

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.Test

class JdbcFixtureFailureInjectionTest {
    // coroutine debug의 예외 복제 대신 최초 실패 인스턴스의 보존 여부를 검사한다.
    private class FixtureFailure(val phase: String): RuntimeException(phase)

    private fun database(configure: DatabaseConfig.Builder.() -> Unit): Database =
        Database.connect(
            "jdbc:h2:mem:jdbc_fixture_failure;DB_CLOSE_DELAY=-1",
            "org.h2.Driver",
            databaseConfig = DatabaseConfig { configure() },
        )

    @Test
    fun `본문과 unregister가 함께 실패해도 최초 실패와 기본 연결을 보존한다`() {
        val fixture = jdbcTestDbFixture("body-cleanup", { database(it) })
        val primary = FixtureFailure("body")
        val cleanup = FixtureFailure("unregister")
        var temporary: Database? = null
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
            fixture.semaphore.availablePermits() shouldBeEqualTo 1
            withDb(fixture) { db shouldBeSameInstanceAs fixture.database }
        } finally {
            unmockkObject(TransactionManager.Companion)
            temporary?.let { TransactionManager.closeAndUnregister(it) }
        }
    }

    @Test
    fun `본문 성공 뒤 unregister 실패는 호출자에게 전파하고 permit을 반환한다`() {
        val fixture = jdbcTestDbFixture("cleanup-only", { database(it) })
        val cleanup = FixtureFailure("unregister")
        var temporary: Database? = null
        withDb(fixture) {}
        mockkObject(TransactionManager.Companion)
        try {
            every { TransactionManager.closeAndUnregister(any()) } throws cleanup
            assertFailsWith<FixtureFailure> {
                withDb(fixture, configure = {}) { temporary = db }
            } shouldBeSameInstanceAs cleanup
            fixture.semaphore.availablePermits() shouldBeEqualTo 1
            withDb(fixture) { db shouldBeSameInstanceAs fixture.database }
        } finally {
            unmockkObject(TransactionManager.Companion)
            temporary?.let { TransactionManager.closeAndUnregister(it) }
        }
    }

    @Test
    fun `종료 등록과 unregister 동시 실패 후에도 초기화를 재시도한다`() {
        val primary = FixtureFailure("register")
        val cleanup = FixtureFailure("unregister")
        var registrations = 0
        var created: Database? = null
        val fixture = JdbcTestDbFixture("registration", {
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
            fixture.semaphore.availablePermits() shouldBeEqualTo 1
        } finally {
            unmockkObject(TransactionManager.Companion)
            created?.let { TransactionManager.closeAndUnregister(it) }
        }
        withDb(fixture) {}
        registrations shouldBeEqualTo 2
    }

    @Test
    fun `legacy beforeConnection은 일시 wrapper마다 한 번 실행한다`() {
        val selected = TestDB.H2
        withDb(selected) {}
        val original = selected.beforeConnection
        var calls = 0
        val counting: () -> Unit = { calls++; original() }
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
