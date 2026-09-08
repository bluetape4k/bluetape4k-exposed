package io.bluetape4k.spring.data.exposed.jdbc.repository.query

import io.bluetape4k.logging.KLogging
import io.bluetape4k.spring.data.exposed.common.repository.query.replaceSqlParameters
import io.bluetape4k.spring.data.exposed.common.repository.query.requireEntityQueryId
import io.bluetape4k.spring.data.exposed.jdbc.repository.support.ExposedEntityInformation
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.InternalApi
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.resolveColumnType
import org.jetbrains.exposed.v1.dao.Entity
import org.jetbrains.exposed.v1.dao.EntityClass
import org.jetbrains.exposed.v1.dao.flushCache
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.springframework.data.repository.query.RepositoryQuery
import java.sql.ResultSet

/**
 * [@Query][io.bluetape4k.spring.data.exposed.annotation.Query] 어노테이션으로 지정한 raw SQL을 실행합니다.
 * 위치 기반 파라미터(?1, ?2, ...)를 Prepared Statement 바인딩으로 안전하게 처리합니다.
 *
 * 결과는 매핑된 ID 컬럼명과 같은 결과 라벨에서 ID를 읽어 다시 로드합니다.
 * 라벨의 대소문자는 무시하지만 다른 이름의 alias나 첫 컬럼 대체는 허용하지 않습니다.
 * ID 누락과 NULL은 엔티티 조회 전에 거부합니다.
 */
class DeclaredExposedQuery<E: Entity<ID>, ID: Any>(
    private val queryMethod: ExposedQueryMethod,
    private val entityInformation: ExposedEntityInformation<E, ID>,
): RepositoryQuery {

    companion object: KLogging()

    private data class BoundSql(
        val sql: String,
        val args: List<Pair<ColumnType<*>, Any?>>,
    )

    private val entityClass: EntityClass<ID, E> = entityInformation.entityClass
    private val rawSql: String =
        queryMethod.getAnnotatedQuery()
            ?: error("@Query annotation is required for DeclaredExposedQuery")

    override fun getQueryMethod(): ExposedQueryMethod = queryMethod

    @Suppress("UNCHECKED_CAST")
    override fun execute(parameters: Array<out Any?>): Any? {
        val boundSql = bindParameters(rawSql, parameters)
        val tx = TransactionManager.current()
        tx.flushCache()

        return tx.exec(boundSql.sql, boundSql.args) { rs ->
            val idColumnIndex = findIdColumn(rs)
            val results = mutableListOf<E>()
            while (rs.next()) {
                val idVal = requireEntityQueryId(rs.getObject(idColumnIndex), queryMethod.name)
                val normalizedId = coerceIdValue(idVal)
                entityClass.findById(normalizedId)?.let { results.add(it) }
            }
            results
        } ?: emptyList<E>()
    }

    private fun bindParameters(
        sql: String,
        parameters: Array<out Any?>,
    ): BoundSql {
        val args = mutableListOf<Pair<ColumnType<*>, Any?>>()
        val normalizedSql =
            replaceSqlParameters(sql) { number ->
                val placeholderIndex = number - 1
                require(placeholderIndex in parameters.indices) {
                    "Query placeholder index out of bounds: ?$number for parameter size ${parameters.size}"
                }
                args += toSqlArg(parameters[placeholderIndex])
                "?"
            }
        return BoundSql(normalizedSql, args)
    }

    @OptIn(InternalApi::class)
    private fun toSqlArg(value: Any?): Pair<ColumnType<*>, Any?> {
        if (value == null) return TextColumnType() to null

        val columnType =
            runCatching {
                @Suppress("UNCHECKED_CAST")
                resolveColumnType(value::class as kotlin.reflect.KClass<Any>, defaultType = TextColumnType())
            }.getOrElse { TextColumnType() }

        val normalizedValue = if (columnType is TextColumnType && value !is String) value.toString() else value
        return columnType to normalizedValue
    }

    private fun findIdColumn(rs: ResultSet): Int {
        val idColumnName = entityInformation.table.id.name
        val metadata = rs.metaData
        return (1..metadata.columnCount).singleOrNull {
            metadata.getColumnLabel(it).equals(idColumnName, ignoreCase = true)
        } ?: throw IllegalArgumentException(
            "@Query method '${queryMethod.name}' must select entity id column '$idColumnName'"
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun coerceIdValue(rawId: Any): ID {
        val idType = entityInformation.idType
        if (idType.isInstance(rawId)) {
            return rawId as ID
        }
        return when (idType) {
            Long::class.java   -> if (rawId is Number) rawId.toLong() as ID
                else throw IllegalStateException(
                    "Cannot coerce id value '$rawId' (${rawId::class.java.simpleName}) to Long"
                )
            Int::class.java    -> if (rawId is Number) rawId.toInt() as ID
                else throw IllegalStateException(
                    "Cannot coerce id value '$rawId' (${rawId::class.java.simpleName}) to Int"
                )
            Short::class.java  -> if (rawId is Number) rawId.toShort() as ID
                else throw IllegalStateException(
                    "Cannot coerce id value '$rawId' (${rawId::class.java.simpleName}) to Short"
                )
            String::class.java -> rawId.toString() as ID
            else               -> throw IllegalStateException(
                "Cannot coerce id value '$rawId' (${rawId::class.java.simpleName}) to entity id type " +
                    "${idType.simpleName}. Add a coercion rule in DeclaredExposedQuery.coerceIdValue()."
            )
        }
    }
}
