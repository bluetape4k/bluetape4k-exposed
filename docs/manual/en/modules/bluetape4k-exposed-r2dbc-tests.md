---
manualId: "bluetape4k-exposed-r2dbc-tests"
id: "bluetape4k-exposed-r2dbc-tests"
title: "Exposed R2DBC Test Support"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-exposed-r2dbc-tests"
sourceDir: "exposed/r2dbc-tests"
releaseRef: "1.12.1"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-r2dbc-tests
---

# Exposed R2DBC Test Support

> Suspending transaction, schema/table lifecycle, R2DBC driver, assertion, and Testcontainers fixtures.

## Problem {#problem}

R2DBC tests must prove coroutine transaction propagation, driver behavior, cleanup after suspension/failure, and real dialect behavior. Reusing JDBC-only fixtures leaves the most important R2DBC boundaries untested.

## When to use it {#when-to-use}

Use it for R2DBC repository, query, driver, cancellation, and framework integration tests. It is a test dependency, not an application runtime module.

## Coordinates {#coordinates}

`testImplementation("io.github.bluetape4k.exposed:bluetape4k-exposed-r2dbc-tests")`, managed by the central BOM.

## Core concepts {#concepts}

`withDb` acquires the per-database semaphore without blocking a coroutine worker, opens a `maxAttempts = 1` `suspendTransaction`, and restores temporary configuration. `withTables` and `withSchemas` create, commit, and clean up fixtures; schema cleanup preserves the primary failure and suppresses a secondary drop failure.

## Quick start {#quick-start}

```kotlin
withTables(TestDB.POSTGRESQL, Actors) {
    Actors.insert { it[name] = "Ada" }
    Actors.selectAll().count() shouldBeEqualTo 1L
}
```

## API by task {#api-by-task}

| Task | API |
|---|---|
| Suspending transaction fixture | `withDb` |
| Table/schema lifecycle | `withTables`, `withSchemas` |
| Auto-commit probe | `withAutoCommit` |
| R2DBC database matrix | `TestDB`, configs and container helpers |
| Shared assertions/schema | assertion and `shared` packages |

## Recommended patterns {#patterns}

Collect database flows inside the fixture transaction. Test the deployed R2DBC driver with its matching Testcontainer. Add bounded cancellation tests and assert cleanup before the next test uses the same database.

## Integrations {#integrations}

The module exposes R2DBC SPI/pool, H2/MariaDB/MySQL/PostgreSQL R2DBC drivers, JUnit 5, and Testcontainers support for tests.

## Configuration {#configuration}

Choose `TestDB`, driver options, and optional `DatabaseConfig` per fixture. Temporary database references are restored after execution.

## Failure modes {#failures}

Collecting after the fixture closes loses transaction context. Blocking semaphore acquisition on a coroutine worker reduces test concurrency; the fixture moves that acquisition to `Dispatchers.IO`. Cleanup failures must not replace the original assertion failure.

## Operations {#operations}

Capture driver/container logs, coroutine timeout, and pool state on failure. Bound every test so a driver cancellation defect cannot hang the suite indefinitely.

## Testing {#testing}

The module tests transaction fixtures, DDL cleanup, assertions, shared schemas, and SQL behavior. Consumer tests should add rollback, cancellation, partial-flow collection, and dialect-specific cases.

## Workshops and learning path {#workshops}

Apply it after [Coroutine transactions](bluetape4k-exposed-r2dbc/coroutine-transactions.md), then follow [Cancellation and testing](bluetape4k-exposed-r2dbc/cancellation-and-testing.md).

## Limitations {#limitations}

The fixture cannot guarantee that a particular driver cancels server-side work. It does not model production pool load or replace long-running resilience tests.

<!-- release-readme-diagrams:start -->
## Release diagrams {#release-diagrams}

These diagrams are loaded directly from README assets published with the `1.12.1` release and pinned to its immutable commit. They describe this manual's released structure and runtime flows, not later Snapshot changes. Select a preview to open the SVG at the same release commit.

### Test Infrastructure Structure diagram

[![Test Infrastructure Structure diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-r2dbc-tests-diagram-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-r2dbc-tests-diagram-01.svg)

_Release README: [`exposed/r2dbc-tests/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/r2dbc-tests/README.md)_

### withTables R2DBC test lifecycle diagram

[![withTables R2DBC test lifecycle diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-r2dbc-tests-diagram-02.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-r2dbc-tests-diagram-02.svg)

_Release README: [`exposed/r2dbc-tests/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/r2dbc-tests/README.md)_

<!-- release-readme-diagrams:end -->

## Sources {#sources}

- [Test module build](../../../../exposed/r2dbc-tests/build.gradle.kts)
- [`withDb`](../../../../exposed/r2dbc-tests/src/main/kotlin/io/bluetape4k/exposed/r2dbc/tests/withDb.kt)
- [`withTables`](../../../../exposed/r2dbc-tests/src/main/kotlin/io/bluetape4k/exposed/r2dbc/tests/withTables.kt)
