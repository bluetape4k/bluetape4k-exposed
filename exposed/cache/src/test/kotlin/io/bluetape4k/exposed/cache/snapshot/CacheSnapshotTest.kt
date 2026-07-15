package io.bluetape4k.exposed.cache.snapshot

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.Entity
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.lang.reflect.Modifier

class CacheSnapshotTest {

    @Test
    fun `snapshot is an immutable serializable value with an optional revision`() {
        val snapshot = CacheSnapshot(value = Payload("detached"))

        snapshot.revision.shouldBeNull()
        Modifier.isFinal(CacheSnapshot::class.java.getDeclaredField("value").modifiers) shouldBeEqualTo true
        Modifier.isFinal(CacheSnapshot::class.java.getDeclaredField("revision").modifiers) shouldBeEqualTo true
        serializeRoundTrip(snapshot) shouldBeEqualTo snapshot
        snapshot.copy(revision = "r-1") shouldBeEqualTo CacheSnapshot(Payload("detached"), "r-1")
    }

    @Test
    fun `mapper produces a detached snapshot envelope`() {
        val mapper = CacheSnapshotMapper<Source, Payload> { source ->
            CacheSnapshot(Payload(source.name), source.revision)
        }

        mapper.toSnapshot(Source("detached", "r-2")) shouldBeEqualTo CacheSnapshot(Payload("detached"), "r-2")
    }

    @Test
    fun `custom value validator receives the snapshot value`() {
        var validated: Payload? = null
        val validator = CacheSnapshotValueValidator<Payload> { validated = it }
        val payload = Payload("detached")

        validator.validate(payload)

        validated shouldBeEqualTo payload
    }

    @Test
    fun `direct Exposed DAO Entity values are rejected`() {
        val entity = SerializableEntity(EntityID(1, Entities))

        assertFailsWith<IllegalArgumentException> {
            rejectDirectEntitySnapshotValues<SerializableEntity>().validate(entity)
        }
    }

    @Test
    fun `ordinary DTO values are accepted by the direct Entity validator`() {
        rejectDirectEntitySnapshotValues<Payload>().validate(Payload("detached"))
    }

    @Test
    fun `maximum payload validator accepts zero and exact-limit estimates`() {
        val validator = maximumEstimatedPayloadBytes(
            sizer = SnapshotValueSizer<Payload> { it.text.length.toLong() },
            limit = 8L,
        )

        validator.validate(Payload(""))
        validator.validate(Payload("12345678"))
    }

    @Test
    fun `maximum payload validator rejects estimates above the limit`() {
        val validator = maximumEstimatedPayloadBytes(
            sizer = SnapshotValueSizer<Payload> { it.text.length.toLong() },
            limit = 8L,
        )

        assertFailsWith<IllegalArgumentException> {
            validator.validate(Payload("123456789"))
        }
    }

    @Test
    fun `maximum payload validator requires a positive limit`() {
        val sizer = SnapshotValueSizer<Payload> { it.text.length.toLong() }

        assertFailsWith<IllegalArgumentException> {
            maximumEstimatedPayloadBytes(sizer, 0L)
        }
        assertFailsWith<IllegalArgumentException> {
            maximumEstimatedPayloadBytes(sizer, -1L)
        }
    }

    @Test
    fun `maximum payload validator rejects a negative sizer estimate`() {
        val validator = maximumEstimatedPayloadBytes(
            sizer = SnapshotValueSizer<Payload> { -1L },
            limit = 8L,
        )

        assertFailsWith<IllegalArgumentException> {
            validator.validate(Payload("detached"))
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Serializable> serializeRoundTrip(value: T): T {
        val bytes = ByteArrayOutputStream().use { output ->
            ObjectOutputStream(output).use { it.writeObject(value) }
            output.toByteArray()
        }
        return ByteArrayInputStream(bytes).use { input ->
            ObjectInputStream(input).use { it.readObject() as T }
        }
    }

    private data class Source(
        val name: String,
        val revision: String?,
    ): Serializable {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    private data class Payload(
        val text: String,
    ): Serializable {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    private object Entities: IntIdTable("snapshot_entities")

    private class SerializableEntity(id: EntityID<Int>): Entity<Int>(id), Serializable {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }
}
