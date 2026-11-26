# Module exposed-r2dbc-dao

English | [한국어](./README.ko.md)

`bluetape4k-exposed-r2dbc-dao` is a dependency-only integration module for the experimental R2DBC DAO shipped by JetBrains Exposed 1.6.1. It keeps that DAO dependency path separate from the existing JDBC DAO modules. This module targets the `2.2.0` development line and is not included in the stable `2.1.0` release.

## Add the dependency

Use the central Bluetape4k dependencies BOM to select the Bluetape4k version:

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-r2dbc-dao")
    runtimeOnly("org.postgresql:r2dbc-postgresql") // choose the application's R2DBC driver
}
```

The published module exposes `org.jetbrains.exposed:exposed-dao-r2dbc:1.6.1`, Exposed core, and Exposed R2DBC through its API dependencies. The application supplies its R2DBC driver, `ConnectionFactory` or pool, `R2dbcDatabase`, and shutdown lifecycle.

## Use the experimental DAO API

The upstream API requires `ExperimentalR2dbcDaoApi` opt-in. Keep DAO work and relationship `Flow` collection inside the same `suspendTransaction`:

```kotlin
@file:OptIn(org.jetbrains.exposed.v1.dao.r2dbc.ExperimentalR2dbcDaoApi::class)

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.dao.r2dbc.LongEntity
import org.jetbrains.exposed.v1.dao.r2dbc.LongEntityClass
import org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.toList

object Users : LongIdTable("users") {
    val name = varchar("name", 100)
}

object Posts : LongIdTable("posts") {
    val user = reference("user_id", Users)
    val title = varchar("title", 200)
}

class User(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<User>(Users)

    var name by Users.name
    val posts by Post referrersOn Posts.user
}

class Post(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<Post>(Posts)

    var title by Posts.title
    var user by User referencedOn Posts.user
}

suspend fun createPost(database: org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase) =
    suspendTransaction(db = database) {
        SchemaUtils.create(Users, Posts)
        val user = User.newSuspend { name = "Ada" }
        Post.newSuspend {
            title = "Exposed R2DBC DAO"
            this.user.set(user)
        }

        val titles = user.posts.toList().map { it.title }
        val postCount = user.posts.count()
        titles to postCount
    }
```

`newSuspend` returns after the generated ID is available. Relationship collections are `Flow` values; collect them before the transaction closes.

## Attach an entity in another transaction

`attach()` connects an entity loaded by one transaction to the entity cache used by another transaction:

```kotlin
val detached = suspendTransaction(db = database) {
    User.newSuspend { name = "Ada" }
}

suspendTransaction(db = database) {
    User.attach(detached)
    detached.name = "Ada Lovelace"
}
```

`attach()` fails when the row no longer exists or when a conflicting dirty entity is already tracked. `force = true` replaces a dirty tracked instance; use it only when discarding that instance's pending changes is intentional.

## Keep DAO variants separate

Keep JDBC DAO and R2DBC DAO dependencies in separate consumer modules and source sets. The upstream artifacts define colliding DAO types, and the existing Bluetape4k `bluetape4k-exposed-r2dbc` path includes the JDBC DAO integration. Combining those paths in one consumer classpath is unsupported; the broader compatibility design is tracked in [#954](https://github.com/bluetape4k/bluetape4k-exposed/issues/954).

## Experimental API and lifecycle limits

JetBrains marks the R2DBC DAO API experimental. It can change incompatibly in a patch release, so recompile and rerun consumer tests when upgrading Exposed. This integration adds no wrapper API or production classes. The application owns the connection factory or pool, transaction boundaries, driver configuration, and resource shutdown. Cancellation and rollback behavior must be verified with the selected driver and database.
