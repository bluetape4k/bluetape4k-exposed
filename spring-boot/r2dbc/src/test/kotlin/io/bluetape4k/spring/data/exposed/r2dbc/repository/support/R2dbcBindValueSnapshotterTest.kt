package io.bluetape4k.spring.data.exposed.r2dbc.repository.support

import io.bluetape4k.assertions.assertFailsWith
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

class R2dbcBindValueSnapshotterTest {

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

        assertNotSame(original, copy)
        assertContentEquals(byteArrayOf(1, 2), copy["bytes"] as ByteArray)
        assertEquals(listOf("alpha", listOf("beta")), copy["values"])
        assertContentEquals(byteArrayOf(3, 4), (copy["buffer"] as ByteBuffer).array())
    }

    @Test
    fun `approved immutable scalars remain usable and unsupported mutable values fail`() {
        val id = UUID.randomUUID()
        assertEquals("text", R2dbcBindValueSnapshotter.snapshot("text"))
        assertEquals(BigDecimal("1.20"), R2dbcBindValueSnapshotter.snapshot(BigDecimal("1.20")))
        assertEquals(id, R2dbcBindValueSnapshotter.snapshot(id))
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
