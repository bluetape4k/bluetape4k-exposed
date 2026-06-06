package io.bluetape4k.exposed.starrocks.dialect

import org.jetbrains.exposed.v1.jdbc.vendors.MysqlDialectMetadata

/**
 * StarRocks JDBC metadata adapter for Exposed.
 *
 * StarRocks Connector/J documents standard [java.sql.DatabaseMetaData] support.
 * The initial adapter therefore keeps MySQL metadata behavior instead of
 * masking metadata calls preemptively.
 */
class StarRocksDialectMetadata: MysqlDialectMetadata()
