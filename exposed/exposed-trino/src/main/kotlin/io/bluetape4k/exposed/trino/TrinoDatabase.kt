package io.bluetape4k.exposed.trino

import io.bluetape4k.exposed.trino.dialect.TrinoDialect
import io.bluetape4k.exposed.trino.dialect.TrinoDialectMetadata
import io.bluetape4k.logging.KLogging
import org.jetbrains.exposed.v1.core.DatabaseApi
import org.jetbrains.exposed.v1.jdbc.Database
import java.sql.DriverManager

/**
 * Factory object for connecting to a Trino database via Exposed ORM.
 *
 * Registers the Trino JDBC driver and dialect on first access, then provides
 * `connect` overloads for host/port, JDBC URL, and `javax.sql.DataSource`.
 *
 * ## Basic usage
 *
 * ```kotlin
 * val db = TrinoDatabase.connect(
 *     host = "trino-coordinator",
 *     port = 8080,
 *     catalog = "hive",
 *     schema = "default",
 *     user = "analyst",
 * )
 * transaction(db) {
 *     val rows = MyTable.selectAll().toList()
 * }
 * ```
 *
 * ## Coroutine usage
 *
 * ```kotlin
 * val db = TrinoDatabase.connect("jdbc:trino://host:8080/hive/default", user = "analyst")
 *
 * val rows = suspendTransaction(db) {
 *     MyTable.selectAll().toList()
 * }
 *
 * queryFlow(db) {
 *     MyTable.selectAll()
 * }.collect { row -> ... }
 * ```
 *
 * ## autocommit behaviour
 *
 * - Trino does not support transactions. Every statement runs in autocommit mode.
 * - Multiple DML statements inside a `transaction {}` block are NOT atomic; a
 *   mid-block failure leaves preceding statements already committed.
 * - `rollback()` is a no-op adapter provided for Exposed framework compatibility.
 * - Nested transactions and savepoints are accepted but provide no atomicity guarantees.
 * - Prefer [TrinoTable] over [org.jetbrains.exposed.v1.core.Table] for DDL so that
 *   `PRIMARY KEY` clauses are stripped before Trino sees them.
 */
object TrinoDatabase : KLogging() {

    /**
     * Trino JDBC driver class name.
     *
     * Declared as `val` (not `const val`) so that accessing this property triggers
     * the `init` block and registers the driver. A `const val` is inlined at compile
     * time and may not trigger object initialisation.
     */
    val DRIVER = "io.trino.jdbc.TrinoDriver"

    init {
        Database.registerJdbcDriver("jdbc:trino", DRIVER, TrinoDialect.dialectName)
        DatabaseApi.registerDialect(TrinoDialect.dialectName) { TrinoDialect() }
        Database.registerDialectMetadata(TrinoDialect.dialectName) { TrinoDialectMetadata() }
        log.debug("Trino dialect registered: ${TrinoDialect.dialectName}")
    }

    /**
     * Connects to a Trino database using individual host/port/catalog/schema parameters.
     *
     * Builds a JDBC URL of the form `jdbc:trino://{host}:{port}/{catalog}/{schema}`.
     *
     * **Warning**: Trino does not support transactions. All statements run in autocommit
     * mode; a failure mid-block leaves preceding DML already committed.
     * Use [TrinoTable] for DDL to strip unsupported `PRIMARY KEY` syntax.
     *
     * @param host Trino coordinator host (default: `localhost`)
     * @param port Trino coordinator port (default: `8080`)
     * @param catalog Trino catalog name (default: `memory`)
     * @param schema Trino schema name (default: `default`)
     * @param user Connection user (default: `trino`)
     * @return Exposed [Database] instance
     */
    fun connect(
        host: String = "localhost",
        port: Int = 8080,
        catalog: String = "memory",
        schema: String = "default",
        user: String = "trino",
        options: TrinoConnectionOptions = TrinoConnectionOptions(),
    ): Database {
        // A blank host produces "jdbc:trino://:8080//" — an invalid URL that causes
        // an obscure DriverManager exception. Fail early with a clear message.
        requireNotNull(host.ifBlank { null }) { "host must not be blank." }
        // An invalid port only fails at TCP connect time; reject it early.
        require(port in 1..65535) { "port must be in range 1..65535: $port" }
        // Trino requires catalog and schema as path segments in the JDBC URL.
        requireNotNull(catalog.ifBlank { null }) { "catalog must not be blank." }
        requireNotNull(schema.ifBlank { null }) { "schema must not be blank." }

        val url = "jdbc:trino://$host:$port/$catalog/$schema"
        return Database.connect(
            getNewConnection = {
                val props = options.toProperties(user)
                // Close the raw connection on wrapper construction failure to prevent leaks.
                val raw = DriverManager.getConnection(url, props)
                runCatching { TrinoConnectionWrapper(raw) }
                    .getOrElse { e -> raw.runCatching { close() }; throw e }
            }
        )
    }

    /**
     * Connects to a Trino database using a fully-qualified JDBC URL.
     *
     * **Warning**: Trino does not support transactions. All statements run in autocommit
     * mode; a failure mid-block leaves preceding DML already committed.
     * Use [TrinoTable] for DDL to strip unsupported `PRIMARY KEY` syntax.
     *
     * @param jdbcUrl Trino JDBC URL (e.g. `jdbc:trino://host:8080/hive/default`)
     * @param user Connection user (default: `trino`)
     * @return Exposed [Database] instance
     */
    fun connect(
        jdbcUrl: String,
        user: String = "trino",
        options: TrinoConnectionOptions = TrinoConnectionOptions(),
    ): Database {
        // A blank URL causes a "No suitable driver" exception from DriverManager.
        requireNotNull(jdbcUrl.ifBlank { null }) { "jdbcUrl must not be blank." }
        // The Trino driver only handles URLs prefixed with "jdbc:trino://".
        // Passing a different DB URL silently fails with an unhelpful "No suitable driver" error.
        require(jdbcUrl.startsWith("jdbc:trino://")) { "jdbcUrl must start with 'jdbc:trino://': $jdbcUrl" }

        return Database.connect(
            getNewConnection = {
                val props = options.toProperties(user)
                // Close the raw connection on wrapper construction failure to prevent leaks.
                val raw = DriverManager.getConnection(jdbcUrl, props)
                runCatching { TrinoConnectionWrapper(raw) }
                    .getOrElse { e -> raw.runCatching { close() }; throw e }
            }
        )
    }

    /**
     * Connects to a Trino database using a JDBC URL and typed JDBC options.
     */
    fun connect(
        jdbcUrl: String,
        options: TrinoConnectionOptions,
    ): Database =
        connect(jdbcUrl = jdbcUrl, user = "trino", options = options)

    /**
     * Connects to a Trino database via a `javax.sql.DataSource` (e.g. HikariCP).
     *
     * Use this overload in production when the application manages a connection pool.
     * A connection is obtained from the pool via `dataSource.getConnection()`, then
     * wrapped in [TrinoConnectionWrapper] to enforce `autoCommit = true`.
     * If wrapper construction fails, the raw connection is closed to prevent leaks.
     *
     * **Warning**: Trino does not support transactions. All statements run in autocommit
     * mode; a failure mid-block leaves preceding DML already committed.
     *
     * @param dataSource Connection pool supplying JDBC connections (e.g. HikariCP)
     * @return Exposed [Database] instance
     */
    fun connect(dataSource: javax.sql.DataSource): Database {
        return Database.connect(
            getNewConnection = {
                val raw = dataSource.connection
                runCatching { TrinoConnectionWrapper(raw) }
                    .getOrElse { e -> raw.runCatching { close() }; throw e }
            }
        )
    }
}
