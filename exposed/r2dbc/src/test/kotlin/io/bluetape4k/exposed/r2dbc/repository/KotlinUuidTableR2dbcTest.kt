package io.bluetape4k.exposed.r2dbc.repository

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.exposed.core.dao.id.KotlinUuidTable
import io.bluetape4k.exposed.r2dbc.tests.AbstractExposedR2dbcTest
import io.bluetape4k.exposed.r2dbc.tests.TestDB
import io.bluetape4k.exposed.r2dbc.tests.withTables
import io.bluetape4k.junit5.coroutines.runSuspendIO
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table.UuidVersion
import org.jetbrains.exposed.v1.r2dbc.insertAndGetId
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class KotlinUuidTableR2dbcTest: AbstractExposedR2dbcTest() {

    private object Items: KotlinUuidTable(
        name = "kotlin_uuid_r2dbc_items",
        uuidVersion = UuidVersion.V7,
    ) {
        val name = varchar("name", 100)
    }

    private data class ItemRecord(
        val id: Uuid,
        val name: String,
    )

    private class ItemRepository: KotlinUuidR2dbcRepository<ItemRecord> {
        override val table = Items

        override fun extractId(entity: ItemRecord): Uuid = entity.id

        override suspend fun ResultRow.toEntity(): ItemRecord = ItemRecord(
            id = this[Items.id].value,
            name = this[Items.name],
        )
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `KotlinUuidR2dbcRepository는 V7 ID를 R2DBC round-trip한다`(testDB: TestDB) = runSuspendIO {
        withTables(testDB, Items) {
            val id = Items.insertAndGetId {
                it[Items.name] = "r2dbc-kotlin-uuid-item"
            }
            val loaded = ItemRepository().findById(id.value)

            loaded.id shouldBeEqualTo id.value
            loaded.name shouldBeEqualTo "r2dbc-kotlin-uuid-item"
            loaded.id.uuidVersion() shouldBeEqualTo 7
        }
    }

    private fun Uuid.uuidVersion(): Int = (toByteArray()[6].toInt() ushr 4) and 0x0F
}
