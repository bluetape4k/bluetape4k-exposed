package io.bluetape4k.exposed.clickhouse

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Properties

/**
 * V2 옵션을 JDBC [Properties]로 변환하는 내부 경계입니다.
 *
 * 이 함수는 입력 map을 변경하지 않고 새 [Properties]를 반환합니다. JDBC URL의 일반
 * query 값은 원본 URL과 함께 driver에 전달되며, 이 함수는 인증 key 우회만 차단합니다.
 */
internal fun ClickHouseV2Options.toEffectiveProperties(
    user: String,
    password: String,
    jdbcUrl: String? = null,
): Properties {
    validateUrlAuthenticationKeys(jdbcUrl)
    validateExplicitCredentials(authentication, user, password)

    val properties = Properties().apply {
        applyRawProperties(this@toEffectiveProperties)
        applyTypedProperties(this@toEffectiveProperties)
        applyAuthentication(authentication, user, password)
    }

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

private fun validateUrlAuthenticationKeys(jdbcUrl: String?) {
    if (jdbcUrl.isNullOrBlank()) return

    val query = jdbcUrl.substringAfter('?', missingDelimiterValue = "")
        .substringBefore('#')
    if (query.isBlank()) return

    val authenticationKeys = setOf("user", "password", "access_token", "bearer_token", "http_use_basic_auth")
    val present = query.split('&', ';')
        .filter { it.isNotBlank() }
        .map { pair ->
            pair.substringBefore('=').decodeUrlComponent().lowercase()
        }
        .filter { it in authenticationKeys }
    require(present.isEmpty()) {
        "JDBC URL 인증 property는 options connect에서 사용할 수 없습니다: " +
            ClickHouseV2Redaction.redactJdbcUrl(jdbcUrl)
    }
}

private fun String.decodeUrlComponent(): String = URLDecoder.decode(this, StandardCharsets.UTF_8)
