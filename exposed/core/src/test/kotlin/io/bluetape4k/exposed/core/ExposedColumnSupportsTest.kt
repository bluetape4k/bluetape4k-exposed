package io.bluetape4k.exposed.core

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.logging.KLogging
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.math.BigDecimal
import java.math.BigInteger
import java.util.*
import kotlin.random.Random
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import io.bluetape4k.idgenerators.uuid.Uuid as BluetapeUuid

@OptIn(ExperimentalUuidApi::class)
class ExposedColumnSupportsTest {

    companion object: KLogging()

    private object TypedTable: Table("typed_table_for_supports") {
        val intCol = integer("int_col")
        val uuidCol = javaUUID("uuid_col")
    }

    @Test
    fun `convertToLanguageType 는 기본 타입 변환을 지원한다`() {
        convertToLanguageType<Int>("12") shouldBeEqualTo 12
        convertToLanguageType<Long>("123456789") shouldBeEqualTo 123456789L
        convertToLanguageType<String>(123) shouldBeEqualTo "123"

        convertToLanguageType<Boolean>("true") shouldBeEqualTo true
        convertToLanguageType<Boolean>("false") shouldBeEqualTo false
        convertToLanguageType<Boolean>('Y') shouldBeEqualTo true
        convertToLanguageType<Boolean>('N') shouldBeEqualTo false

        val uuid = BluetapeUuid.V7.nextId()
        convertToLanguageType<UUID>(uuid) shouldBeEqualTo uuid

        val bytes = Random.nextBytes(16)
        convertToLanguageType<ByteArray>(bytes) shouldBeEqualTo bytes


        val bigInt = Random.nextLong().toBigInteger()
        convertToLanguageType<BigInteger>(bigInt.toString()) shouldBeEqualTo bigInt

        val bigDecimal = 1234.56.toBigDecimal()
        convertToLanguageType<BigDecimal>("1234.56") shouldBeEqualTo bigDecimal
    }

    @Test
    fun `convertToLanguageType for kotlin Uuid type`() {
        val kotlinUuid = Uuid.generateV7()
        convertToLanguageType<kotlin.uuid.Uuid>(kotlinUuid) shouldBeEqualTo kotlinUuid
    }

    @Test
    fun `convertToLanguageType 는 지원하지 않는 타입에 대해 null 을 반환한다`() {
        convertToLanguageType<Serializable>("serializable").shouldBeNull()
    }

    @Test
    fun `mapToLanguageType 은 컬럼의 language type 으로 변환합니다 - 변환 실패 시는 무시합니다`() {
        listOf("1", "2", "oops", "4").mapToLanguageType(TypedTable.intCol) shouldBeEqualTo listOf(1, 2, 4)
    }

    @Test
    fun `mapToLanguageType 는 컬럼의 language type 을 사용해 변환하고 실패 항목은 제외한다`() {
        val uuid = BluetapeUuid.V7.nextId()
        listOf(uuid.toString(), "not-uuid").mapToLanguageType(TypedTable.uuidCol) shouldBeEqualTo listOf(uuid)
    }
}
