package io.bluetape4k.exposed.trino

import io.bluetape4k.exposed.trino.dialect.TrinoDialect
import io.bluetape4k.exposed.trino.dialect.TrinoDialectMetadata
import io.bluetape4k.logging.KLogging
import org.jetbrains.exposed.v1.core.DatabaseApi
import org.jetbrains.exposed.v1.jdbc.Database
import java.sql.DriverManager

/**
 * Exposed ORM을 통해 Trino database에 연결하는 factory object입니다.
 *
 * 최초 접근 시 Trino JDBC driver와 dialect를 등록하고, host/port, JDBC URL,
 * `javax.sql.DataSource`용 `connect` overload를 제공합니다.
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
 * ## Autocommit 동작
 *
 * - Trino는 transaction을 지원하지 않으며 모든 statement를 autocommit mode로 실행합니다.
 * - `transaction {}` block 안의 여러 DML statement는 원자적이지 않습니다. Block 중간에 실패하면
 *   앞선 statement는 이미 commit된 상태로 남습니다.
 * - `rollback()`은 Exposed framework 호환성을 위한 no-op adapter입니다.
 * - Nested transaction과 savepoint를 허용하지만 원자성을 보장하지 않습니다.
 * - DDL에서는 [org.jetbrains.exposed.v1.core.Table]보다 [TrinoTable]을 사용하여 Trino에
 *   전달하기 전에 `PRIMARY KEY` clause를 제거하십시오.
 */
object TrinoDatabase : KLogging() {

    /**
     * Trino JDBC driver class name입니다.
     *
     * 이 property 접근 시 `init` block이 실행되어 driver를 등록하도록 `const val`이 아닌
     * `val`로 선언했습니다. `const val`은 compile time에 inline되므로 object 초기화를
     * 유발하지 않을 수 있습니다.
     */
    val DRIVER = "io.trino.jdbc.TrinoDriver"

    init {
        Database.registerJdbcDriver("jdbc:trino", DRIVER, TrinoDialect.dialectName)
        DatabaseApi.registerDialect(TrinoDialect.dialectName) { TrinoDialect() }
        Database.registerDialectMetadata(TrinoDialect.dialectName) { TrinoDialectMetadata() }
        log.debug("Trino dialect registered: ${TrinoDialect.dialectName}")
    }

    /**
     * 개별 host/port/catalog/schema parameter를 사용해 Trino database에 연결합니다.
     *
     * `jdbc:trino://{host}:{port}/{catalog}/{schema}` 형식의 JDBC URL을 구성합니다.
     *
     * **주의**: Trino는 transaction을 지원하지 않습니다. 모든 statement는 autocommit mode로
     * 실행되며 block 중간에 실패하면 앞선 DML은 이미 commit된 상태로 남습니다.
     * DDL에서는 [TrinoTable]을 사용해 지원하지 않는 `PRIMARY KEY` syntax를 제거하십시오.
     *
     * @param host Trino coordinator host, 기본값은 `localhost`
     * @param port Trino coordinator port, 기본값은 `8080`
     * @param catalog Trino catalog name, 기본값은 `memory`
     * @param schema Trino schema name, 기본값은 `default`
     * @param user connection user, 기본값은 `trino`
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
     * Fully-qualified JDBC URL을 사용해 Trino database에 연결합니다.
     *
     * **주의**: Trino는 transaction을 지원하지 않습니다. 모든 statement는 autocommit mode로
     * 실행되며 block 중간에 실패하면 앞선 DML은 이미 commit된 상태로 남습니다.
     * DDL에서는 [TrinoTable]을 사용해 지원하지 않는 `PRIMARY KEY` syntax를 제거하십시오.
     *
     * @param jdbcUrl Trino JDBC URL, 예: `jdbc:trino://host:8080/hive/default`
     * @param user connection user, 기본값은 `trino`
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

    /** JDBC URL과 typed JDBC option을 사용해 Trino database에 연결합니다. */
    fun connect(
        jdbcUrl: String,
        options: TrinoConnectionOptions,
    ): Database =
        connect(jdbcUrl = jdbcUrl, user = "trino", options = options)

    /**
     * `javax.sql.DataSource`(예: HikariCP)를 통해 Trino database에 연결합니다.
     *
     * Application이 connection pool을 관리하는 production 환경에서 이 overload를 사용합니다.
     * `dataSource.getConnection()`으로 pool에서 connection을 가져온 뒤
     * [TrinoConnectionWrapper]로 감싸 `autoCommit = true`를 강제합니다. Wrapper 생성에
     * 실패하면 leak을 방지하기 위해 raw connection을 닫습니다.
     *
     * **주의**: Trino는 transaction을 지원하지 않습니다. 모든 statement는 autocommit mode로
     * 실행되며 block 중간에 실패하면 앞선 DML은 이미 commit된 상태로 남습니다.
     *
     * @param dataSource JDBC connection을 제공하는 connection pool, 예: HikariCP
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
