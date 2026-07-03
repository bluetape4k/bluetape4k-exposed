package io.bluetape4k.exposed.core.fastjson2

import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONArray
import com.alibaba.fastjson2.JSONObject
import io.r2dbc.spi.Readable
import kotlin.test.Test
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull

class ReadableExtensionsTest {

    private data class Payload(val name: String, val age: Int)

    private class FakeReadable(
        private val valuesByIndex: Map<Int, Any?> = emptyMap(),
        private val valuesByName: Map<String, Any?> = emptyMap(),
    ): Readable {
        override fun <T: Any?> get(index: Int, type: Class<T>): T? {
            val value = valuesByIndex[index] ?: return null
            if (!type.isInstance(value)) return null
            @Suppress("UNCHECKED_CAST")
            return value as T
        }

        override fun <T: Any?> get(name: String, type: Class<T>): T? {
            val value = valuesByName[name] ?: return null
            if (!type.isInstance(value)) return null
            @Suppress("UNCHECKED_CAST")
            return value as T
        }

        override fun get(index: Int): Any? = valuesByIndex[index]
        override fun get(name: String): Any? = valuesByName[name]
    }

    @Test
    fun `Readable fastjson getter supports index and name`() {
        val jsonText = """{"name":"blue","age":20}"""
        val readable = FakeReadable(
            valuesByIndex = mapOf(0 to jsonText),
            valuesByName = mapOf("payload" to jsonText),
        )

        readable.getFastjson<Payload>(0) shouldBeEqualTo Payload("blue", 20)
        readable.getFastjson<Payload>("payload") shouldBeEqualTo Payload("blue", 20)
    }

    @Test
    fun `Readable fastjson getter supports ByteArray value by index`() {
        val payload = Payload("bytes", 5)
        val jsonbBytes = DefaultFastjsonSerializer.serialize(payload)
        val readable = FakeReadable(valuesByIndex = mapOf(0 to jsonbBytes))

        readable.getFastjson<Payload>(0) shouldBeEqualTo payload
    }

    @Test
    fun `Readable fastjson getter supports ByteArray value by name`() {
        val payload = Payload("bytes", 5)
        val jsonbBytes = DefaultFastjsonSerializer.serialize(payload)
        val readable = FakeReadable(valuesByName = mapOf("payload" to jsonbBytes))

        readable.getFastjson<Payload>("payload") shouldBeEqualTo payload
    }

    @Test
    fun `Readable fastjson getter returns T directly when value is already T by index`() {
        val payload = Payload("direct", 99)
        val readable = FakeReadable(valuesByIndex = mapOf(0 to payload))

        readable.getFastjson<Payload>(0) shouldBeEqualTo payload
    }

    @Test
    fun `Readable fastjson getter returns T directly when value is already T by name`() {
        val payload = Payload("direct", 99)
        val readable = FakeReadable(valuesByName = mapOf("payload" to payload))

        readable.getFastjson<Payload>("payload") shouldBeEqualTo payload
    }

    @Test
    fun `Readable fastjson getter handles else branch via toString by index`() {
        val jsonText = """{"name":"tostring","age":7}"""
        val customObj = object {
            override fun toString() = jsonText
        }
        val readable = FakeReadable(valuesByIndex = mapOf(0 to customObj))

        readable.getFastjson<Payload>(0) shouldBeEqualTo Payload("tostring", 7)
    }

    @Test
    fun `Readable fastjson getter handles else branch via toString by name`() {
        val jsonText = """{"name":"tostring","age":7}"""
        val customObj = object {
            override fun toString() = jsonText
        }
        val readable = FakeReadable(valuesByName = mapOf("payload" to customObj))

        readable.getFastjson<Payload>("payload") shouldBeEqualTo Payload("tostring", 7)
    }

    @Test
    fun `Readable fastjson object and array getter supports text`() {
        val objectText = """{"user":{"name":"tester"}}"""
        val arrayText = """[1,2,3]"""
        val readable = FakeReadable(
            valuesByIndex = mapOf(1 to objectText, 2 to arrayText),
        )

        readable.getFastjsonObject(1).getJSONObject("user").getString("name") shouldBeEqualTo "tester"
        readable.getFastjsonArray(2) shouldHaveSize 3
    }

    @Test
    fun `Readable fastjson object getter supports ByteArray by index`() {
        val jsonText = """{"x":10}"""
        val readable = FakeReadable(valuesByIndex = mapOf(0 to jsonText.toByteArray()))

        readable.getFastjsonObject(0).getInteger("x") shouldBeEqualTo 10
    }

    @Test
    fun `Readable fastjson object getter supports ByteArray by name`() {
        val jsonText = """{"x":10}"""
        val readable = FakeReadable(valuesByName = mapOf("obj" to jsonText.toByteArray()))

        readable.getFastjsonObject("obj").getInteger("x") shouldBeEqualTo 10
    }

    @Test
    fun `Readable fastjson object getter returns JSONObject directly when value is JSONObject by index`() {
        val jsonObject: JSONObject = JSON.parseObject("""{"x":5}""")
        val readable = FakeReadable(valuesByIndex = mapOf(0 to jsonObject))

        readable.getFastjsonObject(0) shouldBeEqualTo jsonObject
    }

    @Test
    fun `Readable fastjson object getter returns JSONObject directly when value is JSONObject by name`() {
        val jsonObject: JSONObject = JSON.parseObject("""{"x":5}""")
        val readable = FakeReadable(valuesByName = mapOf("obj" to jsonObject))

        readable.getFastjsonObject("obj") shouldBeEqualTo jsonObject
    }

    @Test
    fun `Readable fastjson object getter handles else branch via toString by index`() {
        val jsonText = """{"z":99}"""
        val customObj = object {
            override fun toString() = jsonText
        }
        val readable = FakeReadable(valuesByIndex = mapOf(0 to customObj))

        readable.getFastjsonObject(0).getInteger("z") shouldBeEqualTo 99
    }

    @Test
    fun `Readable fastjson object getter handles else branch via toString by name`() {
        val jsonText = """{"z":99}"""
        val customObj = object {
            override fun toString() = jsonText
        }
        val readable = FakeReadable(valuesByName = mapOf("obj" to customObj))

        readable.getFastjsonObject("obj").getInteger("z") shouldBeEqualTo 99
    }

    @Test
    fun `Readable fastjson array getter supports ByteArray by index`() {
        val jsonText = """[10,20,30]"""
        val readable = FakeReadable(valuesByIndex = mapOf(0 to jsonText.toByteArray()))

        readable.getFastjsonArray(0) shouldHaveSize 3
    }

    @Test
    fun `Readable fastjson array getter supports ByteArray by name`() {
        val jsonText = """[10,20,30]"""
        val readable = FakeReadable(valuesByName = mapOf("arr" to jsonText.toByteArray()))

        readable.getFastjsonArray("arr") shouldHaveSize 3
    }

    @Test
    fun `Readable fastjson array getter returns JSONArray directly when value is JSONArray by index`() {
        val jsonArray: JSONArray = JSON.parseArray("""[1,2,3]""")
        val readable = FakeReadable(valuesByIndex = mapOf(0 to jsonArray))

        readable.getFastjsonArray(0) shouldBeEqualTo jsonArray
    }

    @Test
    fun `Readable fastjson array getter returns JSONArray directly when value is JSONArray by name`() {
        val jsonArray: JSONArray = JSON.parseArray("""[1,2,3]""")
        val readable = FakeReadable(valuesByName = mapOf("arr" to jsonArray))

        readable.getFastjsonArray("arr") shouldBeEqualTo jsonArray
    }

    @Test
    fun `Readable fastjson array getter handles else branch via toString by index`() {
        val jsonText = """[7,8,9]"""
        val customObj = object {
            override fun toString() = jsonText
        }
        val readable = FakeReadable(valuesByIndex = mapOf(0 to customObj))

        readable.getFastjsonArray(0) shouldHaveSize 3
    }

    @Test
    fun `Readable fastjson array getter handles else branch via toString by name`() {
        val jsonText = """[7,8,9]"""
        val customObj = object {
            override fun toString() = jsonText
        }
        val readable = FakeReadable(valuesByName = mapOf("arr" to customObj))

        readable.getFastjsonArray("arr") shouldHaveSize 3
    }

    @Test
    fun `Readable fastjson object getter supports name based string`() {
        val jsonText = """{"user":{"name":"tester"}}"""
        val readable = FakeReadable(valuesByName = mapOf("payload" to jsonText))

        readable.getFastjsonObject("payload").getJSONObject("user").getString("name") shouldBeEqualTo "tester"
    }

    @Test
    fun `Readable fastjson array getter supports name based string`() {
        val arrayText = """[1,2,3]"""
        val readable = FakeReadable(valuesByName = mapOf("items" to arrayText))

        readable.getFastjsonArray("items") shouldHaveSize 3
    }

    @Test
    fun `Readable fastjson nullable getter returns null when value is null by index`() {
        val readable = FakeReadable(valuesByIndex = mapOf(3 to null))
        readable.getFastjsonOrNull<Payload>(3).shouldBeNull()
        readable.getFastjsonObjectOrNull(3).shouldBeNull()
        readable.getFastjsonArrayOrNull(3).shouldBeNull()
    }

    @Test
    fun `Readable fastjson nullable getter returns null when key absent by name`() {
        val readable = FakeReadable()
        readable.getFastjsonOrNull<Payload>("missing").shouldBeNull()
        readable.getFastjsonObjectOrNull("missing").shouldBeNull()
        readable.getFastjsonArrayOrNull("missing").shouldBeNull()
    }

    @Test
    fun `Readable fastjson nullable getter returns result when value exists by name`() {
        val jsonText = """{"name":"present","age":1}"""
        val arrayText = """[1,2]"""
        val readable = FakeReadable(
            valuesByName = mapOf("payload" to jsonText, "items" to arrayText),
        )

        readable.getFastjsonOrNull<Payload>("payload").shouldNotBeNull()
        readable.getFastjsonObjectOrNull("payload").shouldNotBeNull()
        readable.getFastjsonArrayOrNull("items").shouldNotBeNull()
    }

    @Test
    fun `Readable fastjson non null getter throws descriptive error when value is null`() {
        val readable = FakeReadable(valuesByName = mapOf("payload" to null))

        val ex = assertFailsWith<IllegalStateException> {
            readable.getFastjson<Payload>("payload")
        }
        ex.message shouldBeEqualTo "Column[payload] is null or not convertible to Payload."
    }

    @Test
    fun `Readable fastjson non null getter throws descriptive error when index value is null`() {
        val readable = FakeReadable(valuesByIndex = mapOf(0 to null))

        val ex = assertFailsWith<IllegalStateException> {
            readable.getFastjson<Payload>(0)
        }
        ex.message shouldBeEqualTo "Column[0] is null or not convertible to Payload."
    }

    @Test
    fun `Readable fastjson object non null getter throws descriptive error when null by index`() {
        val readable = FakeReadable(valuesByIndex = mapOf(0 to null))

        val ex = assertFailsWith<IllegalStateException> {
            readable.getFastjsonObject(0)
        }
        ex.message shouldBeEqualTo "Column[0] is null or not convertible to JSONObject."
    }

    @Test
    fun `Readable fastjson object non null getter throws descriptive error when null by name`() {
        val readable = FakeReadable(valuesByName = mapOf("obj" to null))

        val ex = assertFailsWith<IllegalStateException> {
            readable.getFastjsonObject("obj")
        }
        ex.message shouldBeEqualTo "Column[obj] is null or not convertible to JSONObject."
    }

    @Test
    fun `Readable fastjson array non null getter throws descriptive error when null by index`() {
        val readable = FakeReadable(valuesByIndex = mapOf(0 to null))

        val ex = assertFailsWith<IllegalStateException> {
            readable.getFastjsonArray(0)
        }
        ex.message shouldBeEqualTo "Column[0] is null or not convertible to JSONArray."
    }

    @Test
    fun `Readable fastjson array non null getter throws descriptive error when null by name`() {
        val readable = FakeReadable(valuesByName = mapOf("arr" to null))

        val ex = assertFailsWith<IllegalStateException> {
            readable.getFastjsonArray("arr")
        }
        ex.message shouldBeEqualTo "Column[arr] is null or not convertible to JSONArray."
    }
}
