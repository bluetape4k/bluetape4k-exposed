package io.bluetape4k.exposed.clickhouse

import java.sql.SQLException

/** query diagnostics 경계에서 SQL·bind·credential·예외 메시지를 안전하게 축약합니다. */
internal object ClickHouseQueryDiagnosticsRedaction {

    private val safeResponseHeaders = setOf(
        "x-clickhouse-query-id",
        "x-clickhouse-summary",
        "x-clickhouse-server-display-name",
        "x-clickhouse-database",
        "x-clickhouse-user",
    )

    private val sensitiveAssignment = Regex(
        "(?i)(password|access[_-]?token|bearer[_-]?token|token|secret|credential|authorization|" +
            "x-api-key|bind|parameter|value)\\s*([:=])\\s*(\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;&]+)",
    )

    /** 알려진 민감한 assignment의 value를 제거합니다. */
    fun redact(value: String?): String = value
        ?.let { sensitiveAssignment.replace(it) { match -> "${match.groupValues[1]}${match.groupValues[2]}REDACTED" } }
        ?.replace(Regex("(?i)jdbc:clickhouse://[^\\s?]+\\?[^\\s]*"), "jdbc:clickhouse://REDACTED")
        ?: ""

    /** 이름이 credential을 나타내는 session setting은 값 전체를 숨깁니다. */
    fun redactSetting(name: String, value: String): String =
        if (name.contains(Regex("(?i)(password|token|secret|credential|authorization|api[-_]?key|bind|parameter)"))) {
            "REDACTED"
        } else {
            redact(value)
        }

    /** V2 driver가 수집한 allowlisted response header만 snapshot합니다. */
    fun redactResponseHeaders(headers: Map<*, *>): Map<String, String> = headers
        .mapNotNull { (key, value) ->
            val name = key as? String ?: return@mapNotNull null
            if (name.lowercase() !in safeResponseHeaders) return@mapNotNull null
            val text = value as? String ?: return@mapNotNull null
            name to redactSetting(name, text)
        }
        .toMap()

    /** 원본 cause/suppressed/message/stack을 넘기지 않고 타입과 JDBC code만 보존합니다. */
    fun sanitizeThrowable(error: Throwable?): String {
        if (error == null) return ""
        val sqlException = generateSequence(error) { it.cause }
            .filterIsInstance<SQLException>()
            .firstOrNull()
        val type = error::class.simpleName ?: "Throwable"
        val sqlState = sqlException?.sqlState?.let { " sqlState=$it" }.orEmpty()
        val vendorCode = sqlException?.errorCode?.takeIf { it != 0 }?.let { " vendorCode=$it" }.orEmpty()
        return "$type$vendorCode$sqlState (sanitized)"
    }
}
