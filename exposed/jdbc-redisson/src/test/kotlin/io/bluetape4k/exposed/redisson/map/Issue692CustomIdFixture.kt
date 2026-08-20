package io.bluetape4k.exposed.redisson.map

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IdTable

/** Issue #692 전용 custom ID 값 객체입니다. 의도적으로 [Comparable]을 구현하지 않습니다. */
data class Issue692CustomId(val value: String)

/** 실제 transformed column을 사용하는 JDBC Redisson test-local [IdTable]입니다. */
object Issue692CustomIdTable: IdTable<Issue692CustomId>("issue_692_jdbc_redisson_custom") {
    private val rawId = varchar("id", 32)

    override val id: Column<EntityID<Issue692CustomId>> =
        rawId
            .transform(::Issue692CustomId, Issue692CustomId::value)
            .entityId()

    val name = varchar("name", 64)

    override val primaryKey = PrimaryKey(id)
}
