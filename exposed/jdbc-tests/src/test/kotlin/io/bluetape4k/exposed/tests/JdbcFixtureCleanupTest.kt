package io.bluetape4k.exposed.tests

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.junit5.coroutines.runSuspendIO
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.Schema
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.exists
import org.junit.jupiter.api.Test

class JdbcFixtureCleanupTest {
    private class BodyFailure(val marker: Int): IllegalArgumentException("body sentinel")
    private val selected = TestDB.valueOf(System.getenv("EXPOSED_TEST_DB") ?: "H2").also {
        check(it in setOf(TestDB.H2, TestDB.POSTGRESQL))
    }
    private val fixture = jdbcTestDbFixture(selected, { configure -> selected.connect(configure) })
    private val table = object: Table("jdbc_fixture_cleanup") { val id = integer("id") }

    @Test
    fun `생성 도중 실패는 요청 테이블만 정리하고 opt out을 존중한다`() {
        for (dropTables in listOf(true, false)) {
            var drops = 0
            var bodyRan = false
            val broken = object: Table("jdbc_fixture_partial_create") {
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
    fun `cleanup 취소와 자기 자신인 예외가 본문 실패를 덮지 않는다`() {
        val primary = BodyFailure(9)
        val cancellation = java.util.concurrent.CancellationException("cleanup")
        retainJdbcFailure(primary, primary) shouldBeSameInstanceAs primary
        primary.suppressed.size shouldBeEqualTo 0
        withDb(fixture) {
            cleanupJdbcFixture(primary, recover = false,
            ) { throw cancellation }
        }
        primary.suppressed.single() shouldBeSameInstanceAs cancellation
    }

    @Test
    fun `본문 성공 뒤 cleanup 실패는 호출자에게 전달한다`() {
        val failure = BodyFailure(10)
        assertFailsWith<BodyFailure> {
            withDb(fixture) {
                cleanupJdbcFixture(null, recover = false,
                ) { throw failure }
            }
        } shouldBeSameInstanceAs failure
    }

    @Test
    fun `schema 본문 실패와 suspend 취소 후 schema가 남지 않는다`() = runSuspendIO {
        val schema = Schema("jdbc_fixture_schema")
        assertFailsWith<BodyFailure> {
            withSchemas(fixture, schema) { key ->
                key shouldBeEqualTo selected
                throw BodyFailure(4)
            }
        }
        coroutineScope {
            val entered = CompletableDeferred<Unit>()
            val job = launch {
                withSchemasSuspending(fixture, schema) { entered.complete(Unit); awaitCancellation() }
            }
            entered.await()
            job.cancelAndJoin()
        }
        withDb(fixture) {
            exec("SELECT COUNT(*) FROM INFORMATION_SCHEMA.SCHEMATA WHERE LOWER(SCHEMA_NAME) = 'jdbc_fixture_schema'") {
                it.next()
                it.getInt(1)
            } shouldBeEqualTo 0
        }
    }

    @Test
    fun `본문 실패 뒤 테이블이 없고 무관한 테이블은 유지된다`() {
        val sentinel = object: Table("jdbc_fixture_sentinel") { val id = integer("id") }
        val failure = BodyFailure(1)
        withDb(fixture) { SchemaUtils.create(sentinel) }
        try {
            assertFailsWith<BodyFailure> {
                withTables(fixture, table) { throw failure }
            } shouldBeSameInstanceAs failure
            withDb(fixture) {
                table.exists() shouldBeEqualTo false
                sentinel.exists() shouldBeEqualTo true
            }
        } finally {
            withDb(fixture) { SchemaUtils.drop(sentinel, table) }
        }
    }

    @Test
    fun `drop과 recovery 실패를 본문 예외에 순서대로 보존한다`() {
        val failure = BodyFailure(2)
        val cleanup = mutableListOf<Throwable>()
        var failDrop = false
        val broken = object: Table("jdbc_fixture_broken_drop") {
            val id = integer("id")
            override fun dropStatement(): List<String> {
                if (failDrop) throw IllegalStateException("drop-${cleanup.size}").also(cleanup::add)
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
    fun `drop opt out은 본문 실패에도 테이블을 남긴다`() {
        try {
            assertFailsWith<BodyFailure> {
                withTables(fixture, table, dropTables = false) { throw BodyFailure(3) }
            }
            withDb(fixture) { table.exists() shouldBeEqualTo true }
        } finally {
            withDb(fixture) { SchemaUtils.drop(table) }
        }
    }

    @Test
    fun `실제 suspend 취소 뒤 테이블을 정리한다`() = runSuspendIO {
        coroutineScope {
            val entered = CompletableDeferred<Unit>()
            val job = launch {
                withTablesSuspending(fixture, table) { entered.complete(Unit); awaitCancellation() }
            }
            entered.await()
            job.cancelAndJoin()
            withDbSuspending(fixture) { table.exists() shouldBeEqualTo false }
        }
    }
}
