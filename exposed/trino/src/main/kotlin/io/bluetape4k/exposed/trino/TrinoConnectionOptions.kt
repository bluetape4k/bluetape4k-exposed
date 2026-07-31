package io.bluetape4k.exposed.trino

import java.io.Serializable
import java.util.Properties

/**
 * 운영 및 성능 tuning에 사용하는 typed Trino JDBC connection property입니다.
 *
 * Option은 표준 Trino JDBC property name으로 변환되며, 기존 connection overload를 변경하지
 * 않고 [TrinoDatabase.connect]에 전달할 수 있습니다.
 */
data class TrinoConnectionOptions(
    val explicitPrepare: Boolean? = null,
    val encoding: String? = null,
    val validateConnection: Boolean? = null,
    val source: String? = null,
    val clientTags: List<String> = emptyList(),
    val sessionProperties: Map<String, String> = emptyMap(),
    val extraCredentials: Map<String, String> = emptyMap(),
    val extraHeaders: Map<String, String> = emptyMap(),
): Serializable {

    companion object {
        private const val serialVersionUID: Long = 1L
    }

    init {
        encoding?.let { require(it.isNotBlank()) { "encoding must not be blank." } }
        source?.let { require(it.isNotBlank()) { "source must not be blank." } }
        require(clientTags.none { it.isBlank() }) { "clientTags must not contain blank entries." }
        validatePairs("sessionProperties", sessionProperties)
        validatePairs("extraCredentials", extraCredentials)
        validatePairs("extraHeaders", extraHeaders)
    }

    internal fun toProperties(user: String): Properties {
        require(user.isNotBlank()) { "user must not be blank." }

        return Properties().apply {
            setProperty("user", user)
            explicitPrepare?.let { setProperty("explicitPrepare", it.toString()) }
            encoding?.let { setProperty("encoding", it) }
            validateConnection?.let { setProperty("validateConnection", it.toString()) }
            source?.let { setProperty("source", it) }
            clientTags.takeIf { it.isNotEmpty() }?.let {
                setProperty("clientTags", it.joinToString(","))
            }
            sessionProperties.takeIf { it.isNotEmpty() }?.let {
                setProperty("sessionProperties", it.toTrinoPropertyValue())
            }
            extraCredentials.takeIf { it.isNotEmpty() }?.let {
                setProperty("extraCredentials", it.toTrinoPropertyValue())
            }
            extraHeaders.takeIf { it.isNotEmpty() }?.let {
                setProperty("extraHeaders", it.toTrinoPropertyValue())
            }
        }
    }
}

private fun validatePairs(name: String, values: Map<String, String>) {
    values.forEach { (key, value) ->
        require(key.isNotBlank()) { "$name key must not be blank." }
        require(value.isNotBlank()) { "$name value must not be blank for key '$key'." }
    }
}

private fun Map<String, String>.toTrinoPropertyValue(): String =
    entries.joinToString(",") { (key, value) -> "$key=$value" }
