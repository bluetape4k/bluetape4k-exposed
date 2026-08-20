# Module exposed-jdbc

English | [한국어](./README.ko.md)

Provides the Repository pattern, transaction extensions, and query utilities for the JetBrains Exposed JDBC layer. Built on top of
`exposed-core` and `exposed-dao`, it delivers JDBC-specific features.

## Overview

`exposed-jdbc` provides:

- **Repository pattern**: `JdbcRepository<ID, T, E>` and `SoftDeletedJdbcRepository<ID, T, E>` interfaces
- **Coroutines support**: `SuspendedQuery` — run JDBC queries as suspend functions
- **Virtual Thread transactions**: Transaction execution on JDK 21+ Virtual Threads
- **CTE SELECT queries**: `withCte()` / `withCtes()` for PostgreSQL/MySQL `WITH` and `WITH RECURSIVE`
- **Table/schema extensions**: `ImplicitSelectAll`, `TableExtensions`, `SchemaUtilsExtensions`

## Adding Dependencies

```kotlin
dependencies {
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-jdbc:${version}")

    // For Coroutines support (SuspendedQuery)
    implementation("io.github.bluetape4k:bluetape4k-coroutines:${version}")
}
```

## Diagrams

### JDBC Architecture Overview

This overview separates the repository contract, transaction helpers, runtime query utilities, and Exposed JDBC execution boundary.

![JDBC Architecture Overview diagram](../../docs/images/readme-diagrams/exposed-jdbc-diagram-01.png)

### Repository Contract Map

Use this map when implementing a repository: it shows which contracts provide table access, ID extraction, result mapping, paging, and soft-delete behavior.

![Repository Contract Map diagram](../../docs/images/readme-diagrams/exposed-jdbc-diagram-02.png)

## Sequence Diagrams

### VirtualThread transaction helper

![VirtualThread transaction helper diagram](../../docs/images/readme-diagrams/exposed-jdbc-sequence-01.png)

### findById — Single record lookup

![findById — Single record lookup diagram](../../docs/images/readme-diagrams/exposed-jdbc-sequence-02.png)

### save + findPage — Save then paginate

![save + findPage — Save then paginate diagram](../../docs/images/readme-diagrams/exposed-jdbc-sequence-03.png)

### softDeleteById / restoreById — Soft delete and restore

![softDeleteById / restoreById — Soft delete and restore diagram](../../docs/images/readme-diagrams/exposed-jdbc-sequence-04.png)

## Basic Usage

### 1. Implementing JdbcRepository

```kotlin
import io.bluetape4k.exposed.jdbc.repository.LongJdbcRepository
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

data class UserRecord(
    val id: Long = 0L,
    val name: String,
    val email: String,
)

object UserTable: LongIdTable("users") {
    val name = varchar("name", 100)
    val email = varchar("email", 200)
}

class UserRepository: LongJdbcRepository<UserTable, UserRecord> {

    override val table = UserTable

    override fun ResultRow.toEntity() = UserRecord(
        id = this[UserTable.id].value,
        name = this[UserTable.name],
        email = this[UserTable.email],
    )

    fun save(user: UserRecord): UserRecord {
        val id = UserTable.insert {
            it[name] = user.name
            it[email] = user.email
        } get UserTable.id
        return user.copy(id = id.value)
    }
}

// Usage
transaction {
    val repo = UserRepository()
    val user = repo.save(UserRecord(name = "Hong Gildong", email = "hong@example.com"))

    val found = repo.findById(user.id)
    val page = repo.findPage(pageNumber = 0, pageSize = 20)
    println("Total records: ${page.totalCount}, Total pages: ${page.totalPages}")
}
```

### 2. Implementing SoftDeletedJdbcRepository

```kotlin
import io.bluetape4k.exposed.core.dao.id.SoftDeletedIdTable
import io.bluetape4k.exposed.jdbc.repository.LongSoftDeletedJdbcRepository

object PostTable: SoftDeletedIdTable<Long>("posts") {
    override val id = long("id").autoIncrement().entityId()
    val title = varchar("title", 255)
    val content = text("content")
    override val primaryKey = PrimaryKey(id)
}

data class PostRecord(
    val id: Long = 0L,
    val title: String,
    val content: String,
    val isDeleted: Boolean = false,
)

class PostRepository: LongSoftDeletedJdbcRepository<PostTable, PostRecord> {
    override val table = PostTable

    override fun ResultRow.toEntity() = PostRecord(
        id = this[PostTable.id].value,
        title = this[PostTable.title],
        content = this[PostTable.content],
        isDeleted = this[PostTable.isDeleted],
    )
}

transaction {
    val repo = PostRepository()

    // Soft delete
    repo.softDeleteById(postId)

    // Query only active records
    val activePosts = repo.findActive()

    // Query only deleted records
    val deletedPosts = repo.findDeleted()

    // Restore
    repo.restoreById(postId)
}
```

### 3. Coroutines-based batch query (SuspendedQuery)

```kotlin
import io.bluetape4k.exposed.core.fetchBatchedResultFlow
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList

// Flow-based query that reads in batches of 10
val allIds = UserTable
    .select(UserTable.id)
    .fetchBatchedResultFlow(batchSize = 10)
    .flatMapConcat { rows -> rows.asFlow() }
    .toList()
```

### 4. Virtual Thread transactions

```kotlin
import io.bluetape4k.exposed.jdbc.newVirtualThreadJdbcTransaction
import io.bluetape4k.exposed.jdbc.virtualThreadJdbcTransactionAsync

// Run a synchronous transaction on a JDK 21+ Virtual Thread
val count = newVirtualThreadJdbcTransaction {
    UserTable.selectAll().count()
}

// Run multiple transactions asynchronously in parallel
val futures = List(10) { index ->
    virtualThreadJdbcTransactionAsync {
        UserTable.insert { it[name] = "user-$index" }
        index
    }
}
val results = futures.awaitAll()
```

Passing `executor = null` reuses the shared `VirtualThreadExecutor`. Custom executors remain caller-owned and must be shut down by the caller.

### 5. ExposedPage — paginated results

```kotlin
// Using JdbcRepository.findPage()
transaction {
    val repo = UserRepository()
    val page = repo.findPage(
        pageNumber = 0,
        pageSize = 20,
        sortOrder = SortOrder.ASC
    ) { UserTable.name like "Hong%" }

    println("Total count: ${page.totalCount}")
    println("Current page: ${page.pageNumber}")
    println("Total pages: ${page.totalPages}")
    println("Is last page: ${page.isLast}")
    page.content.forEach { println(it) }
}
```

### 6. Typed cursor pagination

Use `findCursorPage` when a stable primary-key position is more useful than an offset and total count.
The repository still runs inside the caller-owned JDBC `transaction {}`.

```kotlin
import io.bluetape4k.exposed.jdbc.repository.findCursorPage
import org.jetbrains.exposed.v1.core.SortOrder

transaction {
    val first = repo.findCursorPage(
        pageSize = 20,
        predicate = { UserTable.name like "Hong%" },
    )
    val next = repo.findCursorPage(
        pageSize = 20,
        cursor = first.nextCursor,
        sortOrder = SortOrder.ASC,
        predicate = { UserTable.name like "Hong%" },
    )
}
```

The extension uses the raw `IdTable.id` value and a strict `>`/`<` boundary for ascending/descending
sort orders. All six `SortOrder` variants are accepted; null-placement variants only keep their direction
because primary keys are non-null. Each call executes one bounded `SELECT` with `LIMIT pageSize + 1`,
never a count or offset query, and accepts `pageSize` from 1 through 10,000. `hasNext` and `nextCursor`
follow the invariant documented by `ExposedCursorPage`.

The caller owns cursor token encoding, signing, expiry, tenant/authorization scope, and reuse of the same
sort and predicate. There is no single read-view guarantee across calls. The default predicate is `Op.TRUE`, so a
soft-delete repository must pass its active-row predicate explicitly; `findPage` and Spring Batch keyset
readers remain separate contracts.

### 7. Batch insert / Upsert

```kotlin
transaction {
    val repo = UserRepository()

    // Batch insert
    val inserted = repo.batchInsert(userList) { user ->
        this[UserTable.name] = user.name
        this[UserTable.email] = user.email
    }

    // Batch upsert
    val upserted = repo.batchUpsert(userList) { user ->
        this[UserTable.name] = user.name
        this[UserTable.email] = user.email
    }
}
```

### 8. Common Table Expressions

```kotlin
import io.bluetape4k.exposed.core.CteTable
import io.bluetape4k.exposed.jdbc.withCte
import org.jetbrains.exposed.v1.jdbc.select

transaction {
    val activeUsers = CteTable(
        name = "active_users",
        query = UserTable
            .select(UserTable.id, UserTable.name)
            .where { UserTable.active eq true }
    )

    activeUsers
        .select(activeUsers[UserTable.id], activeUsers[UserTable.name])
        .withCte(activeUsers)
        .orderBy(activeUsers[UserTable.id])
        .toList()
}
```

`withCte()` renders the CTE body and the final SELECT through the same Exposed `QueryBuilder`, so prepared
parameters from CTE predicates keep their binding order.

## JdbcRepository Key Methods

| Method                                | Description                            |
|---------------------------------------|----------------------------------------|
| `count()`                             | Total record count                     |
| `countBy(predicate)`                  | Count matching records                 |
| `existsById(id)`                      | Check existence by ID                  |
| `existsBy(predicate)`                 | Check existence by condition           |
| `findById(id)`                        | Find by ID (throws if not found)       |
| `findByIdOrNull(id)`                  | Find by ID (returns null if not found) |
| `findAll(limit, offset, ...)`         | Find all (supports paging and sorting) |
| `findWithFilters(...)`                | Find with multiple AND conditions      |
| `findBy(...)`                         | Alias for `findWithFilters`            |
| `findFirstOrNull(...)`                | First matching entity                  |
| `findLastOrNull(...)`                 | Last matching entity                   |
| `findByField(field, value)`           | Find by a specific column value        |
| `findAllByIds(ids)`                   | Find multiple entities by IDs          |
| `findPage(pageNumber, pageSize, ...)` | Paginated query                        |
| `findCursorPage(pageSize, cursor, ...)` | Typed primary-key cursor page       |
| `deleteById(id)`                      | Delete by ID                           |
| `deleteByIdIgnore(id)`                | Delete by ID (ignore exceptions)       |
| `deleteAll(op)`                       | Delete matching records                |
| `deleteAllByIds(ids)`                 | Delete multiple records by IDs         |
| `updateById(id, ...)`                 | Update by ID                           |
| `updateAll(predicate, ...)`           | Bulk update matching records           |
| `batchInsert(entities, ...)`          | Batch insert                           |
| `batchUpsert(entities, ...)`          | Batch upsert                           |

## SoftDeletedJdbcRepository Additional Methods

| Method                                      | Description                          |
|---------------------------------------------|--------------------------------------|
| `softDeleteById(id)`                        | Soft delete by ID (`isDeleted=true`) |
| `restoreById(id)`                           | Restore a soft-deleted record by ID  |
| `countActive(predicate)`                    | Count active records                 |
| `countDeleted(predicate)`                   | Count deleted records                |
| `findActive(limit, offset, ...)`            | Find only active records             |
| `findDeleted(limit, offset, ...)`           | Find only deleted records            |
| `softDeleteAll(predicate)`                  | Bulk soft delete matching records    |
| `restoreAll(predicate)`                     | Bulk restore matching records        |
| `findActivePage(pageNumber, pageSize, ...)` | Paginated query of active records    |

## AuditableJdbcRepository (Audit Tracking Repository)

`AuditableJdbcRepository` automatically sets `updatedAt` and `updatedBy` on UPDATE operations.

### Table definition (exposed-core)

```kotlin
import io.bluetape4k.exposed.core.auditable.AuditableLongIdTable

object ArticleTable : AuditableLongIdTable("articles") {
    val title = varchar("title", 255)
    val content = text("content")
    // createdBy, createdAt, updatedBy, updatedAt are added automatically
}
```

### Repository implementation

```kotlin
import io.bluetape4k.exposed.jdbc.repository.LongAuditableJdbcRepository
import org.jetbrains.exposed.v1.core.ResultRow

data class ArticleRecord(
    val id: Long = 0L,
    val title: String,
    val content: String,
)

class ArticleRepository : LongAuditableJdbcRepository<ArticleRecord, ArticleTable> {
    override val table = ArticleTable

    override fun extractId(entity: ArticleRecord) = entity.id

    override fun ResultRow.toEntity() = ArticleRecord(
        id = this[ArticleTable.id].value,
        title = this[ArticleTable.title],
        content = this[ArticleTable.content],
    )
}
```

### auditedUpdateById — Update by ID

On UPDATE, automatically sets `updatedAt` to DB `CURRENT_TIMESTAMP` (UTC) and `updatedBy` to
`UserContext.getCurrentUser()`.

```kotlin
import io.bluetape4k.exposed.core.auditable.UserContext
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

transaction {
    UserContext.withUser("editor@example.com") {
        val repo = ArticleRepository()

        // updatedBy="editor@example.com", updatedAt set to current DB time
        val rows = repo.auditedUpdateById(1L) {
            it[ArticleTable.title] = "Updated Title"
        }
        println("Rows updated: $rows")
    }
}
```

### auditedUpdateAll — Bulk update by condition

Updates all matching records and automatically sets audit fields.

```kotlin
transaction {
    UserContext.withUser("batch-job") {
        val repo = ArticleRepository()

        // Update all records where title = "Draft"
        // updatedBy="batch-job", updatedAt set to current DB time
        val rows = repo.auditedUpdateAll(predicate = { ArticleTable.title eq "Draft" }) {
            it[ArticleTable.title] = "Published"
        }
        println("Rows updated: $rows")
    }
}
```

### Complete example

```kotlin
import io.bluetape4k.exposed.core.auditable.UserContext
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

transaction {
    val repo = ArticleRepository()

    // 1. INSERT
    UserContext.withUser("alice") {
        val newArticle = ArticleRecord(
            title = "Hello Auditable",
            content = "Tracking changes automatically",
        )
        // INSERT: createdBy="alice", createdAt set to current DB time
        repo.save(newArticle)
    }

    // 2. SELECT
    val article = repo.findByIdOrNull(1L)
    println("Creator: ${article?.createdBy}")   // "alice"
    println("Created at: ${article?.createdAt}") // DB timestamp

    // 3. UPDATE
    UserContext.withUser("bob") {
        // updatedBy="bob", updatedAt set to current DB time
        repo.auditedUpdateById(1L) {
            it[ArticleTable.title] = "Updated by Bob"
        }
    }

    // 4. Verify the update
    val updated = repo.findByIdOrNull(1L)
    println("Modifier: ${updated?.updatedBy}")   // "bob"
    println("Updated at: ${updated?.updatedAt}") // DB timestamp (different from createdAt)
}
```

### Important notes

- Always use `auditedUpdateById()` or `auditedUpdateAll()` for auditable entities.
- Using the plain `JdbcRepository.updateById()` will not automatically set audit fields.

### Convenience type aliases

| Interface                     | Primary key type |
|-------------------------------|------------------|
| `IntAuditableJdbcRepository`  | `Int`            |
| `LongAuditableJdbcRepository` | `Long`           |
| `UUIDAuditableJdbcRepository` | `java.util.UUID` |

## Convenience Type Aliases (Standard Repository)

| Interface                         | Primary key type   |
|-----------------------------------|--------------------|
| `IntJdbcRepository`               | `Int`              |
| `LongJdbcRepository`              | `Long`             |
| `UuidJdbcRepository`              | `kotlin.uuid.Uuid` |
| `UUIDJdbcRepository`              | `java.util.UUID`   |
| `StringJdbcRepository`            | `String`           |
| `IntSoftDeletedJdbcRepository`    | `Int`              |
| `LongSoftDeletedJdbcRepository`   | `Long`             |
| `UuidSoftDeletedJdbcRepository`   | `kotlin.uuid.Uuid` |
| `UUIDSoftDeletedJdbcRepository`   | `java.util.UUID`   |
| `StringSoftDeletedJdbcRepository` | `String`           |

## Key Files and Classes

| File                                                | Description                                    |
|-----------------------------------------------------|------------------------------------------------|
| `jdbc/repository/JdbcRepository.kt`                 | JDBC Repository base interface                 |
| `jdbc/repository/SoftDeletedJdbcRepository.kt`      | Soft Delete Repository                         |
| `repository/ExposedRepository.kt`                   | (Deprecated) Legacy Repository interface       |
| `core/SuspendedQuery.kt`                            | Cursor-based batch Flow query                  |
| `jdbc/VirtualThreadJdbcTransaction.kt`              | Virtual Thread-based JDBC transaction          |
| `core/transactions/VirtualThreadTransaction.kt`     | (Deprecated) Legacy Virtual Thread transaction |
| `core/ImplicitSelectAll.kt`                         | Implicit `SELECT *` query                      |
| `core/TableExtensions.kt`                           | Table metadata extension functions             |
| `core/SchemaUtilsExtensions.kt`                     | SchemaUtils extension functions                |

## MySQL 8 JDBC conformance

`MySQLJdbcParallelKeyEnumerationTest` verifies the JDBC parallel key enumeration
boundary with MySQL 8 Connector/J, HikariCP, and Testcontainers. The fixture is
test-only; it does not change the production API, pool configuration, or release
manual.

| Contract | Evidence |
|----------|----------|
| Sparse IDs and disjoint range ordering | MySQL 8: PASS |
| Overlap/reverse validation and empty-range no-lease path | MySQL 8: PASS |
| Hikari pool 1/2/4 exact lease peak with `maxConcurrency=2` | MySQL 8: PASS |
| `READ_COMMITTED` and `REPEATABLE_READ` two-SELECT fixture | MySQL 8: PASS |
| Statement rollback, SQLState `23000`, and lease retry request count | MySQL 8: PASS |
| Cleanup primary/suppressed failures and caller executor ownership | MySQL 8: PASS |

Run the driver-specific test with:

```bash
EXPOSED_TEST_DB=MYSQL_V8 TESTCONTAINERS_RYUK_DISABLED=true \
./gradlew :bluetape4k-exposed-jdbc:test \
  --tests 'io.bluetape4k.exposed.jdbc.MySQLJdbcParallelKeyEnumerationTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache \
  --no-parallel --max-workers=1 --console=plain
```

The H2 unit baseline remains `JdbcParallelKeyEnumerationTest`. The existing
nightly MySQL job in `.github/workflows/nightly-tests.yml` supplies the Docker
environment; this conformance class does not claim all MySQL deployments or all
JDBC drivers are equivalent. Pool size 1 is an intentional under-provisioned
pressure case, not an operational recommendation. The isolation callback uses
an internal two-SELECT test seam and does not promise a shared read view for the
public overload. `SERIALIZABLE`, network faults, and cancellation remain outside
this issue; see follow-up issues #697 and #690.

## Performance Benchmarks

JMH benchmark results for `ExposedJdbcBenchmark` (PostgreSQL via Testcontainers, HikariCP pool).
See [2026-04-21-self-improve.md](./2026-04-21-self-improve.md) for the full optimization history.

**Environment**: Java 21, Kotlin 2.3, PostgreSQL 16, HikariCP max=24, @Threads(14), @Warmup(3×3s) + @Measurement(5×5s)

| Benchmark | ops/s |
|-----------|-------|
| `singleInsert` | ~14,400 |
| `singleFindById` | ~15,000 |
| `singleUpdate` | ~14,300 |
| `joinQuery` (INNER JOIN + WHERE + LIMIT 100) | ~1,510 |
| `batchInsert` (batchSize=100) | ~217 |
| **Total** | **~45,431** |

![Exposed JDBC benchmark throughput chart](../../docs/images/readme-charts/exposed-jdbc-benchmark-chart-01.png)

> Optimized over 8 rounds of automated self-improve: **+78.9% improvement** from baseline (25,401 → 45,431 ops/s).
> Key wins: HikariCP pool tuning (+71%), composite index on `bench_orders` (+1.5%), JMH measurement stabilization (+2.9%).

## Testing

### Application-owned idempotency boundary

The PostgreSQL integration fixture in
[`ApplicationOwnedIdempotencyRecordJdbcTest`](src/test/kotlin/io/bluetape4k/exposed/jdbc/idempotency/ApplicationOwnedIdempotencyRecordJdbcTest.kt)
demonstrates a test-local idempotency record; this module does not expose a public idempotency repository API.

- The database enforces one `(scope, idempotency_key)` record and compare-and-set updates for owner-token finalization and stale-owner replacement.
- The application owns scope selection, request-fingerprint calculation, stale timeout, retry behavior, and result-reference retention.
- Persist hashes and opaque result references rather than raw payloads or PII. Keep metric and log labels bounded to values such as state and policy.
- These boundaries diagnose and resolve interrupted owners; they do not claim exactly-once delivery and do not replace an outbox.

```bash
./gradlew :exposed-jdbc:test
```

## References

- [JetBrains Exposed JDBC](https://github.com/JetBrains/Exposed/wiki/DSL)
- [exposed-core](../exposed-core)
- [exposed-dao](../exposed-dao)
