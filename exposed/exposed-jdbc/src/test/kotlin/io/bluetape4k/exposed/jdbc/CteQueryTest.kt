package io.bluetape4k.exposed.jdbc

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.exposed.core.CteTable
import io.bluetape4k.exposed.tests.AbstractExposedTest
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withTables
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.crossJoin
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class CteQueryTest: AbstractExposedTest() {

    private object CteUsers: Table("cte_query_users") {
        val id = integer("id")
        val name = varchar("name", 64)
        val active = bool("active")
        val managerId = integer("manager_id").nullable()

        override val primaryKey = PrimaryKey(id)
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `CTE query renders WITH clause and preserves prepared arguments`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in cteCapableDialects }

        withTables(testDB, CteUsers) {
            seedUsers()

            val activeUsers = CteTable(
                name = "active_users",
                query = CteUsers
                    .select(CteUsers.id, CteUsers.name)
                    .where { CteUsers.active eq true }
            )
            val activeName = activeUsers[CteUsers.name]
            val query = activeUsers
                .select(activeName)
                .withCte(activeUsers)
                .orderBy(activeUsers[CteUsers.id])

            val builder = QueryBuilder(prepared = true)
            val sql = query.prepareSQL(builder)

            sql shouldContain "WITH"
            sql.lowercase() shouldContain "active_users"
            builder.args.map { it.second } shouldBeEqualTo listOf(true)

            query.map { it[activeName] } shouldBeEqualTo listOf("root", "child")
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `recursive CTE query can reference its temporary table fields`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in cteCapableDialects }

        withTables(testDB, CteUsers) {
            seedUsers()

            val hierarchy = CteTable(
                name = "user_hierarchy",
                query = CteUsers
                    .select(CteUsers.id, CteUsers.name, CteUsers.managerId)
                    .where { CteUsers.managerId.isNull() and (CteUsers.active eq true) },
                recursiveQuery = { cte ->
                    CteUsers
                        .crossJoin(cte)
                        .select(CteUsers.id, CteUsers.name, CteUsers.managerId)
                        .where { CteUsers.managerId eq cte[CteUsers.id] }
                }
            )
            val hierarchyName = hierarchy[CteUsers.name]
            val query = hierarchy
                .select(hierarchyName)
                .withCte(hierarchy)
                .orderBy(hierarchy[CteUsers.id])

            val sql = query.prepareSQL(QueryBuilder(prepared = true))

            sql shouldContain "WITH RECURSIVE"
            query.map { it[hierarchyName] } shouldBeEqualTo listOf("root", "child", "inactive-child")
        }
    }

    private fun seedUsers() {
        CteUsers.insert {
            it[id] = 1
            it[name] = "root"
            it[active] = true
            it[managerId] = null
        }
        CteUsers.insert {
            it[id] = 2
            it[name] = "child"
            it[active] = true
            it[managerId] = 1
        }
        CteUsers.insert {
            it[id] = 3
            it[name] = "inactive-child"
            it[active] = false
            it[managerId] = 2
        }
        CteUsers.insert {
            it[id] = 4
            it[name] = "other-root"
            it[active] = false
            it[managerId] = null
        }
    }

    companion object {
        private val cteCapableDialects: Set<TestDB> =
            TestDB.ALL_H2 + TestDB.ALL_POSTGRES_LIKE + TestDB.MYSQL_V8
    }
}
