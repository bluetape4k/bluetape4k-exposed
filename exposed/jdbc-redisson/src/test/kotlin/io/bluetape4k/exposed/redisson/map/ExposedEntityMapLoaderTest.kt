package io.bluetape4k.exposed.redisson.map

import io.bluetape4k.exposed.tests.AbstractExposedTest
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withTables
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.assertions.shouldBeTrue
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SqlLogger
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.Test
import java.io.Serializable
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldHaveSize

class ExposedEntityMapLoaderTest: AbstractExposedTest() {

    private data class ComparableCustomId(val value: String): Comparable<ComparableCustomId> {
        override fun compareTo(other: ComparableCustomId): Int = value.compareTo(other.value)
    }

    private data class LoaderEntity(
        val id: Long,
        val name: String,
    ): Serializable

    private object LoaderTable: LongIdTable("redisson_loader_test") {
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
    fun `batch loader는 배치 경계를 넘어 모든 id를 로드한다`() {
        withTables(TestDB.H2, LoaderTable) {
            repeat(3) { index ->
                LoaderTable.insert {
                    it[name] = "user-$index"
                }
            }

            val loader = ExposedEntityMapLoader(
                entityTable = LoaderTable,
                batchSize = 2,
                toEntity = { toLoaderEntity() },
            )

            val ids = loader.loadAllKeys()!!.toList()
            ids shouldHaveSize 3
            ids shouldBeEqualTo ids.sorted()
        }
    }

    @Test
    fun `loadAllKeys - 표준 scalar PK는 offset 없이 keyset page를 사용한다`() {
        withTables(
            TestDB.H2,
            LoaderTable,
        ) {
            repeat(5) { index ->
                LoaderTable.insert { it[name] = "user-$index" }
            }

            val sqlStatements = mutableListOf<String>()
            addLogger(object : SqlLogger {
                override fun log(context: StatementContext, transaction: Transaction) {
                    sqlStatements += context.sql(transaction)
                }
            })
            sqlStatements.clear()

            val loader = ExposedEntityMapLoader(
                entityTable = LoaderTable,
                batchSize = 2,
                toEntity = { toLoaderEntity() },
            )

            val ids = loader.loadAllKeys()!!.toList()
            ids shouldHaveSize 5
            ids shouldBeEqualTo ids.sorted()

            val selects = sqlStatements.filter { it.trimStart().startsWith("SELECT", ignoreCase = true) }
            selects.size shouldBeEqualTo 3
            selects.none { it.contains("offset", ignoreCase = true) }.shouldBeTrue()
            selects.drop(1).all { it.contains(">") }.shouldBeTrue()
        }
    }

    @Test
    fun `loadAllKeys - 큰 fixture의 page cardinality와 query 수를 bounded하게 유지한다`() {
        withTables(
            TestDB.H2,
            LoaderTable,
        ) {
            repeat(101) { index ->
                LoaderTable.insert { it[name] = "large-user-$index" }
            }

            val sqlStatements = mutableListOf<String>()
            addLogger(object : SqlLogger {
                override fun log(context: StatementContext, transaction: Transaction) {
                    sqlStatements += context.sql(transaction)
                }
            })
            sqlStatements.clear()

            val loader = ExposedEntityMapLoader(
                entityTable = LoaderTable,
                batchSize = 16,
                toEntity = { toLoaderEntity() },
            )
            val ids = loader.loadAllKeys()!!.toList()
            val selects = sqlStatements.filter { it.trimStart().startsWith("SELECT", ignoreCase = true) }

            ids shouldHaveSize 101
            selects.size shouldBeEqualTo 7
            selects.all { it.contains("limit", ignoreCase = true) }.shouldBeTrue()
            selects.drop(1).all { it.contains(">") }.shouldBeTrue()
        }
    }

    @Test
    fun `loadAllKeys - sparse ID는 keyset 경계에서 중복 없이 순회한다`() {
        withTables(TestDB.H2, LoaderTable) {
            val initialIds =
                List(5) { index ->
                    LoaderTable.insert { it[name] = "user-$index" } get LoaderTable.id
                }.map { it.value }
            LoaderTable.deleteWhere { LoaderTable.id eq initialIds[1] }

            val loader = ExposedEntityMapLoader(
                entityTable = LoaderTable,
                batchSize = 2,
                toEntity = { toLoaderEntity() },
            )

            val ids = loader.loadAllKeys()!!.toList()
            ids shouldBeEqualTo initialIds.filterNot { it == initialIds[1] }
            ids.distinct() shouldBeEqualTo ids
        }
    }

    @Test
    fun `load - 단건 조회 성공`() {
        withTables(TestDB.H2, LoaderTable) {
            val insertedId = LoaderTable.insert {
                it[name] = "alice"
            } get LoaderTable.id

            val loader = ExposedEntityMapLoader(
                entityTable = LoaderTable,
                toEntity = { toLoaderEntity() },
            )

            val entity = loader.load(insertedId.value)
            entity.shouldNotBeNull()
            entity.name shouldBeEqualTo "alice"
        }
    }

    @Test
    fun `load 로그는 원시 ID와 엔티티 payload를 노출하지 않는다`() {
        withTables(TestDB.H2, LoaderTable) {
            val sensitiveName = "credential=jdbc-redisson-secret"
            val insertedId = LoaderTable.insert {
                it[name] = sensitiveName
            } get LoaderTable.id
            val loader = ExposedEntityMapLoader(
                entityTable = LoaderTable,
                toEntity = { toLoaderEntity() },
            )

            RecordingLogAppender().use { appender ->
                loader.load(insertedId.value).shouldNotBeNull()

                appender.rendered shouldNotContain insertedId.value.toString()
                appender.rendered shouldNotContain sensitiveName
            }
        }
    }

    @Test
    fun `load - 존재하지 않는 ID는 null을 반환한다`() {
        withTables(TestDB.H2, LoaderTable) {
            val loader = ExposedEntityMapLoader(
                entityTable = LoaderTable,
                toEntity = { toLoaderEntity() },
            )

            loader.load(Long.MIN_VALUE).shouldBeNull()
        }
    }

    @Test
    fun `loadAllKeys - 빈 테이블은 빈 컬렉션을 반환한다`() {
        withTables(TestDB.H2, LoaderTable) {
            val loader = ExposedEntityMapLoader(
                entityTable = LoaderTable,
                toEntity = { toLoaderEntity() },
            )

            val ids = loader.loadAllKeys()!!.toList()
            ids.shouldBeEmpty()
        }
    }

    @Test
    fun `loadAllKeys - JDBC queryTimeout은 초 단위 30으로 설정한다`() {
        var observedQueryTimeout: Int? = null

        withTables(TestDB.H2, LoaderTable) {
            val loader = EntityMapLoader<Long, LoaderEntity>(
                loadByIdFromDB = { null },
                loadAllIdsFromDB = {
                    observedQueryTimeout = TransactionManager.current().queryTimeout
                    emptyList()
                },
            )

            loader.loadAllKeys()
        }

        observedQueryTimeout.shouldNotBeNull() shouldBeEqualTo 30
    }

    @Test
    fun `batchSize 는 0보다 커야 한다`() {
        withTables(TestDB.H2, LoaderTable) {
            assertFailsWith<IllegalArgumentException> {
                ExposedEntityMapLoader(
                    entityTable = LoaderTable,
                    batchSize = 0,
                    toEntity = { toLoaderEntity() },
                )
            }

            assertFailsWith<IllegalArgumentException> {
                SuspendedExposedEntityMapLoader(
                    entityTable = LoaderTable,
                    batchSize = 0,
                    toEntity = { toLoaderEntity() },
                )
            }
        }
    }

}
