package io.bluetape4k.exposed.druid

import io.bluetape4k.logging.KLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/** Query-only Apache Druid JDBC helper backed by Apache Calcite Avatica. */
object DruidJdbc: KLogging() {

    /** Apache Calcite Avatica remote JDBC driver class name. */
    val DRIVER = "org.apache.calcite.avatica.remote.Driver"

    init {
        Class.forName(DRIVER)
        log.debug("Druid Avatica JDBC driver registered: $DRIVER")
    }

    /** Opens a new JDBC [Connection] for a Druid Router/Broker Avatica endpoint. */
    fun connection(options: DruidConnectionOptions = DruidConnectionOptions()): Connection =
        DriverManager.getConnection(options.jdbcUrl(), options.toProperties())

    /**
     * Executes a read-only SQL query and maps the [ResultSet].
     *
     * This helper intentionally does not expose DDL, DML, repository, migration,
     * or Exposed dialect behavior. Callers own SQL text and result mapping.
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

    /** Executes [query] on [Dispatchers.IO] or the supplied dispatcher. */
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

    /** Discovers Druid datasource columns through `INFORMATION_SCHEMA.COLUMNS`. */
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

/** Column metadata returned from Druid `INFORMATION_SCHEMA.COLUMNS`. */
data class DruidColumnMetadata(
    val tableSchema: String,
    val tableName: String,
    val columnName: String,
    val dataType: String,
    val ordinalPosition: Int,
    val isNullable: String,
)
