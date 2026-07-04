package io.bluetape4k.exposed.druid

import io.bluetape4k.support.requireNotBlank
import java.io.Serializable
import java.net.URI
import java.util.Properties

private const val DEFAULT_DRUID_AVATICA_ENDPOINT = "http://localhost:8888/druid/v2/sql/avatica/"

/**
 * Apache Druid Avatica JDBC connection options.
 *
 * Druid JDBC is a query-only integration surface in this module. The generated
 * URL targets the Router/Broker Avatica endpoint and enables transparent
 * reconnection by default, matching the Druid JDBC guidance for Broker restarts
 * or membership changes.
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

    /** Returns the Avatica JDBC URL used by [DruidJdbc]. */
    fun jdbcUrl(): String = buildString {
        append("jdbc:avatica:remote:url=")
        append(avaticaEndpoint)
        append(";transparent_reconnection=")
        append(transparentReconnection)
        if (serialization == DruidAvaticaSerialization.PROTOBUF) {
            append(";serialization=protobuf")
        }
    }

    /** Returns JDBC properties for Druid query context and optional authentication. */
    fun toProperties(): Properties = Properties().apply {
        user?.let { setProperty("user", it) }
        password?.let { setProperty("password", it) }
        contextProperties.forEach { (key, value) -> setProperty(key, value) }
        extraProperties.forEach { (key, value) -> setProperty(key, value) }
    }
}

/** Avatica wire serialization mode supported by Druid Router/Broker endpoints. */
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
