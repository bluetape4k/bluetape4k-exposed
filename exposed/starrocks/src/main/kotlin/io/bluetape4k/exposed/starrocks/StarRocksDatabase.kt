package io.bluetape4k.exposed.starrocks

import io.bluetape4k.exposed.starrocks.dialect.StarRocksDialect
import io.bluetape4k.exposed.starrocks.dialect.StarRocksDialectMetadata
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import org.jetbrains.exposed.v1.core.DatabaseApi
import org.jetbrains.exposed.v1.jdbc.Database
import java.sql.DriverManager
import javax.sql.DataSource

/**
 * Factory object for connecting Exposed to StarRocks through Connector/J.
 *
 * The factory registers the `jdbc:starrocks` driver prefix, the `starrocks`
 * Exposed dialect, and StarRocks metadata adapter on first access.
 *
 * ## Basic usage
 *
 * ```kotlin
 * val db = StarRocksDatabase.connect(
 *     host = "localhost",
 *     port = 9030,
 *     catalog = "default_catalog",
 *     database = "analytics",
 *     user = "root",
 * )
 * ```
 *
 * StarRocks table DDL is engine-specific. This module only proves the narrow
 * local smoke path and does not claim MySQL or PostgreSQL DDL parity.
 */
object StarRocksDatabase: KLogging() {

    /**
     * StarRocks Connector/J driver class.
     *
     * This is a `val` so property access triggers dialect registration through
     * this object's initializer.
     */
    val DRIVER: String = "com.starrocks.cj.jdbc.Driver"

    init {
        Database.registerJdbcDriver("jdbc:starrocks", DRIVER, StarRocksDialect.dialectName)
        DatabaseApi.registerDialect(StarRocksDialect.dialectName) { StarRocksDialect() }
        Database.registerDialectMetadata(StarRocksDialect.dialectName) { StarRocksDialectMetadata() }
        log.debug("StarRocks dialect registered: ${StarRocksDialect.dialectName}")
    }

    /**
     * Connects to a StarRocks database using host, FE query port, catalog, and database.
     *
     * Builds a JDBC URL of the form
     * `jdbc:starrocks://{host}:{port}/{catalog}.{database}`.
     */
    fun connect(
        host: String = "localhost",
        port: Int = 9030,
        catalog: String = "default_catalog",
        database: String,
        user: String = "root",
        password: String = "",
        options: StarRocksConnectionOptions = StarRocksConnectionOptions(),
    ): Database {
        host.requireNotBlank("host")
        port.requireInRange(1, 65535, "port")
        catalog.requireNotBlank("catalog")
        database.requireNotBlank("database")
        user.requireNotBlank("user")

        return connect(
            jdbcUrl = buildJdbcUrl(host, port, catalog, database),
            user = user,
            password = password,
            options = options,
        )
    }

    /**
     * Connects to StarRocks using a fully-qualified JDBC URL.
     */
    fun connect(
        jdbcUrl: String,
        user: String = "root",
        password: String = "",
        options: StarRocksConnectionOptions = StarRocksConnectionOptions(),
    ): Database {
        jdbcUrl.requireNotBlank("jdbcUrl")
        require(jdbcUrl.startsWith(STARROCKS_JDBC_PREFIX)) {
            "jdbcUrl must start with '$STARROCKS_JDBC_PREFIX': $jdbcUrl"
        }
        user.requireNotBlank("user")

        return Database.connect(
            getNewConnection = {
                val props = options.toProperties(user, password)
                val raw = DriverManager.getConnection(jdbcUrl, props)
                runCatching { StarRocksConnectionWrapper(raw) }
                    .getOrElse { e ->
                        runCatching { raw.close() }.onFailure { closeEx ->
                            e.addSuppressed(closeEx)
                        }
                        throw e
                    }
            }
        )
    }

    /**
     * Connects to StarRocks through a caller-managed [DataSource].
     */
    fun connect(dataSource: DataSource): Database =
        Database.connect(
            getNewConnection = {
                val raw = dataSource.connection
                runCatching { StarRocksConnectionWrapper(raw) }
                    .getOrElse { e ->
                        runCatching { raw.close() }.onFailure { closeEx ->
                            e.addSuppressed(closeEx)
                        }
                        throw e
                    }
            }
        )

    internal fun buildJdbcUrl(host: String, port: Int, catalog: String, database: String): String =
        "jdbc:starrocks://$host:$port/$catalog.$database"

    internal fun buildBootstrapJdbcUrl(host: String, port: Int): String {
        host.requireNotBlank("host")
        port.requireInRange(1, 65535, "port")
        return "jdbc:starrocks://$host:$port"
    }

    private const val STARROCKS_JDBC_PREFIX: String = "jdbc:starrocks://"
}
