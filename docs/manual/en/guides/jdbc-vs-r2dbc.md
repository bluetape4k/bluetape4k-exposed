---
manualId: jdbc-vs-r2dbc
title: JDBC vs R2DBC
locale: en
releaseRef: 1.12.1
---

# JDBC vs R2DBC

Choose the driver and transaction model before choosing repository syntax. Both paths provide Exposed DSL and repository helpers, but they differ in blocking behavior, context propagation, connection ownership, framework integration, and failure testing.

## Operational comparison

| Concern | JDBC | R2DBC |
| --- | --- | --- |
| Driver API | Blocking JDBC `DataSource`/`Connection` | Reactive Streams-based `ConnectionFactory`/R2DBC connection |
| Kotlin surface | Regular functions; blocking calls need an appropriate thread | `suspend` functions and `Flow` |
| Transaction entry | Exposed `transaction` or Spring JDBC transaction integration | Caller/framework-owned `suspendTransaction`; Spring coroutine repository bridge can open it |
| Context | Usually thread-bound transaction context | Coroutine transaction context; do not detach work into unrelated scopes |
| Cancellation | Thread interruption is not a universal statement-cancel contract | Coroutine cancellation propagates as a signal, but driver/database cancellation timing varies |
| Connection owner | Application/framework owns `DataSource` and pool | Application/framework owns `ConnectionFactory` and pool |
| Spring fit | MVC, imperative services, Spring Batch, conventional transaction managers | WebFlux/coroutine call chains and R2DBC-aware infrastructure |
| Testcontainers | JDBC driver plus database container | Matching R2DBC driver plus database container; JDBC may still be needed by migration tools |
| Migration cost | Existing blocking libraries and transactions remain natural | Call chain, driver, pool, migrations, observability, and tests must become R2DBC-aware |

## Decision rules

Choose JDBC when the database driver and surrounding framework are blocking, the service already uses imperative Spring transactions, or the workload is dominated by batch/ETL code that expects JDBC semantics. Java virtual threads can reduce the cost of blocking thread-per-request code without changing the driver contract.

Choose R2DBC when the supported database has a suitable R2DBC driver and the complete request path—framework, repository, transaction, pool, and downstream work—stays coroutine/reactive. One R2DBC repository inside an otherwise blocking call chain does not create an end-to-end non-blocking system.

## R2DBC is not automatically faster

R2DBC changes how waiting work uses threads. It does not remove database latency, lock contention, query planning, network round trips, or pool limits. A JDBC path with a well-sized pool and virtual threads may outperform or simplify an R2DBC path for a given workload. Compare representative latency, throughput, connection occupancy, CPU, and failure recovery before migrating.

## Transaction and context cost

With JDBC, transaction context is commonly thread-bound. With R2DBC, the transaction belongs to the coroutine context opened by `suspendTransaction`. Returning a `Flow` and collecting it after that context ends is unsafe. In either path, the service layer should own the business transaction instead of letting every repository method start an independent transaction.

## Spring and migration tooling

The 1.12.1 Spring JDBC module integrates Exposed repositories with imperative Spring data access. The Spring R2DBC module preserves suspend/`Flow` signatures and delegates execution to Exposed R2DBC transactions. Do not assume every Spring component is interchangeable: migration tools, batch libraries, and third-party integrations may still require JDBC even when runtime queries use R2DBC.

## Proof before migration

1. Run the current JDBC workload and record latency, connection occupancy, timeout, and cancellation behavior.
2. Verify R2DBC feature and SQL parity with the selected driver.
3. Port one vertical slice including framework handler, transaction, repository, pool, and tests.
4. Run H2 feedback tests and sequential Testcontainers tests with the production database.
5. Exercise timeout, cancellation, pool exhaustion, database restart, and shutdown.
6. Compare results. Keep JDBC when the R2DBC path adds complexity without a measured operational benefit.

## Sources and next steps

- [`exposed/jdbc/build.gradle.kts`](../../../../exposed/jdbc/build.gradle.kts)
- [`exposed/r2dbc/build.gradle.kts`](../../../../exposed/r2dbc/build.gradle.kts)
- [`JdbcRepository.kt`](../../../../exposed/jdbc/src/main/kotlin/io/bluetape4k/exposed/jdbc/repository/JdbcRepository.kt)
- [`R2dbcRepository.kt`](../../../../exposed/r2dbc/src/main/kotlin/io/bluetape4k/exposed/r2dbc/repository/R2dbcRepository.kt)
- [Transaction boundaries](./transaction-boundaries.md)
- [`exposed-workshop`](https://github.com/bluetape4k/exposed-workshop) for the JDBC learning path
- [`exposed-r2dbc-workshop`](https://github.com/bluetape4k/exposed-r2dbc-workshop) for the R2DBC learning path
