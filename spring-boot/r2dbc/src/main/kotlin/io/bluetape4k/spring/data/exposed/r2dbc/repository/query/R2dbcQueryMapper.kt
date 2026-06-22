package io.bluetape4k.spring.data.exposed.r2dbc.repository.query

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.dao.id.IdTable

internal data class R2dbcQueryMapper<R: Any, ID: Any>(
    val table: IdTable<ID>,
    val toDomain: (ResultRow) -> R,
)
