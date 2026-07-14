---
title: Cancellation, failure, and testing
description: Preserve coroutine cancellation and verify transaction behavior against the real R2DBC driver.
manualId: bluetape4k-exposed-r2dbc
chapterId: cancellation-and-testing
---

# Cancellation, failure, and testing

Cancellation is a control signal from the caller, timeout, or shutdown path. Treating it as an ordinary repository failure can trigger retries, error logs, or fallback writes after the caller has already abandoned the operation.

## Preserve cancellation in broad handlers

```kotlin
suspend fun loadActor(id: Long): ActorRecord? = try {
    suspendTransaction(db = database) {
        repository.findByIdOrNull(id)
    }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    logger.warn(e) { "actor lookup failed: id=$id" }
    null
}
```

Prefer narrow catches around errors the application can actually handle. If a broad catch is required, rethrow `CancellationException` before mapping other failures.

## What cancellation does and does not prove

Caller cancellation requests that the coroutine stop. It does not, by itself, prove when the driver cancels an in-flight statement, when the database observes that cancellation, whether a transaction rollback has completed, or when a pooled connection becomes reusable. Those behaviors span Exposed, the R2DBC driver, the pool, and the database server.

Therefore test observable outcomes instead of documenting a universal close sequence:

- the caller receives cancellation rather than a fallback result;
- an interrupted write does not leave an accepted partial business state;
- the connection pool returns to its expected in-use count;
- a subsequent transaction can acquire a connection and execute;
- shutdown finishes within the application's stated deadline.

## Fast and database-backed tests

Use H2 for mapper and basic repository feedback. Use the production database's R2DBC driver with Testcontainers for SQL, isolation, generated IDs, conflict behavior, cancellation, and pool lifecycle.

```kotlin
class ActorRepositoryTest : AbstractExposedR2dbcTest() {
    @Test
    fun `save and read actor`() = runTest {
        withTables(TestDB.POSTGRESQL, ActorTable) {
            val repository = ActorRepository()
            val ids = repository.saveAll(listOf(ActorRecord(name = "Ada")))
            repository.findById(ids.single()).name shouldBeEqualTo "Ada"
        }
    }
}
```

The shared `withDb` helper serializes tests that use the same `TestDB`, opens `suspendTransaction` with `maxAttempts = 1`, and restores a temporary database configuration. `withTables` creates the requested tables and attempts cleanup in `finally`. These are test-support contracts, not promises about an application's production shutdown order.

## Cancellation test shape

Use a deterministic barrier instead of timing a random delay. Start a transaction, wait until the statement reaches the intended boundary, cancel the caller, and then assert the observable database and pool state. Run this test sequentially because it shares a database and connection pool.

```kotlin
val job = launch {
    suspendTransaction(db = database) {
        repository.runBlockedOperation(started)
    }
}

started.await()
job.cancelAndJoin()
assertTrue(job.isCancelled)
```

The exact blocked operation is driver-specific. Keep the assertion aligned with what that driver and database can reliably expose.

## Failure matrix

| Failure | Expected application response |
| --- | --- |
| Mapping or constraint error | Propagate or map once at the service boundary |
| Pool acquisition timeout | Record pool pressure and reject/retry only under an explicit policy |
| Caller cancellation | Rethrow cancellation; do not count it as an ordinary repository error |
| Connection loss | Treat transaction outcome as uncertain until the database contract proves otherwise |
| Cleanup failure in a test | Fail or quarantine the fixture; do not let contaminated state reach the next test |

## Sources

- [`AbstractExposedR2dbcTest.kt`](../../../../../exposed/r2dbc-tests/src/main/kotlin/io/bluetape4k/exposed/r2dbc/tests/AbstractExposedR2dbcTest.kt)
- [`withDb.kt`](../../../../../exposed/r2dbc-tests/src/main/kotlin/io/bluetape4k/exposed/r2dbc/tests/withDb.kt)
- [`withTables.kt`](../../../../../exposed/r2dbc-tests/src/main/kotlin/io/bluetape4k/exposed/r2dbc/tests/withTables.kt)
- [`TestDB.kt`](../../../../../exposed/r2dbc-tests/src/main/kotlin/io/bluetape4k/exposed/r2dbc/tests/TestDB.kt)

For the cross-runtime decision, continue to [JDBC vs R2DBC](../../guides/jdbc-vs-r2dbc.md).
