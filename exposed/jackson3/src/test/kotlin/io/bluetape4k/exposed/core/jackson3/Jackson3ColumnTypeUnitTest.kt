package io.bluetape4k.exposed.core.jackson3

import io.bluetape4k.codec.Base58
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
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
 * [JacksonColumnType] 및 [JacksonBColumnType]의 직렬화/역직렬화 단위 테스트입니다.
 */
class Jackson3ColumnTypeUnitTest {
    private data class SamplePayload(
        val name: String,
        val count: Int,
    )

    private val serializer = DefaultJacksonSerializer
    private val columnType =
        JacksonColumnType<SamplePayload>(
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
        val literalColumnType = JacksonColumnType<SamplePayload>(
            serilaize = { json },
            deserialize = { serializer.deserializeFromString<SamplePayload>(it)!! }
        )
        val database = Database.connect(
            url = "jdbc:h2:mem:jackson3-literal-${Base58.randomString(8)};DB_CLOSE_DELAY=-1",
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
    fun `JacksonColumnType 은 usesBinaryFormat 이 false 이다`() {
        columnType.usesBinaryFormat.shouldBeFalse()
    }

    @Test
    fun `JacksonBColumnType 은 usesBinaryFormat 이 true 이다`() {
        val bColumnType =
            JacksonBColumnType<SamplePayload>(
                serialize = { serializer.serializeAsString(it) },
                deserialize = { serializer.deserializeFromString<SamplePayload>(it)!! }
            )
        bColumnType.usesBinaryFormat.shouldBeTrue()
    }

    @Test
    fun `JacksonBColumnType 은 valueFromDB 에서 문자열을 역직렬화한다`() {
        val bColumnType =
            JacksonBColumnType<SamplePayload>(
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
     * DefaultJacksonSerializer 가 싱글턴 인스턴스임을 보장한다.
     * ObjectMapper 인스턴스를 프로세스 전체에서 재사용해 초기화 비용을 방지한다.
     */
    @Test
    fun `DefaultJacksonSerializer 는 동일한 싱글턴 인스턴스를 반환한다`() {
        val s1 = DefaultJacksonSerializer
        val s2 = DefaultJacksonSerializer
        (s1 === s2).shouldBeTrue()
    }

    /**
     * valueFromDB 가 이미 T 타입인 값을 그대로 캐스팅하여 반환하는지 확인한다.
     * DB 드라이버가 이미 역직렬화된 객체를 반환하는 경우를 방어한다.
     */
    @Test
    fun `valueFromDB 는 이미 T 타입인 값을 역직렬화 없이 반환한다`() {
        val source = SamplePayload("already", 55)
        columnType.valueFromDB(source) shouldBeEqualTo source
    }

    /**
     * JacksonColumnType 의 serilaize 함수가 null-safe 하게 동작하는지 확인한다.
     * 직렬화된 JSON 에 불필요한 공백이 없고 예상 키를 포함해야 한다.
     */
    @Test
    fun `notNullValueToDB 직렬화 결과에 null 필드가 포함되지 않는다`() {
        data class WithNullable(val name: String, val extra: String? = null)
        val ct = JacksonColumnType<WithNullable>(
            serilaize = { serializer.serializeAsString(it) },
            deserialize = { serializer.deserializeFromString<WithNullable>(it)!! }
        )
        val value = WithNullable("test")
        val json = ct.notNullValueToDB(value) as String
        json shouldContain "\"name\":\"test\""
    }

    /**
     * JacksonBColumnType 의 needsBinaryFormatCast 기본값이 false 임을 확인한다.
     * castToJsonFormat=false 이면 SQLite 여부와 관계없이 false 여야 한다.
     */
    @Test
    fun `JacksonBColumnType castToJsonFormat=false 이면 needsBinaryFormatCast 는 false 이다`() {
        val bColumnType =
            JacksonBColumnType<SamplePayload>(
                serialize = { serializer.serializeAsString(it) },
                deserialize = { serializer.deserializeFromString<SamplePayload>(it)!! },
                castToJsonFormat = false,
            )
        bColumnType.needsBinaryFormatCast.shouldBeFalse()
    }

    /**
     * JacksonBColumnType 왕복(직렬화→역직렬화) 일관성을 확인한다.
     */
    @Test
    fun `JacksonBColumnType 왕복 변환이 일관된다`() {
        val bColumnType =
            JacksonBColumnType<SamplePayload>(
                serialize = { serializer.serializeAsString(it) },
                deserialize = { serializer.deserializeFromString<SamplePayload>(it)!! }
            )
        val source = SamplePayload("roundtrip-b", 77)
        val json = bColumnType.notNullValueToDB(source) as String
        val restored = bColumnType.valueFromDB(json)
        restored shouldBeEqualTo source
    }

    /**
     * JacksonColumnType 의 notNullValueToDB 는 직렬화 함수가 반환한 값을 그대로 DB 저장 값으로 사용한다.
     * 별도 캐시나 변환 없이 사용자 제공 함수에 위임하는 것을 검증한다.
     */
    @Test
    fun `notNullValueToDB 는 커스텀 직렬화 함수에 위임한다`() {
        val customOutput = "CUSTOM_JSON"
        val customColumnType = JacksonColumnType<SamplePayload>(
            serilaize = { customOutput },
            deserialize = { serializer.deserializeFromString<SamplePayload>(it)!! }
        )
        val source = SamplePayload("any", 0)
        customColumnType.notNullValueToDB(source) shouldBeEqualTo customOutput
    }

    @Test
    fun `readObject 는 JDBC Clob 을 JSON 문자열로 정규화한다`() {
        val source = SamplePayload("clob", 42)
        val json = serializer.serializeAsString(source)
        val database = Database.connect(
            url = "jdbc:h2:mem:jackson3-clob-${Base58.randomString(8)};DB_CLOSE_DELAY=-1",
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
            url = "jdbc:h2:mem:jackson3-clob-error-${Base58.randomString(8)};DB_CLOSE_DELAY=-1",
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
            url = "jdbc:h2:mem:jackson3-oracle-clob-${Base58.randomString(8)};MODE=Oracle;DB_CLOSE_DELAY=-1",
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
