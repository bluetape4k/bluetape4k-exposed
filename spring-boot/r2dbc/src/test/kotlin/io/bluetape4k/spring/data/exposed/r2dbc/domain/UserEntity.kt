package io.bluetape4k.spring.data.exposed.r2dbc.domain

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import java.io.Serializable

const val USERS_TABLE_NAME = "coroutine_users"

object Users: LongIdTable(USERS_TABLE_NAME) {
    val name = varchar("name", 255)
    val email = varchar("email", 255)
    val age = integer("age")
}

data class User(
    val id: Long? = null,
    val name: String,
    val email: String,
    val age: Int,
): Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }

    fun withId(newId: Long?) = copy(id = newId)
}
