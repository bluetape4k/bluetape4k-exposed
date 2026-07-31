package io.bluetape4k.exposed.core.tink

import org.jetbrains.exposed.v1.core.Table

internal val EMPTY_TINK_ASSOCIATED_DATA: ByteArray = ByteArray(0)

/**
 * Tink encrypted column의 associated data를 제공하는 계약입니다.
 *
 * Column transformer가 provider 결과를 복사하므로, 호출자는 column을 생성한 뒤 원본 array를
 * 안전하게 재사용하거나 변경할 수 있습니다.
 */
fun interface TinkColumnAssociatedDataProvider {

    /**
     * [tableName]과 [columnName]으로 식별되는 encrypted column에 바인딩할 associated data를 반환합니다.
     */
    fun associatedData(tableName: String, columnName: String): ByteArray

    companion object {

        /**
         * Ciphertext를 column context에 바인딩하지 않는 legacy 호환 provider입니다.
         */
        val Empty: TinkColumnAssociatedDataProvider = TinkColumnAssociatedDataProvider { _, _ ->
            EMPTY_TINK_ASSOCIATED_DATA
        }

        /**
         * Ciphertext를 안정적인 Exposed table과 column name에 바인딩하는 기본 provider입니다.
         */
        val TableAndColumn: TinkColumnAssociatedDataProvider = TinkColumnAssociatedDataProvider { tableName, columnName ->
            "bluetape4k-exposed-tink:v1:$tableName:$columnName".toByteArray(Charsets.UTF_8)
        }
    }
}

internal fun Table.tinkAssociatedData(
    columnName: String,
    provider: TinkColumnAssociatedDataProvider,
): ByteArray =
    provider.associatedData(tableName, columnName).copyOf()
