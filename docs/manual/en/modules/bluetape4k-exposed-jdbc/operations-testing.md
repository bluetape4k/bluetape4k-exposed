---
title: JDBC operations and testing
description: Test JDBC repositories with release-backed transaction, schema, dialect, rollback, and cleanup helpers and operate their connection boundary safely.
manualId: bluetape4k-exposed-jdbc
chapterId: operations-testing
---

# JDBC operations and testing

Repository tests are most useful when they verify the same boundary production owns: a real Exposed transaction, the selected dialect, schema setup and cleanup, and a fully materialized result. `bluetape4k-exposed-jdbc-tests` supplies this infrastructure so every module does not invent its own container and transaction lifecycle.

## Test fixture layers

| Helper | What it owns |
|---|---|
| `withDb` | Connection setup, one transaction, per-database semaphore, temporary config restoration |
| `withTables` | Drop/create before the test and best-effort drop after it |
| `withSchemas` | Schema setup and cleanup |
| `withAutoCommit` | Temporary connection auto-commit value and restoration |
| `assertFailAndRollback` | Commit the fixture state, assert a failing operation, then rollback |
| `withDbSuspending` / `withTablesSuspending` | Coroutine-friendly wrappers for the blocking JDBC test path |

`withDb` sets `maxAttempts = 1` so a failed assertion or SQL statement is not obscured by transaction retries. A fair semaphore serializes tests that share the same `TestDB`, preventing concurrent schema teardown from corrupting another test.

```kotlin
@ParameterizedTest
@MethodSource(ENABLE_DIALECTS_METHOD)
fun `save and find an actor`(testDB: TestDB) {
    withTables(testDB, ActorTable) {
        val saved = repository.save(newActor())
        repository.findById(saved.id) shouldBeEqualTo saved
    }
}
```

## Failure-path tests

Use `assertFailAndRollback` when a statement should violate a constraint. It commits the setup state first, executes the failing block, asserts failure, and rolls back so the current transaction can continue. Do not use it as a general exception-swallowing helper.

Also test:

- not-found and duplicate-key behavior;
- nullable and defaulted columns;
- `bindSave` for empty and multi-row input;
- audit fields after audited and generic updates;
- soft-delete visibility and restore;
- paging input validation and the accepted consistency model;
- savepoints when application code relies on partial rollback.

## Coroutine wrappers do not change the driver

The stable 1.11 JDBC test helper uses Exposed's experimental `newSuspendedTransaction` with an optional dispatcher. It makes a blocking JDBC path callable from suspend tests; it is not an R2DBC transaction. Keep blocking work on an appropriate dispatcher or use virtual threads, and do not run it on an event-loop thread.

## Database matrix strategy

Use H2 for the fastest deterministic feedback, then run the dialects that production actually deploys. SQL syntax, generated keys, upsert, isolation, DDL, and constraint errors can differ. Testcontainers is valuable for PostgreSQL/MySQL/MariaDB integration, but sharing mutable schemas across parallel tests creates false failures; the test helpers serialize a `TestDB` for this reason.

## Operational checks

- Size the connection pool from measured concurrency and query latency; virtual threads can increase callers without increasing available DB connections.
- Set statement/transaction timeouts at the layer that owns the transaction.
- Log slow query context without logging credentials or sensitive parameter values.
- Monitor pool wait time separately from query execution time.
- Keep migrations outside request-time repository initialization.
- Decide whether retries belong to Exposed, Spring, or the service. Multiple retry layers multiply work.

## Diagnosing common failures

| Symptom | Check first |
|---|---|
| `No transaction in context` | The service did not open `transaction {}` or crossed a thread boundary incorrectly |
| DAO property fails during serialization | Conversion to a record happened after the transaction closed |
| Test passes on H2 but fails in production | Run the production dialect and inspect SQL/upsert/generated-key behavior |
| Pool exhaustion | Long transactions, external calls inside transactions, and virtual-thread fan-out |
| Cleanup failure after a test | Earlier transaction was not committed/rolled back or another test shares the schema |
| Page total does not match content | Count and content are separate queries under concurrent writes |

The [`exposed-workshop`](https://github.com/bluetape4k/exposed-workshop) expands these basics into transactions, Spring integration, repository boundaries, multi-tenancy, and production examples.

## Sources

- [JDBC `withDb`](../../../../../exposed/jdbc-tests/src/main/kotlin/io/bluetape4k/exposed/tests/WithDB.kt)
- [JDBC `withTables`](../../../../../exposed/jdbc-tests/src/main/kotlin/io/bluetape4k/exposed/tests/WithTables.kt)
- [Suspending JDBC test helper](../../../../../exposed/jdbc-tests/src/main/kotlin/io/bluetape4k/exposed/tests/WithDBSuspending.kt)
- [JDBC assertions](../../../../../exposed/jdbc-tests/src/main/kotlin/io/bluetape4k/exposed/tests/Assertions.kt)
- [Repository integration tests](../../../../../exposed/jdbc/src/test/kotlin/io/bluetape4k/exposed/jdbc/repository/MovieJdbcRepositoryTest.kt)
