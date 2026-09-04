package io.bluetape4k.exposed.core.dao.id

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.exposed.tests.AbstractExposedTest
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withTables
import org.jetbrains.exposed.v1.core.Table.UuidVersion
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class KotlinUuidTableTest: AbstractExposedTest() {

    private object V4Items: KotlinUuidTable("kotlin_uuid_v4_items") {
        val name = varchar("name", 100)
    }

    private object V7Items: KotlinUuidTable(
        name = "kotlin_uuid_v7_items",
        uuidVersion = UuidVersion.V7,
    ) {
        val name = varchar("name", 100)
        val externalId = uuid("external_id").autoGenerate(UuidVersion.V4)
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `기본 KotlinUuidTable은 V4 UUID를 생성하고 JDBC round-trip을 보장한다`(testDB: TestDB) {
        withTables(testDB, V4Items) {
            val id = V4Items.insert {
                it[name] = "v4-item"
            }[V4Items.id]
            val loadedId = V4Items.selectAll().single()[V4Items.id]

            id.value.uuidVersion() shouldBeEqualTo 4
            loadedId.value shouldBeEqualTo id.value
            V4Items.selectAll().toList() shouldHaveSize 1
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `KotlinUuidTable은 V7을 선택하면 시간 순서와 고유성을 보장한다`(testDB: TestDB) {
        withTables(testDB, V7Items) {
            val ids = listOf("first", "second").map { name ->
                V7Items.insert {
                    it[V7Items.name] = name
                }[V7Items.id].value
            }

            ids.forEach { it.uuidVersion() shouldBeEqualTo 7 }
            ids.zipWithNext().forEach { (previous, current) ->
                (previous.v7Timestamp() <= current.v7Timestamp()).shouldBeTrue()
            }
            ids.toSet() shouldHaveSize 2
            val rows = V7Items.selectAll().toList()
            rows shouldHaveSize 2
            rows.forEach { row ->
                row[V7Items.externalId].uuidVersion() shouldBeEqualTo 4
            }
        }
    }

    private fun Uuid.uuidVersion(): Int = (toByteArray()[6].toInt() ushr 4) and 0x0F

    private fun Uuid.v7Timestamp(): Long = toByteArray()
        .take(6)
        .fold(0L) { timestamp, byte ->
            (timestamp shl 8) or (byte.toInt() and 0xFF).toLong()
        }
}
