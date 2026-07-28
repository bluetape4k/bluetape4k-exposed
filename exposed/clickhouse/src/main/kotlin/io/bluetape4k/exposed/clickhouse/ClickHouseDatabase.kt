package io.bluetape4k.exposed.clickhouse

import io.bluetape4k.exposed.clickhouse.dialect.ClickHouseDialect
import io.bluetape4k.exposed.clickhouse.dialect.ClickHouseDialectMetadata
import io.bluetape4k.logging.KLogging
import org.jetbrains.exposed.v1.core.DatabaseApi
import org.jetbrains.exposed.v1.jdbc.Database
import java.sql.DriverManager
import java.util.Properties

/**
 * Exposed ORM에서 ClickHouse 데이터베이스 연결을 생성하는 팩토리 객체입니다.
 *
 * 최초 접근 시 ClickHouse JDBC 드라이버, dialect, dialect metadata를 등록하고,
 * 애플리케이션 코드가 Exposed [Database] 인스턴스를 만들 수 있도록 편의 overload를 제공합니다.
 *
 * ## 기본 사용
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
 * ## JDBC URL 직접 사용
 *
 * ```kotlin
 * val db = ClickHouseDatabase.connect(
 *     jdbcUrl = "jdbc:clickhouse://host:8123/default",
 *     user = "test",
 *     password = "test",
 * )
 * ```
 *
 * ## Autocommit 계약
 *
 * - ClickHouse는 트랜잭션을 지원하지 않습니다. 모든 statement는 autocommit 모드로 실행됩니다.
 * - 블록 중간에서 실패해도 이미 실행된 DML statement는 rollback되지 않습니다.
 * - `rollback()`은 Exposed framework 호환성을 위한 no-op입니다.
 * - 중첩 트랜잭션과 savepoint 호출은 수용하지만 원자성을 보장하지 않습니다.
 */
object ClickHouseDatabase: KLogging() {

    /**
     * ClickHouse JDBC 드라이버 클래스 이름입니다.
     *
     * 이 값은 의도적으로 `const val`이 아니라 `val`입니다. 프로퍼티 접근이 객체 초기화(`init {}`)
     * 를 반드시 트리거해야 JDBC driver/dialect 등록이 실행되기 때문입니다. `const val`은 컴파일 시점에
     * inline되어 객체 초기화를 건너뛸 수 있습니다.
     */
    val DRIVER = "com.clickhouse.jdbc.ClickHouseDriver"

    init {
        Database.registerJdbcDriver("jdbc:clickhouse", DRIVER, ClickHouseDialect.dialectName)
        DatabaseApi.registerDialect(ClickHouseDialect.dialectName) { ClickHouseDialect() }
        Database.registerDialectMetadata(ClickHouseDialect.dialectName) { ClickHouseDialectMetadata() }
        log.debug("ClickHouse dialect registered: ${ClickHouseDialect.dialectName}")
    }

    /**
     * `jdbc:clickhouse://{host}:{port}/{database}` 형식의 JDBC URL을 조립해 ClickHouse에 연결합니다.
     *
     * **주의**: ClickHouse는 트랜잭션을 지원하지 않습니다. 모든 statement는 autocommit 모드로 실행되며,
     * 블록 중간에서 실패해도 이미 실행된 DML statement는 rollback되지 않습니다.
     *
     * @param host ClickHouse host입니다. 기본값은 `localhost`입니다.
     * @param port ClickHouse HTTP port입니다. 기본값은 `8123`입니다.
     * @param database ClickHouse database 이름입니다. 기본값은 `default`입니다.
     * @param user 로그인 사용자입니다. 기본값은 `default`입니다.
     * @param password 로그인 비밀번호입니다. 기본값은 빈 문자열입니다.
     * @return Exposed [Database] 인스턴스입니다.
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
                        runCatching { raw.close() }.onFailure { closeEx ->
                            e.addSuppressed(closeEx)
                        }
                        throw e
                    }
            }
        )
    }

    /**
     * 전달받은 JDBC URL을 그대로 사용해 ClickHouse에 연결합니다.
     *
     * **주의**: ClickHouse는 트랜잭션을 지원하지 않습니다. 모든 statement는 autocommit 모드로 실행되며,
     * 블록 중간에서 실패해도 이미 실행된 DML statement는 rollback되지 않습니다.
     *
     * @param jdbcUrl ClickHouse JDBC URL입니다. 예: `jdbc:clickhouse://host:8123/default`
     * @param user 로그인 사용자입니다. 기본값은 `default`입니다.
     * @param password 로그인 비밀번호입니다. 기본값은 빈 문자열입니다.
     * @return Exposed [Database] 인스턴스입니다.
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
                        runCatching { raw.close() }.onFailure { closeEx ->
                            e.addSuppressed(closeEx)
                        }
                        throw e
                    }
            }
        )
    }
}
