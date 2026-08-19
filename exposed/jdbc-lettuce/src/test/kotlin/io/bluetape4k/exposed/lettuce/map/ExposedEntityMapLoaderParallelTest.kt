package io.bluetape4k.exposed.lettuce.map

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.exposed.jdbc.JdbcKeyRange
import io.bluetape4k.exposed.jdbc.JdbcParallelKeyEnumerationOptions
import io.bluetape4k.exposed.tests.AbstractExposedTest
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withTables
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.jdbc.insert
import org.junit.jupiter.api.Test

class ExposedEntityMapLoaderParallelTest: AbstractExposedTest() {

    private data class LoaderEntity(val id: Long, val name: String)

    private object LoaderTable: LongIdTable("lettuce_parallel_loader_test") {
        val name = varchar("name", 64)
    }

    private fun ResultRow.toLoaderEntity(): LoaderEntity =
        LoaderEntity(
            id = this[LoaderTable.id].value,
            name = this[LoaderTable.name],
        )

    @Test
    fun `parallel key enumeration matches sequential keyset and preserves range order`() {
        withTables(TestDB.H2, LoaderTable) {
            repeat(8) { index ->
                LoaderTable.insert { it[name] = "user-$index" }
            }
            commit()

            val loader = ExposedEntityMapLoader(
                table = LoaderTable,
                batchSize = 2,
                toEntity = { row -> row.toLoaderEntity() },
            )
            val sequential = loader.loadAllKeys().toList()
            val parallel =
                loader.loadAllKeysInParallel(
                    ranges = listOf(
                        JdbcKeyRange(upperExclusive = 5L),
                        JdbcKeyRange(lowerInclusive = 5L),
                    ),
                    options = JdbcParallelKeyEnumerationOptions(maxConcurrency = 2),
                )

            parallel shouldBeEqualTo sequential
            parallel shouldBeEqualTo (1L..8L).toList()
            parallel.distinct() shouldBeEqualTo parallel
        }
    }

    @Test
    fun `parallel key enumeration keeps empty range and default path contracts`() {
        withTables(TestDB.H2, LoaderTable) {
            commit()
            val loader = ExposedEntityMapLoader(
                table = LoaderTable,
                toEntity = { row -> row.toLoaderEntity() },
            )

            loader.loadAllKeysInParallel(emptyList()).shouldBeEmpty()
            assertFailsWith<IllegalArgumentException> {
                loader.loadAllKeysInParallel(
                    listOf(
                        JdbcKeyRange(upperExclusive = 5L),
                        JdbcKeyRange(lowerInclusive = 4L),
                    )
                )
            }
            loader.loadAllKeys().toList().shouldBeEmpty()
        }
    }
}
