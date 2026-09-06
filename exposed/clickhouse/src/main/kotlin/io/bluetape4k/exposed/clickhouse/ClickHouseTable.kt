package io.bluetape4k.exposed.clickhouse

import io.bluetape4k.exposed.clickhouse.engine.ClickHouseEngine
import io.bluetape4k.exposed.clickhouse.engine.mergeTree
import io.bluetape4k.logging.KLogging
import org.jetbrains.exposed.v1.core.Table

/**
 * ClickHouse DDL과 호환되는 Exposed Table 베이스 클래스.
 *
 * [createStatement] 오버라이드를 통해:
 * 1. CREATE TABLE 구문만 유지 (ALTER/SEQUENCE/COMMENT 등 제거)
 * 2. 기본 키 제약·인라인 REFERENCES·컬럼 nullability 제약 제거
 * 3. ENGINE 절 부착
 *
 * ## 주의
 * - column comment DDL을 지원하지 않음 (filter로 제거됨) — KDoc 또는 README Caveats 참고
 * - PRIMARY KEY는 ORDER BY로 표현 (DSL 빌더에서 설정)
 * - FK 참조는 ClickHouse 미지원. 테이블 수준 외래 키 제약을 선언하지 않습니다.
 * - [options]와 [storageParameters]는 빈 목록만 허용합니다. MySQL/PostgreSQL 옵션과
 *   raw/custom 옵션은 SQL 생성 전에 [IllegalArgumentException]으로 거부하며 `toSQL()`을
 *   호출하지 않습니다. engine/order-by/settings는 [engine] DSL로 설정합니다.
 * - 옵션 변경은 Exposed migration diff에서 추적하지 않습니다. 기존 테이블의 변경은
 *   검토된 수동 migration으로 적용해야 합니다.
 */
@Suppress("AbstractClassCanBeConcreteClass")
abstract class ClickHouseTable(
    name: String = "",
    engine: ClickHouseEngine? = null,
): Table(name) {

    companion object: KLogging()

    open val engine: ClickHouseEngine = engine ?: mergeTree { unsafeRawOrderBy("id") }

    override fun createStatement(): List<String> {
        require(options.isEmpty()) { "ClickHouseTable.options is unsupported; use the engine DSL." }
        require(storageParameters.isEmpty()) {
            "ClickHouseTable.storageParameters is unsupported; use engine settings instead of WITH parameters."
        }
        return super.createStatement()
            // CREATE TABLE 구문만 유지 (ALTER TABLE ADD CONSTRAINT, CREATE SEQUENCE, COMMENT ON 등 제거)
            .filter { sql -> sql.trimStart().startsWith("CREATE TABLE", ignoreCase = true) }
            .map { sql -> sanitizeForClickHouse(sql) + "\n${engine.toClause()}" }
    }
}
private val CH_CONSTRAINT_PK_REGEX = Regex(",?\\s*CONSTRAINT\\s+\\S+\\s+PRIMARY\\s+KEY\\s*\\([^)]*\\)", RegexOption.IGNORE_CASE)
private val CH_INLINE_PK_REGEX = Regex("\\s+PRIMARY\\s+KEY(?!\\s*\\()", RegexOption.IGNORE_CASE)
private val CH_REFERENCES_REGEX = Regex(
    "\\s+REFERENCES\\s+\\S+\\s*\\([^)]*\\)" +
        "(\\s+ON\\s+(DELETE|UPDATE)\\s+(CASCADE|RESTRICT|NO ACTION|SET NULL|SET DEFAULT))*",
    RegexOption.IGNORE_CASE,
)
private val CH_NOT_NULL_REGEX = Regex("\\s+NOT\\s+NULL\\b", RegexOption.IGNORE_CASE)
private val CH_NULL_REGEX = Regex("\\s+NULL\\b", RegexOption.IGNORE_CASE)
private val CH_DEFAULT_REGEX = Regex("\\bDEFAULT\\b", RegexOption.IGNORE_CASE)
private val CH_IS_REGEX = Regex("\\bIS\\s*$", RegexOption.IGNORE_CASE)

/**
 * Exposed가 생성한 CREATE TABLE에서 PK·인라인 REFERENCES·nullability 제약을 제거합니다.
 *
 * 첫 번째 괄호 깊이에 있는 제약만 처리하며 Nullable 타입과 DEFAULT 식은 보존합니다.
 * 문자열·인용 식별자·주석을 같은 길이의 마스크로 보호하므로 원문을 다시 인코딩하지 않습니다.
 * 범용 SQL 파서가 아니며 임의의 수동 DDL이나 다른 dialect의 인용 문법은 지원하지 않습니다.
 */
internal fun sanitizeForClickHouse(sql: String): String {
    val masked = maskDdlQuotedRegions(sql)
    val depths = IntArray(sql.length)
    var depth = 0
    masked.forEachIndexed { index, char ->
        depths[index] = depth
        if (char == '(' || char == '[') depth++
        if (char == ')' || char == ']') depth--
    }
    val removed = BooleanArray(sql.length)
    listOf(
        CH_CONSTRAINT_PK_REGEX, CH_INLINE_PK_REGEX, CH_REFERENCES_REGEX,
        CH_NOT_NULL_REGEX, CH_NULL_REGEX,
    ).forEach { pattern ->
        pattern.findAll(masked).forEach { match ->
            val keywordStart = match.range.first + match.value.indexOfFirst { it.isLetter() }
            if (depths[keywordStart] == 1 && !isDefaultExpression(masked, depths, keywordStart, pattern)) {
                match.range.forEach { removed[it] = true }
            }
        }
    }
    return buildString(sql.length) {
        sql.forEachIndexed { index, char -> if (!removed[index]) append(char) }
    }
}

/** 컬럼 DEFAULT 식의 NULL과 IS NOT NULL 연산자는 nullability 제약이 아닙니다. */
private fun isDefaultExpression(sql: String, depths: IntArray, index: Int, pattern: Regex): Boolean {
    if (pattern != CH_NULL_REGEX && pattern != CH_NOT_NULL_REGEX) return false
    val columnStart = (index - 1 downTo 0).firstOrNull { depths[it] == 1 && sql[it] == ',' }
        ?.plus(1) ?: 0
    val prefix = sql.substring(columnStart, index)
    return if (pattern == CH_NOT_NULL_REGEX) {
        CH_IS_REGEX.containsMatchIn(prefix)
    } else {
        val next = sql.substring(index + "NULL".length).trimStart().firstOrNull()
        // Exposed는 DEFAULT 식 뒤에 nullability를 붙입니다. DEFAULT NULL 자체는 남깁니다.
        val nullabilitySuffix = next == ',' || next == ')'
        val defaultValueIsNull = prefix.trimEnd().endsWith("DEFAULT", ignoreCase = true)
        CH_DEFAULT_REGEX.containsMatchIn(prefix) && (!nullabilitySuffix || defaultValueIsNull)
    }
}

/** 인용 영역과 주석의 원문 위치를 유지하며 제약 검색에서 제외합니다. */
private fun maskDdlQuotedRegions(sql: String): String {
    val masked = sql.toCharArray()
    var index = 0
    while (index < sql.length) {
        val start = index
        val quote = sql[index]
        when {
            quote == '\'' || quote == '"' || quote == '`' -> {
                index = quotedRegionEnd(sql, index)
            }
            sql.startsWith("--", index) -> index = sql.indexOf('\n', index).takeIf { it >= 0 } ?: sql.length
            sql.startsWith("/*", index) ->
                index = sql.indexOf("*/", index + 2).takeIf { it >= 0 }?.plus(2) ?: sql.length
            else -> {
                index++
                continue
            }
        }
        for (position in start until index) masked[position] = '_'
    }
    return String(masked)
}

/** 백슬래시와 반복된 인용 부호를 건너뛰며 인용 영역의 끝을 찾습니다. */
private fun quotedRegionEnd(sql: String, start: Int): Int {
    val quote = sql[start]
    var index = start + 1
    while (index < sql.length) {
        val char = sql[index++]
        if (char == '\\' && index < sql.length) {
            index++
        } else if (char == quote) {
            if (sql.getOrNull(index) == quote) index++ else break
        }
    }
    return index
}
