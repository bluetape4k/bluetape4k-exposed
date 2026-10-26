package io.bluetape4k.exposed.core.jackson

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.bluetape4k.jackson.JacksonSerializer

inline fun <reified T: Any> deserializeOrNull(value: Any, serializer: JacksonSerializer): T? = when (value) {
    is T         -> value
    is String    -> serializer.deserializeFromString<T>(value)
    is ByteArray -> serializer.deserialize<T>(value)
    else         -> serializer.deserializeFromString<T>(value.toString())
}

internal fun readJsonNodeOrNull(value: Any, mapper: ObjectMapper): JsonNode? = when (value) {
    is JsonNode  -> value
    is String    -> mapper.readTree(value)
    is ByteArray -> mapper.readTree(value)
    else         -> mapper.readTree(value.toString())
}
