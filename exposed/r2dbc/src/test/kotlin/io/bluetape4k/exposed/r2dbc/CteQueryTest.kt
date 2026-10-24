package io.bluetape4k.exposed.r2dbc

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.exposed.core.CteTable
import io.bluetape4k.exposed.r2dbc.tests.AbstractExposedR2dbcTest
import io.bluetape4k.exposed.r2dbc.tests.TestDB
import io.bluetape4k.exposed.r2dbc.tests.withTables
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.IExpressionAlias
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.select
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class CteQueryTest: AbstractExposedR2dbcTest() {

    companion object: KLoggingChannel() {
        private val cteCapableDialects: Set<TestDB> =
            TestDB.ALL_H2 + TestDB.ALL_POSTGRES_LIKE + TestDB.MYSQL_V8
    }

    private object CteUsers: IntIdTable("r2dbc_cte_query_users") {
        val name = varchar("name", 64)
        val active = bool("active")
        val managerId = integer("manager_id").nullable()
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `CTE query renders WITH clause and preserves prepared arguments`(testDB: TestDB) = runSuspendIO {
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

            log.debug { "cte sql=$sql" }
            sql shouldContain "WITH"
            sql.lowercase() shouldContain "active_users"
            builder.args.map { it.second } shouldBeEqualTo listOf(true)

            query.map { it[activeName] }.toList() shouldBeEqualTo listOf("root", "child")
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `recursive CTE query can reference its temporary table fields`(testDB: TestDB) = runSuspendIO {
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

            log.debug { "cte sql=$sql" }
            sql shouldContain "WITH RECURSIVE"
            query.map { it[hierarchyName] }.toList() shouldBeEqualTo listOf("root", "child", "inactive-child")
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `multiple CTEs joined in single WITH clause`(testDB: TestDB) = runSuspendIO {
        Assumptions.assumeTrue { testDB in cteCapableDialects }

        withTables(testDB, CteUsers) {
            seedUsers()

            val activeUsers = CteTable(
                name = "active_users",
                query = CteUsers
                    .select(CteUsers.id, CteUsers.name)
                    .where { CteUsers.active eq true }
            )
            val rootUsers = CteTable(
                name = "root_users",
                query = CteUsers
                    .select(CteUsers.id, CteUsers.name)
                    .where { CteUsers.managerId.isNull() }
            )
            val activeName = activeUsers[CteUsers.name]
            val rootName = rootUsers[CteUsers.name]
            val query = activeUsers
                .crossJoin(rootUsers)
                .select(activeName, rootName)
                .withCtes(activeUsers, rootUsers)
                .orderBy(
                    activeUsers[CteUsers.id] to SortOrder.ASC,
                    rootUsers[CteUsers.id] to SortOrder.ASC
                )
            val expectedPairs = listOf("root:root", "root:other-root", "child:root", "child:other-root")

            val sql = query.prepareSQL(QueryBuilder(prepared = true)).lowercase()

            log.debug { "cte sql=$sql" }
            sql shouldContain "active_users"
            sql shouldContain ", root_users"
            query.map { "${it[activeName]}:${it[rootName]}" }.toList() shouldBeEqualTo expectedPairs
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `CTE with unionAll=false renders UNION not UNION ALL`(testDB: TestDB) = runSuspendIO {
        Assumptions.assumeTrue { testDB in cteCapableDialects }

        withTables(testDB, CteUsers) {
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
                },
                unionAll = false,
            )
            val query = hierarchy
                .select(hierarchy[CteUsers.name])
                .withCte(hierarchy)

            val sql = query.prepareSQL(QueryBuilder(prepared = true))

            log.debug { "sql=$sql" }
            sql shouldContain " UNION "
            sql shouldNotContain "UNION ALL"
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `accessing field not in CTE query set throws error`(testDB: TestDB) = runSuspendIO {
        Assumptions.assumeTrue { testDB in cteCapableDialects }

        withTables(testDB, CteUsers) {
            val idOnlyUsers = CteTable(
                name = "id_only_users",
                query = CteUsers.select(CteUsers.id)
            )

            val ex = assertFailsWith<IllegalStateException> {
                idOnlyUsers[CteUsers.name]
            }

            log.debug { "ex=$ex" }
            ex.message shouldContain "is not in CTE query set"
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `CTE field map handles expression alias correctly`(testDB: TestDB) = runSuspendIO {
        Assumptions.assumeTrue { testDB in cteCapableDialects }

        withTables(testDB, CteUsers) {
            seedUsers()

            val userNameAlias = CteUsers.name.alias("user_name")
            val aliasedUsers = CteTable(
                name = "aliased_users",
                query = CteUsers
                    .select(CteUsers.id, userNameAlias)
                    .where { CteUsers.active eq true }
            )
            val aliasedName = aliasedUsers[userNameAlias as IExpressionAlias<String>]
            val query = aliasedUsers
                .select(aliasedName)
                .withCte(aliasedUsers)
                .orderBy(aliasedUsers[CteUsers.id])

            val sql = query.prepareSQL(QueryBuilder(prepared = true)).lowercase()

            log.debug { "sql=$sql" }
            sql shouldContain "user_name"
            query.map { it[aliasedName] }.toList() shouldBeEqualTo listOf("root", "child")
        }
    }

    private suspend fun seedUsers() {
        CteUsers.insert {
            it[name] = "root"
            it[active] = true
            it[managerId] = null
        }
        CteUsers.insert {
            it[name] = "child"
            it[active] = true
            it[managerId] = 1
        }
        CteUsers.insert {
            it[name] = "inactive-child"
            it[active] = false
            it[managerId] = 2
        }
        CteUsers.insert {
            it[name] = "other-root"
            it[active] = false
            it[managerId] = null
        }
    }
}
