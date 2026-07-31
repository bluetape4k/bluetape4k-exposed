package io.bluetape4k.exposed.druid

import io.bluetape4k.support.requireNotBlank
import java.io.Serializable
import java.net.URI
import java.util.Properties

private const val DEFAULT_DRUID_AVATICA_ENDPOINT = "http://localhost:8888/druid/v2/sql/avatica/"

/**
 * Apache Druid Avatica JDBC 연결 옵션입니다.
 *
 * 이 module에서 Druid JDBC는 query-only integration surface입니다. 생성되는 URL은
 * Router/Broker Avatica endpoint를 대상으로 하며, Broker 재시작이나 membership 변경에 대한
 * Druid JDBC 지침에 맞게 transparent reconnection을 기본으로 활성화합니다.
 */
data class DruidConnectionOptions(
    val avaticaEndpoint: String = DEFAULT_DRUID_AVATICA_ENDPOINT,
    val transparentReconnection: Boolean = true,
    val serialization: DruidAvaticaSerialization = DruidAvaticaSerialization.JSON,
    val user: String? = null,
    val password: String? = null,
    val contextProperties: Map<String, String> = emptyMap(),
    val extraProperties: Map<String, String> = emptyMap(),
): Serializable {

    companion object {
        private const val serialVersionUID: Long = 1L
    }

    init {
        validateHttpEndpoint(avaticaEndpoint)
        user?.requireNotBlank("user")
        password?.requireNotBlank("password")
        validatePairs("contextProperties", contextProperties)
        validatePairs("extraProperties", extraProperties)
    }

    /** [DruidJdbc]가 사용할 Avatica JDBC URL을 반환합니다. */
    fun jdbcUrl(): String = buildString {
        append("jdbc:avatica:remote:url=")
        append(avaticaEndpoint)
        append(";transparent_reconnection=")
        append(transparentReconnection)
        if (serialization == DruidAvaticaSerialization.PROTOBUF) {
            append(";serialization=protobuf")
        }
    }

    /** Druid query context와 선택적 authentication에 사용할 JDBC property를 반환합니다. */
    fun toProperties(): Properties = Properties().apply {
        user?.let { setProperty("user", it) }
        password?.let { setProperty("password", it) }
        contextProperties.forEach { (key, value) -> setProperty(key, value) }
        extraProperties.forEach { (key, value) -> setProperty(key, value) }
    }
}

/** Druid Router/Broker endpoint가 지원하는 Avatica wire serialization mode입니다. */
enum class DruidAvaticaSerialization {
    JSON,
    PROTOBUF,
}

private fun validatePairs(name: String, values: Map<String, String>) {
    values.forEach { (key, value) ->
        key.requireNotBlank("$name key")
        value.requireNotBlank("$name value for '$key'")
    }
}

private fun validateHttpEndpoint(endpoint: String) {
    endpoint.requireNotBlank("avaticaEndpoint")
    val uri = runCatching { URI(endpoint) }.getOrElse {
        throw IllegalArgumentException("avaticaEndpoint must be a valid URI: $endpoint", it)
    }
    require(uri.scheme == "http" || uri.scheme == "https") {
        "avaticaEndpoint must use http or https: $endpoint"
    }
    require(!uri.host.isNullOrBlank()) {
        "avaticaEndpoint must include a host: $endpoint"
    }
    require(endpoint.endsWith("/druid/v2/sql/avatica/") || endpoint.endsWith("/druid/v2/sql/avatica-protobuf/")) {
        "avaticaEndpoint should target Druid Router/Broker Avatica endpoint: $endpoint"
    }
}
