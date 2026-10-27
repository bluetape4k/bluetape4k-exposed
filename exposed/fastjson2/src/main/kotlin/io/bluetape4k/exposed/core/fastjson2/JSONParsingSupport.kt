package io.bluetape4k.exposed.core.fastjson2

import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONArray
import com.alibaba.fastjson2.JSONObject
import io.bluetape4k.fastjson2.FastjsonSerializer


inline fun <reified T: Any> deserializeValueOrNull(
    value: Any,
    serializer: FastjsonSerializer = DefaultFastjsonSerializer,
): T? {
    return when (value) {
        is String    -> serializer.deserializeFromString<T>(value)
        is ByteArray -> serializer.deserialize<T>(value)
        is T         -> value
        else         -> serializer.deserializeFromString<T>(value.toString())
    }
}

internal fun parseJsonObject(value: Any): JSONObject = when (value) {
    is JSONObject -> value
    is String     -> JSON.parseObject(value)
    is ByteArray  -> JSON.parseObject(value)
    else          -> JSON.parseObject(value.toString())
}


internal fun parseJsonArray(value: Any): JSONArray = when (value) {
    is JSONArray -> value
    is String    -> JSON.parseArray(value)
    is ByteArray -> JSON.parseArray(value)
    else         -> JSON.parseArray(value.toString())
}
