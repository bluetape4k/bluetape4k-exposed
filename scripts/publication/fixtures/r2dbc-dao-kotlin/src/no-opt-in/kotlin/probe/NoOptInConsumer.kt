package probe

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.dao.r2dbc.LongEntity
import org.jetbrains.exposed.v1.dao.r2dbc.LongEntityClass

private object Rows : LongIdTable("r2dbc_dao_no_opt_in_rows")

private class Row(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<Row>(Rows)
}

suspend fun noExperimentalApiOptIn() {
    Row.newSuspend { }
}
