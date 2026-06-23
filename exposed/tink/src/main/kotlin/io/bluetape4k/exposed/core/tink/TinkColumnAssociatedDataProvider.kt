package io.bluetape4k.exposed.core.tink

import org.jetbrains.exposed.v1.core.Table

internal val EMPTY_TINK_ASSOCIATED_DATA: ByteArray = ByteArray(0)

/**
 * Tink encrypted column associated data provider.
 *
 * The provider result is copied by column transformers, so callers may safely reuse or mutate their source array after
 * constructing the column.
 */
fun interface TinkColumnAssociatedDataProvider {

    /**
     * Returns the associated data bound to the encrypted column identified by [tableName] and [columnName].
     */
    fun associatedData(tableName: String, columnName: String): ByteArray

    companion object {

        /**
         * Legacy-compatible provider that does not bind ciphertext to a column context.
         */
        val Empty: TinkColumnAssociatedDataProvider = TinkColumnAssociatedDataProvider { _, _ ->
            EMPTY_TINK_ASSOCIATED_DATA
        }

        /**
         * Default provider that binds ciphertext to the stable Exposed table and column names.
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
