package io.bluetape4k.spring.data.exposed.r2dbc.repository.query

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.warn
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.EntityIDColumnType
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.InternalApi
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.resolveColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.springframework.data.repository.query.RepositoryQuery
import kotlin.coroutines.Continuation
import kotlin.reflect.KClass

/**
 * [@Query][io.bluetape4k.spring.data.exposed.jdbc.annotation.Query]로 선언한 raw SQL을 현재
 * R2DBC transaction 경계 안에서 실행합니다.
 *
 * 호출자가 이미 `suspendTransaction { }` 안에서 실행 중이면 해당 transaction을 재사용하여
 * commit되지 않은 호출자 data가 계속 보이게 합니다. 그렇지 않으면 PartTree와 기본 R2DBC
 * repository method가 사용하는 것과 같은 transaction 경계를 엽니다.
 *
 * Positional parameter(`?1`, `?2`, ...)는 실행 전에 바인딩합니다. SELECT query는 매핑된
 * name으로 entity id column을 노출해야 합니다. Entity는 다시 load되며 raw SQL이 만든 정확한
 * id 순서로 반환됩니다. 따라서 `ORDER BY`, `LIMIT`, join 순서는 보존하지만 entity id를 반환하지
 * 않는 projection이나 grouping 형태는 의도적으로 거부합니다.
 */
internal class DeclaredExposedR2dbcQuery<R: Any, ID: Any>(
    private val queryMethod: ExposedR2dbcQueryMethod,
    private val mapper: R2dbcQueryMapper<R, ID>,
): RepositoryQuery {

    companion object: KLoggingChannel()

    private val positionalPlaceholderRegex = Regex("\\?(\\d+)")
    private val selectModifierRegex = Regex("(?i)^\\s*(?:DISTINCT|ALL)\\s+")

    private val rawSql: String = queryMethod.getAnnotatedQuery()
        ?: error("@Query annotation is required for DeclaredExposedR2dbcQuery on method '${queryMethod.name}'")

    override fun getQueryMethod(): ExposedR2dbcQueryMethod = queryMethod

    override fun execute(parameters: Array<out Any?>): Any? =
        error("DeclaredExposedR2dbcQuery '${queryMethod.name}' must be invoked as a suspend method")

    suspend fun executeSuspending(parameters: Array<out Any?>): Any? {
        validateEntityIdSelection()
        val values = parameters.withoutContinuation()
        val boundSql = bindParameters(rawSql, values)

        return TransactionManager.currentOrNull()?.let { tx ->
            executeInTransaction(tx, boundSql)
        } ?: suspendTransaction {
            executeInTransaction(this, boundSql)
        }
    }

    private suspend fun executeInTransaction(tx: R2dbcTransaction, boundSql: BoundSql): Any? {
        val idColumnName = mapper.table.id.name
        val rawIds = try {
            tx.exec(boundSql.sql, boundSql.args, StatementType.SELECT) { row ->
                try {
                    row.get(idColumnName, Any::class.java)
                } catch (e: IllegalArgumentException) {
                    throw UnsupportedQueryShapeException(queryMethod.name, idColumnName, e)
                }
            }?.toList().orEmpty()
        } catch (e: Exception) {
            e.findUnsupportedQueryShape()?.let { throw it }
            throw e
        }

        if (rawIds.isEmpty()) return emptyList<R>()

        val ids = rawIds.map(::decodeId)
        val resultsById = mutableMapOf<ID, R>()
        mapper.table.selectAll()
            .where { mapper.table.id inList ids.distinct() }
            .collect { row ->
                resultsById[row[mapper.table.id].value] = mapper.toDomain(row)
            }
        return ids.map { id ->
            resultsById[id]
                ?: error("@Query method '${queryMethod.name}' returned unknown entity id '$id'")
        }
    }

    private class UnsupportedQueryShapeException(
        methodName: String,
        idColumnName: String,
        cause: Throwable? = null,
    ): IllegalArgumentException(
        "@Query method '$methodName' must select entity id column '$idColumnName'",
        cause,
    )

    private fun Throwable.findUnsupportedQueryShape(): UnsupportedQueryShapeException? =
        generateSequence(this) { it.cause }
            .filterIsInstance<UnsupportedQueryShapeException>()
            .firstOrNull()

    private fun validateEntityIdSelection() {
        val idColumnName = mapper.table.id.name
        val selectClauses = topLevelSelectClauses(rawSql.withoutSqlComments())
        val hasUnsupportedClause = selectClauses.isEmpty() || selectClauses.any { selectedColumns ->
            val normalizedColumns = selectModifierRegex.replaceFirst(selectedColumns, "")
            projectionItems(normalizedColumns).none { it.selectsId(idColumnName) }
        }
        if (hasUnsupportedClause) {
            throw UnsupportedQueryShapeException(queryMethod.name, idColumnName)
        }
    }

    private fun String.withoutSqlComments(): String {
        val sanitized = StringBuilder(length)
        var quote: Char? = null
        var dollarQuote: String? = null
        var index = 0
        while (index < length) {
            val char = this[index]
            when {
                dollarQuote != null -> {
                    val delimiter = requireNotNull(dollarQuote)
                    if (startsWith(delimiter, index)) {
                        sanitized.append(delimiter)
                        index += delimiter.length - 1
                        dollarQuote = null
                    } else {
                        sanitized.append(char)
                    }
                }
                quote != null -> {
                    sanitized.append(char)
                    if (char == '\\' && getOrNull(index + 1) != null) {
                        sanitized.append(this[++index])
                    } else if (char == quote) {
                        if (getOrNull(index + 1) == quote) {
                            sanitized.append(char)
                            index++
                        } else {
                            quote = null
                        }
                    }
                }
                char == '$' && dollarQuoteDelimiterAt(index) != null -> {
                    val delimiter = requireNotNull(dollarQuoteDelimiterAt(index))
                    dollarQuote = delimiter
                    sanitized.append(delimiter)
                    index += delimiter.length - 1
                }
                char == '\'' || char == '"' || char == '`' -> {
                    quote = char
                    sanitized.append(char)
                }
                (char == '-' && getOrNull(index + 1) == '-') ||
                    (char == '#' && getOrNull(index + 1) != '>') -> {
                    index = indexOf('\n', index + 2).takeIf { it >= 0 } ?: length
                    sanitized.append('\n')
                }
                char == '/' && getOrNull(index + 1) == '*' -> {
                    val commentEnd = indexOf("*/", index + 2)
                    val end = if (commentEnd >= 0) commentEnd + 2 else length
                    sanitized.append(' ')
                    substring(index, end).forEach { if (it == '\n') sanitized.append('\n') }
                    index = end - 1
                }
                else -> sanitized.append(char)
            }
            index++
        }
        return sanitized.toString()
    }

    private fun String.dollarQuoteDelimiterAt(index: Int): String? {
        if (getOrNull(index) != '$') return null
        val end = indexOf('$', index + 1)
        if (end < 0) return null
        val tag = substring(index + 1, end)
        val validTag = tag.isEmpty() ||
            ((tag.first().isLetter() || tag.first() == '_') && tag.drop(1).all { it.isLetterOrDigit() || it == '_' })
        return if (validTag) substring(index, end + 1) else null
    }

    private fun topLevelSelectClauses(sql: String): List<String> {
        val clauses = mutableListOf<String>()
        var depth = 0
        var quote: Char? = null
        var dollarQuote: String? = null
        var selectStart = -1
        var index = 0

        while (index < sql.length) {
            val char = sql[index]
            when {
                dollarQuote != null -> {
                    val delimiter = requireNotNull(dollarQuote)
                    if (sql.startsWith(delimiter, index)) {
                        index += delimiter.length - 1
                        dollarQuote = null
                    }
                }
                quote != null -> {
                    if (char == '\\' && sql.getOrNull(index + 1) != null) {
                        index++
                    } else if (char == quote) {
                        if (sql.getOrNull(index + 1) == quote) index++ else quote = null
                    }
                }
                char == '$' && sql.dollarQuoteDelimiterAt(index) != null -> {
                    val delimiter = requireNotNull(sql.dollarQuoteDelimiterAt(index))
                    dollarQuote = delimiter
                    index += delimiter.length - 1
                }
                char == '-' && sql.getOrNull(index + 1) == '-' -> {
                    index = sql.indexOf('\n', index + 2).takeIf { it >= 0 } ?: sql.length
                    continue
                }
                char == '/' && sql.getOrNull(index + 1) == '*' -> {
                    val commentEnd = sql.indexOf("*/", index + 2)
                    index = if (commentEnd >= 0) commentEnd + 2 else sql.length
                    continue
                }
                char == '\'' || char == '"' || char == '`' -> quote = char
                char == '(' -> depth++
                char == ')' -> depth--
                depth == 0 && sql.isKeywordAt(index, "SELECT") -> {
                    selectStart = index + "SELECT".length
                    index = selectStart
                    continue
                }
                depth == 0 && selectStart >= 0 && sql.isKeywordAt(index, "FROM") -> {
                    clauses += sql.substring(selectStart, index)
                    selectStart = -1
                    index += "FROM".length
                    continue
                }
            }
            index++
        }
        return clauses
    }

    private fun String.isKeywordAt(index: Int, keyword: String): Boolean {
        if (!regionMatches(index, keyword, 0, keyword.length, ignoreCase = true)) return false
        val before = getOrNull(index - 1)
        val after = getOrNull(index + keyword.length)
        return before?.isSqlIdentifierPart() != true && after?.isSqlIdentifierPart() != true
    }

    private fun Char.isSqlIdentifierPart(): Boolean = isLetterOrDigit() || this == '_' || this == '$'

    private fun projectionItems(selectedColumns: String): List<String> {
        val items = mutableListOf<String>()
        var start = 0
        var depth = 0
        var quote: Char? = null
        var dollarQuote: String? = null
        var index = 0
        while (index < selectedColumns.length) {
            val char = selectedColumns[index]
            when {
                dollarQuote != null -> {
                    val delimiter = requireNotNull(dollarQuote)
                    if (selectedColumns.startsWith(delimiter, index)) {
                        index += delimiter.length - 1
                        dollarQuote = null
                    }
                }
                quote != null && char == '\\' && selectedColumns.getOrNull(index + 1) != null -> index++
                quote != null && char == quote -> quote = null
                quote != null -> Unit
                char == '$' && selectedColumns.dollarQuoteDelimiterAt(index) != null -> {
                    val delimiter = requireNotNull(selectedColumns.dollarQuoteDelimiterAt(index))
                    dollarQuote = delimiter
                    index += delimiter.length - 1
                }
                char == '\'' || char == '"' || char == '`' -> quote = char
                char == '(' -> depth++
                char == ')' -> depth--
                char == ',' && depth == 0 -> {
                    items += selectedColumns.substring(start, index)
                    start = index + 1
                }
            }
            index++
        }
        items += selectedColumns.substring(start)
        return items
    }

    private fun String.selectsId(idColumnName: String): Boolean {
        val identifier = Regex.escape(idColumnName)
        val quotedIdentifier = "[\"`]?${identifier}[\"`]?"
        val qualifier = "(?:[\"`]?[A-Za-z_][A-Za-z0-9_$]*[\"`]?\\s*\\.\\s*)?"
        val directId = Regex(
            "(?i)^\\s*$qualifier$quotedIdentifier(?:\\s+(?:AS\\s+)?$quotedIdentifier)?\\s*$"
        )
        val wildcard = Regex("(?i)^\\s*$qualifier\\*\\s*$")
        return directId.matches(this) || wildcard.matches(this)
    }

    @Suppress("UNCHECKED_CAST")
    private fun decodeId(rawId: Any?): ID {
        val value = requireNotNull(rawId) {
            "@Query method '${queryMethod.name}' returned null entity id"
        }
        return try {
            val entityIdColumnType = mapper.table.id.columnType as EntityIDColumnType<ID>
            requireNotNull(entityIdColumnType.idColumn.columnType.valueFromDB(value)) {
                "@Query method '${queryMethod.name}' returned invalid entity id '$value'"
            }
        } catch (e: Exception) {
            throw IllegalArgumentException(
                "@Query method '${queryMethod.name}' returned invalid entity id '$value'",
                e,
            )
        }
    }

    private data class BoundSql(
        val sql: String,
        val args: List<Pair<IColumnType<*>, Any?>>,
    )

    private fun bindParameters(sql: String, parameters: Array<out Any?>): BoundSql {
        val args = mutableListOf<Pair<IColumnType<*>, Any?>>()
        val normalizedSql = positionalPlaceholderRegex.replace(sql) { match ->
            val idx = match.groupValues[1].toInt() - 1
            require(idx in parameters.indices) {
                "Query placeholder index out of bounds: ${match.value} (param count: ${parameters.size})"
            }
            // Duplicate repeated placeholders in args because positional binding needs
            // one independent argument for each question-mark slot.
            args += toSqlArg(parameters[idx])
            "?"
        }
        return BoundSql(normalizedSql, args)
    }

    @OptIn(InternalApi::class)
    private fun toSqlArg(value: Any?): Pair<IColumnType<*>, Any?> {
        if (value == null) return TextColumnType() to null
        val columnType = try {
            @Suppress("UNCHECKED_CAST")
            resolveColumnType(value::class as KClass<Any>, defaultType = TextColumnType())
        } catch (e: Exception) {
            log.warn(e) { "Cannot resolve column type for ${value::class.simpleName}, falling back to TextColumnType" }
            TextColumnType()
        }
        val normalized = if (columnType is TextColumnType && value !is String) value.toString() else value
        return columnType to normalized
    }

    private fun Array<out Any?>.withoutContinuation(): Array<Any?> =
        if (lastOrNull() is Continuation<*>) dropLast(1).toTypedArray()
        else toList().toTypedArray()
}
