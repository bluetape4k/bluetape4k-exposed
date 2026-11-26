# Module exposed-r2dbc-dao

[English](./README.md) | 한국어

`bluetape4k-exposed-r2dbc-dao`는 JetBrains Exposed 1.6.1의 실험 R2DBC DAO를 사용할 수 있도록 의존성만 제공하는 연동 모듈이다. 기존 JDBC DAO 모듈과 의존 경로를 분리한다. 이 모듈은 `2.2.0` 개발 버전 계열 대상이며 안정판 `2.1.0`에는 포함되지 않는다.

## 의존성 추가

중앙 Bluetape4k dependencies BOM에서 Bluetape4k 버전을 선택한다.

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-r2dbc-dao")
    runtimeOnly("org.postgresql:r2dbc-postgresql") // 애플리케이션에 맞는 R2DBC 드라이버를 선택
}
```

배포 모듈은 API 의존성으로 `org.jetbrains.exposed:exposed-dao-r2dbc:1.6.1`, Exposed core와 Exposed R2DBC를 노출한다. R2DBC 드라이버, `ConnectionFactory` 또는 pool, `R2dbcDatabase`와 자원 종료는 애플리케이션이 맡는다.

## 실험 DAO API 사용

JetBrains의 API를 사용하려면 `ExperimentalR2dbcDaoApi` 옵트인을 선언한다. DAO 작업과 관계 `Flow` 수집은 같은 `suspendTransaction` 안에서 끝낸다.

```kotlin
@file:OptIn(org.jetbrains.exposed.v1.dao.r2dbc.ExperimentalR2dbcDaoApi::class)

import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.dao.r2dbc.LongEntity
import org.jetbrains.exposed.v1.dao.r2dbc.LongEntityClass
import org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

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

`newSuspend`는 자동 생성된 ID를 확인할 수 있는 시점에 반환한다. 관계 목록은 `Flow`이므로 트랜잭션이 끝나기 전에 수집한다.

## 다른 트랜잭션에서 Entity 연결

`attach()`는 한 트랜잭션에서 읽은 엔티티를 다른 트랜잭션의 엔티티 캐시에 연결한다.

```kotlin
val detached = suspendTransaction(db = database) {
    User.newSuspend { name = "Ada" }
}

suspendTransaction(db = database) {
    User.attach(detached)
    detached.name = "Ada Lovelace"
}
```

행이 이미 삭제됐거나 현재 캐시에 저장되지 않은 변경이 남은 엔티티가 있으면 `attach()`가 실패한다. `force = true`는 현재 엔티티를 교체하므로 그 변경 내용을 버려도 될 때만 사용한다.

## DAO 구현을 분리한다

JDBC DAO와 R2DBC DAO 의존성은 별도 소비 모듈과 소스 세트에 둔다. JetBrains의 두 artifact는 이름이 같은 DAO 타입을 제공하고, 기존 Bluetape4k `bluetape4k-exposed-r2dbc` 경로에도 JDBC DAO 연동이 포함된다. 하나의 consumer classpath에서 두 경로를 함께 사용하는 구성은 지원하지 않는다. 더 폭넓은 호환성 설계는 [#954](https://github.com/bluetape4k/bluetape4k-exposed/issues/954)에서 추적한다.

## 실험 API와 생명 주기 제약

JetBrains는 R2DBC DAO API를 실험 기능으로 표시한다. patch 버전에서도 호환되지 않는 변경이 생길 수 있으므로 Exposed를 업그레이드할 때 소비 코드를 다시 컴파일하고 테스트한다. 이 모듈은 wrapper API나 production class를 추가하지 않는다. connection factory 또는 pool, 트랜잭션 경계, 드라이버 설정과 자원 종료는 애플리케이션 책임이다. 취소와 rollback 동작은 선택한 드라이버 및 데이터베이스 조합으로 검증한다.
