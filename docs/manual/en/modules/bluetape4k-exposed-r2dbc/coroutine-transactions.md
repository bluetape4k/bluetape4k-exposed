---
title: Coroutine transactions
description: Keep Exposed R2DBC work inside a caller-owned suspendTransaction and make transaction context explicit.
manualId: bluetape4k-exposed-r2dbc
chapterId: coroutine-transactions
---

# Coroutine transactions

An R2DBC repository does not create an application transaction merely because its methods are `suspend`. The caller chooses the `R2dbcDatabase`, opens `suspendTransaction`, and decides which operations must succeed or fail as one unit.

## The ownership rule

```kotlin
suspend fun renameActor(
    database: R2dbcDatabase,
    repository: ActorRepository,
    id: Long,
    newName: String,
) = suspendTransaction(db = database) {
    val actor = repository.findById(id)
    repository.updateById(id) { it[ActorTable.name] = newName }
    actor.copy(name = newName)
}
```

`R2dbcRepository` delegates to Exposed statements. It does not hide the database or start a transaction around each method. This keeps multi-step consistency visible: the service method owns the transaction because it knows which reads and writes form one business operation.

## Keep Flow collection in the transaction

Several repository reads return `Flow<E>`. Building a `Flow` is not the same as consuming the query. Collect it, call `toList`, or apply a terminal operator while the transaction context is still active.

```kotlin
suspend fun activeActors(database: R2dbcDatabase): List<ActorRecord> =
    suspendTransaction(db = database) {
        ActorRepository()
            .findBy { ActorTable.active eq true }
            .toList()
    }
```

Do not return the cold flow from the transaction and collect it later. The later collector may no longer have the transaction and connection context used to construct the query.

## Context and child coroutines

Keep database work in the structured scope that received the transaction context. Launching work in an unrelated application scope makes ownership unclear and may detach it from caller cancellation. Parallel database work also needs enough pool capacity and a deliberate transaction model; one transaction should not be treated as a general-purpose concurrent work queue.

`virtualThreadTransactionAsync` attaches a child `async` to the current coroutine context and runs `suspendTransaction` on the selected dispatcher. A caller-provided `ExecutorService` remains caller-owned. The helper does not close it.

## Isolation and retry decisions

Pass `transactionIsolation` and `readOnly` when the operation requires a non-default contract. Do not assume that a retry is harmless: inserts, external calls, and event publication can duplicate effects. The repository test support deliberately uses `maxAttempts = 1`, which makes failures easier to attribute during integration tests.

## Failure diagnosis

| Symptom | Check first |
| --- | --- |
| Query fails when a returned Flow is collected | Whether collection escaped `suspendTransaction` |
| Partial business update | Whether all required statements share one caller-owned transaction |
| Pool acquisition timeout | In-flight requests, pool size, held-connection time, and nested transactions |
| Cancellation reported as an application error | Broad exception handlers and `CancellationException` propagation |
| Executor threads remain after shutdown | Which component created and therefore owns the executor or pool |

Do not promise a universal rollback or resource-close order from this module alone. Those details also depend on JetBrains Exposed, the R2DBC driver, the connection pool, and the framework lifecycle. Test the selected stack with the same cancellation and shutdown path used in production.

## Sources

- [`R2dbcRepository.kt`](../../../../../exposed/r2dbc/src/main/kotlin/io/bluetape4k/exposed/r2dbc/repository/R2dbcRepository.kt)
- [`VirtualThreadTransaction.kt`](../../../../../exposed/r2dbc/src/main/kotlin/io/bluetape4k/exposed/r2dbc/VirtualThreadTransaction.kt)
- [`withDb.kt`](../../../../../exposed/r2dbc-tests/src/main/kotlin/io/bluetape4k/exposed/r2dbc/tests/withDb.kt)
- [`exposed/r2dbc/README.md`](../../../../../exposed/r2dbc/README.md)

Continue with [repository patterns](./repository-patterns.md), then validate failure behavior in [cancellation and testing](./cancellation-and-testing.md).
