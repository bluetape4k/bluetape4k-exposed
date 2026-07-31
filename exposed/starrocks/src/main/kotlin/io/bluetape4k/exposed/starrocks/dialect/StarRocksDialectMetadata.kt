package io.bluetape4k.exposed.starrocks.dialect

import org.jetbrains.exposed.v1.jdbc.vendors.MysqlDialectMetadata

/**
 * Exposed용 StarRocks JDBC metadata adapter입니다.
 *
 * StarRocks Connector/J가 표준 [java.sql.DatabaseMetaData] 지원을 문서화하므로
 * 초기 adapter는 metadata 호출을 선제적으로 가리지 않고 MySQL metadata 동작을 유지합니다.
 */
class StarRocksDialectMetadata: MysqlDialectMetadata()
