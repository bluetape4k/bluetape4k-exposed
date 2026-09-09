package io.bluetape4k.exposed.clickhouse

import java.sql.SQLException

/** V2 연결 경계에서 URL과 예외에 남는 민감한 값을 제거하는 내부 도구입니다. */
internal object ClickHouseV2Redaction {

    fun redactJdbcUrl(url: String): String {
        val queryIndex = url.indexOf('?')
        if (queryIndex < 0) return url
        val base = url.substring(0, queryIndex)
        val query = url.substring(queryIndex + 1).substringBefore('#')
        return if (query.isBlank()) {
            base
        } else {
            val redacted = query.split('&', ';')
                .filter { it.isNotBlank() }
                .joinToString("&") { pair ->
                    val key = pair.substringBefore('=')
                    "$key=REDACTED"
                }
            "$base?$redacted"
        }
    }

    /** 원본 cause/suppressed를 보존하지 않고 타입과 JDBC code만 남긴 안전한 요약입니다. */
    fun sanitizeThrowable(error: Throwable): Throwable {
        val sqlException = error as? SQLException
        val type = error::class.simpleName ?: "Throwable"
        val sqlState = sqlException?.sqlState?.let { " sqlState=$it" }.orEmpty()
        val vendorCode = sqlException?.errorCode?.let { " vendorCode=$it" }.orEmpty()
        return IllegalStateException("$type$vendorCode$sqlState (sanitized)")
    }

    fun sanitizeMessage(message: String?): String = message
        ?.replace(credentialPattern, "$1=REDACTED")
        ?.replace(genericSecretPattern, "$1=REDACTED")
        ?: "ClickHouse JDBC V2 connection failed"

    private val credentialPattern = Regex(
        "(?i)(password|access_token|bearer_token|proxy_password|" +
            "key_store_password|ssl_key|sslrootcert|sslcert)=[^&\\s]+",
    )
    private val genericSecretPattern = Regex("(?i)(token|secret|credential)[:=]\\s*[^,;\\s]+")
}
