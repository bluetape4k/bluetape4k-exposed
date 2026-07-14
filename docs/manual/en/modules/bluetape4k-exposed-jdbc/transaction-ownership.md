---
title: JDBC transaction ownership
description: Keep JDBC transactions caller-owned and make connection, isolation, retry, and DAO conversion boundaries explicit.
manualId: bluetape4k-exposed-jdbc
chapterId: transaction-ownership
---

# JDBC transaction ownership

`JdbcRepository` executes against the current Exposed JDBC transaction. It does not open, commit, or close one for the caller. The application service or framework adapter owns that boundary, so it also owns atomicity, isolation, retry policy, and the point where a connection returns to the pool.

## The normal boundary

```kotlin
fun updateProduct(id: Long, command: UpdateProduct): ProductRecord =
    transaction(database) {
        val entity = ProductEntity.findById(id) ?: error("Product not found: $id")
        entity.name = command.name
        entity.price = command.price
        entity.toRecord()
    }
```

The important part is not the placement of a brace. All related reads and writes, including DAO-to-record conversion, happen before `transaction` returns. Returning an Exposed DAO entity would move transaction-bound state into the web or messaging layer.

## What each layer owns

| Layer | Responsibility |
|---|---|
| Controller/consumer | Parse input and return a materialized result |
| Application service | Choose the business transaction boundary and retry semantics |
| Exposed `transaction` | Bind a `JdbcTransaction`, connection, isolation level, and read-only flag |
| Repository | Execute table/DSL operations in the current transaction |
| JDBC driver and pool | Perform blocking I/O and manage physical connection lifecycle |

Repository methods can be composed in one service transaction. Conversely, hiding a `transaction {}` inside every repository method prevents the service from making two repository calls atomic.

## Nested and independent work

`newVirtualThreadJdbcTransaction` runs a JDBC transaction on a virtual thread. `JdbcTransaction.withVirtualThreadJdbcTransaction` does not reuse the outer connection: it starts an independent transaction with the outer transaction's read-only setting. Uncommitted outer changes are therefore not automatically visible.

```kotlin
transaction(database) {
    val id = Orders.insertAndGetId { /* values */ }
    commit() // required if the independent transaction must observe this row

    withVirtualThreadJdbcTransaction {
        Orders.selectAll().where { Orders.id eq id }.single()
    }
}
```

Virtual threads make blocking waits cheaper for platform-thread capacity; they do not turn JDBC into R2DBC and do not merge independent transactions.

## Isolation and paging

`JdbcRepository.findPage` runs a count query followed by a content query. Under concurrent writes, the total and content can describe different moments. Use `SERIALIZABLE` or another database-specific consistency strategy only when that cost is justified; ordinary user-facing pagination often accepts this small window.

## Failure and retry

- Let exceptions leave the transaction block so Exposed can roll back. Do not catch an error, return a success value, and expect rollback to happen.
- Keep external network calls out of a long database transaction when possible. Holding a JDBC connection while waiting on HTTP increases pool pressure.
- If a retry repeats the whole block, every side effect inside it must be idempotent or deferred until commit.
- A read-only flag is a transaction hint/contract, not a replacement for database permissions.

## Spring ownership

Spring integration can own the boundary through its transaction infrastructure, but the same rule remains: one layer must own it explicitly. Do not combine an outer Spring transaction with unrelated manually opened Exposed transactions without first deciding whether they share the same connection and commit outcome.

Continue with [Repository patterns](repository-patterns.md) and [Operations and testing](operations-testing.md). For a fully non-blocking driver path, compare the [R2DBC manual](../bluetape4k-exposed-r2dbc.md).

## Sources

- [JDBC repository usage contract](../../../../../exposed/jdbc/src/main/kotlin/io/bluetape4k/exposed/jdbc/repository/JdbcRepository.kt)
- [Virtual-thread JDBC transactions](../../../../../exposed/jdbc/src/main/kotlin/io/bluetape4k/exposed/jdbc/VirtualThreadJdbcTransaction.kt)
- [JDBC demo controller](../../../../../examples/jdbc-demo/src/main/kotlin/io/bluetape4k/examples/exposed/mvc/controller/ProductController.kt)
- [JDBC repository transaction test](../../../../../exposed/jdbc/src/test/kotlin/io/bluetape4k/exposed/jdbc/repository/MovieJdbcRepositoryTest.kt)
