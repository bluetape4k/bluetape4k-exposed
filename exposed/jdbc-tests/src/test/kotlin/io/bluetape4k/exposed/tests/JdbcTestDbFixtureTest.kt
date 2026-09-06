package io.bluetape4k.exposed.tests

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.Test

class JdbcTestDbFixtureTest {
    @Test
    fun `종료 callback은 fixture별 한 번 등록하고 초기화 중 실행하지 않는다`() {
        val hooks = mutableListOf<() -> Unit>()
        val calls = mutableListOf<Int>()
        val first = JdbcTestDbFixture(1, { database(it) }, {
            calls.add(1)
            throw IllegalStateException("shutdown")
        }, hooks::add)
        val second = JdbcTestDbFixture(2, { database(it) }, { calls.add(2) }, hooks::add)
        withDb(first) {}
        withDb(first, configure = {}) {}
        withDb(second) {}
        hooks.size shouldBeEqualTo 2
        calls.isEmpty() shouldBeEqualTo true
        assertFailsWith<IllegalStateException> { hooks[0]() }
        hooks[1]()
        calls shouldBeEqualTo listOf(1, 2)
    }

    @Test
    fun `provider 로그는 key와 callback 예외의 민감값을 출력하지 않는다`() {
        val sentinel = "fixture-sensitive-sentinel"
        val key = object { override fun toString(): String = error(sentinel) }
        val logger = org.slf4j.LoggerFactory
            .getLogger("io.bluetape4k.exposed.tests") as ch.qos.logback.classic.Logger
        val appender = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        try {
            val fixture = jdbcTestDbFixture(key, { database(it) })
            withDb(fixture) {}
            val broken = jdbcTestDbFixture(key, { throw IllegalArgumentException(sentinel) })
            assertFailsWith<IllegalArgumentException> { withDb(broken) {} }
            appender.list.isNotEmpty() shouldBeEqualTo true
            val leaked = appender.list.any { it.formattedMessage.contains(sentinel) || it.throwableProxy != null }
            leaked shouldBeEqualTo false
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    @Test
    fun `legacy 첫 일시 구성 실패도 기본 db 참조를 남긴다`() {
        assertFailsWith<IllegalArgumentException> {
            withDb(TestDB.H2_MYSQL, configure = { throw IllegalArgumentException("configure sentinel") }) {}
        }
        TestDB.H2_MYSQL.db shouldBeSameInstanceAs jdbcFixtureFor(TestDB.H2_MYSQL).database
    }

    private fun database(configure: DatabaseConfig.Builder.() -> Unit = {}): Database =
        Database.connect(
            url = "jdbc:h2:mem:jdbc_fixture_contract;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
            databaseConfig = DatabaseConfig { configure() },
        )

    @Test
    fun `첫 일시 구성은 기본 wrapper와 분리되고 등록이 해제된다`() {
        var creates = 0
        val fixture = jdbcTestDbFixture("custom", { config -> creates++; database(config) })
        fixture.database.shouldBeNull()
        var temporary: Database? = null
        withDb(fixture, configure = { defaultFetchSize = 17 }) { key ->
            key shouldBeEqualTo "custom"
            currentJdbcTestDbFixture shouldBeSameInstanceAs fixture
            temporary = db
            db.config.defaultFetchSize shouldBeEqualTo 17
            commit()
            currentJdbcTestDbFixture shouldBeSameInstanceAs fixture
        }
        creates shouldBeEqualTo 2
        assertFailsWith<IllegalStateException> { TransactionManager.managerFor(checkNotNull(temporary)) }
        val baseline = fixture.database
        withDb(fixture) { db shouldBeSameInstanceAs baseline }
        creates shouldBeEqualTo 2
    }

    @Test
    fun `생성 실패 다음 호출은 초기화를 다시 시도한다`() {
        var attempts = 0
        val failure = IllegalStateException("create sentinel")
        val fixture = jdbcTestDbFixture("retry", {
            if (++attempts == 1) throw failure
            database(it)
        })
        assertFailsWith<IllegalStateException> { withDb(fixture) {} } shouldBeSameInstanceAs failure
        fixture.database.shouldBeNull()
        withDb(fixture) {}
        attempts shouldBeEqualTo 2
    }

    @Test
    fun `종료 등록 실패는 새 wrapper를 해제하고 다시 시도한다`() {
        var registrations = 0
        var created: Database? = null
        val failure = IllegalStateException("hook sentinel")
        val fixture = JdbcTestDbFixture("hook", { database(it).also { db -> created = db } }, {}, {
            if (++registrations == 1) throw failure
        })
        assertFailsWith<IllegalStateException> { withDb(fixture) {} } shouldBeSameInstanceAs failure
        fixture.database.shouldBeNull()
        assertFailsWith<IllegalStateException> { TransactionManager.managerFor(checkNotNull(created)) }
        withDb(fixture) {}
        withDb(fixture) {}
        registrations shouldBeEqualTo 2
    }

    @Test
    fun `일시 wrapper에 기본 인스턴스를 반환하면 기본 연결은 유지된다`() {
        val shared = database()
        val fixture = jdbcTestDbFixture("same", { shared })
        withDb(fixture) {}
        assertFailsWith<IllegalArgumentException> { withDb(fixture, configure = {}) {} }
        fixture.database shouldBeSameInstanceAs shared
        withDb(fixture) { db shouldBeSameInstanceAs shared }
    }

    @Test
    fun `본문 실패 뒤 기본 wrapper와 permit을 복원한다`() {
        val failure = IllegalArgumentException("body sentinel")
        val fixture = jdbcTestDbFixture("body", { database(it) })
        assertFailsWith<IllegalArgumentException> {
            withDb(fixture, configure = {}) { throw failure }
        } shouldBeSameInstanceAs failure
        withDb(fixture) { db shouldBeSameInstanceAs fixture.database }
    }
}
