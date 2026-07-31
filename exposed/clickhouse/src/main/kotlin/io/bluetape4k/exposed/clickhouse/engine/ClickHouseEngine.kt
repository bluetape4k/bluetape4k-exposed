package io.bluetape4k.exposed.clickhouse.engine

import io.bluetape4k.exposed.clickhouse.functions.ArgMax
import io.bluetape4k.exposed.clickhouse.functions.ArgMin
import io.bluetape4k.exposed.clickhouse.functions.DateDiff
import io.bluetape4k.exposed.clickhouse.functions.Quantile
import io.bluetape4k.exposed.clickhouse.functions.ToStartOfInterval
import io.bluetape4k.exposed.clickhouse.functions.ToYYYYMM
import io.bluetape4k.exposed.clickhouse.functions.ToYYYYMMDD
import io.bluetape4k.exposed.clickhouse.functions.Uniq
import io.bluetape4k.exposed.clickhouse.functions.UniqExact
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.QueryBuilder
import java.io.Serializable

private val SETTING_NAME_REGEX = Regex("[A-Za-z_][A-Za-z0-9_]*")
private val SAFE_SETTING_NAMES = setOf(
    "allow_nullable_key",
    "index_granularity",
    "index_granularity_bytes",
    "min_bytes_for_wide_part",
    "min_rows_for_wide_part",
    "storage_policy",
)
private val UNSAFE_RAW_FORBIDDEN_TOKENS = listOf(";", "--", "/*", "*/", "\n", "\r", "'", "\"")
private val UNSAFE_RAW_CLAUSE_BOUNDARY = Regex(
    pattern = "\\b(engine|order\\s+by|partition\\s+by|primary\\s+key|sample\\s+by|settings|create|alter|drop|truncate|insert|update|delete)\\b",
    option = RegexOption.IGNORE_CASE,
)

/**
 * 렌더링된 ClickHouse 엔진 표현식입니다.
 *
 * [Expression] 기반 DSL 메서드를 우선 사용하십시오.
 * [unsafeRaw]는 Exposed가 아직 모델링할 수 없는 ClickHouse 문법 조각에만 사용합니다.
 * 이 함수는 조각이 생성 DDL에 도달하기 전에 문장 및 절 경계 토큰을 거부합니다.
 */
@JvmInline
value class ClickHouseEngineExpression private constructor(val sql: String): Serializable {

    companion object {
        private const val serialVersionUID: Long = 1L

        fun unsafeRaw(sql: String): ClickHouseEngineExpression =
            ClickHouseEngineExpression(validateUnsafeRawFragment(sql, "ClickHouse engine expression"))

        internal fun from(expression: Expression<*>): ClickHouseEngineExpression =
            ClickHouseEngineExpression(expression.toClickHouseSql())
    }
}

/**
 * 검증된 ClickHouse 설정 이름입니다.
 */
@JvmInline
value class ClickHouseSettingName private constructor(val sql: String): Serializable {

    companion object {
        private const val serialVersionUID: Long = 1L

        val ALLOW_NULLABLE_KEY: ClickHouseSettingName = ClickHouseSettingName("allow_nullable_key")
        val INDEX_GRANULARITY: ClickHouseSettingName = ClickHouseSettingName("index_granularity")
        val INDEX_GRANULARITY_BYTES: ClickHouseSettingName = ClickHouseSettingName("index_granularity_bytes")
        val MIN_BYTES_FOR_WIDE_PART: ClickHouseSettingName = ClickHouseSettingName("min_bytes_for_wide_part")
        val MIN_ROWS_FOR_WIDE_PART: ClickHouseSettingName = ClickHouseSettingName("min_rows_for_wide_part")
        val STORAGE_POLICY: ClickHouseSettingName = ClickHouseSettingName("storage_policy")

        fun of(name: String): ClickHouseSettingName {
            val trimmed = validateSettingIdentifier(name)
            require(trimmed in SAFE_SETTING_NAMES) {
                "ClickHouse setting name is not allowlisted: $name. Use unsafeRawSetting for explicit raw settings."
            }
            return ClickHouseSettingName(trimmed)
        }

        fun unsafeRaw(name: String): ClickHouseSettingName =
            ClickHouseSettingName(validateSettingIdentifier(name))

        private fun validateSettingIdentifier(name: String): String {
            val trimmed = name.trim()
            require(SETTING_NAME_REGEX.matches(trimmed)) {
                "ClickHouse setting name must be an identifier: $name"
            }
            return trimmed
        }
    }
}

/**
 * 타입이 지정된 ClickHouse 설정 값입니다.
 */
sealed interface ClickHouseSettingValue: Serializable {
    fun toSql(): String

    companion object {
        fun of(value: Int): ClickHouseSettingValue = NumericSettingValue(value.toString())
        fun of(value: Long): ClickHouseSettingValue = NumericSettingValue(value.toString())
        fun of(value: Double): ClickHouseSettingValue = NumericSettingValue(value.toString())
        fun of(value: Boolean): ClickHouseSettingValue = NumericSettingValue(if (value) "1" else "0")
        fun of(value: String): ClickHouseSettingValue = StringSettingValue(value)

        fun unsafeRaw(sql: String): ClickHouseSettingValue =
            RawSettingValue(validateUnsafeRawFragment(sql, "ClickHouse setting value"))
    }
}

private data class NumericSettingValue(private val value: String): ClickHouseSettingValue {
    override fun toSql(): String = value
}

private data class StringSettingValue(private val value: String): ClickHouseSettingValue {
    override fun toSql(): String = "'${value.replace("'", "''")}'"
}

private data class RawSettingValue(private val sql: String): ClickHouseSettingValue {
    override fun toSql(): String = sql
}

/**
 * 검증된 이름과 타입 지정 값을 가진 ClickHouse 엔진 설정입니다.
 */
@ConsistentCopyVisibility
data class ClickHouseSetting private constructor(
    val name: ClickHouseSettingName,
    val value: ClickHouseSettingValue,
): Serializable {

    companion object {
        private const val serialVersionUID: Long = 1L

        fun of(name: ClickHouseSettingName, value: Int): ClickHouseSetting =
            ClickHouseSetting(name, ClickHouseSettingValue.of(value))

        fun of(name: ClickHouseSettingName, value: Long): ClickHouseSetting =
            ClickHouseSetting(name, ClickHouseSettingValue.of(value))

        fun of(name: ClickHouseSettingName, value: Double): ClickHouseSetting =
            ClickHouseSetting(name, ClickHouseSettingValue.of(value))

        fun of(name: ClickHouseSettingName, value: Boolean): ClickHouseSetting =
            ClickHouseSetting(name, ClickHouseSettingValue.of(value))

        fun of(name: ClickHouseSettingName, value: String): ClickHouseSetting =
            ClickHouseSetting(name, ClickHouseSettingValue.of(value))

        fun of(name: String, value: Int): ClickHouseSetting =
            ClickHouseSetting(ClickHouseSettingName.of(name), ClickHouseSettingValue.of(value))

        fun of(name: String, value: Long): ClickHouseSetting =
            ClickHouseSetting(ClickHouseSettingName.of(name), ClickHouseSettingValue.of(value))

        fun of(name: String, value: Double): ClickHouseSetting =
            ClickHouseSetting(ClickHouseSettingName.of(name), ClickHouseSettingValue.of(value))

        fun of(name: String, value: Boolean): ClickHouseSetting =
            ClickHouseSetting(ClickHouseSettingName.of(name), ClickHouseSettingValue.of(value))

        fun of(name: String, value: String): ClickHouseSetting =
            ClickHouseSetting(ClickHouseSettingName.of(name), ClickHouseSettingValue.of(value))

        fun unsafeRaw(name: String, value: String): ClickHouseSetting =
            ClickHouseSetting(ClickHouseSettingName.unsafeRaw(name), ClickHouseSettingValue.unsafeRaw(value))
    }

    fun toSql(): String = "${name.sql} = ${value.toSql()}"
}

/**
 * ClickHouse 테이블 엔진입니다.
 *
 * [toClause]는 `CREATE TABLE` DDL에 추가할 `ENGINE` 절을 반환합니다.
 */
sealed interface ClickHouseEngine: Serializable {
    fun toClause(): String
}

/** 테스트와 임시 테이블에 사용할 Memory 엔진입니다. */
data object Memory: ClickHouseEngine {
    private const val serialVersionUID: Long = 1L
    override fun toClause(): String = "ENGINE = Memory()"
}

/** 작은 컬럼 파일 테이블에 사용할 TinyLog 엔진입니다. */
data object TinyLog: ClickHouseEngine {
    private const val serialVersionUID: Long = 1L
    override fun toClause(): String = "ENGINE = TinyLog()"
}

/** 작은 추가 중심 테이블에 사용할 Log 엔진입니다. */
data object Log: ClickHouseEngine {
    private const val serialVersionUID: Long = 1L
    override fun toClause(): String = "ENGINE = Log()"
}

/**
 * MergeTree 계열의 기본 엔진입니다.
 */
data class MergeTree(
    val orderBy: List<ClickHouseEngineExpression>,
    val partitionBy: ClickHouseEngineExpression? = null,
    val primaryKeyColumns: List<ClickHouseEngineExpression> = emptyList(),
    val sampleBy: ClickHouseEngineExpression? = null,
    val settings: List<ClickHouseSetting> = emptyList(),
): ClickHouseEngine {
    companion object {
        private const val serialVersionUID: Long = 1L
    }

    init {
        require(orderBy.isNotEmpty()) { "MergeTree requires at least one ORDER BY expression" }
    }

    override fun toClause(): String = buildString {
        append("ENGINE = MergeTree()")
        append("\nORDER BY (${orderBy.joinSql()})")
        partitionBy?.let { append("\nPARTITION BY ${it.sql}") }
        if (primaryKeyColumns.isNotEmpty()) {
            append("\nPRIMARY KEY (${primaryKeyColumns.joinSql()})")
        }
        sampleBy?.let { append("\nSAMPLE BY ${it.sql}") }
        appendSettings(settings)
    }
}

/**
 * ReplacingMergeTree 엔진입니다.
 */
data class ReplacingMergeTree(
    val orderBy: List<ClickHouseEngineExpression>,
    val versionColumn: ClickHouseEngineExpression? = null,
    val partitionBy: ClickHouseEngineExpression? = null,
    val settings: List<ClickHouseSetting> = emptyList(),
): ClickHouseEngine {
    companion object {
        private const val serialVersionUID: Long = 1L
    }

    init {
        require(orderBy.isNotEmpty()) { "ReplacingMergeTree requires at least one ORDER BY expression" }
    }

    override fun toClause(): String = buildString {
        val engineArgs = versionColumn?.let { "(${it.sql})" } ?: "()"
        append("ENGINE = ReplacingMergeTree$engineArgs")
        append("\nORDER BY (${orderBy.joinSql()})")
        partitionBy?.let { append("\nPARTITION BY ${it.sql}") }
        appendSettings(settings)
    }
}

/**
 * SummingMergeTree 엔진입니다.
 */
data class SummingMergeTree(
    val orderBy: List<ClickHouseEngineExpression>,
    val sumColumns: List<ClickHouseEngineExpression> = emptyList(),
    val partitionBy: ClickHouseEngineExpression? = null,
    val settings: List<ClickHouseSetting> = emptyList(),
): ClickHouseEngine {
    companion object {
        private const val serialVersionUID: Long = 1L
    }

    init {
        require(orderBy.isNotEmpty()) { "SummingMergeTree requires at least one ORDER BY expression" }
    }

    override fun toClause(): String = buildString {
        val engineArgs = if (sumColumns.isNotEmpty()) "(${sumColumns.joinSql()})" else "()"
        append("ENGINE = SummingMergeTree$engineArgs")
        append("\nORDER BY (${orderBy.joinSql()})")
        partitionBy?.let { append("\nPARTITION BY ${it.sql}") }
        appendSettings(settings)
    }
}

/**
 * AggregatingMergeTree 엔진입니다.
 */
data class AggregatingMergeTree(
    val orderBy: List<ClickHouseEngineExpression>,
    val partitionBy: ClickHouseEngineExpression? = null,
    val settings: List<ClickHouseSetting> = emptyList(),
): ClickHouseEngine {
    companion object {
        private const val serialVersionUID: Long = 1L
    }

    init {
        require(orderBy.isNotEmpty()) { "AggregatingMergeTree requires at least one ORDER BY expression" }
    }

    override fun toClause(): String = buildString {
        append("ENGINE = AggregatingMergeTree()")
        append("\nORDER BY (${orderBy.joinSql()})")
        partitionBy?.let { append("\nPARTITION BY ${it.sql}") }
        appendSettings(settings)
    }
}

private fun Expression<*>.toClickHouseSql(): String = when (this) {
    is Column<*>            -> name
    is ToYYYYMM<*>          -> "toYYYYMM(${expr.toClickHouseSql()})"
    is ToYYYYMMDD<*>        -> "toYYYYMMDD(${expr.toClickHouseSql()})"
    is DateDiff<*>          -> "dateDiff(${unit.sqlValue}, ${from.toClickHouseSql()}, ${to.toClickHouseSql()})"
    is ToStartOfInterval<*> -> "toStartOfInterval(${expr.toClickHouseSql()}, INTERVAL $intervalSeconds SECOND)"
    is ArgMax<*, *>         -> "argMax(${value.toClickHouseSql()}, ${key.toClickHouseSql()})"
    is ArgMin<*, *>         -> "argMin(${value.toClickHouseSql()}, ${key.toClickHouseSql()})"
    is Quantile<*>          -> "quantile($level)(${expr.toClickHouseSql()})"
    is Uniq                 -> exprs.joinToString(prefix = "uniq(", postfix = ")") { it.toClickHouseSql() }
    is UniqExact            -> exprs.joinToString(prefix = "uniqExact(", postfix = ")") { it.toClickHouseSql() }
    else                    -> {
        val queryBuilder = QueryBuilder(prepared = true)
        toQueryBuilder(queryBuilder)
        queryBuilder.toString()
    }
}

private fun List<ClickHouseEngineExpression>.joinSql(): String =
    joinToString(", ") { it.sql }

private fun StringBuilder.appendSettings(settings: List<ClickHouseSetting>) {
    if (settings.isNotEmpty()) {
        append("\nSETTINGS ${settings.joinToString(", ") { it.toSql() }}")
    }
}

private fun validateUnsafeRawFragment(sql: String, label: String): String {
    val trimmed = sql.trim()
    require(trimmed.isNotEmpty()) { "$label must not be blank" }
    require(UNSAFE_RAW_FORBIDDEN_TOKENS.none { token -> trimmed.contains(token) }) {
        "$label contains a statement delimiter, comment, newline, or quote"
    }
    require(!UNSAFE_RAW_CLAUSE_BOUNDARY.containsMatchIn(trimmed)) {
        "$label contains a DDL statement or clause boundary"
    }
    return trimmed
}
