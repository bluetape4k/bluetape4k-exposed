package io.bluetape4k.exposed.dao.id

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.exposed.core.dao.id.KotlinUuidTable
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withTables
import org.jetbrains.exposed.v1.core.Table.UuidVersion
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass
import org.jetbrains.exposed.v1.dao.flushCache
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class KotlinUuidEntityTest: AbstractCustomIdTableTest() {

    object Items: KotlinUuidTable(
        name = "kotlin_uuid_entities",
        uuidVersion = UuidVersion.V7,
    ) {
        val name = varchar("name", 100)
    }

    class Item(id: EntityID<Uuid>): UuidEntity(id) {
        companion object : UuidEntityClass<Item>(Items)

        var name by Items.name
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `upstream Kotlin UuidEntity는 KotlinUuidTable ID를 DAO로 round-trip한다`(testDB: TestDB) {
        withTables(testDB, Items) {
            val entity = Item.new {
                name = "kotlin-uuid-item"
            }
            flushCache()

            val loaded = Item.findById(entity.id).shouldNotBeNull()
            loaded.id.value shouldBeEqualTo entity.id.value
            loaded.name shouldBeEqualTo "kotlin-uuid-item"
            loaded.id.value.uuidVersion() shouldBeEqualTo 7
            Items.selectAll().count().toInt() shouldBeEqualTo 1
        }
    }

    private fun Uuid.uuidVersion(): Int = (toByteArray()[6].toInt() ushr 4) and 0x0F
}
