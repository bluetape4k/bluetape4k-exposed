package io.bluetape4k.exposed.clickhouse.types

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.statements.api.PreparedStatementApi
import org.jetbrains.exposed.v1.core.statements.api.RowApi
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * ClickHouse `DateTime64(precision, 'zone')` 컬럼 타입.
 *
 * [Instant]를 정밀도(precision)에 따라 매핑합니다. 기본 [precision]은 3 (밀리초).
 *
 * @property precision 소수점 초 자릿수 (0~9, 기본값 3=밀리초)
 * @property zone 문자열/LocalDateTime 변환에 사용할 명시적 IANA zone (기본값 UTC)
 *
 * 입력 [Instant]의 소수부가 선언된 정밀도보다 길면 ClickHouse 저장 규칙에
 * 맞춰 절삭합니다.
 */
class DateTime64ColumnType(val precision: Int = 3): ColumnType<Instant>() {

    private var zoneValue: ZoneId = ZoneOffset.UTC

    /** 문자열/LocalDateTime 변환에 사용할 명시적 IANA zone입니다. */
    val zone: ZoneId
        get() = zoneValue

    constructor(precision: Int, zone: ZoneId): this(precision) {
        zoneValue = zone
    }

    init {
        require(precision in 0..9) { "DateTime64 precision must be in 0..9: $precision" }
    }

    override fun sqlType(): String {
        val zoneId = zone.id.takeUnless { it == "Z" } ?: "UTC"
        return "DateTime64($precision, '${zoneId.replace("'", "''")}')"
    }

    override fun valueFromDB(value: Any): Instant = normalizePrecision(when (value) {
        is Instant -> value
        is java.sql.Timestamp -> value.toInstant()
        is LocalDateTime -> value.toInstant(zone.rules.getOffset(value))
        is OffsetDateTime -> value.toInstant()
        is Long -> runCatching { Instant.ofEpochMilli(value) }
            .getOrElse { error("DateTime64 epoch millis overflow: $value") }
        is String -> parseDateTime(value)
        else -> error("Unexpected DateTime64 value: $value (${value::class.simpleName})")
    })

    override fun notNullValueToDB(value: Instant): Any = java.sql.Timestamp.from(normalizePrecision(value))

    override fun readObject(rs: RowApi, index: Int): Any? =
        super.readObject(rs, index)?.let(::valueFromDB)

    override fun setParameter(stmt: PreparedStatementApi, index: Int, value: Any?) {
        clickHouseSetParameter(stmt, index, value, this)
    }

    private fun parseDateTime(value: String): Instant = runCatching { Instant.parse(value) }
        .recoverCatching { OffsetDateTime.parse(value).toInstant() }
        .recoverCatching {
            val localDateTime = LocalDateTime.parse(value)
            localDateTime.toInstant(zone.rules.getOffset(localDateTime))
        }
        .getOrElse { error("Malformed DateTime64 value: $value") }

    private fun normalizePrecision(value: Instant): Instant {
        val divisor = precisionDivisor(precision)
        val normalizedNanos = value.nano / divisor * divisor
        return if (normalizedNanos == value.nano) {
            value
        } else {
            Instant.ofEpochSecond(value.epochSecond, normalizedNanos.toLong())
        }
    }

    private fun precisionDivisor(value: Int): Int = PRECISION_DIVISORS[value]

    private companion object {
        val PRECISION_DIVISORS = intArrayOf(
            1_000_000_000,
            100_000_000,
            10_000_000,
            1_000_000,
            100_000,
            10_000,
            1_000,
            100,
            10,
            1,
        )
    }
}

/**
 * ClickHouse `DateTime64(precision, 'UTC')` 컬럼을 등록합니다. [Instant] 와 매핑됩니다.
 *
 * - precision=0: 초 단위
 * - precision=3: 밀리초 단위 (기본값)
 * - precision=6: 마이크로초 단위
 * - precision=9: 나노초 단위
 *
 * ```kotlin
 * object EventTable : Table("events") {
 *     val createdAt = dateTime64("created_at")              // 밀리초 (기본)
 *     val highPrecTs = dateTime64("high_prec_ts", 6)        // 마이크로초
 * }
 * ```
 *
 * @param name 컬럼명
 * @param precision 소수점 초 자릿수 (0~9, 기본값 3=밀리초)
 */
fun Table.dateTime64(name: String, precision: Int = 3): Column<Instant> =
    registerColumn(name, DateTime64ColumnType(precision))

/** ClickHouse `DateTime64` 컬럼에 사용할 명시적 timezone을 지정합니다. */
fun Table.dateTime64(name: String, precision: Int, zone: ZoneId): Column<Instant> =
    registerColumn(name, DateTime64ColumnType(precision, zone))
