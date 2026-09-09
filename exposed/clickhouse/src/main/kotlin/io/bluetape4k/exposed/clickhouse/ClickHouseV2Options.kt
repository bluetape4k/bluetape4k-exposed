package io.bluetape4k.exposed.clickhouse

import io.bluetape4k.AbstractValueObject
import io.bluetape4k.ToStringBuilder
import java.time.ZoneId

/**
 * ClickHouse JDBC V2 인증 방식을 나타냅니다.
 *
 * 토큰 값은 연결 property로 변환되는 짧은 구간에서만 사용하며, 이 타입의
 * 문자열 표현에는 비밀값을 포함하지 않습니다.
 */
sealed interface ClickHouseV2Authentication {

    /** 사용자명과 비밀번호를 사용하는 기본 인증입니다. */
    data object Basic: ClickHouseV2Authentication

    /** ClickHouse access token 인증입니다. */
    data class AccessToken(val value: String): ClickHouseV2Authentication {
        init {
            requireNotBlank(value, "access token")
            require(value.none(Char::isISOControl)) { "access token에는 제어 문자를 사용할 수 없습니다." }
        }

        override fun toString(): String = "AccessToken(***)"
    }

    /** HTTP bearer token 인증입니다. */
    data class BearerToken(val value: String): ClickHouseV2Authentication {
        init {
            requireNotBlank(value, "bearer token")
            require(value.none(Char::isISOControl)) { "bearer token에는 제어 문자를 사용할 수 없습니다." }
        }

        override fun toString(): String = "BearerToken(***)"
    }
}

/** HTTP connection pool에서 연결을 선택하는 순서입니다. */
enum class ClickHouseV2ConnectionReuseStrategy {
    FIFO,
    LIFO,
}

/**
 * password 또는 keystore password를 필요할 때만 제공하는 함수형 계약입니다.
 * 반환된 배열은 driver property 변환 직후 호출자가 지워야 합니다.
 */
fun interface ClickHouseV2SecretProvider {
    fun resolve(): CharArray
}

/** ClickHouse JDBC V2 HTTP proxy 설정입니다. */
data class ClickHouseV2ProxyOptions(
    val type: String = "HTTP",
    val host: String,
    val port: Int,
    val user: String? = null,
    val password: ClickHouseV2SecretProvider? = null,
) {
    init {
        requireNotBlank(type, "proxy type")
        require(type.equals("HTTP", ignoreCase = true)) { "proxy type은 HTTP만 지원합니다." }
        requireNotBlank(host, "proxy host")
        require(host.none(Char::isISOControl)) { "proxy host에는 제어 문자를 사용할 수 없습니다." }
        require(port in MIN_PORT..MAX_PORT) { "proxy port는 1~65535 범위여야 합니다: $port" }
        user?.let {
            requireNotBlank(it, "proxy user")
            require(it.none(Char::isISOControl)) { "proxy user에는 제어 문자를 사용할 수 없습니다." }
        }
    }
}

/**
 * ClickHouse JDBC V2 TLS 설정입니다.
 *
 * 인증서와 키 자체가 아니라 파일 또는 secret-store reference만 받습니다.
 */
data class ClickHouseV2TlsOptions(
    val trustStore: String? = null,
    val keyStoreType: String? = null,
    val sslKeyStore: String? = null,
    val keyStorePassword: ClickHouseV2SecretProvider? = null,
    val sslKeyReference: String? = null,
    val sslRootCertReference: String? = null,
    val sslCertReference: String? = null,
    val sslAuthentication: String? = null,
    val sslSocketSni: String? = null,
) {
    init {
        listOf(
            "trustStore" to trustStore,
            "sslKeyStore" to sslKeyStore,
            "sslKeyReference" to sslKeyReference,
            "sslRootCertReference" to sslRootCertReference,
            "sslCertReference" to sslCertReference,
        ).forEach { (name, value) -> value?.let { validateReference(name, it) } }
        keyStoreType?.let {
            requireNotBlank(it, "keyStoreType")
            require(it.none(Char::isISOControl)) { "keyStoreType에는 제어 문자를 사용할 수 없습니다." }
        }
        sslAuthentication?.let {
            requireNotBlank(it, "sslAuthentication")
            require(it.none(Char::isISOControl)) { "sslAuthentication에는 제어 문자를 사용할 수 없습니다." }
        }
        sslSocketSni?.let {
            requireNotBlank(it, "sslSocketSni")
            require(it.none(Char::isISOControl)) { "sslSocketSni에는 제어 문자를 사용할 수 없습니다." }
        }
    }
}

/**
 * ClickHouse JDBC V2 연결에 사용할 검증된 immutable 옵션입니다.
 *
 * 기본 `connect` overload와의 호환성을 위해 옵션은 별도 trailing parameter로
 * 제공하며, 인증서/키 본문은 저장하지 않고 reference만 보관합니다.
 */
class ClickHouseV2Options(
    val connectionTimeoutMillis: Long? = null,
    val socketOperationTimeoutMillis: Int? = null,
    val connectionRequestTimeoutMillis: Long? = null,
    val connectionTtlMillis: Long? = null,
    val httpKeepAliveTimeoutMillis: Long? = null,
    val connectionPoolEnabled: Boolean? = null,
    val maxOpenConnections: Int? = null,
    val connectionReuseStrategy: ClickHouseV2ConnectionReuseStrategy? = null,
    val useServerTimeZone: Boolean? = null,
    val compressServerResponse: Boolean? = null,
    val compressClientRequest: Boolean? = null,
    val useHttpCompression: Boolean? = null,
    val lz4UncompressedBufferSize: Int? = null,
    val retryOnFailure: Int? = null,
    val authentication: ClickHouseV2Authentication = ClickHouseV2Authentication.Basic,
    val clientName: String? = null,
    sessionDbRoles: List<String> = emptyList(),
    val sessionTimezone: ZoneId? = null,
    val queryId: String? = null,
    val logComment: String? = null,
    serverSettings: Map<String, String> = emptyMap(),
    val proxy: ClickHouseV2ProxyOptions? = null,
    val tls: ClickHouseV2TlsOptions? = null,
    customHeaders: Map<String, String> = emptyMap(),
    rawProperties: Map<String, String> = emptyMap(),
): AbstractValueObject() {
    /** 외부 collection 변경과 분리된 immutable session role 목록입니다. */
    val sessionDbRoles: List<String> = sessionDbRoles.toList()

    /** 외부 map 변경과 분리된 immutable server settings입니다. */
    val serverSettings: Map<String, String> = serverSettings.toMap()

    /** 외부 map 변경과 분리된 immutable custom headers입니다. */
    val customHeaders: Map<String, String> = customHeaders.toMap()

    /** 외부 map 변경과 분리된 immutable raw properties입니다. */
    val rawProperties: Map<String, String> = rawProperties.toMap()

    init {
        requirePositive("connectionTimeoutMillis", connectionTimeoutMillis)
        requireNonNegative("socketOperationTimeoutMillis", socketOperationTimeoutMillis)
        requireNonNegative("connectionRequestTimeoutMillis", connectionRequestTimeoutMillis)
        connectionTtlMillis?.let {
            require(it == -1L || it > 0) { "connectionTtlMillis는 -1 또는 양수여야 합니다: $it" }
        }
        requirePositive("httpKeepAliveTimeoutMillis", httpKeepAliveTimeoutMillis)
        maxOpenConnections?.let { require(it > 0) { "maxOpenConnections는 양수여야 합니다: $it" } }
        lz4UncompressedBufferSize?.let {
            require(it > 0) { "lz4UncompressedBufferSize는 양수여야 합니다: $it" }
        }
        retryOnFailure?.let { require(it >= 0) { "retryOnFailure는 음수가 아니어야 합니다: $it" } }

        clientName?.let { validateText("clientName", it, allowBlank = true) }
        sessionDbRoles.forEach { validateText("sessionDbRoles", it, allowBlank = false) }
        require(sessionDbRoles.distinct().size == sessionDbRoles.size) { "sessionDbRoles에는 중복 역할을 사용할 수 없습니다." }
        queryId?.let { validateText("queryId", it, allowBlank = false) }
        logComment?.let { validateText("logComment", it, allowBlank = false) }

        serverSettings.forEach { (key, value) ->
            requireNotBlank(key, "serverSettings key")
            require(key.none(Char::isISOControl)) { "serverSettings key에는 제어 문자를 사용할 수 없습니다." }
            require(value.none(Char::isISOControl)) { "serverSettings[$key]에는 제어 문자를 사용할 수 없습니다." }
        }

        validateCustomHeaders(customHeaders)
        validateRawProperties(rawProperties)
        validateTypedRawCollisions()
    }

    /** 외부 map/list 변경과 분리된 defensive copy를 반환합니다. */
    val immutableSessionDbRoles: List<String> = sessionDbRoles

    /** 외부 map 변경과 분리된 server settings copy입니다. */
    val immutableServerSettings: Map<String, String> = serverSettings

    /** 외부 map 변경과 분리된 custom header copy입니다. */
    val immutableCustomHeaders: Map<String, String> = customHeaders

    /** 외부 map 변경과 분리된 raw property copy입니다. */
    val immutableRawProperties: Map<String, String> = rawProperties

    override fun equalProperties(other: Any): Boolean =
        other is ClickHouseV2Options && equalityComponents() == other.equalityComponents()

    override fun equals(other: Any?): Boolean = super.equals(other)

    override fun hashCode(): Int = equalityComponents().hashCode()

    override fun buildStringHelper(): ToStringBuilder =
        super.buildStringHelper()
            .add("connectionTimeoutMillis", connectionTimeoutMillis)
            .add("socketOperationTimeoutMillis", socketOperationTimeoutMillis)
            .add("connectionRequestTimeoutMillis", connectionRequestTimeoutMillis)
            .add("connectionTtlMillis", connectionTtlMillis)
            .add("httpKeepAliveTimeoutMillis", httpKeepAliveTimeoutMillis)
            .add("connectionPoolEnabled", connectionPoolEnabled)
            .add("maxOpenConnections", maxOpenConnections)
            .add("connectionReuseStrategy", connectionReuseStrategy)
            .add("useServerTimeZone", useServerTimeZone)
            .add("compressServerResponse", compressServerResponse)
            .add("compressClientRequest", compressClientRequest)
            .add("useHttpCompression", useHttpCompression)
            .add("lz4UncompressedBufferSize", lz4UncompressedBufferSize)
            .add("retryOnFailure", retryOnFailure)
            .add("authentication", authentication::class.simpleName)
            .add("clientName", clientName)
            .add("sessionDbRoles", sessionDbRoles)
            .add("sessionTimezone", sessionTimezone)
            .add("queryId", queryId)
            .add("logComment", logComment)
            .add("serverSettings", serverSettings.keys)
            .add("proxy", proxy?.copy(password = null))
            .add("tls", tls?.copy(keyStorePassword = null))
            .add("customHeaders", customHeaders.keys)
            .add("rawProperties", rawProperties.keys)

    private fun equalityComponents(): List<Any?> = listOf(
        connectionTimeoutMillis,
        socketOperationTimeoutMillis,
        connectionRequestTimeoutMillis,
        connectionTtlMillis,
        httpKeepAliveTimeoutMillis,
        connectionPoolEnabled,
        maxOpenConnections,
        connectionReuseStrategy,
        useServerTimeZone,
        compressServerResponse,
        compressClientRequest,
        useHttpCompression,
        lz4UncompressedBufferSize,
        retryOnFailure,
        authentication,
        clientName,
        sessionDbRoles,
        sessionTimezone,
        queryId,
        logComment,
        serverSettings,
        proxy,
        tls,
        customHeaders,
        rawProperties,
    )

    private fun validateTypedRawCollisions() {
        val typedKeys = typedPropertyKeys()
        rawProperties.keys.forEach { rawKey -> validateRawPropertyCollision(rawKey, typedKeys) }
        serverSettings.keys.forEach(::validateServerSettingCollision)
    }

    private fun typedPropertyKeys(): Set<String> {
        val keys = listOf(
            connectionTimeoutMillis to "connection_timeout",
            socketOperationTimeoutMillis to "socket_timeout",
            connectionRequestTimeoutMillis to "connection_request_timeout",
            connectionTtlMillis to "connection_ttl",
            httpKeepAliveTimeoutMillis to "http_keep_alive_timeout",
            connectionPoolEnabled to "connection_pool_enabled",
            maxOpenConnections to "max_open_connections",
            connectionReuseStrategy to "connection_reuse_strategy",
            useServerTimeZone to "use_server_time_zone",
            compressServerResponse to "compress",
            compressClientRequest to "decompress",
            useHttpCompression to "client.use_http_compression",
            lz4UncompressedBufferSize to "compression.lz4.uncompressed_buffer_size",
            retryOnFailure to "retry",
            clientName to "client_name",
            sessionDbRoles.takeIf { it.isNotEmpty() } to "session_db_roles",
            sessionTimezone to "use_time_zone",
            queryId to "query_id",
            logComment to "clickhouse_setting_log_comment",
        ).filter { (value, _) -> value != null }
            .mapTo(mutableSetOf()) { (_, key) -> key }
        keys += proxy?.let { PROXY_KEYS }.orEmpty()
        keys += tls?.let { TLS_KEYS }.orEmpty()
        keys += customHeaders.keys.map(::headerPropertyKey)
        keys += "http_use_basic_auth"
        return keys
    }

    private fun validateRawPropertyCollision(rawKey: String, typedKeys: Set<String>) {
        val normalizedRawKey = if (rawKey.startsWith(HTTP_HEADER_PREFIX)) {
            rawKey.substring(HTTP_HEADER_PREFIX.length).let(::normalizeHeaderName).let(::headerPropertyKey)
        } else {
            rawKey
        }
        require(normalizedRawKey !in typedKeys) {
            "typed option과 raw property를 중복 지정할 수 없습니다: $rawKey"
        }
        require(rawKey != "clickhouse_setting_log_comment" || logComment == null) {
            "logComment과 raw property를 중복 지정할 수 없습니다."
        }
    }

    private fun validateServerSettingCollision(key: String) {
        require("clickhouse_setting_$key" !in rawProperties) {
            "serverSettings와 raw property를 중복 지정할 수 없습니다: $key"
        }
    }
}

private const val HTTP_HEADER_PREFIX = "http_header_"
private const val MIN_PORT = 1
private const val MAX_PORT = 65535

private val PROXY_KEYS = setOf("proxy_type", "proxy_host", "proxy_port", "proxy_user", "proxy_password")

private val TLS_KEYS = setOf(
    "trust_store",
    "key_store_type",
    "ssl_key_store",
    "key_store_password",
    "ssl_key",
    "sslrootcert",
    "sslcert",
    "ssl_authentication",
    "ssl_socket_sni",
)

private val FORBIDDEN_RAW_KEYS = setOf(
    "user",
    "password",
    "database",
    "access_token",
    "bearer_token",
    "http_use_basic_auth",
    "proxy_password",
    "key_store_password",
    "ssl_key",
    "sslrootcert",
    "sslcert",
    "beta.row_binary_for_simple_insert",
)

private val ALLOWED_RAW_KEYS = setOf(
    "session_db_roles",
    "connection_timeout",
    "socket_timeout",
    "connection_request_timeout",
    "connection_ttl",
    "connection_reuse_strategy",
    "socket_rcvbuf",
    "socket_sndbuf",
    "socket_reuseaddr",
    "socket_keepalive",
    "socket_tcp_nodelay",
    "socket_linger",
    "http_keep_alive_timeout",
    "max_open_connections",
    "use_server_time_zone",
    "use_time_zone",
    "compress",
    "decompress",
    "client.use_http_compression",
    "compression.lz4.uncompressed_buffer_size",
    "disable_native_compression",
    "max_execution_time",
    "retry",
    "format",
    "max_threads_per_client",
    "query_id",
    "client_network_buffer_size",
    "connection_pool_enabled",
    "client_retry_on_failures",
    "client_name",
    "product_name",
    "app_compressed_data",
    "metrics_name",
    "client.http.cookies_enabled",
    "client_allow_binary_reader_to_reuse_buffers",
    "type_hint_mapping",
    "ssl_authentication",
    "ssl_socket_sni",
    "client.http.use_form_request_for_query",
    "custom_settings_prefix",
    "ignore_unknown_config_key",
    "server_version",
    "server_time_zone",
    "async",
    "_default_",
)

private fun validateRawProperties(properties: Map<String, String>) {
    val seenHeaders = mutableSetOf<String>()
    properties.forEach { (key, value) ->
        require(key.isNotBlank()) { "rawProperties key는 공백일 수 없습니다." }
        require(value.none(Char::isISOControl)) { "rawProperties[$key]에는 제어 문자를 사용할 수 없습니다." }
        require(key !in FORBIDDEN_RAW_KEYS) { "raw property는 보안 또는 다른 이슈가 소유한 key를 사용할 수 없습니다: $key" }
        if (key.startsWith(HTTP_HEADER_PREFIX)) {
            val headerName = key.removePrefix(HTTP_HEADER_PREFIX)
            validateHeaderName(headerName)
            val normalized = normalizeHeaderName(headerName)
            require(normalized.equals("X-ClickHouse-User-Agent", ignoreCase = true)) {
                "허용되지 않은 ClickHouse custom header입니다: $headerName"
            }
            require(seenHeaders.add(normalized.lowercase())) { "중복 custom header입니다: $headerName" }
        } else {
            require(key in ALLOWED_RAW_KEYS || key.startsWith("clickhouse_setting_")) {
                "허용되지 않은 ClickHouse V2 raw property입니다: $key"
            }
        }
    }
}

private fun validateCustomHeaders(headers: Map<String, String>) {
    val seen = mutableSetOf<String>()
    headers.forEach { (name, value) ->
        validateHeaderName(name)
        require(normalizeHeaderName(name).equals("X-ClickHouse-User-Agent", ignoreCase = true)) {
            "허용되지 않은 ClickHouse custom header입니다: $name"
        }
        require(value.isNotEmpty()) { "custom header 값은 비어 있을 수 없습니다." }
        require(value.none(Char::isISOControl)) { "custom header 값에는 제어 문자를 사용할 수 없습니다." }
        require(seen.add(normalizeHeaderName(name).lowercase())) { "중복 custom header입니다: $name" }
    }
}

private fun validateHeaderName(name: String) {
    requireNotBlank(name, "custom header name")
    require(name.none(Char::isISOControl)) { "custom header 이름에는 제어 문자를 사용할 수 없습니다." }
    val forbidden = setOf(
        "authorization",
        "proxy-authorization",
        "cookie",
        "x-clickhouse-key",
        "x-clickhouse-user",
        "host",
        "content-length",
        "transfer-encoding",
        "connection",
        "keep-alive",
        "te",
        "trailer",
        "upgrade",
    )
    require(name.lowercase() !in forbidden) { "인증·routing·hop-by-hop header는 허용하지 않습니다: $name" }
}

internal fun normalizeHeaderName(name: String): String =
    if (name.equals("X-ClickHouse-User-Agent", ignoreCase = true)) {
        "X-ClickHouse-User-Agent"
    } else {
        name.split('-').joinToString("-") { it.replaceFirstChar(Char::uppercaseChar) }
    }

private fun headerPropertyKey(name: String): String = "$HTTP_HEADER_PREFIX${normalizeHeaderName(name)}"

private fun validateReference(name: String, value: String) {
    requireNotBlank(value, name)
    require(value.none(Char::isISOControl)) { "${name}에는 제어 문자를 사용할 수 없습니다." }
    require(!value.contains("BEGIN ", ignoreCase = true)) { "${name}에는 인증서·키 본문을 넣을 수 없습니다." }
}

private fun validateText(name: String, value: String, allowBlank: Boolean) {
    if (!allowBlank) requireNotBlank(value, name)
    require(value.none(Char::isISOControl)) { "${name}에는 제어 문자를 사용할 수 없습니다." }
}

private fun requirePositive(name: String, value: Long?) {
        value?.let { require(it > 0) { "${name}은 양수여야 합니다: $it" } }
}

private fun requireNonNegative(name: String, value: Long?) {
        value?.let { require(it >= 0) { "${name}은 음수가 아니어야 합니다: $it" } }
}

private fun requireNonNegative(name: String, value: Int?) {
    value?.let { require(it >= 0) { "${name}은 음수가 아니어야 합니다: $it" } }
}

private fun requireNotBlank(value: String, name: String): String {
    require(value.isNotBlank()) { "${name}은 공백일 수 없습니다." }
    return value
}
