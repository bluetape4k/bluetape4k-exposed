package io.bluetape4k.exposed.clickhouse

/** RowBinary writer가 안전하게 처리할 수 있는 SQL인지 판정한 결과입니다. */
internal data class ClickHouseRowBinaryPreflightResult(
    val eligible: Boolean,
    val reasonCode: String,
)

/**
 * ClickHouse JDBC V2 writer의 documented simple INSERT 경계를 보수적으로 판정합니다.
 *
 * 함수·중첩 expression·여러 values group·INSERT SELECT는 driver가 RowBinary writer로
 * 처리한다는 보장이 없으므로 JDBC fallback으로 보냅니다. 이 판정은 connection이나
 * statement를 만들기 전에 수행됩니다.
 */
internal object ClickHouseRowBinaryPreflight {

    private const val INSERT = "insert"
    private const val VALUES = "values"

    fun inspect(sql: String): ClickHouseRowBinaryPreflightResult {
        val source = stripComments(sql).trim().removeSuffix(";").trim()
        if (source.isEmpty()) return ineligible("INVALID_SQL")
        if (!startsWithWord(source, INSERT)) return ineligible("NOT_INSERT")

        val valuesIndex = findKeyword(source, VALUES)
        if (valuesIndex < 0) {
            return if (findKeyword(source, "select") >= 0) {
                ineligible("INSERT_SELECT")
            } else {
                ineligible("VALUES_MISSING")
            }
        }
        val selectIndex = findKeyword(source, "select")
        if (selectIndex >= 0 && selectIndex < valuesIndex) {
            return ineligible("INSERT_SELECT")
        }

        val values = source.substring(valuesIndex + VALUES.length).trim()
        if (values.isEmpty() || values.first() != '(') return ineligible("VALUES_INVALID")

        val closingIndex = matchingParenthesis(values, 0)
        if (closingIndex < 0) return ineligible("VALUES_INVALID")

        val trailing = values.substring(closingIndex + 1).trim()
        if (trailing.isNotEmpty()) {
            return if (trailing.startsWith(",")) {
                ineligible("MULTIPLE_VALUES_GROUPS")
            } else {
                ineligible("UNSUPPORTED_TRAILING_SQL")
            }
        }

        val expressions = values.substring(1, closingIndex)
        if (expressions.isBlank()) return ineligible("VALUES_EMPTY")
        if (containsParenthesis(expressions)) return ineligible("VALUES_FUNCTION")

        val tokens = splitComma(expressions)
        if (tokens.isEmpty() || tokens.any { !isSupportedExpression(it) }) {
            return ineligible("UNSUPPORTED_VALUES")
        }
        return ClickHouseRowBinaryPreflightResult(eligible = true, reasonCode = "ELIGIBLE")
    }

    private fun isSupportedExpression(expression: String): Boolean {
        val token = expression.trim()
        return token == "?" || token.equals("DEFAULT", ignoreCase = true)
    }

    private fun ineligible(reasonCode: String): ClickHouseRowBinaryPreflightResult =
        ClickHouseRowBinaryPreflightResult(eligible = false, reasonCode = reasonCode)

    private fun startsWithWord(source: String, word: String): Boolean =
        source.regionMatches(0, word, 0, word.length, ignoreCase = true) &&
            (source.length == word.length || !source[word.length].isIdentifierPart())

    private fun findKeyword(source: String, keyword: String): Int {
        var quote: Char? = null
        var index = 0
        while (index <= source.length - keyword.length) {
            val current = source[index]
            if (quote != null) {
                if (current == quote) {
                    if (index + 1 < source.length && source[index + 1] == quote) {
                        index += 2
                        continue
                    }
                    quote = null
                }
                index++
                continue
            }
            if (current == '\'' || current == '"' || current == '`') {
                quote = current
                index++
                continue
            }
            if (source.regionMatches(index, keyword, 0, keyword.length, ignoreCase = true) &&
                isWordBoundary(source, index, keyword.length)
            ) {
                return index
            }
            index++
        }
        return -1
    }

    private fun isWordBoundary(source: String, start: Int, length: Int): Boolean {
        val before = start == 0 || !source[start - 1].isIdentifierPart()
        val end = start + length
        val after = end >= source.length || !source[end].isIdentifierPart()
        return before && after
    }

    private fun matchingParenthesis(source: String, openingIndex: Int): Int {
        var depth = 0
        var quote: Char? = null
        var index = openingIndex
        while (index < source.length) {
            val current = source[index]
            if (quote != null) {
                if (current == quote) {
                    if (index + 1 < source.length && source[index + 1] == quote) {
                        index += 2
                        continue
                    }
                    quote = null
                }
            } else {
                when (current) {
                    '\'', '"', '`' -> quote = current
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) return index
                    }
                }
            }
            index++
        }
        return -1
    }

    private fun containsParenthesis(source: String): Boolean {
        var quote: Char? = null
        source.forEach { current ->
            if (quote != null) {
                if (current == quote) quote = null
            } else if (current == '\'' || current == '"' || current == '`') {
                quote = current
            } else if (current == '(' || current == ')') {
                return true
            }
        }
        return false
    }

    private fun splitComma(source: String): List<String> {
        val result = ArrayList<String>()
        var start = 0
        var quote: Char? = null
        source.forEachIndexed { index, current ->
            if (quote != null) {
                if (current == quote) quote = null
            } else if (current == '\'' || current == '"' || current == '`') {
                quote = current
            } else if (current == ',') {
                result += source.substring(start, index)
                start = index + 1
            }
        }
        result += source.substring(start)
        return result
    }

    private fun stripComments(source: String): String {
        val result = StringBuilder(source.length)
        var quote: Char? = null
        var index = 0
        while (index < source.length) {
            val current = source[index]
            if (quote != null) {
                result.append(current)
                if (current == quote) {
                    if (index + 1 < source.length && source[index + 1] == quote) {
                        result.append(source[++index])
                    } else {
                        quote = null
                    }
                }
                index++
                continue
            }
            when {
                current == '\'' || current == '"' || current == '`' -> {
                    quote = current
                    result.append(current)
                    index++
                }
                current == '-' && index + 1 < source.length && source[index + 1] == '-' -> {
                    index += 2
                    while (index < source.length && source[index] != '\n') index++
                }
                current == '/' && index + 1 < source.length && source[index + 1] == '*' -> {
                    index += 2
                    while (index + 1 < source.length && !(source[index] == '*' && source[index + 1] == '/')) index++
                    index = (index + 2).coerceAtMost(source.length)
                }
                else -> {
                    result.append(current)
                    index++
                }
            }
        }
        return result.toString()
    }

    private fun Char.isIdentifierPart(): Boolean = isLetterOrDigit() || this == '_' || this == '$'
}
