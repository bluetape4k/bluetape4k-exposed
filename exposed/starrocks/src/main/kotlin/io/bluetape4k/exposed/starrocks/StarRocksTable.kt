package io.bluetape4k.exposed.starrocks

import org.jetbrains.exposed.v1.core.Table

/**
 * 제한된 StarRocks OLAP DDL smoke test를 위한 Exposed table base입니다.
 *
 * StarRocks OLAP table이 일반 MySQL DDL로 허용하지 않는 generic primary-key 문법을 제거하고
 * 보수적인 StarRocks table option을 추가합니다. 더 넓은 StarRocks DDL 동작이 test로 입증될 때까지
 * 단순 local fixture에만 사용합니다.
 *
 * [options]와 [storageParameters]는 빈 목록만 허용합니다. MySQL engine/charset이나
 * PostgreSQL WITH 옵션을 OLAP 설정으로 해석하지 않으며, raw/custom 옵션도 SQL 생성 전에
 * [IllegalArgumentException]으로 거부합니다. 옵션 값의 `toSQL()`은 호출하지 않습니다.
 * 기본 `ENGINE=OLAP`과 `replication_num=1`은 유지합니다. 옵션 변경은 Exposed migration
 * diff로 추적되지 않으므로 별도의 검토된 수동 migration이 필요합니다.
 */
open class StarRocksTable(name: String = ""): Table(name) {
    override fun createStatement(): List<String> {
        require(options.isEmpty()) { "StarRocksTable.options is unsupported; use the fixed OLAP table contract." }
        require(storageParameters.isEmpty()) {
            "StarRocksTable.storageParameters is unsupported; WITH parameters are not OLAP PROPERTIES."
        }
        return super.createStatement().map { sql -> sql.sanitizeForStarRocks() }
    }
}

private val STARROCKS_CONSTRAINT_PK_REGEX =
    Regex(",\\s*CONSTRAINT\\s+\\S+\\s+PRIMARY\\s+KEY\\s*\\([^)]*\\)", RegexOption.IGNORE_CASE)
private val STARROCKS_INLINE_PK_REGEX = Regex("\\s+PRIMARY\\s+KEY", RegexOption.IGNORE_CASE)
private val STARROCKS_NULL_REGEX = Regex("\\s+NULL\\b", RegexOption.IGNORE_CASE)
private val STARROCKS_DEFAULT_REGEX = Regex("\\bDEFAULT\\b", RegexOption.IGNORE_CASE)
private val STARROCKS_ENGINE_REGEX = Regex("\\bENGINE\\s*=", RegexOption.IGNORE_CASE)

/**
 * Exposed가 생성한 CREATE TABLE에서 실제 제약만 StarRocks 문법에 맞게 보정합니다.
 *
 * 문자열·인용 식별자·주석은 같은 길이의 마스크로 검색에서 제외하고, 첫 번째 괄호 깊이에
 * 있는 PK와 nullable clause만 처리합니다. 범용 SQL parser가 아니므로 수동 DDL의 모든 dialect를
 * 해석하지 않으며, 실제 ENGINE 절이 없을 때만 기존 기본 OLAP 옵션을 추가합니다.
 */
internal fun String.sanitizeForStarRocks(): String {
    val masked = maskDdlQuotedRegions(this)
    val depths = IntArray(length)
    var depth = 0
    masked.forEachIndexed { index, char ->
        depths[index] = depth
        when (char) {
            '(' -> depth++
            ')' -> depth--
        }
    }

    val removed = BooleanArray(length)
    listOf(STARROCKS_CONSTRAINT_PK_REGEX, STARROCKS_INLINE_PK_REGEX, STARROCKS_NULL_REGEX).forEach { pattern ->
        pattern.findAll(masked).forEach { match ->
            val keywordStart = match.range.first + match.value.indexOfFirst { it.isLetter() }
            if (depths[keywordStart] == 1 &&
                !isStarRocksDefaultExpression(masked, depths, keywordStart, pattern) &&
                !isStarRocksNotNullClause(masked, keywordStart, pattern)
            ) {
                match.range.forEach { removed[it] = true }
            }
        }
    }

    val sanitized = buildString(length) {
        this@sanitizeForStarRocks.forEachIndexed { index, char ->
            if (!removed[index]) append(char)
        }
    }
    val hasEngineClause = STARROCKS_ENGINE_REGEX
        .findAll(masked)
        .any { match -> depths[match.range.first] == 0 }

    return if (hasEngineClause) sanitized
    else "$sanitized ENGINE=OLAP PROPERTIES (\"replication_num\" = \"1\")"
}

/** 컬럼 DEFAULT 식의 NULL은 nullable clause가 아니므로 보존합니다. */
private fun isStarRocksDefaultExpression(sql: String, depths: IntArray, index: Int, pattern: Regex): Boolean {
    if (pattern !== STARROCKS_NULL_REGEX) return false

    val columnStart = (index - 1 downTo 0).firstOrNull { depths[it] == 1 && sql[it] == ',' }
        ?.plus(1) ?: 0
    val prefix = sql.substring(columnStart, index)
    val next = sql.substring(index + "NULL".length).trimStart().firstOrNull()
    val nullabilitySuffix = next == ',' || next == ')'
    val defaultValueIsNull = prefix.trimEnd().endsWith("DEFAULT", ignoreCase = true)

    return STARROCKS_DEFAULT_REGEX.containsMatchIn(prefix) && (!nullabilitySuffix || defaultValueIsNull)
}

/** 기존 StarRocks 계약인 NOT NULL은 nullable 제거 대상에서 제외합니다. */
private fun isStarRocksNotNullClause(sql: String, index: Int, pattern: Regex): Boolean =
    pattern === STARROCKS_NULL_REGEX &&
            sql.substring(0, index).trimEnd().endsWith("NOT", ignoreCase = true)

/** 인용 영역과 주석의 원문 위치를 유지하며 제약 검색에서 제외합니다. */
private fun maskDdlQuotedRegions(sql: String): String {
    val masked = sql.toCharArray()
    var index = 0
    while (index < sql.length) {
        val start = index
        val quote = sql[index]
        when {
            quote == '\'' || quote == '"' || quote == '`' ->
                index = quotedRegionEnd(sql, index)

            sql.startsWith("--", index)                   ->
                index = sql.indexOf('\n', index).takeIf { it >= 0 } ?: sql.length

            sql.startsWith("/*", index)                   ->
                index = sql.indexOf("*/", index + 2).takeIf { it >= 0 }?.plus(2) ?: sql.length

            else                                          -> {
                index++
                continue
            }
        }
        for (position in start until index)
            masked[position] = '_'
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
