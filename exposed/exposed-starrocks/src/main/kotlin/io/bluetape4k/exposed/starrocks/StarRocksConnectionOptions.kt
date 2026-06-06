package io.bluetape4k.exposed.starrocks

import io.bluetape4k.support.requireNotBlank
import java.io.Serializable
import java.util.Properties

/**
 * Typed StarRocks JDBC connection options.
 *
 * StarRocks currently documents only standard `user` and `password`
 * connection properties for Connector/J. Additional properties are accepted as
 * a narrow escape hatch for driver-level tuning after the caller verifies the
 * option against the StarRocks driver documentation.
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
