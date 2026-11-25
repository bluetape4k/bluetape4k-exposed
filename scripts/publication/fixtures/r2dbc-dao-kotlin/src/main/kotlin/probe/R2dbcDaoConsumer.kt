@file:OptIn(org.jetbrains.exposed.v1.dao.r2dbc.ExperimentalR2dbcDaoApi::class)

package probe

import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.dao.r2dbc.LongEntity
import org.jetbrains.exposed.v1.dao.r2dbc.LongEntityClass
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabaseConfig
import org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

fun main() = runBlocking {
    val database = R2dbcDatabase.connect(
        databaseConfig = R2dbcDatabaseConfig.Builder().apply {
            setUrl("r2dbc:h2:mem:///r2dbc_dao_consumer;DB_CLOSE_DELAY=-1;")
        },
    )

    try {
        val carriedOver = suspendTransaction(database) {
            SchemaUtils.create(Parents, Children)
            val parent = Parent.newSuspend { name = "before-attach" }
            val parentEntity = parent
            Child.newSuspend {
                name = "child"
                this.parent.set(parentEntity)
            }

            check(parent.id.value > 0L)
            check(parent.children.toList().single().name == "child")
            check(parent.children.count() == 1L)
            parent
        }

        suspendTransaction(database) {
            Parent.attach(carriedOver)
            carriedOver.name = "after-attach"
        }

        check(suspendTransaction(database) { Parent.findById(carriedOver.id)?.name } == "after-attach")
    } finally {
        TransactionManager.closeAndUnregister(database)
    }
}

private object Parents : LongIdTable("r2dbc_dao_consumer_parents") {
    val name = varchar("name", 100)
}

private object Children : LongIdTable("r2dbc_dao_consumer_children") {
    val parent = reference("parent_id", Parents)
    val name = varchar("name", 100)
}

class Parent(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<Parent>(Parents)

    var name by Parents.name
    val children by Child referrersOn Children.parent
}

class Child(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<Child>(Children)

    var name by Children.name
    val parent by Parent referencedOn Children.parent
}
