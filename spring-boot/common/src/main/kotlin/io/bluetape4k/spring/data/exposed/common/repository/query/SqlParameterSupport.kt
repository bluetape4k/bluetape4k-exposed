package io.bluetape4k.spring.data.exposed.common.repository.query

/**
 * SQL 코드 영역의 위치 파라미터(`?1`, `?2`, …)만 [replace]로 치환합니다.
 *
 * 문자열·인용 식별자·줄/블록 주석·PostgreSQL dollar-quoted 문자열은 그대로 유지합니다.
 * [replace]는 1부터 시작하는 파라미터 번호를 출현 순서대로 받습니다.
 * JDBC·R2DBC의 값 변환과 파라미터 개수 검증은 각 어댑터가 담당합니다.
 */
fun replaceSqlParameters(sql: String, replace: (Int) -> String): String = buildString(sql.length) {
    var index = 0
    while (index < sql.length) {
        val char = sql[index]
        val end = sql.protectedRegionEnd(index)
        if (end != null) {
            append(sql, index, end)
            index = end
        } else if (char == '?' && sql.getOrNull(index + 1) in '0'..'9') {
            var numberEnd = index + 1
            while (sql.getOrNull(numberEnd) in '0'..'9') numberEnd++
            val number = sql.substring(index + 1, numberEnd).toIntOrNull()
                ?: throw IllegalArgumentException("Query placeholder index is too large")
            append(replace(number))
            index = numberEnd
        } else {
            append(char)
            index++
        }
    }
}

private fun String.protectedRegionEnd(index: Int): Int? = when (val char = this[index]) {
    '\'', '"', '`' -> quotedEnd(index, char)
    '#' -> if (getOrNull(index + 1) == '>') null else lineCommentEnd(index)
    '$' -> dollarQuotedEnd(index)
    else -> when {
        startsWith("--", index) -> lineCommentEnd(index)
        startsWith("/*", index) -> blockCommentEnd(index)
        else -> null
    }
}

private fun String.quotedEnd(start: Int, quote: Char): Int {
    var index = start + 1
    while (index < length) {
        when {
            this[index] == '\\' && index + 1 < length -> index += 2
            this[index] == quote && getOrNull(index + 1) == quote -> index += 2
            this[index] == quote -> return index + 1
            else -> index++
        }
    }
    return length
}

private fun String.lineCommentEnd(start: Int): Int {
    var index = start
    while (index < length && this[index] != '\n' && this[index] != '\r') index++
    return index
}

private fun String.blockCommentEnd(start: Int): Int {
    var depth = 1
    var index = start + 2
    while (index < length) {
        when {
            startsWith("/*", index) -> { depth++; index += 2 }
            startsWith("*/", index) -> {
                depth--
                index += 2
                if (depth == 0) return index
            }
            else -> index++
        }
    }
    return length
}

private val dollarQuoteDelimiter = Regex("\\$(?:[\\p{L}_][\\p{L}\\p{N}_]*)?\\$")

private fun String.dollarQuotedEnd(start: Int): Int? {
    // 인용되지 않은 식별자의 일부인 '$'는 dollar quote 시작이 아닙니다.
    if (start > 0 && (this[start - 1].isLetterOrDigit() || this[start - 1] in "_$")) return null
    val delimiter = dollarQuoteDelimiter.matchAt(this, start)?.value
    return delimiter?.let {
        val close = indexOf(it, start + it.length)
        if (close < 0) length else close + it.length
    }
}
