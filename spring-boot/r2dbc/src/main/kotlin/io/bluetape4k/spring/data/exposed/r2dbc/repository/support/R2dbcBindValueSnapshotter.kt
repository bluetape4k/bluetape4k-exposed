package io.bluetape4k.spring.data.exposed.r2dbc.repository.support

import org.springframework.dao.InvalidDataAccessApiUsageException
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.ByteBuffer
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.MonthDay
import java.time.OffsetDateTime
import java.time.OffsetTime
import java.time.Period
import java.time.Year
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.UUID

/** QBE bind value를 SQL 실행 전에 detached defensive snapshot으로 복사합니다. */
internal object R2dbcBindValueSnapshotter {

    fun snapshot(value: Any?): Any? = when {
        value == null -> null
        value.isImmutableScalar() -> value
        value.isPrimitiveArray() -> snapshotPrimitiveArray(value)
        value is Array<*> -> value.map(::snapshot).toTypedArray()
        value is ByteBuffer -> snapshotBuffer(value)
        value is List<*> -> value.map(::snapshot)
        value is Set<*> -> value.mapTo(LinkedHashSet(), ::snapshot)
        value is Collection<*> -> value.map(::snapshot)
        value is Map<*, *> -> value.entries.associateTo(LinkedHashMap()) { entry ->
            snapshot(entry.key) to snapshot(entry.value)
        }
        else -> unsupported(value)
    }

    private fun snapshotPrimitiveArray(value: Any): Any = when (value) {
        is ByteArray -> value.copyOf()
        is ShortArray -> value.copyOf()
        is IntArray -> value.copyOf()
        is LongArray -> value.copyOf()
        is FloatArray -> value.copyOf()
        is DoubleArray -> value.copyOf()
        is CharArray -> value.copyOf()
        is BooleanArray -> value.copyOf()
        else -> error("Unsupported primitive array")
    }

    private fun Any.isImmutableScalar(): Boolean =
        this::class.java in IMMUTABLE_SCALAR_TYPES || this is Enum<*>

    private fun Any.isPrimitiveArray(): Boolean = this::class.java in PRIMITIVE_ARRAY_TYPES

    private fun snapshotBuffer(value: ByteBuffer): ByteBuffer {
        val source = value.duplicate()
        val copy = ByteBuffer.allocate(source.remaining())
        copy.put(source)
        copy.flip()
        return copy
    }

    private fun unsupported(value: Any): Nothing {
        val type = R2dbcDiagnosticSanitizer.propertyToken(value::class.qualifiedName ?: "unknown")
        throw InvalidDataAccessApiUsageException("Unsupported QBE bind value type: $type")
    }

    private val IMMUTABLE_SCALAR_TYPES = setOf(
        String::class.java,
        Boolean::class.javaObjectType,
        Byte::class.javaObjectType,
        Short::class.javaObjectType,
        Int::class.javaObjectType,
        Long::class.javaObjectType,
        Float::class.javaObjectType,
        Double::class.javaObjectType,
        Char::class.javaObjectType,
        BigInteger::class.java,
        BigDecimal::class.java,
        UUID::class.java,
        Instant::class.java,
        LocalDate::class.java,
        LocalDateTime::class.java,
        LocalTime::class.java,
        MonthDay::class.java,
        OffsetDateTime::class.java,
        OffsetTime::class.java,
        Period::class.java,
        Year::class.java,
        YearMonth::class.java,
        ZonedDateTime::class.java,
        ZoneId::class.java,
        ZoneOffset::class.java,
        Duration::class.java,
    )

    private val PRIMITIVE_ARRAY_TYPES = setOf(
        ByteArray::class.java,
        ShortArray::class.java,
        IntArray::class.java,
        LongArray::class.java,
        FloatArray::class.java,
        DoubleArray::class.java,
        CharArray::class.java,
        BooleanArray::class.java,
    )
}
