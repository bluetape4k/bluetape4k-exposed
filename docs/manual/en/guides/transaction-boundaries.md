---
manualId: transaction-boundaries
title: Transaction boundaries
locale: en
releaseRef: 1.12.1
---

# Transaction boundaries

A repository can issue statements, but only the application service knows which statements form one business operation. Put the transaction boundary around that operation and keep connection, coroutine, and framework ownership visible.

> Explore the execution paths in the [JDBC and R2DBC transaction boundary visual companion](https://bluetape4k.github.io/visual-companions/bluetape4k-exposed/jdbc-r2dbc-transaction-boundaries/).

## One business operation, one explicit owner

```kotlin
suspend fun transfer(command: TransferCommand) =
    suspendTransaction(db = database) {
        accountRepository.debit(command.from, command.amount)
        accountRepository.credit(command.to, command.amount)
        transferRepository.record(command)
    }
```

Starting a transaction inside each repository method would allow debit, credit, and audit rows to commit independently. The service owns this boundary because it owns the invariant.

## Boundary map

| Layer | Responsibility |
| --- | --- |
| Application/framework | Own request/job cancellation, timeout, and shutdown policy |
| Service/use case | Define the business transaction and retry/idempotency rule |
| Exposed transaction | Carry database and isolation context for repository statements |
| Repository | Map domain data to Exposed tables and statements |
| Driver/pool | Supply, validate, and reclaim connections under its own contract |
| Database | Enforce SQL isolation, constraints, locks, and commit outcome |

Do not assign a lower layer a guarantee it does not make. For example, coroutine cancellation is not itself evidence that the database has completed rollback or that the pool has already reclaimed the connection.

## JDBC boundary

Use Exposed `transaction` when application code owns the imperative boundary. In Spring, align repository work with the configured JDBC transaction manager rather than nesting unrelated Exposed transactions. Blocking database work must run on threads intended for blocking work; using a `suspend` wrapper does not make JDBC non-blocking.

## R2DBC boundary

Use `suspendTransaction` around all statements and terminal `Flow` operations that belong together. Keep child work in the caller's structured coroutine scope. If a framework repository opens transactions internally, do not add another application transaction until its propagation and connection behavior are understood and tested.

## External effects

HTTP calls, messages, and files are not rolled back by a database transaction. Avoid holding a connection while waiting for slow external I/O. Use an outbox or another explicit delivery protocol when a database commit must lead to message publication. Retrying the database block is safe only when every effect inside it is retryable or idempotent.

## Timeout and cancellation

Set time budgets at the application boundary, then configure pool acquisition, statement, and framework timeouts so the failure can be diagnosed. In coroutine code, rethrow `CancellationException` from broad handlers. Observe cancellation separately from database errors.

Do not document one universal sequence such as “cancel, rollback, close.” The observable order varies with Exposed, the driver, the pool, and the database. Verify the selected stack and assert the final business state, connection availability, and shutdown deadline.

## Test the boundary, not only the mapper

- unit-test mapping and invariant decisions without a database where possible;
- use H2 for fast repository feedback;
- use the production database with Testcontainers for isolation, locks, SQL, generated keys, and cancellation;
- run shared database tests sequentially;
- force failures between statements and assert the complete business state;
- test pool exhaustion and application shutdown with bounded timeouts.

## Sources

- [`JdbcRepository.kt`](../../../../exposed/jdbc/src/main/kotlin/io/bluetape4k/exposed/jdbc/repository/JdbcRepository.kt)
- [`R2dbcRepository.kt`](../../../../exposed/r2dbc/src/main/kotlin/io/bluetape4k/exposed/r2dbc/repository/R2dbcRepository.kt)
- [`withDb.kt`](../../../../exposed/r2dbc-tests/src/main/kotlin/io/bluetape4k/exposed/r2dbc/tests/withDb.kt)
- [JDBC vs R2DBC](./jdbc-vs-r2dbc.md)
