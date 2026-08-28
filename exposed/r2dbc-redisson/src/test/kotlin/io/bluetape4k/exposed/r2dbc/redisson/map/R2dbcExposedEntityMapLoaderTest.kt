package io.bluetape4k.exposed.r2dbc.redisson.map

import io.bluetape4k.exposed.r2dbc.tests.AbstractExposedR2dbcTest
import io.bluetape4k.exposed.r2dbc.tests.TestDB
import io.bluetape4k.exposed.r2dbc.tests.withDb
import io.bluetape4k.exposed.r2dbc.tests.withTables
import io.bluetape4k.junit5.coroutines.runSuspendIO
import kotlinx.coroutines.future.await
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SqlLogger
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.r2dbc.insert
import java.io.Serializable
import org.junit.jupiter.api.Test
import io.bluetape4k.assertions.assertFailsWith

class R2dbcExposedEntityMapLoaderTest: AbstractExposedR2dbcTest() {

    private data class ComparableCustomId(val value: String): Comparable<ComparableCustomId> {
        override fun compareTo(other: ComparableCustomId): Int = value.compareTo(other.value)
    }

    private data class LoaderEntity(
        val id: Long,
        val name: String,
    ): Serializable

    private object LoaderTable: LongIdTable("r2dbc_redisson_loader_test") {
        val name = varchar("name", 64)
    }

    private object MissingLoaderTable: LongIdTable("r2dbc_redisson_missing_log_table_credential_751")

    @Test
    fun `loadAllKeys DB 오류 로그는 errorType만 기록하고 원시 예외를 첨부하지 않는다`() = runSuspendIO {
        withDb(TestDB.H2) {
            val secret = "R2DBC_REDISSON_MISSING_LOG_TABLE_CREDENTIAL_751"
            val loader = R2dbcExposedEntityMapLoader(
                entityTable = MissingLoaderTable,
                toEntity = { error(secret) },
            )

            RecordingLogAppender().use { appender ->
                val failure = assertFailsWith<Throwable> {
                    loader.useLoader(TestDB.H2) {
                        loader.loadAllKeys().toList()
                    }
                }

                failure.javaClass.simpleName shouldBeEqualTo "ExposedR2dbcException"
                val errorEvents = appender.events.filter { event ->
                    event.formattedMessage.contains("모든 ID") &&
                        event.level.levelInt >= ch.qos.logback.classic.Level.ERROR.levelInt
                }
                errorEvents.size shouldBeEqualTo 2
                errorEvents.forEach { event ->
                    event.formattedMessage shouldContain "errorType="
                    event.throwableProxy.shouldBeNull()
                }
                appender.rendered shouldNotContain secret
            }
        }
    }

    private fun ResultRow.toLoaderEntity(): LoaderEntity =
        LoaderEntity(
            id = this[LoaderTable.id].value,
            name = this[LoaderTable.name],
        )

    @Test
    fun `keyset capability는 표준 scalar만 허용하고 custom Comparable ID는 fallback으로 분류한다`() {
        42L.isKeysetScalar().shouldBeTrue()
        ComparableCustomId("custom").isKeysetScalar().shouldBeFalse()
    }

    @Test
    fun `batch loader는 배치 경계를 넘어 모든 id를 로드한다`() = runSuspendIO {
        withTables(TestDB.H2, LoaderTable) {
            repeat(3) { index ->
                LoaderTable.insert {
                    it[name] = "user-$index"
                }
            }

            val loader = R2dbcExposedEntityMapLoader(
                entityTable = LoaderTable,
                batchSize = 2,
            ) {
                toLoaderEntity()
            }

            val ids = loader.useLoader(TestDB.H2) { loader.loadAllKeys().toList() }
            ids shouldHaveSize 3
            ids shouldBeEqualTo ids.sorted()
        }
    }

    @Test
    fun `loadAllKeys - 표준 scalar PK는 offset 없이 keyset page를 사용한다`() = runSuspendIO {
        val sqlStatements = mutableListOf<String>()
        withTables(
            TestDB.H2,
            LoaderTable,
            configure = {
                sqlLogger = object : SqlLogger {
                    override fun log(context: StatementContext, transaction: Transaction) {
                        sqlStatements += context.sql(transaction)
                    }
                }
            },
        ) {
            repeat(5) { index ->
                LoaderTable.insert { it[name] = "user-$index" }
            }
            commit()
            sqlStatements.clear()

            val loader = R2dbcExposedEntityMapLoader(
                entityTable = LoaderTable,
                batchSize = 2,
            ) {
                toLoaderEntity()
            }

            val (ids, selects) = loader.useLoader(TestDB.H2) {
                val loadedIds = loader.loadAllKeys().toList()
                loadedIds to sqlStatements.filter { it.trimStart().startsWith("SELECT", ignoreCase = true) }
            }
            selects.size shouldBeEqualTo 3
            ids shouldHaveSize 5
            ids shouldBeEqualTo ids.sorted()
            selects.none { it.contains("offset", ignoreCase = true) }.shouldBeTrue()
            selects.drop(1).all { it.contains(">") }.shouldBeTrue()
        }
    }

    @Test
    fun `loadAllKeys - 큰 fixture의 page cardinality와 query 수를 bounded하게 유지한다`() = runSuspendIO {
        val sqlStatements = mutableListOf<String>()
        withTables(
            TestDB.H2,
            LoaderTable,
            configure = {
                sqlLogger = object : SqlLogger {
                    override fun log(context: StatementContext, transaction: Transaction) {
                        sqlStatements += context.sql(transaction)
                    }
                }
            },
        ) {
            repeat(101) { index ->
                LoaderTable.insert { it[name] = "large-user-$index" }
            }
            commit()
            sqlStatements.clear()

            val loader = R2dbcExposedEntityMapLoader(
                entityTable = LoaderTable,
                batchSize = 16,
            ) {
                toLoaderEntity()
            }
            val (ids, selects) = loader.useLoader(TestDB.H2) {
                val loadedIds = loader.loadAllKeys().toList()
                loadedIds to sqlStatements.filter { it.trimStart().startsWith("SELECT", ignoreCase = true) }
            }

            ids shouldHaveSize 101
            selects.size shouldBeEqualTo 7
            selects.all { it.contains("limit", ignoreCase = true) }.shouldBeTrue()
            selects.drop(1).all { it.contains(">") }.shouldBeTrue()
        }
    }

    @Test
    fun `loadAllKeys - exact multiple fixture는 마지막 빈 page query를 계약으로 고정한다`() = runSuspendIO {
        val sqlStatements = mutableListOf<String>()
        withTables(
            TestDB.H2,
            LoaderTable,
            configure = {
                sqlLogger = object : SqlLogger {
                    override fun log(context: StatementContext, transaction: Transaction) {
                        sqlStatements += context.sql(transaction)
                    }
                }
            },
        ) {
            repeat(4) { index ->
                LoaderTable.insert { it[name] = "exact-user-$index" }
            }
            commit()
            sqlStatements.clear()

            val loader = R2dbcExposedEntityMapLoader(
                entityTable = LoaderTable,
                batchSize = 2,
            ) {
                toLoaderEntity()
            }
            val (ids, selects) = loader.useLoader(TestDB.H2) {
                val loadedIds = loader.loadAllKeys().toList()
                loadedIds to sqlStatements.filter { it.trimStart().startsWith("SELECT", ignoreCase = true) }
            }

            ids shouldHaveSize 4
            selects.size shouldBeEqualTo 3
            selects.drop(1).all { it.contains(">") }.shouldBeTrue()
        }
    }

    @Test
    fun `loadAllKeys - sparse ID는 keyset 경계에서 중복 없이 순회한다`() = runSuspendIO {
        withTables(TestDB.H2, LoaderTable) {
            val initialIds =
                List(5) { index ->
                    LoaderTable.insert { it[name] = "user-$index" } get LoaderTable.id
                }.map { it.value }
            commit()

            val loader = R2dbcExposedEntityMapLoader(
                entityTable = LoaderTable,
                batchSize = 2,
            ) {
                toLoaderEntity()
            }
            val ids = loader.useLoader(TestDB.H2) {
                val iterator = loader.loadAllKeys()
                buildList {
                    while (iterator.hasNext().await() == true) {
                        add(iterator.next().await())
                    }
                }
            }

            ids shouldBeEqualTo initialIds
            ids.distinct() shouldBeEqualTo ids
        }
    }

    @Test
    fun `batchSize 는 0보다 커야 한다`() {
        assertFailsWith<IllegalArgumentException> {
            R2dbcExposedEntityMapLoader(
                entityTable = LoaderTable,
                batchSize = 0,
                toEntity = { toLoaderEntity() },
            )
        }
    }
}
