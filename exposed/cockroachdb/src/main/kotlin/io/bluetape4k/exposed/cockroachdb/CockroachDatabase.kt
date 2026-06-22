package io.bluetape4k.exposed.cockroachdb

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import java.sql.DriverManager
import javax.sql.DataSource

/**
 * Factory object for connecting Exposed to CockroachDB through PostgreSQL JDBC.
 *
 * CockroachDB speaks the PostgreSQL wire protocol, so this first module slice
 * uses the PostgreSQL JDBC driver and the default Exposed PostgreSQL dialect.
 * It intentionally does not register a custom CockroachDB dialect; PostgreSQL
 * compatibility and DDL boundary work is tracked separately.
 *
 * ## Basic usage
 *
 * ```kotlin
 * val db = CockroachDatabase.connect(
 *     host = "localhost",
 *     port = 26257,
 *     database = "defaultdb",
 *     user = "root",
 * )
 * ```
 */
object CockroachDatabase: KLogging() {

    /**
     * PostgreSQL JDBC driver used by CockroachDB's PostgreSQL wire protocol.
     */
    val DRIVER: String = "org.postgresql.Driver"

    /**
     * Connects to CockroachDB using host, SQL port, database, and credentials.
     */
    fun connect(
        host: String = "localhost",
        port: Int = 26257,
        database: String = "defaultdb",
        user: String = "root",
        password: String = "",
        databaseConfig: DatabaseConfig? = null,
    ): Database {
        host.requireNotBlank("host")
        port.requireInRange(1, 65535, "port")
        database.requireNotBlank("database")
        user.requireNotBlank("user")

        return connect(
            jdbcUrl = buildJdbcUrl(host, port, database),
            user = user,
            password = password,
            databaseConfig = databaseConfig,
        )
    }

    /**
     * Connects to CockroachDB using a PostgreSQL JDBC URL.
     */
    fun connect(
        jdbcUrl: String,
        user: String = "root",
        password: String = "",
        databaseConfig: DatabaseConfig? = null,
    ): Database {
        jdbcUrl.requireNotBlank("jdbcUrl")
        require(jdbcUrl.startsWith(COCKROACH_JDBC_PREFIX)) {
            "jdbcUrl must start with '$COCKROACH_JDBC_PREFIX': $jdbcUrl"
        }
        user.requireNotBlank("user")

        Class.forName(DRIVER)

        return Database.connect(
            getNewConnection = { DriverManager.getConnection(jdbcUrl, user, password) },
            databaseConfig = databaseConfig ?: DatabaseConfig {},
        )
    }

    /**
     * Connects to CockroachDB through a caller-managed [DataSource].
     */
    fun connect(
        dataSource: DataSource,
        databaseConfig: DatabaseConfig? = null,
    ): Database =
        Database.connect(
            datasource = dataSource,
            databaseConfig = databaseConfig ?: DatabaseConfig {},
        )

    /**
     * Builds a PostgreSQL JDBC URL suitable for CockroachDB.
     */
    fun buildJdbcUrl(host: String, port: Int = 26257, database: String = "defaultdb"): String {
        host.requireNotBlank("host")
        port.requireInRange(1, 65535, "port")
        database.requireNotBlank("database")
        return "$COCKROACH_JDBC_PREFIX$host:$port/$database"
    }

    private const val COCKROACH_JDBC_PREFIX: String = "jdbc:postgresql://"
}
