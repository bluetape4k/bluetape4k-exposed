package io.bluetape4k.exposed.clickhouse

import java.util.Properties

internal fun Properties.applyRawProperties(options: ClickHouseV2Options) {
    options.immutableRawProperties.forEach { (key, value) ->
        setProperty(normalizeRawPropertyKey(key), value)
    }
}

internal fun Properties.applyTypedProperties(options: ClickHouseV2Options) {
    listOfNotNull(
        property("connection_timeout", options.connectionTimeoutMillis),
        property("socket_timeout", options.socketOperationTimeoutMillis),
        property("connection_request_timeout", options.connectionRequestTimeoutMillis),
        property("connection_ttl", options.connectionTtlMillis),
        property("http_keep_alive_timeout", options.httpKeepAliveTimeoutMillis),
        property("connection_pool_enabled", options.connectionPoolEnabled),
        property("max_open_connections", options.maxOpenConnections),
        options.connectionReuseStrategy?.let { "connection_reuse_strategy" to it.name },
        property("use_server_time_zone", options.useServerTimeZone),
        property("compress", options.compressServerResponse),
        property("decompress", options.compressClientRequest),
        property("client.use_http_compression", options.useHttpCompression),
        property("compression.lz4.uncompressed_buffer_size", options.lz4UncompressedBufferSize),
        property("retry", options.retryOnFailure),
        property("client_name", options.clientName),
        options.sessionDbRoles.takeIf { it.isNotEmpty() }?.let { "session_db_roles" to it.joinToString(",") },
        options.sessionTimezone?.let { "use_time_zone" to it.id },
        property("query_id", options.queryId),
        property("clickhouse_setting_log_comment", options.logComment),
    ).forEach { (key, value) -> setProperty(key, value) }
    options.immutableServerSettings.forEach { (key, value) ->
        setProperty("clickhouse_setting_$key", value)
    }
    options.proxy?.let(::applyProxy)
    options.tls?.let(::applyTls)
    options.immutableCustomHeaders.forEach { (name, value) ->
        setProperty("http_header_${normalizeHeaderName(name)}", value)
    }
}

private fun property(key: String, value: Any?): Pair<String, String>? = value?.let { key to it.toString() }

private fun Properties.applyProxy(proxyOptions: ClickHouseV2ProxyOptions) {
    listOfNotNull(
        "proxy_type" to proxyOptions.type.uppercase(),
        "proxy_host" to proxyOptions.host,
        "proxy_port" to proxyOptions.port.toString(),
        proxyOptions.user?.let { "proxy_user" to it },
    ).forEach { (key, value) -> setProperty(key, value) }
    proxyOptions.password?.let { withSecret("proxy_password", it) }
}

private fun Properties.applyTls(tlsOptions: ClickHouseV2TlsOptions) {
    listOfNotNull(
        property("ssl", true),
        tlsOptions.trustStore?.let { "trust_store" to it },
        tlsOptions.keyStoreType?.let { "key_store_type" to it },
        tlsOptions.sslKeyStore?.let { "ssl_key_store" to it },
        tlsOptions.sslKeyReference?.let { "ssl_key" to it },
        tlsOptions.sslRootCertReference?.let { "sslrootcert" to it },
        tlsOptions.sslCertReference?.let { "sslcert" to it },
        property("ssl_authentication", tlsOptions.sslAuthentication),
        tlsOptions.sslSocketSni?.let { "ssl_socket_sni" to it },
    ).forEach { (key, value) -> setProperty(key, value) }
    tlsOptions.keyStorePassword?.let { withSecret("key_store_password", it) }
}

internal fun Properties.applyAuthentication(
    authentication: ClickHouseV2Authentication,
    user: String,
    password: String,
) {
    when (authentication) {
        ClickHouseV2Authentication.Basic -> {
            setProperty("user", user)
            setProperty("password", password)
            setProperty("http_use_basic_auth", "true")
        }

        is ClickHouseV2Authentication.AccessToken -> {
            setProperty("access_token", authentication.value)
            setProperty("http_use_basic_auth", "false")
        }

        is ClickHouseV2Authentication.BearerToken -> {
            setProperty("bearer_token", authentication.value)
            setProperty("http_use_basic_auth", "false")
        }
    }
}

internal fun Properties.copy(): Properties = Properties().also { copy ->
    forEach { (key, value) -> copy[key] = value }
}

// Secret providers are user-supplied and may throw any unchecked exception; sanitize it here
// before the failure crosses the JDBC boundary.
@Suppress("TooGenericExceptionCaught")
private fun Properties.withSecret(key: String, provider: ClickHouseV2SecretProvider) {
    val secret = try {
        provider.resolve()
    } catch (error: RuntimeException) {
        throw IllegalArgumentException("$key secret provider failed", ClickHouseV2Redaction.sanitizeThrowable(error))
    }
    try {
        setProperty(key, String(secret))
    } finally {
        secret.fill('\u0000')
    }
}

private fun normalizeRawPropertyKey(key: String): String {
    if (!key.startsWith("http_header_")) return key
    return "http_header_${normalizeHeaderName(key.removePrefix("http_header_"))}"
}
