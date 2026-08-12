package io.bluetape4k.exposed.cockroachdb

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import java.sql.DriverManager
import javax.sql.DataSource

/**
 * PostgreSQL JDBC를 통해 Exposed를 CockroachDB에 연결하는 factory object입니다.
 *
 * CockroachDB는 PostgreSQL wire protocol을 사용하므로 이 module은 PostgreSQL JDBC driver와
 * Exposed의 기본 PostgreSQL dialect를 사용합니다. Custom CockroachDB dialect는 의도적으로
 * 등록하지 않으며, PostgreSQL compatibility와 DDL 경계는 별도 범위에서 다룹니다.
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

    /** CockroachDB의 PostgreSQL wire protocol에 사용하는 PostgreSQL JDBC driver입니다. */
    val DRIVER: String = "org.postgresql.Driver"

    /** Host, SQL port, database, credential을 사용해 CockroachDB에 연결합니다. */
    @Suppress("LongParameterList")
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

    /** PostgreSQL JDBC URL을 사용해 CockroachDB에 연결합니다. */
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

    /** 호출자가 관리하는 [DataSource]를 통해 CockroachDB에 연결합니다. */
    fun connect(
        dataSource: DataSource,
        databaseConfig: DatabaseConfig? = null,
    ): Database =
        Database.connect(
            datasource = dataSource,
            databaseConfig = databaseConfig ?: DatabaseConfig {},
        )

    /** CockroachDB에 사용할 PostgreSQL JDBC URL을 구성합니다. */
    fun buildJdbcUrl(host: String, port: Int = 26257, database: String = "defaultdb"): String {
        host.requireNotBlank("host")
        port.requireInRange(1, 65535, "port")
        database.requireNotBlank("database")
        return "$COCKROACH_JDBC_PREFIX$host:$port/$database"
    }

    private const val COCKROACH_JDBC_PREFIX: String = "jdbc:postgresql://"
}
