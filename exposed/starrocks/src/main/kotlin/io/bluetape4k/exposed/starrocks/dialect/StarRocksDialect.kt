package io.bluetape4k.exposed.starrocks.dialect

import io.bluetape4k.logging.KLogging
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnDiff
import org.jetbrains.exposed.v1.core.InternalApi
import org.jetbrains.exposed.v1.core.vendors.MysqlDialect

/**
 * StarRocks Connector/J용 최소 Exposed dialect입니다.
 *
 * StarRocks는 MySQL 호환 wire protocol을 사용하지만 OLAP DDL은 StarRocks 고유 형식입니다.
 * 이 dialect는 제한된 query와 smoke-test 범위에서만 Exposed의 MySQL SQL generator를 재사용하며
 * 입증되지 않은 schema mutation 기능은 비활성화합니다.
 */
class StarRocksDialect: MysqlDialect() {

    companion object: KLogging() {
/** Exposed에 등록하는 dialect 이름입니다. */
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
