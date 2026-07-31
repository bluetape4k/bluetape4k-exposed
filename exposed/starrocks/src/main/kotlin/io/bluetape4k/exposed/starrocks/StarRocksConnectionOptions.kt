package io.bluetape4k.exposed.starrocks

import io.bluetape4k.support.requireNotBlank
import java.io.Serializable
import java.util.Properties

/**
 * type이 지정된 StarRocks JDBC connection option입니다.
 *
 * StarRocks는 현재 Connector/J의 표준 `user`, `password` connection property만 문서화합니다.
 * 추가 property는 호출자가 StarRocks driver 문서에서 option을 검증한 뒤 driver 수준 tuning에 사용하는
 * 제한된 escape hatch로 허용합니다.
 */
data class StarRocksConnectionOptions(
    val extraProperties: Map<String, String> = emptyMap(),
): Serializable {

    companion object {
        private const val serialVersionUID: Long = 1L
    }

    init {
        extraProperties.forEach { (key, value) ->
            key.requireNotBlank("extraProperties key")
            value.requireNotBlank("extraProperties[$key]")
        }
    }

    internal fun toProperties(user: String, password: String): Properties {
        user.requireNotBlank("user")

        return Properties().apply {
            setProperty("user", user)
            setProperty("password", password)
            extraProperties.forEach { (key, value) ->
                setProperty(key, value)
            }
        }
    }
}
