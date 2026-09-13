package io.bluetape4k.spring.data.exposed.r2dbc.repository.support

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldNotBeSameInstanceAs
import io.bluetape4k.logging.KLogging
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.util.*
import kotlin.test.Test

class R2dbcBindValueSnapshotterTest {

    companion object: KLogging()

    @Test
    fun `defensive copies nested arrays collections maps and byte buffer`() {
        val bytes = byteArrayOf(1, 2)
        val buffer = ByteBuffer.wrap(byteArrayOf(3, 4))
        val original = linkedMapOf<String, Any?>(
            "bytes" to bytes,
            "values" to mutableListOf("alpha", mutableListOf("beta")),
            "buffer" to buffer,
        )

        @Suppress("UNCHECKED_CAST")
        val copy = R2dbcBindValueSnapshotter.snapshot(original) as Map<String, Any?>
        bytes[0] = 9
        (original["values"] as MutableList<Any?>)[0] = "changed"
        buffer.put(0, 8)

        original shouldNotBeSameInstanceAs copy
        copy["bytes"].shouldBeInstanceOf<ByteArray>() shouldBeEqualTo byteArrayOf(1, 2)
        copy["values"] shouldBeEqualTo listOf("alpha", listOf("beta"))
        copy["buffer"].shouldBeInstanceOf<ByteBuffer>().array() shouldBeEqualTo byteArrayOf(3, 4)
    }

    @Test
    fun `approved immutable scalars remain usable and unsupported mutable values fail`() {
        val id = UUID.randomUUID()
        R2dbcBindValueSnapshotter.snapshot("text") shouldBeEqualTo "text"
        R2dbcBindValueSnapshotter.snapshot(BigDecimal("1.20")) shouldBeEqualTo BigDecimal("1.20")
        R2dbcBindValueSnapshotter.snapshot(id) shouldBeEqualTo id

        assertFailsWith<org.springframework.dao.InvalidDataAccessApiUsageException> {
            R2dbcBindValueSnapshotter.snapshot(MutableNumber())
        }
    }

    private class MutableNumber: Number() {
        override fun toByte(): Byte = 1
        override fun toDouble(): Double = 1.0
        override fun toFloat(): Float = 1.0f
        override fun toInt(): Int = 1
        override fun toLong(): Long = 1L
        override fun toShort(): Short = 1
    }
}
