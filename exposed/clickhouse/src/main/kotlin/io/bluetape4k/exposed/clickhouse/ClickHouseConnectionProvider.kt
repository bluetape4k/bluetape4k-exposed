package io.bluetape4k.exposed.clickhouse

import java.sql.Connection

/**
 * RowBinary enabled/disabled connection profile을 caller가 생성하는 경계입니다.
 *
 * 반환한 connection은 한 번의 RowBinary/JDBC 작업에만 사용해야 합니다. executor는
 * 빌린 connection만 닫고 DataSource, pool, ambient Exposed transaction은 소유하지
 * 않습니다.
 */
fun interface ClickHouseConnectionProvider {

    /**
     * [rowBinaryEnabled]에 맞는 독립 connection profile을 엽니다.
     *
     * beta property는 connection-scoped로 적용해야 하며, 이미 열린 다른 profile의
     * property를 mutate해서는 안 됩니다.
     */
    fun open(rowBinaryEnabled: Boolean): Connection
}
