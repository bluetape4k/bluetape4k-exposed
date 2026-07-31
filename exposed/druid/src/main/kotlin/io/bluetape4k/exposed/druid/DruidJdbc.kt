package io.bluetape4k.exposed.druid

import io.bluetape4k.logging.KLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/** Apache Calcite Avatica 기반의 query-only Apache Druid JDBC helper입니다. */
object DruidJdbc: KLogging() {

    /** Apache Calcite Avatica remote JDBC driver class name입니다. */
    val DRIVER = "org.apache.calcite.avatica.remote.Driver"

    init {
        Class.forName(DRIVER)
        log.debug("Druid Avatica JDBC driver registered: $DRIVER")
    }

    /** Druid Router/Broker Avatica endpoint에 대한 새 JDBC [Connection]을 엽니다. */
    fun connection(options: DruidConnectionOptions = DruidConnectionOptions()): Connection =
        DriverManager.getConnection(options.jdbcUrl(), options.toProperties())

    /**
     * Read-only SQL query를 실행하고 [ResultSet]을 매핑합니다.
     *
     * 이 helper는 DDL, DML, repository, migration, Exposed dialect 동작을 의도적으로 노출하지
     * 않습니다. SQL text와 결과 매핑의 책임은 호출자에게 있습니다.
     */
    fun <T> query(
        sql: String,
        options: DruidConnectionOptions = DruidConnectionOptions(),
        mapper: (ResultSet) -> T,
    ): List<T> {
        requireQueryOnlySql(sql)
        return connection(options).use { conn ->
            conn.createStatement().use { statement ->
                statement.executeQuery(sql).use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(mapper(rs))
                        }
                    }
                }
            }
        }
    }

    /** [query]를 [Dispatchers.IO] 또는 호출자가 제공한 dispatcher에서 실행합니다. */
    suspend fun <T> querySuspend(
        sql: String,
        options: DruidConnectionOptions = DruidConnectionOptions(),
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        mapper: (ResultSet) -> T,
    ): List<T> = withContext(dispatcher) {
        try {
            query(sql = sql, options = options, mapper = mapper)
        } catch (e: CancellationException) {
            throw e
        }
    }

    /** `INFORMATION_SCHEMA.COLUMNS`를 통해 Druid datasource column을 조회합니다. */
    fun listColumns(
        datasource: String,
        schema: String = "druid",
        options: DruidConnectionOptions = DruidConnectionOptions(),
    ): List<DruidColumnMetadata> {
        require(datasource.isNotBlank()) { "datasource must not be blank." }
        require(schema.isNotBlank()) { "schema must not be blank." }

        return connection(options).use { conn ->
            conn.prepareStatement(DRUID_COLUMNS_SQL).use { statement ->
                statement.setString(1, schema)
                statement.setString(2, datasource)
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                DruidColumnMetadata(
                                    tableSchema = rs.getString("TABLE_SCHEMA"),
                                    tableName = rs.getString("TABLE_NAME"),
                                    columnName = rs.getString("COLUMN_NAME"),
                                    dataType = rs.getString("DATA_TYPE"),
                                    ordinalPosition = rs.getInt("ORDINAL_POSITION"),
                                    isNullable = rs.getString("IS_NULLABLE"),
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    private fun requireQueryOnlySql(sql: String) {
        val normalized = sql.trimStart().lowercase()
        require(normalized.isNotBlank()) { "sql must not be blank." }
        require(
            normalized.startsWith("select") ||
                normalized.startsWith("with") ||
                normalized.startsWith("explain") ||
                normalized.startsWith("describe") ||
                normalized.startsWith("show")
        ) {
            "DruidJdbc is query-only; DDL, DML, repository, and migration statements are out of scope."
        }
    }

    internal const val DRUID_COLUMNS_SQL: String =
        "SELECT TABLE_SCHEMA, TABLE_NAME, COLUMN_NAME, DATA_TYPE, ORDINAL_POSITION, IS_NULLABLE " +
            "FROM INFORMATION_SCHEMA.COLUMNS " +
            "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? " +
            "ORDER BY ORDINAL_POSITION"
}

/** Druid `INFORMATION_SCHEMA.COLUMNS`가 반환하는 column metadata입니다. */
data class DruidColumnMetadata(
    val tableSchema: String,
    val tableName: String,
    val columnName: String,
    val dataType: String,
    val ordinalPosition: Int,
    val isNullable: String,
)
