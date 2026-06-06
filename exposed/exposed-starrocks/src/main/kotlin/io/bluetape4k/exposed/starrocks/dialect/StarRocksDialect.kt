package io.bluetape4k.exposed.starrocks.dialect

import io.bluetape4k.logging.KLogging
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnDiff
import org.jetbrains.exposed.v1.core.InternalApi
import org.jetbrains.exposed.v1.core.vendors.MysqlDialect

/**
 * Minimal Exposed dialect for StarRocks Connector/J.
 *
 * StarRocks uses a MySQL-compatible wire protocol, but its OLAP DDL is
 * StarRocks-specific. This dialect reuses Exposed's MySQL SQL generator only
 * for the narrow query and smoke-test surface, while disabling unproven schema
 * mutation features.
 */
class StarRocksDialect: MysqlDialect() {

    companion object: KLogging() {
        /** Dialect name registered with Exposed. */
        const val dialectName: String = "starrocks"
    }

    override val name: String = dialectName

    override val supportsColumnTypeChange: Boolean = false

    override val supportsCreateSequence: Boolean = false

    override val supportsMultipleGeneratedKeys: Boolean = false

    override val supportsTernaryAffectedRowValues: Boolean = false

    override val supportsSetDefaultReferenceOption: Boolean = false

    override val supportsRestrictReferenceOption: Boolean = false

    override val requiresAutoCommitOnCreateDrop: Boolean = true

    @OptIn(InternalApi::class)
    override fun modifyColumn(column: Column<*>, columnDiff: ColumnDiff): List<String> = emptyList()
}
