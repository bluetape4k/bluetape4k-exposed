package io.bluetape4k.exposed.r2dbc.redisson.map

import io.bluetape4k.exposed.r2dbc.tests.AbstractExposedR2dbcTest
import io.bluetape4k.exposed.r2dbc.tests.TestDB
import io.bluetape4k.exposed.r2dbc.tests.withTables
import io.bluetape4k.junit5.coroutines.runSuspendIO
import kotlinx.coroutines.future.await
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldBeTrue
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

            val ids = loader.loadAllKeys().toList()
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

            val ids = loader.loadAllKeys().toList()
            ids shouldHaveSize 5
            ids shouldBeEqualTo ids.sorted()

            val selects = sqlStatements.filter { it.trimStart().startsWith("SELECT", ignoreCase = true) }
            selects.size shouldBeEqualTo 3
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
            val ids = loader.loadAllKeys().toList()
            val selects = sqlStatements.filter { it.trimStart().startsWith("SELECT", ignoreCase = true) }

            ids shouldHaveSize 101
            selects.size shouldBeEqualTo 7
            selects.all { it.contains("limit", ignoreCase = true) }.shouldBeTrue()
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
            val iterator = loader.loadAllKeys()
            val ids = buildList {
                while (iterator.hasNext().await() == true) {
                    add(iterator.next().await())
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
