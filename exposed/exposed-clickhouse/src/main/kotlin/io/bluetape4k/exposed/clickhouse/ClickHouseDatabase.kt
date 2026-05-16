package io.bluetape4k.exposed.clickhouse

import io.bluetape4k.exposed.clickhouse.dialect.ClickHouseDialect
import io.bluetape4k.exposed.clickhouse.dialect.ClickHouseDialectMetadata
import io.bluetape4k.logging.KLogging
import org.jetbrains.exposed.v1.core.DatabaseApi
import org.jetbrains.exposed.v1.jdbc.Database
import java.sql.DriverManager
import java.util.Properties

/**
 * Factory object for connecting to a ClickHouse database via Exposed ORM.
 *
 * Registers the ClickHouse JDBC driver, dialect, and dialect metadata on first access,
 * then provides convenience overloads for creating an Exposed [Database] instance.
 *
 * ## Basic usage
 *
 * ```kotlin
 * val db = ClickHouseDatabase.connect(
 *     host = "clickhouse-host",
 *     port = 8123,
 *     database = "default",
 *     user = "test",
 *     password = "test",
 * )
 * transaction(db) {
 *     val rows = MyTable.selectAll().toList()
 * }
 * ```
 *
 * ## Direct JDBC URL usage
 *
 * ```kotlin
 * val db = ClickHouseDatabase.connect(
 *     jdbcUrl = "jdbc:clickhouse://host:8123/default",
 *     user = "test",
 *     password = "test",
 * )
 * ```
 *
 * ## Autocommit contract
 *
 * - ClickHouse does not support transactions. All statements run in autocommit mode.
 * - A failure mid-block does not roll back earlier DML statements.
 * - `rollback()` is a no-op — present only for Exposed framework compatibility.
 * - Nested transactions and savepoints are not supported; calls are accepted but atomicity is not guaranteed.
 */
object ClickHouseDatabase: KLogging() {

    /**
     * ClickHouse JDBC driver class name.
     *
     * Declared as `val` rather than `const val` to guarantee that accessing this property
     * triggers object initialization (`init {}`). A `const val` is inlined at compile time
     * and may not trigger object initialization.
     */
    val DRIVER = "com.clickhouse.jdbc.ClickHouseDriver"

    init {
        Database.registerJdbcDriver("jdbc:clickhouse", DRIVER, ClickHouseDialect.dialectName)
        DatabaseApi.registerDialect(ClickHouseDialect.dialectName) { ClickHouseDialect() }
        Database.registerDialectMetadata(ClickHouseDialect.dialectName) { ClickHouseDialectMetadata() }
        log.debug("ClickHouse dialect registered: ${ClickHouseDialect.dialectName}")
    }

    /**
     * Connects to a ClickHouse database, assembling the JDBC URL as
     * `jdbc:clickhouse://{host}:{port}/{database}`.
     *
     * **Note**: ClickHouse does not support transactions. All statements run in autocommit mode;
     * a failure mid-block does not roll back earlier DML statements.
     *
     * @param host ClickHouse host (default: `localhost`)
     * @param port ClickHouse HTTP port (default: `8123`)
     * @param database ClickHouse database name (default: `default`)
     * @param user Login user (default: `default`)
     * @param password Login password (default: empty string)
     * @return Exposed [Database] instance
     */
    fun connect(
        host: String = "localhost",
        port: Int = 8123,
        database: String = "default",
        user: String = "default",
        password: String = "",
    ): Database {
        // 빈 값으로 JDBC URL을 구성하면 무효한 URL이 만들어져 DriverManager.getConnection()
        // 호출 시점에 불명확한 예외가 발생합니다. 조기에 명확한 메시지로 실패시킵니다.
        requireNotNull(host.ifBlank { null }) { "host는 공백일 수 없습니다." }
        // 유효하지 않은 포트 번호는 TCP 연결 시도 단계에서야 실패하므로, 미리 차단합니다.
        require(port in 1..65535) { "port는 1~65535 범위여야 합니다: $port" }
        // ClickHouse JDBC URL은 `database`를 path 세그먼트로 요구합니다.
        requireNotNull(database.ifBlank { null }) { "database는 공백일 수 없습니다." }

        val url = "jdbc:clickhouse://$host:$port/$database"
        return Database.connect(
            getNewConnection = {
                val props = Properties().apply {
                    setProperty("user", user)
                    setProperty("password", password)
                }
                // 연결 획득 후 래퍼 생성 실패 시 원본 연결을 닫아 leak을 방지합니다.
                val raw = DriverManager.getConnection(url, props)
                runCatching { ClickHouseConnectionWrapper(raw) }
                    .getOrElse { e ->
                        raw.runCatching { close() }.onFailure { closeEx ->
                            log.warn("Connection close failed after wrapper creation error: ${closeEx.message}")
                        }
                        throw e
                    }
            }
        )
    }

    /**
     * Connects to a ClickHouse database using the given JDBC URL directly.
     *
     * **Note**: ClickHouse does not support transactions. All statements run in autocommit mode;
     * a failure mid-block does not roll back earlier DML statements.
     *
     * @param jdbcUrl ClickHouse JDBC URL (e.g. `jdbc:clickhouse://host:8123/default`)
     * @param user Login user (default: `default`)
     * @param password Login password (default: empty string)
     * @return Exposed [Database] instance
     */
    fun connect(
        jdbcUrl: String,
        user: String = "default",
        password: String = "",
    ): Database {
        // 빈 URL은 DriverManager.getConnection()에서 No suitable driver 예외를 발생시킵니다.
        requireNotNull(jdbcUrl.ifBlank { null }) { "jdbcUrl은 공백일 수 없습니다." }
        // ClickHouse 드라이버는 "jdbc:clickhouse://" 접두사가 있는 URL만 처리합니다.
        require(jdbcUrl.startsWith("jdbc:clickhouse://")) {
            "jdbcUrl은 'jdbc:clickhouse://'로 시작해야 합니다: $jdbcUrl"
        }

        return Database.connect(
            getNewConnection = {
                val props = Properties().apply {
                    setProperty("user", user)
                    setProperty("password", password)
                }
                // 연결 획득 후 래퍼 생성 실패 시 원본 연결을 닫아 leak을 방지합니다.
                val raw = DriverManager.getConnection(jdbcUrl, props)
                runCatching { ClickHouseConnectionWrapper(raw) }
                    .getOrElse { e ->
                        raw.runCatching { close() }.onFailure { closeEx ->
                            log.warn("Connection close failed after wrapper creation error: ${closeEx.message}")
                        }
                        throw e
                    }
            }
        )
    }
}
