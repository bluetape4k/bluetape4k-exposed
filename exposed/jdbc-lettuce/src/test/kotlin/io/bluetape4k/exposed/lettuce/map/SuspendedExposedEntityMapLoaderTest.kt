package io.bluetape4k.exposed.lettuce.map

import io.bluetape4k.exposed.tests.AbstractExposedTest
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withTablesSuspending
import io.bluetape4k.logging.KLogging
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldBeTrue
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SqlLogger
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.jdbc.insert
import org.junit.jupiter.api.Test
import java.io.Serializable
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldHaveSize

/**
 * [SuspendedExposedEntityMapLoader] 단위 테스트.
 *
 * [SuspendedEntityMapLoader.load]는 내부적으로 [suspendedTransactionAsync]를 열므로
 * 데이터가 커밋된 상태여야 새 트랜잭션에서 READ_COMMITTED 격리 수준으로 조회 가능하다.
 * 따라서 각 테스트에서 insert 후 `commit()`을 호출한다.
 */
class SuspendedExposedEntityMapLoaderTest: AbstractExposedTest() {
    companion object: KLogging()

    private data class SuspendedLoaderEntity(
        val id: Long,
        val name: String,
    ): Serializable

    private object SuspendedLoaderTable: LongIdTable("suspended_loader_test") {
        val name = varchar("name", 64)
    }

    private fun ResultRow.toSuspendedLoaderEntity(): SuspendedLoaderEntity =
        SuspendedLoaderEntity(
            id = this[SuspendedLoaderTable.id].value,
            name = this[SuspendedLoaderTable.name]
        )

    @Test
    fun `load - suspend 컨텍스트에서 단건 조회 성공`() = runSuspendIO {
        withTablesSuspending(TestDB.H2, SuspendedLoaderTable) {
            val insertedId =
                SuspendedLoaderTable.insert {
                    it[name] = "alice"
                } get SuspendedLoaderTable.id

            // suspendedTransactionAsync는 새 트랜잭션을 열므로 먼저 커밋해야 데이터가 보인다
            commit()

            val loader = SuspendedExposedEntityMapLoader(
                table = SuspendedLoaderTable,
                toEntity = { row -> row.toSuspendedLoaderEntity() }
            )

            val entity = loader.load(insertedId.value)
            entity.shouldNotBeNull()
            entity.name shouldBeEqualTo "alice"
        }
    }

    @Test
    fun `load - 존재하지 않는 ID는 null을 반환한다`() = runSuspendIO {
        withTablesSuspending(TestDB.H2, SuspendedLoaderTable) {
            val loader = SuspendedExposedEntityMapLoader(
                table = SuspendedLoaderTable,
                toEntity = { row -> row.toSuspendedLoaderEntity() }
            )

            loader.load(Long.MIN_VALUE).shouldBeNull()
        }
    }

    @Test
    fun `loadAllKeys - 빈 테이블은 빈 리스트를 반환한다`() = runSuspendIO {
        withTablesSuspending(TestDB.H2, SuspendedLoaderTable) {
            // 데이터 없이 바로 loadAllKeys 호출 — 빈 리스트 반환
            commit()

            val loader = SuspendedExposedEntityMapLoader(
                table = SuspendedLoaderTable,
                toEntity = { row -> row.toSuspendedLoaderEntity() }
            )

            loader.loadAllKeys().shouldBeEmpty()
        }
    }

    @Test
    fun `loadAllKeys - 배치 경계를 넘어 모든 ID를 로드한다`() = runSuspendIO {
        withTablesSuspending(TestDB.H2, SuspendedLoaderTable) {
            repeat(5) { index ->
                SuspendedLoaderTable.insert { it[name] = "user-$index" }
            }

            // suspendedTransactionAsync는 새 트랜잭션을 열므로 먼저 커밋해야 한다
            commit()

            // batchSize=2 로 설정하여 페이지 경계를 여러 번 넘도록 강제
            val loader = SuspendedExposedEntityMapLoader(
                table = SuspendedLoaderTable,
                batchSize = 2,
                toEntity = { row -> row.toSuspendedLoaderEntity() }
            )

            val ids = loader.loadAllKeys()
            ids shouldHaveSize 5
            ids shouldBeEqualTo ids.sorted()
        }
    }

    @Test
    fun `loadAllKeys - 표준 scalar PK는 offset 없이 keyset page를 사용한다`() = runSuspendIO {
        val sqlStatements = mutableListOf<String>()
        withTablesSuspending(
            TestDB.H2,
            SuspendedLoaderTable,
            configure = {
                sqlLogger = object : SqlLogger {
                    override fun log(context: StatementContext, transaction: Transaction) {
                        sqlStatements += context.sql(transaction)
                    }
                }
            },
        ) {
            repeat(5) { index ->
                SuspendedLoaderTable.insert { it[name] = "user-$index" }
            }
            commit()
            sqlStatements.clear()

            val loader = SuspendedExposedEntityMapLoader(
                table = SuspendedLoaderTable,
                batchSize = 2,
                toEntity = { row -> row.toSuspendedLoaderEntity() },
            )

            val ids = loader.loadAllKeys()
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
        withTablesSuspending(
            TestDB.H2,
            SuspendedLoaderTable,
            configure = {
                sqlLogger = object : SqlLogger {
                    override fun log(context: StatementContext, transaction: Transaction) {
                        sqlStatements += context.sql(transaction)
                    }
                }
            },
        ) {
            repeat(101) { index ->
                SuspendedLoaderTable.insert { it[name] = "large-user-$index" }
            }
            commit()
            sqlStatements.clear()

            val loader = SuspendedExposedEntityMapLoader(
                table = SuspendedLoaderTable,
                batchSize = 16,
                toEntity = { row -> row.toSuspendedLoaderEntity() },
            )
            val ids = loader.loadAllKeys()
            val selects = sqlStatements.filter { it.trimStart().startsWith("SELECT", ignoreCase = true) }

            ids shouldHaveSize 101
            selects.size shouldBeEqualTo 7
            selects.all { it.contains("limit", ignoreCase = true) }.shouldBeTrue()
            selects.drop(1).all { it.contains(">") }.shouldBeTrue()
        }
    }

    @Test
    fun `batchSize는 0보다 커야 한다`() {
        assertFailsWith<IllegalArgumentException> {
            SuspendedExposedEntityMapLoader(
                table = SuspendedLoaderTable,
                batchSize = 0,
                toEntity = { row -> row.toSuspendedLoaderEntity() }
            )
        }
    }
}
