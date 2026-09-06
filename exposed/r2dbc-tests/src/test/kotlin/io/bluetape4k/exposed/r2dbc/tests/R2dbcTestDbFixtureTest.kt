package io.bluetape4k.exposed.r2dbc.tests

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.junit5.coroutines.runSuspendIO
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabaseConfig
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.junit.jupiter.api.Test
import kotlinx.coroutines.async

class R2dbcTestDbFixtureTest {
    @Test
    fun `schema 미지원 dialect에서는 fixture 본문을 실행하지 않는다`() = runSuspendIO {
        val unsupported = object: org.jetbrains.exposed.v1.core.vendors.H2Dialect() {
            override val supportsCreateSchema: Boolean = false
        }
        val fixture = r2dbcTestDbFixture("unsupported", { configure ->
            database { configure(); explicitDialect = unsupported }
        })
        var executed = false
        withSchemas(fixture, org.jetbrains.exposed.v1.core.Schema("must_not_create")) { executed = true }
        executed shouldBeEqualTo false
    }

    @Test
    fun `일시 구성 본문이 실제 취소되어도 등록과 기본 연결을 복원한다`() = runSuspendIO {
        val fixture = r2dbcTestDbFixture("temporary-cancel", { database(it) })
        var temporary: R2dbcDatabase? = null
        kotlinx.coroutines.coroutineScope {
            val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
            val job = async {
                withDb(fixture, configure = {}) {
                    temporary = db
                    entered.complete(Unit)
                    kotlinx.coroutines.awaitCancellation()
                }
            }
            entered.await()
            job.cancel()
            assertFailsWith<java.util.concurrent.CancellationException> { job.await() }
        }
        assertFailsWith<IllegalStateException> { TransactionManager.managerFor(checkNotNull(temporary)) }
        withDb(fixture) { db shouldBeSameInstanceAs fixture.database }
    }

    @Test
    fun `종료 callback은 fixture별 한 번 등록하고 초기화 중 실행하지 않는다`() = runSuspendIO {
        val hooks = mutableListOf<() -> Unit>()
        val calls = mutableListOf<Int>()
        val first = R2dbcTestDbFixture(1, { database(it) }, {
            calls.add(1)
            throw IllegalStateException("shutdown")
        }, hooks::add)
        val second = R2dbcTestDbFixture(2, { database(it) }, { calls.add(2) }, hooks::add)
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
    fun `provider 로그는 key와 callback 예외의 민감값을 출력하지 않는다`() = runSuspendIO {
        val sentinel = "fixture-sensitive-sentinel"
        val urlSentinel = "fixture_url_secret"
        val configSentinel = 918273
        val key = object { override fun toString(): String = error(sentinel) }
        val logger = org.slf4j.LoggerFactory
            .getLogger("io.bluetape4k.exposed.r2dbc.tests") as ch.qos.logback.classic.Logger
        val appender = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        try {
            val fixture = r2dbcTestDbFixture(key, { configure ->
                R2dbcDatabase.connect(databaseConfig = R2dbcDatabaseConfig {
                    setUrl("r2dbc:h2:mem:///$urlSentinel;DB_CLOSE_DELAY=-1;")
                    configure()
                })
            })
            withDb(fixture, configure = { defaultFetchSize = configSentinel }) {}
            val broken = r2dbcTestDbFixture(key, { throw IllegalArgumentException(sentinel) })
            assertFailsWith<IllegalArgumentException> { withDb(broken) {} }
            appender.list.isNotEmpty() shouldBeEqualTo true
            val secrets = listOf(sentinel, urlSentinel, configSentinel.toString())
            val leaked = appender.list.any { event ->
                secrets.any(event.formattedMessage::contains) || event.throwableProxy != null
            }
            leaked shouldBeEqualTo false
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    @Test
    fun `legacy 첫 일시 구성 실패도 기본 db 참조를 남긴다`() = runSuspendIO {
        assertFailsWith<IllegalArgumentException> {
            withDb(TestDB.H2_MYSQL, configure = { throw IllegalArgumentException("configure sentinel") }) {}
        }
        TestDB.H2_MYSQL.db shouldBeSameInstanceAs r2dbcFixtureFor(TestDB.H2_MYSQL).database
    }

    // 추가 상태를 가진 예외로 coroutine debug stacktrace 복제와 provider 예외 교체를 구분한다.
    private class CallbackFailure(val marker: Int): IllegalStateException("r2dbc callback sentinel")

    private fun database(configure: DatabaseConfig.Builder.() -> Unit): R2dbcDatabase =
        R2dbcDatabase.connect(databaseConfig = R2dbcDatabaseConfig {
            setUrl("r2dbc:h2:mem:///r2dbc_fixture_contract;DB_CLOSE_DELAY=-1;")
            configure()
        })

    @Test
    fun `첫 일시 구성은 기본 wrapper와 분리되고 등록이 해제된다`() = runSuspendIO {
        var creates = 0
        val fixture = r2dbcTestDbFixture("custom", { config -> creates++; database(config) })
        fixture.database.shouldBeNull()
        var temporary: R2dbcDatabase? = null
        withDb(fixture, configure = { defaultFetchSize = 17 }) {
            it shouldBeEqualTo "custom"
            currentR2dbcTestDbFixture shouldBeSameInstanceAs fixture
            temporary = db
            commit()
            currentR2dbcTestDbFixture shouldBeSameInstanceAs fixture
        }
        creates shouldBeEqualTo 2
        assertFailsWith<IllegalStateException> { TransactionManager.managerFor(checkNotNull(temporary)) }
        withDb(fixture) { db shouldBeSameInstanceAs fixture.database }
        creates shouldBeEqualTo 2
    }

    @Test
    fun `생성과 종료 등록 실패 다음 호출은 초기화를 재시도한다`() = runSuspendIO {
        var creates = 0
        var registrations = 0
        val failure = CallbackFailure(1)
        val fixture = R2dbcTestDbFixture("retry", {
            if (++creates == 1) throw failure
            database(it)
        }, {}, {
            if (++registrations == 1) throw failure
        })
        repeat(2) {
            assertFailsWith<IllegalStateException> { withDb(fixture) {} } shouldBeSameInstanceAs failure
            fixture.database.shouldBeNull()
        }
        withDb(fixture) {}
        withDb(fixture) {}
        creates shouldBeEqualTo 3
        registrations shouldBeEqualTo 2
    }

    @Test
    fun `일시 wrapper에 기본 인스턴스를 반환하면 기본 연결을 유지한다`() = runSuspendIO {
        val shared = database({})
        val fixture = r2dbcTestDbFixture("same", { shared })
        withDb(fixture) {}
        assertFailsWith<IllegalArgumentException> { withDb(fixture, configure = {}) {} }
        withDb(fixture) { db shouldBeSameInstanceAs shared }
    }
}
