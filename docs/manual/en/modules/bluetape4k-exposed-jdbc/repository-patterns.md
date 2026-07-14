---
title: JDBC repository patterns
description: Implement mapping, CRUD, batches, audit updates, soft deletion, and cursor scans without hiding transaction ownership.
manualId: bluetape4k-exposed-jdbc
chapterId: repository-patterns
---

# JDBC repository patterns

`JdbcRepository<ID, E>` is a table-oriented repository contract. It maps `ResultRow` values to records and supplies common CRUD, batch, query, and paging operations. It is intentionally smaller than a domain service: validation, transaction ownership, authorization, and cross-repository workflows stay outside it.

## Minimal repository

```kotlin
class ActorRepository : LongJdbcRepository<ActorRecord> {
    override val table = ActorTable

    override fun extractId(entity: ActorRecord): Long = entity.id

    override fun ResultRow.toEntity() = ActorRecord(
        id = this[ActorTable.id].value,
        firstName = this[ActorTable.firstName],
        lastName = this[ActorTable.lastName],
    )

    override fun BatchInsertStatement.bindSave(entity: ActorRecord) {
        this[ActorTable.firstName] = entity.firstName
        this[ActorTable.lastName] = entity.lastName
    }
}
```

The default `bindSave` throws. This makes `saveAll` an opt-in operation: the repository author must declare which columns participate in the insert.

## API by operation

| Need | API | Contract to remember |
|---|---|---|
| Read one | `findById`, `findByIdOrNull`, `findFirstOrNull` | `findById` expects exactly one row |
| Read many | `findAll`, `findBy`, `findWithFilters` | Results are materialized in the current transaction |
| Existence/count | `existsById`, `existsBy`, `count`, `countBy` | Keep predicates close to the table definition |
| Write | `updateById`, `updateAll`, `deleteById`, `deleteAll` | These do not add audit fields |
| Batch | `saveAll`, `batchInsert`, `batchUpsert` | Generated-value and dialect behavior must be tested |
| Page | `findPage` | Count and content are separate queries |

Use `findByIdOrNull` at a boundary where absence is expected. Use `findById` when absence means a violated invariant and let the exception make that contract visible.

## Audited updates

For an `AuditableIdTable`, implement `AuditableJdbcRepository` and call `auditedUpdateById` or `auditedUpdateAll`. They set `updatedAt = CURRENT_TIMESTAMP` and `updatedBy = UserContext.getCurrentUser()` before applying the caller's column changes.

```kotlin
UserContext.withUser("admin") {
    transaction {
        repository.auditedUpdateById(id) {
            it[Users.name] = "Alice"
        }
    }
}
```

Calling the generic `updateById` is valid SQL but bypasses the audit update contract.

## Soft delete

`SoftDeletedJdbcRepository` provides active/deleted counts, filtered reads, page queries, `softDeleteById`, and restore operations. It does not change the behavior of every inherited generic method. Code that calls the base `findAll` still needs an explicit decision about deleted rows.

## Custom queries

Put reusable, persistence-specific predicates and projections in the repository. Keep business branching in the service. A custom search may build a query with `andWhere`, but input validation and authorization should already be resolved.

For large ordered scans, `fetchBatchedResultFlow` exposes cursor batches as a coroutine `Flow` over JDBC. It supports an Int/Long or matching `EntityID` cursor and rejects a preconfigured manual limit/order. The flow restores its temporary query mutations in `finally`, including cancellation. The underlying I/O is still blocking JDBC.

## Failure modes

- A repository that opens its own transaction prevents a service from composing atomic operations.
- Forgetting `bindSave` makes `saveAll` fail immediately.
- Generic updates on an audited table omit update audit fields.
- Base reads on a soft-delete table can include deleted rows.
- Batch upsert behavior and generated values vary by dialect; verify the databases you deploy.
- A cursor scan needs a stable, non-null numeric cursor. A mutable sort column can skip or repeat rows.

## Learning path

Start with the source repository's `ActorJdbcRepository` and tests, then run the staged [`exposed-workshop`](https://github.com/bluetape4k/exposed-workshop) SQL DSL, transaction, repository, and production-integration examples. See [Operations and testing](operations-testing.md) before copying a repository into a service.

## Sources

- [JDBC repository](../../../../../exposed/jdbc/src/main/kotlin/io/bluetape4k/exposed/jdbc/repository/JdbcRepository.kt)
- [Auditable JDBC repository](../../../../../exposed/jdbc/src/main/kotlin/io/bluetape4k/exposed/jdbc/repository/AuditableJdbcRepository.kt)
- [Soft-delete JDBC repository](../../../../../exposed/jdbc/src/main/kotlin/io/bluetape4k/exposed/jdbc/repository/SoftDeletedJdbcRepository.kt)
- [Batched JDBC query Flow](../../../../../exposed/jdbc/src/main/kotlin/io/bluetape4k/exposed/jdbc/SuspendedQuery.kt)
- [Actor repository example](../../../../../exposed/jdbc/src/test/kotlin/io/bluetape4k/exposed/jdbc/repository/ActorJdbcRepository.kt)
