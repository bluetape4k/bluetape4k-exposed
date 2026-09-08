package io.bluetape4k.spring.data.exposed.jdbc.query

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.spring.data.exposed.jdbc.repository.query.DeclaredExposedQuery
import io.bluetape4k.spring.data.exposed.jdbc.repository.query.ExposedQueryMethod
import io.bluetape4k.spring.data.exposed.jdbc.repository.support.ExposedEntityInformationImpl
import io.mockk.every
import io.mockk.mockk
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.dao.LongEntity
import org.jetbrains.exposed.v1.dao.LongEntityClass
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test

class DeclaredQueryIdContractTest {

    object Accounts: LongIdTable("query_id_contract", "account_id") {
        val name = varchar("name", 64)
    }

    class Account(id: EntityID<Long>): LongEntity(id) {
        companion object: LongEntityClass<Account>(Accounts)
        var name by Accounts.name
    }

    private fun execute(sql: String): Any? {
        val method = mockk<ExposedQueryMethod>()
        every { method.getAnnotatedQuery() } returns sql
        every { method.name } returns "query"
        return DeclaredExposedQuery(method, ExposedEntityInformationImpl<Account, Long>(Account::class.java))
            .execute(emptyArray())
    }

    private fun withAccount(block: (Account) -> Unit) {
        val db = Database.connect("jdbc:h2:mem:query_id_contract;DATABASE_TO_LOWER=TRUE", driver = "org.h2.Driver")
        transaction(db) {
            SchemaUtils.create(Accounts)
            try {
                block(Account.new { name = "owner" })
            } finally {
                SchemaUtils.drop(Accounts)
            }
        }
    }

    @Test
    fun `사용자 정의 ID는 wildcard와 원래 라벨로 노출한 명시적 SELECT를 지원한다`() = withAccount { account ->
        listOf(
            "SELECT * FROM query_id_contract",
            "SELECT name, account_id FROM query_id_contract",
            "SELECT account_id AS ACCOUNT_ID FROM query_id_contract",
        ).forEach { sql ->
            execute(sql) shouldBeEqualTo listOf(account)
        }
    }

    @Test
    fun `사용자 정의 ID를 다른 alias로 숨기거나 중복 라벨로 노출하면 거부한다`() = withAccount {
        listOf(
            "SELECT account_id AS id FROM query_id_contract",
            "SELECT account_id, account_id FROM query_id_contract",
            "SELECT name FROM query_id_contract WHERE 1 = 0",
        ).forEach { sql ->
            assertFailsWith<IllegalArgumentException> { execute(sql) }.message shouldBeEqualTo
                "@Query method 'query' must select entity id column 'account_id'"
        }
    }

    @Test
    fun `잘못된 ID 타입은 엔티티로 변환하지 않는다`() = withAccount {
        assertFailsWith<IllegalStateException> {
            execute("SELECT name AS account_id FROM query_id_contract")
        }
    }
}
