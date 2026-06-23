package io.bluetape4k.exposed.core.tink

import io.bluetape4k.logging.KLogging
import io.bluetape4k.tink.aead.TinkAead
import org.jetbrains.exposed.v1.core.BinaryColumnType
import org.jetbrains.exposed.v1.core.ColumnTransformer
import org.jetbrains.exposed.v1.core.ColumnWithTransform

/**
 * 바이트 배열 값을 Google Tink AEAD로 암호화해서 `VARBINARY` 컬럼에 저장하는 변환 컬럼 타입입니다.
 *
 * ## 동작/계약
 * - DB 저장 시 [ByteArrayTinkAeadEncryptionTransformer.unwrap]로 암호화하고, 조회 시 `wrap`으로 복호화합니다.
 * - AEAD는 매번 다른 nonce를 사용하므로 같은 평문이라도 암호화 결과가 달라집니다.
 *   따라서 인덱스/조건 검색에는 사용할 수 없습니다.
 * - 인덱스/조건 검색이 필요한 경우에는 [TinkDaeadBinaryColumnType]을 사용하세요.
 * - 컬럼 길이 검증은 생성 전에 호출되는 `tinkAeadBinary(...).requirePositiveNumber(...)` 경로에서 수행됩니다.
 *
 * ```kotlin
 * object T1: IntIdTable("binary_secret_table") {
 *     val data = tinkAeadBinary("data", 512, persistedAead)
 * }
 * val id = T1.insertAndGetId { it[data] = "민감한 데이터".toByteArray() }
 * val row = T1.selectAll().where { T1.id eq id }.single()
 * // row[T1.data].toString(Charsets.UTF_8) == "민감한 데이터"
 * ```
 *
 * @param encryptor Tink AEAD 암/복호화를 수행할 인스턴스입니다.
 * @param length 암호문 바이트를 저장할 컬럼 길이입니다.
 */
class TinkAeadBinaryColumnType private constructor(
    length: Int,
    transformer: ByteArrayTinkAeadEncryptionTransformer,
): ColumnWithTransform<ByteArray, ByteArray>(
    BinaryColumnType(length),
    transformer
) {
    @Deprecated(
        message = "This constructor uses empty associated data and is retained for legacy migration only. " +
                "Use Table.tinkAeadBinary(...) or pass explicit associatedData.",
    )
    constructor(
        encryptor: TinkAead,
        length: Int,
    ): this(length, ByteArrayTinkAeadEncryptionTransformer(encryptor))

    constructor(
        encryptor: TinkAead,
        length: Int,
        associatedData: ByteArray,
    ): this(length, ByteArrayTinkAeadEncryptionTransformer(encryptor, associatedData))
}

/**
 * 바이너리 컬럼의 저장/조회 경계에서 Tink AEAD 암복호화를 수행하는 transformer입니다.
 *
 * ## 동작/계약
 * - [unwrap]은 평문 바이트 배열을 암호화해 DB 저장 값으로 사용합니다.
 * - [wrap]은 DB에서 읽은 암호문 바이트 배열을 복호화해 원본 바이트 배열로 변환합니다.
 * - AEAD는 비결정적이므로 동일 평문에 대해 매번 다른 암호문이 생성됩니다.
 * - round-trip(`wrap(unwrap(x))`)은 원본 바이트 배열과 동일합니다.
 *
 * ```kotlin
 * val transformer = ByteArrayTinkAeadEncryptionTransformer(persistedAead)
 * val source = "tink-aead-binary-source".toByteArray()
 * val restored = transformer.wrap(transformer.unwrap(source))
 * // restored.contentEquals(source) == true
 * ```
 *
 * @param encryptor Tink AEAD 암/복호화 인스턴스입니다.
 */
class ByteArrayTinkAeadEncryptionTransformer private constructor(
    private val encryptor: TinkAead,
    private val associatedData: ByteArray,
    @Suppress("UNUSED_PARAMETER") copyMarker: Boolean,
): ColumnTransformer<ByteArray, ByteArray> {

    constructor(
        encryptor: TinkAead,
    ): this(encryptor, EMPTY_TINK_ASSOCIATED_DATA.copyOf(), true)

    constructor(
        encryptor: TinkAead,
        associatedData: ByteArray,
    ): this(encryptor, associatedData.copyOf(), true)

    // ByteArrayTinkDaeadEncryptionTransformer 등 다른 Transformer 클래스와 동일하게
    // KLogging companion object를 추가해 암복호화 실패 등 이상 동작을 로그로 추적할 수 있도록 합니다.
    companion object: KLogging()

    /**
     * 평문 바이트 배열을 Tink AEAD로 암호화합니다.
     *
     * ## 동작/계약
     * - 입력 [value]를 [encryptor]로 암호화합니다.
     * - 비결정적 암호화이므로 동일 입력에 대해 매번 다른 암호문이 반환됩니다.
     *
     * @param value 암호화할 평문 바이트 배열입니다.
     */
    override fun unwrap(value: ByteArray): ByteArray {
        return encryptor.encrypt(value, associatedData)
    }

    /**
     * 암호문 바이트 배열을 Tink AEAD로 복호화합니다.
     *
     * ## 동작/계약
     * - 입력 [value]를 [encryptor]로 복호화합니다.
     * - 암호문 형식이 아니거나 키가 맞지 않으면 Tink 예외가 전파됩니다.
     *
     * @param value 복호화할 암호문 바이트 배열입니다.
     */
    override fun wrap(value: ByteArray): ByteArray {
        return encryptor.decrypt(value, associatedData)
    }
}
