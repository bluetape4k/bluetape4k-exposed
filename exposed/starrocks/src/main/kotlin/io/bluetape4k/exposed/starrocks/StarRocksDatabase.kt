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
 * Connector/J를 통해 Exposed를 StarRocks에 연결하는 factory 객체입니다.
 *
 * 최초 접근 시 `jdbc:starrocks` driver prefix, `starrocks` Exposed dialect,
 * StarRocks metadata adapter를 등록합니다.
 *
 * ## 기본 사용법
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
 * StarRocks table DDL은 engine 고유 형식입니다. 이 모듈은 제한된 local smoke path만 보장하며
 * MySQL 또는 PostgreSQL DDL과의 동등성을 주장하지 않습니다.
 */
object StarRocksDatabase: KLogging() {

    /**
     * StarRocks Connector/J driver class입니다.
     *
     * `val`이므로 property 접근 시 이 객체의 initializer를 통해 dialect 등록이 실행됩니다.
     */
    val DRIVER: String = "com.starrocks.cj.jdbc.Driver"

    init {
        Database.registerJdbcDriver("jdbc:starrocks", DRIVER, StarRocksDialect.dialectName)
        DatabaseApi.registerDialect(StarRocksDialect.dialectName) { StarRocksDialect() }
        Database.registerDialectMetadata(StarRocksDialect.dialectName) { StarRocksDialectMetadata() }
        log.debug("StarRocks dialect registered: ${StarRocksDialect.dialectName}")
    }

    /**
     * host, FE query port, catalog, database를 사용해 StarRocks database에 연결합니다.
     *
     * `jdbc:starrocks://{host}:{port}/{catalog}.{database}` 형식의 JDBC URL을 구성합니다.
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

    /** fully-qualified JDBC URL로 StarRocks에 연결합니다. */
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

    /** 호출자가 관리하는 [DataSource]를 통해 StarRocks에 연결합니다. */
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
