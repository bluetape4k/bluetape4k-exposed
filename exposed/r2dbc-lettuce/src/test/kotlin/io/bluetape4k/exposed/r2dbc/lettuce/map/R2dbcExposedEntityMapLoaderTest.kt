package io.bluetape4k.exposed.r2dbc.lettuce.map

import io.bluetape4k.exposed.r2dbc.lettuce.AbstractR2dbcLettuceTest
import io.bluetape4k.exposed.r2dbc.tests.TestDB
import io.bluetape4k.exposed.r2dbc.tests.withTables
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldBeTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.collect
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SqlLogger
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insertAndGetId
import org.junit.jupiter.api.Test
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldHaveSize
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

/**
 * [R2dbcExposedEntityMapLoader] 단위 테스트.
 *
 * R2DBC `suspendTransaction` 기반으로 동작하며, `runBlocking` 없이 코루틴 네이티브로 실행한다.
 */
class R2dbcExposedEntityMapLoaderTest: AbstractR2dbcLettuceTest() {
    companion object: KLoggingChannel()

    private data class LoaderEntity(
        val id: Long,
        val name: String,
    )

    private data class ComparableCustomId(val value: String): Comparable<ComparableCustomId> {
        override fun compareTo(other: ComparableCustomId): Int = value.compareTo(other.value)
    }

    private object LoaderTable: LongIdTable("r2dbc_lettuce_loader_test") {
        val name = varchar("name", 64)
    }

    private fun ResultRow.toLoaderEntity(): LoaderEntity =
        LoaderEntity(
            id = this[LoaderTable.id].value,
            name = this[LoaderTable.name]
        )

    @Test
    fun `keyset capability는 표준 scalar만 허용하고 custom Comparable ID는 fallback으로 분류한다`() =
        runSuspendIO {
            42L.isKeysetScalar().shouldBeTrue()
            ComparableCustomId("custom").isKeysetScalar().shouldBeFalse()
        }

    @Test
    fun `load - 단건 조회 성공`() =
        runSuspendIO {
            withTables(TestDB.H2, LoaderTable) {
                val insertedId = LoaderTable.insertAndGetId { it[name] = "alice" }
                commit()

                val loader =
                    R2dbcExposedEntityMapLoader(
                        table = LoaderTable,
                        toEntity = { toLoaderEntity() }
                    )

                val entity = loader.load(insertedId.value)
                entity.shouldNotBeNull()
                entity.name shouldBeEqualTo "alice"
            }
        }

    @Test
    fun `load - 존재하지 않는 ID는 null을 반환한다`() =
        runSuspendIO {
            withTables(TestDB.H2, LoaderTable) {
                val loader =
                    R2dbcExposedEntityMapLoader(
                        table = LoaderTable,
                        toEntity = { toLoaderEntity() }
                    )

                loader.load(Long.MIN_VALUE).shouldBeNull()
            }
        }

    @Test
    fun `loadAllKeys - 빈 테이블은 빈 컬렉션을 반환한다`() =
        runSuspendIO {
            withTables(TestDB.H2, LoaderTable) {
                val loader =
                    R2dbcExposedEntityMapLoader(
                        table = LoaderTable,
                        toEntity = { toLoaderEntity() }
                    )

                loader.loadAllKeys().shouldBeEmpty()
            }
        }

    @Test
    fun `loadAllKeys - 배치 경계를 넘어 모든 ID를 로드한다`() =
        runSuspendIO {
            withTables(TestDB.H2, LoaderTable) {
                repeat(5) { index ->
                    LoaderTable.insertAndGetId { it[name] = "user-$index" }
                }
                commit()

                val loader =
                    R2dbcExposedEntityMapLoader(
                        table = LoaderTable,
                        batchSize = 2,
                        toEntity = { toLoaderEntity() }
                    )

                val ids = loader.loadAllKeys()
                ids shouldHaveSize 5
                ids shouldBeEqualTo ids.sorted()
        }
    }

    @Test
    fun `loadAllKeysFlow - List API와 keyset stream 결과가 일치한다`() =
        runSuspendIO {
            withTables(TestDB.H2, LoaderTable) {
                repeat(5) { index ->
                    LoaderTable.insertAndGetId { it[name] = "user-$index" }
                }
                commit()

                val loader =
                    R2dbcExposedEntityMapLoader(
                        table = LoaderTable,
                        batchSize = 2,
                        toEntity = { toLoaderEntity() }
                    )

                val listIds = loader.loadAllKeys()
                val flowIds = loader.loadAllKeysFlow().toList()
                flowIds shouldBeEqualTo listIds
            }
        }

    @Test
    fun `loadAllKeysFlow - page query는 offset 없이 keyset 조건을 사용한다`() =
        runSuspendIO {
            withTables(TestDB.H2, LoaderTable) {
                repeat(5) { index ->
                    LoaderTable.insertAndGetId { it[name] = "user-$index" }
                }
                commit()

                val sqlStatements = mutableListOf<String>()
                addLogger(object : SqlLogger {
                    override fun log(context: StatementContext, transaction: Transaction) {
                        sqlStatements += context.sql(transaction)
                    }
                })

                val loader =
                    R2dbcExposedEntityMapLoader(
                        table = LoaderTable,
                        batchSize = 2,
                        toEntity = { toLoaderEntity() }
                    )

                loader.loadAllKeysFlow().toList() shouldHaveSize 5
                val selects = sqlStatements.filter { it.trimStart().startsWith("SELECT", ignoreCase = true) }
                selects.size shouldBeEqualTo 3
                selects.none { it.contains("offset", ignoreCase = true) }.shouldBeTrue()
                selects.drop(1).all { it.contains(">") }.shouldBeTrue()
            }
        }

    @Test
    fun `loadAllKeysFlow - large fixture는 page cardinality와 query count를 bounded하게 유지한다`() =
        runSuspendIO {
            withTables(TestDB.H2, LoaderTable) {
                repeat(101) { index ->
                    LoaderTable.insertAndGetId { it[name] = "large-user-$index" }
                }
                commit()

                val sqlStatements = mutableListOf<String>()
                addLogger(object : SqlLogger {
                    override fun log(context: StatementContext, transaction: Transaction) {
                        sqlStatements += context.sql(transaction)
                    }
                })

                val loader =
                    R2dbcExposedEntityMapLoader(
                        table = LoaderTable,
                        batchSize = 16,
                        toEntity = { toLoaderEntity() },
                    )
                val ids = loader.loadAllKeysFlow().toList()
                val selects = sqlStatements.filter { it.trimStart().startsWith("SELECT", ignoreCase = true) }

                ids shouldHaveSize 101
                selects.size shouldBeEqualTo 7
                selects.all { it.contains("limit", ignoreCase = true) }.shouldBeTrue()
                selects.drop(1).all { it.contains(">") }.shouldBeTrue()
            }
        }

    @Test
    fun `loadAllKeysFlow - take는 page 다음 transaction을 열지 않고 취소된다`() =
        runSuspendIO {
            withTables(TestDB.H2, LoaderTable) {
                repeat(5) { index ->
                    LoaderTable.insertAndGetId { it[name] = "user-$index" }
                }
                commit()

                val loader =
                    R2dbcExposedEntityMapLoader(
                        table = LoaderTable,
                        batchSize = 2,
                        toEntity = { toLoaderEntity() }
                    )

                val sqlStatements = mutableListOf<String>()
                addLogger(object : SqlLogger {
                    override fun log(context: StatementContext, transaction: Transaction) {
                        sqlStatements += context.sql(transaction)
                    }
                })
                loader.loadAllKeysFlow().take(1).toList() shouldHaveSize 1
                sqlStatements.count { it.trimStart().startsWith("SELECT", ignoreCase = true) } shouldBeEqualTo 1
            }
        }

    @Test
    fun `loadAllKeysFlow - collector Job 취소는 현재 수집과 다음 page를 중단한다`() =
        runSuspendIO {
            withTables(TestDB.H2, LoaderTable) {
                repeat(5) { index ->
                    LoaderTable.insertAndGetId { it[name] = "user-$index" }
                }
                commit()

                val loader =
                    R2dbcExposedEntityMapLoader(
                        table = LoaderTable,
                        batchSize = 2,
                        toEntity = { toLoaderEntity() }
                    )
                val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                try {
                    val firstId = CompletableDeferred<Long>()
                    val observed = mutableListOf<Long>()
                    val collector = collectorScope.launch {
                        loader.loadAllKeysFlow().collect { id ->
                            observed += id
                            firstId.complete(id)
                            awaitCancellation()
                        }
                    }

                    firstId.await()
                    collector.cancelAndJoin()
                    collector.isCancelled.shouldBeTrue()
                    observed shouldHaveSize 1
                } finally {
                    collectorScope.cancel()
                }
            }
        }

    @Test
    fun `loadAllKeysFlow - sparse ID를 순서대로 중복 없이 반환한다`() =
        runSuspendIO {
            withTables(TestDB.H2, LoaderTable) {
                val initialIds =
                    List(5) { index ->
                        LoaderTable.insertAndGetId { it[name] = "user-$index" }.value
                    }
                LoaderTable.deleteWhere { LoaderTable.id eq initialIds[1] }
                commit()

                val loader =
                    R2dbcExposedEntityMapLoader(
                        table = LoaderTable,
                        batchSize = 2,
                        toEntity = { toLoaderEntity() }
                    )
                val ids = loader.loadAllKeysFlow().toList()

                ids shouldBeEqualTo initialIds.filterNot { it == initialIds[1] }
                ids.distinct() shouldBeEqualTo ids
            }
        }

    @Test
    fun `loadAllKeysFlow - page 사이 append와 delete에서 중복 없이 진행한다`() =
        runSuspendIO {
            withTables(TestDB.H2, LoaderTable) {
                val initialIds =
                    List(5) { index ->
                        LoaderTable.insertAndGetId { it[name] = "user-$index" }.value
                    }
                commit()

                val loader =
                    R2dbcExposedEntityMapLoader(
                        table = LoaderTable,
                        batchSize = 2,
                        toEntity = { toLoaderEntity() }
                    )
                val observed = mutableListOf<Long>()
                var appendedId = 0L
                loader.loadAllKeysFlow().collect { id ->
                    observed += id
                    if (observed.size == 2) {
                        LoaderTable.deleteWhere { LoaderTable.id eq initialIds[2] }
                        appendedId = LoaderTable.insertAndGetId { it[name] = "appended" }.value
                        commit()
                    }
                }

                observed shouldBeEqualTo initialIds.filterNot { it == initialIds[2] } + appendedId
                observed.distinct() shouldBeEqualTo observed
            }
        }

    @Test
    fun `batchSize는 0보다 커야 한다`() =
        runSuspendIO {
            withTables(TestDB.H2, LoaderTable) {
                assertFailsWith<IllegalArgumentException> {
                    R2dbcExposedEntityMapLoader(
                        table = LoaderTable,
                        batchSize = 0,
                        toEntity = { toLoaderEntity() }
                    )
                }
            }
        }
}
