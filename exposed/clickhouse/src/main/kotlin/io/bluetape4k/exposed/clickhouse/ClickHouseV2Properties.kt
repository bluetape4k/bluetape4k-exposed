package io.bluetape4k.exposed.clickhouse

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Properties

/**
 * V2 옵션을 JDBC [Properties]로 변환하는 내부 경계입니다.
 *
 * 이 함수는 입력 map을 변경하지 않고 새 [Properties]를 반환합니다. JDBC URL query
 * 값은 driver 규칙에 맞게 마지막에 overlay되어 typed/raw 값보다 우선합니다.
 */
internal fun ClickHouseV2Options.toEffectiveProperties(
    user: String,
    password: String,
    jdbcUrl: String? = null,
): Properties {
    val urlProperties = jdbcUrl?.let(::parseJdbcUrlProperties).orEmpty()
    validateUrlAuthenticationKeys(urlProperties, jdbcUrl)
    validateExplicitCredentials(authentication, user, password)

    val properties = Properties().apply {
        applyRawProperties(this@toEffectiveProperties)
        applyTypedProperties(this@toEffectiveProperties)
        applyAuthentication(authentication, user, password)
    }

    // JDBC URL properties have the highest precedence, but URL authentication keys were
    // rejected above so they cannot bypass the typed authentication mode.
    urlProperties.forEach { (key, value) -> properties.setProperty(key, value) }
    return properties.copy()
}

private fun validateExplicitCredentials(
    authentication: ClickHouseV2Authentication,
    user: String,
    password: String,
) {
    if (authentication !is ClickHouseV2Authentication.Basic) {
        require(user == "default" && password.isEmpty()) {
            "token 인증에서는 기본 placeholder user/password만 사용할 수 있습니다."
        }
    }
}

private fun parseJdbcUrlProperties(jdbcUrl: String): Map<String, String> {
    val query = jdbcUrl.substringAfter('?', missingDelimiterValue = "")
        .substringBefore('#')
    if (query.isBlank()) return emptyMap()
    return query.split('&', ';')
        .filter { it.isNotBlank() }
        .associate { pair ->
            val key = pair.substringBefore('=').decodeUrlComponent()
            val value = pair.substringAfter('=', missingDelimiterValue = "").decodeUrlComponent()
            key to value
        }
}

private fun validateUrlAuthenticationKeys(properties: Map<String, String>, jdbcUrl: String?) {
    val authenticationKeys = setOf("user", "password", "access_token", "bearer_token", "http_use_basic_auth")
    val present = properties.keys.filter { it.lowercase() in authenticationKeys }
    require(present.isEmpty()) {
        "JDBC URL 인증 property는 options connect에서 사용할 수 없습니다: " +
            ClickHouseV2Redaction.redactJdbcUrl(jdbcUrl.orEmpty())
    }
}

private fun String.decodeUrlComponent(): String = URLDecoder.decode(this, StandardCharsets.UTF_8)
