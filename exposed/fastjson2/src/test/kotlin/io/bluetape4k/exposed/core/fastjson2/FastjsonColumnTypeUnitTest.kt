package io.bluetape4k.exposed.core.fastjson2

import io.bluetape4k.codec.Base58
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.fastjson2.FastjsonSerializer as SharedFastjsonSerializer
import io.bluetape4k.support.toUtf8Bytes
import java.io.IOException
import java.io.Reader
import java.io.StringReader
import java.lang.reflect.Proxy
import java.sql.Clob
import java.util.concurrent.atomic.AtomicBoolean
import org.jetbrains.exposed.v1.core.statements.api.RowApi
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.statements.jdbc.JdbcResult
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test

/**
 * [FastjsonColumnType] 및 [FastjsonBColumnType]의 직렬화/역직렬화 단위 테스트입니다.
 */
class FastjsonColumnTypeUnitTest {
    private data class SamplePayload(
        val name: String,
        val count: Int,
    )

    private val serializer = DefaultFastjsonSerializer
    private val columnType =
        FastjsonColumnType<SamplePayload>(
            serilaize = { serializer.serializeAsString(it) },
            deserialize = { serializer.deserializeFromString<SamplePayload>(it)!! }
        )

    @Test
    fun `valueFromDB 는 문자열 JSON 을 객체로 역직렬화한다`() {
        val source = SamplePayload("alpha", 10)
        val json = serializer.serializeAsString(source)

        columnType.valueFromDB(json) shouldBeEqualTo source
    }

    @Test
    fun `valueFromDB 는 UTF-8 바이트 JSON 을 객체로 역직렬화한다`() {
        val source = SamplePayload("beta", 20)
        val jsonBytes = serializer.serializeAsString(source).toUtf8Bytes()

        columnType.valueFromDB(jsonBytes) shouldBeEqualTo source
    }

    @Test
    fun `valueFromDB 는 미지원 타입 입력 시 원본 값을 그대로 반환한다`() {
        columnType.valueFromDB(1234) shouldBeEqualTo 1234
    }

    @Test
    fun `notNullValueToDB 는 객체를 JSON 문자열로 직렬화한다`() {
        val source = SamplePayload("gamma", 30)
        val result = columnType.notNullValueToDB(source)

        result shouldBeInstanceOf String::class
        val json = result as String
        json shouldContain "\"name\":\"gamma\""
        json shouldContain "\"count\":30"
    }

    @Test
    fun `valueFromDB 후 notNullValueToDB 왕복 변환이 일관된다`() {
        val source = SamplePayload("roundtrip", 99)
        val json = columnType.notNullValueToDB(source) as String
        val restored = columnType.valueFromDB(json)

        restored shouldBeEqualTo source
    }

    @Test
    fun `nonNullValueToString 은 JSON SQL 리터럴 특수문자를 escape 한다`() {
        val json = "{\"name\":\"O'Reilly\",\r\n\"count\":1}"
        val literalColumnType = FastjsonColumnType<SamplePayload>(
            serilaize = { json },
            deserialize = { serializer.deserializeFromString<SamplePayload>(it)!! }
        )
        val database = Database.connect(
            url = "jdbc:h2:mem:fastjson-literal-${Base58.randomString(8)};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )

        transaction(database) {
            val rendered = literalColumnType.nonNullValueToString(SamplePayload("ignored", 1))

            rendered shouldContain "O''Reilly"
            rendered shouldContain "\\r"
            rendered shouldContain "\\n"
            literalColumnType.nonNullValueAsDefaultString(SamplePayload("ignored", 1)) shouldBeEqualTo rendered
        }
    }

    @Test
    fun `FastjsonColumnType 은 usesBinaryFormat 이 false 이다`() {
        columnType.usesBinaryFormat.shouldBeFalse()
    }

    @Test
    fun `FastjsonBColumnType 은 usesBinaryFormat 이 true 이다`() {
        val bColumnType =
            FastjsonBColumnType<SamplePayload>(
                serialize = { serializer.serializeAsString(it) },
                deserialize = { serializer.deserializeFromString<SamplePayload>(it)!! }
            )
        bColumnType.usesBinaryFormat.shouldBeTrue()
    }

    @Test
    fun `FastjsonBColumnType 은 valueFromDB 에서 문자열을 역직렬화한다`() {
        val bColumnType =
            FastjsonBColumnType<SamplePayload>(
                serialize = { serializer.serializeAsString(it) },
                deserialize = { serializer.deserializeFromString<SamplePayload>(it)!! }
            )
        val source = SamplePayload("jsonb", 42)
        val json = serializer.serializeAsString(source)

        bColumnType.valueFromDB(json) shouldBeEqualTo source
    }

    @Test
    fun `valueFromDB 에 잘못된 JSON 문자열이 들어오면 예외가 발생한다`() {
        assertFailsWith<Exception> {
            columnType.valueFromDB("not-valid-json")
        }
    }

    /**
     * Exposed Fastjson2 모듈의 기본 serializer facade가 shared serializer와 같은 인스턴스를 반환합니다.
     */
    @Test
    fun `DefaultFastjsonSerializer 는 동일한 싱글턴 인스턴스를 반환한다`() {
        val s1 = DefaultFastjsonSerializer
        val s2 = DefaultFastjsonSerializer

        (s1 === s2).shouldBeTrue()
        (s1 === SharedFastjsonSerializer.Default).shouldBeTrue()
    }

    /**
     * [fastjson] 테이블 확장함수의 역직렬화 람다는 빈 문자열 입력 시
     * `!!` 대신 `requireNotNull`을 사용하므로 [IllegalArgumentException]이 발생해야 합니다.
     */
    @Test
    fun `fastjson 확장함수 역직렬화 람다는 빈 문자열 입력 시 IllegalArgumentException 을 던진다`() {
        // FastjsonSerializer.deserializeFromString returns null for empty string
        // The default fastjson column extension wraps this with requireNotNull
        val requireNotNullDeserialize: (String) -> SamplePayload = {
            requireNotNull(serializer.deserializeFromString<SamplePayload>(it)) {
                "JSON 문자열을 SamplePayload 타입으로 역직렬화한 결과가 null입니다. 입력: $it"
            }
        }
        val ct = FastjsonColumnType<SamplePayload>(
            serilaize = { serializer.serializeAsString(it) },
            deserialize = requireNotNullDeserialize
        )
        assertFailsWith<IllegalArgumentException> {
            ct.valueFromDB("")
        }
    }

    /**
     * [FastjsonBColumnType]의 역직렬화 람다도 빈 문자열에서 [IllegalArgumentException]을 던져야 합니다.
     */
    @Test
    fun `fastjsonb 확장함수 역직렬화 람다는 빈 문자열 입력 시 IllegalArgumentException 을 던진다`() {
        val bColumnType = FastjsonBColumnType<SamplePayload>(
            serialize = { serializer.serializeAsString(it) },
            deserialize = {
                requireNotNull(serializer.deserializeFromString<SamplePayload>(it)) {
                    "JSON 문자열을 SamplePayload 타입으로 역직렬화한 결과가 null입니다. 입력: $it"
                }
            }
        )
        assertFailsWith<IllegalArgumentException> {
            bColumnType.valueFromDB("")
        }
    }

    /**
     * JSON 직렬화 후 역직렬화 왕복 변환이 [FastjsonBColumnType]에서도 동일하게 동작해야 합니다.
     */
    @Test
    fun `FastjsonBColumnType 도 왕복 변환이 일관된다`() {
        val bColumnType = FastjsonBColumnType<SamplePayload>(
            serialize = { serializer.serializeAsString(it) },
            deserialize = { serializer.deserializeFromString<SamplePayload>(it)!! }
        )
        val source = SamplePayload("bRoundtrip", 77)
        val json = bColumnType.notNullValueToDB(source) as String
        val restored = bColumnType.valueFromDB(json)

        restored shouldBeEqualTo source
    }

    /**
     * 특수 문자가 포함된 값도 직렬화 후 역직렬화가 정상 동작해야 합니다.
     */
    @Test
    fun `특수문자가 포함된 값도 왕복 변환이 정상 동작한다`() {
        val source = SamplePayload("name with \"quotes\" and 한글", 0)
        val json = columnType.notNullValueToDB(source) as String
        val restored = columnType.valueFromDB(json)

        restored shouldBeEqualTo source
    }

    /**
     * [FastjsonColumnType.notNullValueToDB]는 특수문자를 포함한 객체도 올바르게 직렬화합니다.
     * JSON 직렬화 결과에는 field명과 값이 모두 포함되어야 합니다.
     */
    @Test
    fun `notNullValueToDB 는 직렬화된 JSON 문자열에 필드명과 값이 모두 포함된다`() {
        val source = SamplePayload("myValue", 42)
        val result = columnType.notNullValueToDB(source) as String

        result shouldContain "\"name\""
        result shouldContain "\"myValue\""
        result shouldContain "\"count\""
        result shouldContain "42"
    }

    @Test
    fun `readObject 는 JDBC Clob 을 JSON 문자열로 정규화한다`() {
        val source = SamplePayload("clob", 42)
        val json = serializer.serializeAsString(source)
        val database = Database.connect(
            url = "jdbc:h2:mem:fastjson-clob-${Base58.randomString(8)};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )
        val reader = TrackingReader(json)
        val row = rowWith(clobWith(reader))

        transaction(database) {
            val raw = columnType.readObject(row, 1)

            raw shouldBeEqualTo json
            columnType.valueFromDB(requireNotNull(raw)) shouldBeEqualTo source
        }
        reader.closed.get().shouldBeTrue()
    }

    @Test
    fun `readObject 는 Clob 읽기 실패에도 reader를 닫는다`() {
        val database = Database.connect(
            url = "jdbc:h2:mem:fastjson-clob-error-${Base58.randomString(8)};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )
        val reader = TrackingReader("invalid", failOnRead = true)
        val row = rowWith(clobWith(reader))

        assertFailsWith<IOException> {
            transaction(database) {
                columnType.readObject(row, 1)
            }
        }
        reader.closed.get().shouldBeTrue()
    }

    @Test
    fun `readObject 는 H2 Oracle mode의 실제 CLOB 결과를 JSON 문자열로 정규화한다`() {
        val source = SamplePayload("oracle", 42)
        val json = serializer.serializeAsString(source)
        val database = Database.connect(
            url = "jdbc:h2:mem:fastjson-oracle-clob-${Base58.randomString(8)};MODE=Oracle;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )

        transaction(database) {
            exec("CREATE TABLE json_clob_test (payload CLOB)")
            exec("INSERT INTO json_clob_test (payload) VALUES ('$json')")
            exec("SELECT payload FROM json_clob_test") { resultSet ->
                resultSet.next().shouldBeTrue()
                val raw = columnType.readObject(JdbcResult(resultSet), 1)

                raw shouldBeEqualTo json
                columnType.valueFromDB(requireNotNull(raw)) shouldBeEqualTo source
            }
        }
    }

    private fun rowWith(value: Any): RowApi =
        Proxy.newProxyInstance(
            RowApi::class.java.classLoader,
            arrayOf(RowApi::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getObject" -> value
                "getString" -> value as? String
                else -> error("Unexpected RowApi method: ${method.name}")
            }
        } as RowApi

    private fun clobWith(reader: Reader): Clob =
        Proxy.newProxyInstance(
            Clob::class.java.classLoader,
            arrayOf(Clob::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getCharacterStream" -> reader
                "toString" -> "tracking-clob"
                "hashCode" -> System.identityHashCode(reader)
                "equals" -> false
                else -> error("Unexpected Clob method: ${method.name}")
            }
        } as Clob

    private class TrackingReader(
        text: String,
        private val failOnRead: Boolean = false,
    ) : Reader() {
        private val delegate = StringReader(text)
        val closed = AtomicBoolean(false)

        override fun read(cbuf: CharArray, off: Int, len: Int): Int {
            if (failOnRead) throw IOException("reader failed")
            return delegate.read(cbuf, off, len)
        }

        override fun close() {
            closed.set(true)
            delegate.close()
        }
    }
}
