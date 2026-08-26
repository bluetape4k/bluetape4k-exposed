package io.bluetape4k.spring.data.exposed.jdbc.repository.support

import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.springframework.data.domain.Sort
import io.bluetape4k.spring.data.exposed.common.repository.support.toExposedOrderBy as commonToExposedOrderBy

/** JDBC artifact에 남겨 둔 canonical common sort extension facade입니다. */
fun Sort.toExposedOrderBy(table: Table): Array<Pair<Expression<*>, SortOrder>> =
    commonToExposedOrderBy(table)

/** 기존 JDBC 내부 callers를 위한 이름 변환 호환 함수입니다. */
internal fun toSnakeCase(camelCase: String): String =
    camelCase.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase()

/** 기존 JDBC 내부 callers를 위한 이름 변환 호환 함수입니다. */
internal fun toCamelCase(snakeCase: String): String =
    snakeCase.replace(Regex("_([a-zA-Z])")) { match -> match.groupValues[1].uppercase() }
