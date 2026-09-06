package io.bluetape4k.exposed.r2dbc.tests

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.junit5.coroutines.runSuspendIO
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.Schema
import kotlinx.coroutines.flow.single
import org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import org.jetbrains.exposed.v1.r2dbc.exists
import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException

class R2dbcFixtureCleanupTest {
    private class BodyFailure(val marker: Int): IllegalArgumentException("body sentinel")
    private class DropFailure(val index: Int): IllegalStateException("drop-$index")
    private val selected = TestDB.valueOf(System.getenv("EXPOSED_TEST_DB") ?: "H2").also {
        check(it in setOf(TestDB.H2, TestDB.POSTGRESQL))
    }
    private val fixture = r2dbcTestDbFixture(selected, { configure ->
        selected.beforeConnection()
        selected.connect { configure() }
    })
    private val table = object: Table("r2dbc_fixture_cleanup") { val id = integer("id") }

    @Test
    fun `생성 도중 실패는 요청 테이블만 정리하고 opt out을 존중한다`() = runSuspendIO {
        for (dropTables in listOf(true, false)) {
            var drops = 0
            var bodyRan = false
            val broken = object: Table("r2dbc_fixture_partial_create") {
                val id = integer("id")
                override fun createStatement(): List<String> =
                    super.createStatement() + "INVALID FIXTURE CREATE STATEMENT"
                override fun dropStatement(): List<String> {
                    drops++
                    return super.dropStatement()
                }
            }
            try {
                assertFailsWith<Exception> {
                    withTables(fixture, broken, dropTables = dropTables) { bodyRan = true }
                }
                bodyRan shouldBeEqualTo false
                if (dropTables) {
                    (drops >= 2) shouldBeEqualTo true
                    withDb(fixture) { broken.exists() shouldBeEqualTo false }
                } else {
                    drops shouldBeEqualTo 1
                    // PostgreSQL은 실패한 DDL을 rollback하므로 잔존 여부가 아닌 drop 미호출을 검증한다.
                }
            } finally {
                withDb(fixture) { SchemaUtils.drop(broken) }
            }
        }
    }

    @Test
    fun `cleanup 취소와 자기 자신인 예외가 본문 실패를 덮지 않는다`() = runSuspendIO {
        val primary = BodyFailure(9)
        val cancellation = java.util.concurrent.CancellationException("cleanup")
        retainR2dbcFailure(primary, primary) shouldBeSameInstanceAs primary
        primary.suppressed.size shouldBeEqualTo 0
        withDb(fixture) {
            cleanupR2dbcFixture(primary, recover = false, suppressOnCancellation = true,
            ) { throw cancellation }
        }
        primary.suppressed.single() shouldBeSameInstanceAs cancellation
    }

    @Test
    fun `본문 성공 뒤 cleanup 실패는 호출자에게 전달한다`() = runSuspendIO {
        val failure = BodyFailure(10)
        assertFailsWith<BodyFailure> {
            withDb(fixture) {
                cleanupR2dbcFixture(null, recover = false, suppressOnCancellation = true,
                ) { throw failure }
            }
        } shouldBeSameInstanceAs failure
    }

    @Test
    fun `schema 본문 실패와 실제 취소 후 schema가 남지 않는다`() = runSuspendIO {
        val schema = Schema("r2dbc_fixture_schema")
        assertFailsWith<BodyFailure> { withSchemas(fixture, schema) { throw BodyFailure(4) } }
        coroutineScope {
            val entered = CompletableDeferred<Unit>()
            val job = async {
                withSchemas(fixture, schema) { entered.complete(Unit); awaitCancellation() }
            }
            entered.await()
            job.cancel()
            assertFailsWith<CancellationException> { job.await() }
        }
        withDb(fixture) {
            exec("SELECT COUNT(*) FROM INFORMATION_SCHEMA.SCHEMATA WHERE LOWER(SCHEMA_NAME) = 'r2dbc_fixture_schema'") {
                (it.get(0) as Number).toInt()
            }.shouldNotBeNull().single() shouldBeEqualTo 0
        }
    }

    @Test
    fun `본문 실패 뒤 테이블을 정리한다`() = runSuspendIO {
        val failure = BodyFailure(1)
        assertFailsWith<BodyFailure> { withTables(fixture, table) { throw failure } } shouldBeSameInstanceAs failure
        withDb(fixture) { table.exists() shouldBeEqualTo false }
    }

    @Test
    fun `drop과 recovery 실패를 본문 아래 발생 순서대로 보존한다`() = runSuspendIO {
        val failure = BodyFailure(2)
        val cleanup = mutableListOf<Throwable>()
        var failDrop = false
        val broken = object: Table("r2dbc_fixture_broken_drop") {
            val id = integer("id")
            override fun dropStatement(): List<String> {
                if (failDrop) throw DropFailure(cleanup.size).also(cleanup::add)
                return super.dropStatement()
            }
        }
        try {
            assertFailsWith<BodyFailure> {
                withTables(fixture, broken) { failDrop = true; throw failure }
            } shouldBeSameInstanceAs failure
            cleanup.size shouldBeEqualTo 2
            failure.suppressed.toList() shouldBeEqualTo cleanup
        } finally {
            failDrop = false
            withDb(fixture) { SchemaUtils.drop(broken) }
        }
    }

    @Test
    fun `실제 취소 뒤 테이블을 정리하고 취소 suppressed를 추가하지 않는다`() = runSuspendIO {
        coroutineScope {
            val entered = CompletableDeferred<Unit>()
            val job = async {
                withTables(fixture, table) { entered.complete(Unit); awaitCancellation() }
            }
            entered.await()
            job.cancel()
            val failure = assertFailsWith<CancellationException> { job.await() }
            failure.suppressed.size shouldBeEqualTo 0
            withDb(fixture) { table.exists() shouldBeEqualTo false }
        }
    }

    @Test
    fun `drop opt out은 테이블을 남긴다`() = runSuspendIO {
        try {
            withTables(fixture, table, dropTables = false) {}
            withDb(fixture) { table.exists() shouldBeEqualTo true }
        } finally {
            withDb(fixture) { SchemaUtils.drop(table) }
        }
    }
}
