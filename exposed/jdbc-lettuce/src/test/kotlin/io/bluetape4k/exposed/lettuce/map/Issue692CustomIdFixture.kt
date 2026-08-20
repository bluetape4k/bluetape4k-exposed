package io.bluetape4k.exposed.lettuce.map

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IdTable

/** Issue #692 전용 custom ID 값 객체입니다. 의도적으로 [Comparable]을 구현하지 않습니다. */
data class Issue692CustomId(val value: String)

/**
 * 실제 `ColumnWithTransform` 기반 custom [IdTable] fixture입니다.
 *
 * 저장 형식은 VARCHAR지만 loader가 보는 ID 타입은 [Issue692CustomId]이므로
 * scalar keyset 대상이 아닌 offset fallback을 검증할 수 있습니다.
 */
object Issue692CustomIdTable: IdTable<Issue692CustomId>("issue_692_jdbc_lettuce_custom") {
    private val rawId = varchar("id", 32)

    override val id: Column<EntityID<Issue692CustomId>> =
        rawId
            .transform(::Issue692CustomId, Issue692CustomId::value)
            .entityId()

    val name = varchar("name", 64)

    override val primaryKey = PrimaryKey(id)
}
