package io.bluetape4k.exposed.core.statements.api

import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContentEqual
import io.bluetape4k.junit5.faker.Fakers
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import kotlin.random.Random

class ExposedBlobExtensionsTest {

    companion object: KLogging() {
        private const val REPEAT_SIZE = 10
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `String toExposedBlob and toUtf8String are reversible`() {
        val text = Fakers.defaultFaker.lorem().paragraph()
        val blob = text.toExposedBlob()

        blob.toUtf8String() shouldBeEqualTo text
    }

    @Test
    fun `빈 문자열을 ExposedBlob으로 변환하면 bytes 가 비어 있다`() {
        val blob = "".toExposedBlob()

        blob.toUtf8String().shouldBeEmpty()
        blob.toByteArray().shouldBeEmpty()
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `ByteArray toExposedBlob and toByteArray are reversible`() {
        val bytes = Fakers.faker.random().nextRandomBytes(Random.nextInt(512, 1024))
        val blob = bytes.toExposedBlob()

        blob.toByteArray() shouldBeEqualTo bytes
    }

    @Test
    fun `빈 ByteArray를 ExposedBlob으로 변환하면 bytes 가 비어 있다`() {
        val blob = ByteArray(0).toExposedBlob()

        blob.toByteArray().shouldBeEmpty()
    }

    @Test
    fun `InputStream toExposedBlob preserves content`() {
        val bytes = "stream-data".toByteArray()
        val blob = bytes.inputStream().toExposedBlob()

        blob.toInputStream().readBytes() shouldBeEqualTo bytes
    }

    @Test
    fun `빈 InputStream을 ExposedBlob으로 변환하면 bytes 가 비어 있다`() {
        val blob = ByteArray(0).inputStream().toExposedBlob()

        blob.toByteArray().shouldBeEmpty()
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `toInputStream 은 blob bytes 를 그대로 읽을 수 있다`() {
        val bytes = Fakers.faker.random().nextRandomBytes(Random.nextInt(512, 1024))
        val blob = bytes.toExposedBlob()

        blob.toInputStream().readBytes() shouldContentEqual bytes 
    }
}
