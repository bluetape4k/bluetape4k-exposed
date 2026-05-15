package io.bluetape4k.spring.data.exposed.r2dbc.repository.query

import io.bluetape4k.logging.coroutines.KLoggingChannel
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.InternalApi
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.resolveColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.springframework.data.repository.query.RepositoryQuery
import kotlin.coroutines.Continuation
import kotlin.reflect.KClass

/**
 * [@Query][io.bluetape4k.spring.data.exposed.jdbc.annotation.Query] 어노테이션으로 지정한
 * raw SQL을 호출자의 활성 R2DBC 트랜잭션 컨텍스트 내에서 실행합니다.
 *
 * JDBC [DeclaredExposedQuery][io.bluetape4k.spring.data.exposed.jdbc.repository.query.DeclaredExposedQuery]와
 * 동일한 패턴: `TransactionManager.current()`로 현재 트랜잭션을 직접 획득하여 실행합니다.
 * 새 트랜잭션을 열지 않으므로 호출자 트랜잭션의 미커밋 데이터가 그대로 보입니다.
 *
 * 위치 기반 파라미터(?1, ?2, ...)를 바인딩하고, SELECT 결과의 id 컬럼으로 엔티티를 reload합니다.
 */
internal class DeclaredExposedR2dbcQuery<R: Any, ID: Any>(
    private val queryMethod: ExposedR2dbcQueryMethod,
    private val mapper: R2dbcQueryMapper<R, ID>,
): RepositoryQuery {

    companion object: KLoggingChannel()

    private val positionalPlaceholderRegex = Regex("\\?(\\d+)")

    private val rawSql: String = queryMethod.getAnnotatedQuery()
        ?: error("@Query annotation is required for DeclaredExposedR2dbcQuery on method '${queryMethod.name}'")

    override fun getQueryMethod(): ExposedR2dbcQueryMethod = queryMethod

    override fun execute(parameters: Array<out Any>): Any =
        error("DeclaredExposedR2dbcQuery '${queryMethod.name}' must be invoked as a suspend method")

    suspend fun executeSuspending(parameters: Array<out Any?>): Any? {
        val values = parameters.withoutContinuation()
        val boundSql = bindParameters(rawSql, values)

        val tx = TransactionManager.current()
        val idColumnName = mapper.table.id.name
        val rawIds = tx.exec(boundSql.sql, boundSql.args, StatementType.SELECT) { row ->
            row.get(idColumnName, Any::class.java) ?: row.get(0, Any::class.java)
        }?.toList().orEmpty()

        if (rawIds.isEmpty()) return emptyList<R>()

        @Suppress("UNCHECKED_CAST")
        val ids = rawIds.filterNotNull() as List<ID>
        val results = mutableListOf<R>()
        mapper.table.selectAll()
            .where { mapper.table.id inList ids }
            .collect { results.add(mapper.toDomain(it)) }
        return results
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
            args += toSqlArg(parameters[idx])
            "?"
        }
        return BoundSql(normalizedSql, args)
    }

    @OptIn(InternalApi::class)
    private fun toSqlArg(value: Any?): Pair<IColumnType<*>, Any?> {
        if (value == null) return TextColumnType() to null
        val columnType = runCatching {
            @Suppress("UNCHECKED_CAST")
            resolveColumnType(value::class as KClass<Any>, defaultType = TextColumnType())
        }.getOrElse { TextColumnType() }
        val normalized = if (columnType is TextColumnType && value !is String) value.toString() else value
        return columnType to normalized
    }

    private fun Array<out Any?>.withoutContinuation(): Array<Any?> =
        if (lastOrNull() is Continuation<*>) dropLast(1).toTypedArray()
        else toList().toTypedArray()
}
