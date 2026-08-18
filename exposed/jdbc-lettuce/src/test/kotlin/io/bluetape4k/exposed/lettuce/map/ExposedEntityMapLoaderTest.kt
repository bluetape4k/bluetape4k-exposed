package io.bluetape4k.exposed.lettuce.map

import io.bluetape4k.exposed.tests.AbstractExposedTest
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withTables
import io.bluetape4k.logging.KLogging
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldBeTrue
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SqlLogger
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.junit.jupiter.api.Test
import java.io.Serializable
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldHaveSize

/**
 * [ExposedEntityMapLoader] 단위 테스트.
 */
class ExposedEntityMapLoaderTest: AbstractExposedTest() {
    companion object: KLogging()

    private data class LoaderEntity(
        val id: Long,
        val name: String,
    ): Serializable

    private data class ComparableCustomId(val value: String): Comparable<ComparableCustomId> {
        override fun compareTo(other: ComparableCustomId): Int = value.compareTo(other.value)
    }

    private object LoaderTable: LongIdTable("lettuce_loader_test") {
        val name = varchar("name", 64)
    }

    private fun ResultRow.toLoaderEntity(): LoaderEntity =
        LoaderEntity(
            id = this[LoaderTable.id].value,
            name = this[LoaderTable.name]
        )

    @Test
    fun `keyset capability는 표준 scalar만 허용하고 custom Comparable ID는 fallback으로 분류한다`() {
        42L.isKeysetScalar().shouldBeTrue()
        ComparableCustomId("custom").isKeysetScalar().shouldBeFalse()
    }

    @Test
    fun `load - 단건 조회 성공`() {
        withTables(TestDB.H2, LoaderTable) {
            val insertedId =
                LoaderTable.insert {
                    it[name] = "alice"
                } get LoaderTable.id

            val loader =
                ExposedEntityMapLoader(
                    table = LoaderTable,
                    toEntity = { row -> row.toLoaderEntity() }
                )

            val entity = loader.load(insertedId.value)
            entity.shouldNotBeNull()
            entity.name shouldBeEqualTo "alice"
        }
    }

    @Test
    fun `load - 존재하지 않는 ID는 null을 반환한다`() {
        withTables(TestDB.H2, LoaderTable) {
            val loader =
                ExposedEntityMapLoader(
                    table = LoaderTable,
                    toEntity = { row -> row.toLoaderEntity() }
                )

            loader.load(Long.MIN_VALUE).shouldBeNull()
        }
    }

    @Test
    fun `loadAllKeys - 빈 테이블은 빈 컬렉션을 반환한다`() {
        withTables(TestDB.H2, LoaderTable) {
            val loader =
                ExposedEntityMapLoader(
                    table = LoaderTable,
                    toEntity = { row -> row.toLoaderEntity() }
                )

            loader.loadAllKeys().toList().shouldBeEmpty()
        }
    }

    @Test
    fun `loadAllKeys - 배치 경계를 넘어 모든 ID를 로드한다`() {
        withTables(TestDB.H2, LoaderTable) {
            repeat(5) { index ->
                LoaderTable.insert { it[name] = "user-$index" }
            }

            val loader =
                ExposedEntityMapLoader(
                    table = LoaderTable,
                    batchSize = 2,
                    toEntity = { row -> row.toLoaderEntity() }
                )

            val ids = loader.loadAllKeys().toList()
            ids shouldHaveSize 5
            ids shouldBeEqualTo ids.sorted()
        }
    }

    @Test
    fun `loadAllKeys - 마지막 partial page의 모든 ID를 방출한다`() {
        withTables(TestDB.H2, LoaderTable) {
            repeat(6) { index ->
                LoaderTable.insert { it[name] = "user-$index" }
            }

            val loader =
                ExposedEntityMapLoader(
                    table = LoaderTable,
                    batchSize = 4,
                    toEntity = { row -> row.toLoaderEntity() }
                )

            loader.loadAllKeys().toList() shouldHaveSize 6
        }
    }

    @Test
    fun `loadAllKeys - keyset page는 offset 없이 lazy iterable로 순회한다`() {
        withTables(TestDB.H2, LoaderTable) {
            repeat(5) { index ->
                LoaderTable.insert { it[name] = "user-$index" }
            }

            val sqlStatements = mutableListOf<String>()
            addLogger(object : SqlLogger {
                override fun log(context: StatementContext, transaction: Transaction) {
                    sqlStatements += context.sql(transaction)
                }
            })

            val loader =
                ExposedEntityMapLoader(
                    table = LoaderTable,
                    batchSize = 2,
                    toEntity = { row -> row.toLoaderEntity() }
                )

            val iterator = loader.loadAllKeys().iterator()
            sqlStatements.count { it.trimStart().startsWith("SELECT", ignoreCase = true) } shouldBeEqualTo 0
            val firstId = iterator.next()
            sqlStatements.count { it.trimStart().startsWith("SELECT", ignoreCase = true) } shouldBeEqualTo 1
            val ids = buildList {
                add(firstId)
                while (iterator.hasNext()) {
                    add(iterator.next())
                }
            }
            ids shouldHaveSize 5
            ids shouldBeEqualTo ids.sorted()
            (loader.loadAllKeys() is List<*>).shouldBeFalse()

            val selects = sqlStatements.filter { it.trimStart().startsWith("SELECT", ignoreCase = true) }
            selects.size shouldBeEqualTo 3
            selects.none { it.contains("offset", ignoreCase = true) }.shouldBeTrue()
            selects.drop(1).all { it.contains(">") }.shouldBeTrue()
        }
    }

    @Test
    fun `loadAllKeys - large fixture는 page cardinality와 query count를 bounded하게 유지한다`() {
        withTables(TestDB.H2, LoaderTable) {
            repeat(101) { index ->
                LoaderTable.insert { it[name] = "large-user-$index" }
            }

            val sqlStatements = mutableListOf<String>()
            addLogger(object : SqlLogger {
                override fun log(context: StatementContext, transaction: Transaction) {
                    sqlStatements += context.sql(transaction)
                }
            })

            val loader =
                ExposedEntityMapLoader(
                    table = LoaderTable,
                    batchSize = 16,
                    toEntity = { row -> row.toLoaderEntity() },
                )
            val ids = loader.loadAllKeys().toList()
            val selects = sqlStatements.filter { it.trimStart().startsWith("SELECT", ignoreCase = true) }

            ids shouldHaveSize 101
            selects.size shouldBeEqualTo 7
            selects.all { it.contains("limit", ignoreCase = true) }.shouldBeTrue()
            selects.drop(1).all { it.contains(">") }.shouldBeTrue()
        }
    }

    @Test
    fun `loadAllKeys - sparse ID와 page 사이 append에서도 중복 없이 진행한다`() {
        withTables(TestDB.H2, LoaderTable) {
            val initialIds =
                List(5) { index ->
                    LoaderTable.insert { it[name] = "user-$index" } get LoaderTable.id
                }.map { it.value }
            LoaderTable.deleteWhere { LoaderTable.id eq initialIds[1] }

            val loader =
                ExposedEntityMapLoader(
                    table = LoaderTable,
                    batchSize = 2,
                    toEntity = { row -> row.toLoaderEntity() }
                )
            val iterator = loader.loadAllKeys().iterator()
            val firstPage = listOf(iterator.next(), iterator.next())
            val appendedId = (LoaderTable.insert { it[name] = "appended" } get LoaderTable.id).value
            val remaining = iterator.asSequence().toList()
            val ids = firstPage + remaining

            ids shouldBeEqualTo (initialIds.filterNot { it == initialIds[1] } + appendedId).sorted()
            ids.distinct() shouldBeEqualTo ids
        }
    }

    @Test
    fun `batchSize는 0보다 커야 한다`() {
        withTables(TestDB.H2, LoaderTable) {
            assertFailsWith<IllegalArgumentException> {
                ExposedEntityMapLoader(
                    table = LoaderTable,
                    batchSize = 0,
                    toEntity = { row -> row.toLoaderEntity() }
                )
            }
        }
    }
}
