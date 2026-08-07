---
manualId: "bluetape4k-exposed-jdbc-tests"
id: "bluetape4k-exposed-jdbc-tests"
title: "Exposed JDBC Test Support"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-exposed-jdbc-tests"
sourceDir: "exposed/jdbc-tests"
releaseRef: "1.12.1"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-jdbc-tests
---

# Exposed JDBC Test Support

> Reusable JDBC database, transaction, schema, table, assertion, and Testcontainers fixtures.

## Problem {#problem}

Database tests need deterministic connection reuse, per-database serialization, transaction setup, schema/table cleanup, and real dialects. Reimplementing that harness in every module makes failures harder to compare.

## When to use it {#when-to-use}

Use it in tests for Exposed JDBC code and shared table/mapping contracts. Keep it out of production runtime dependencies.

## Coordinates {#coordinates}

`testImplementation("io.github.bluetape4k.exposed:bluetape4k-exposed-jdbc-tests")`, managed by the central BOM.

## Core concepts {#concepts}

`withDb` serializes access per `TestDB`, caches the connection, opens a `maxAttempts = 1` transaction, and restores temporary configuration. `withTables` creates fixtures and attempts deterministic cleanup, including a top-level fallback. `withSchemas` commits before cascade cleanup.

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
| Transaction fixture | `withDb` |
| Table lifecycle | `withTables` |
| Schema lifecycle | `withSchemas` |
| Auto-commit probe | `withAutoCommit` |
| Database matrix | `TestDB`, `TestDBConfig`, container helpers |
| Assertions/shared schemas | assertion and `shared` packages |

## Recommended patterns {#patterns}

Use H2 for fast DSL checks only when dialect behavior is irrelevant. Run the deployed database through Testcontainers for SQL, type, isolation, and migration behavior. Let the fixture own cleanup rather than sharing mutable tables between tests.

## Integrations {#integrations}

The module exposes JUnit 5, bluetape4k Testcontainers, and MariaDB/MySQL/PostgreSQL container support as test APIs. Drivers remain compile-time test dependencies.

## Configuration {#configuration}

Select a `TestDB` and optional `DatabaseConfig` override per call. Temporary configuration is restored after the fixture.

## Failure modes {#failures}

Parallel tests against the same database without the fixture can race on DDL. Assuming cleanup always succeeds hides dialect/connection failures. A container test without a bounded startup policy can make the suite flaky.

## Operations {#operations}

These are test-only operations. Capture container logs and database identity on failure; do not expose fixture credentials outside the test process.

## Testing {#testing}

The support module tests its own serialization, transaction, configuration restoration, schema/table cleanup, assertions, and DDL behavior.

## Workshops and learning path {#workshops}

Use it with [JDBC operations and testing](bluetape4k-exposed-jdbc/operations-testing.md) after learning [transaction ownership](bluetape4k-exposed-jdbc/transaction-ownership.md).

## Limitations {#limitations}

The harness does not reproduce production pool load or replace migration/chaos tests. The per-database semaphore intentionally serializes conflicting fixture work.

<!-- release-readme-diagrams:start -->
## Release diagrams {#release-diagrams}

These diagrams are loaded directly from README assets published with the `1.12.1` release and pinned to its immutable commit. They describe this manual's released structure and runtime flows, not later Snapshot changes. Select a preview to open the SVG at the same release commit.

### JDBC test dialect coverage

[![JDBC test dialect coverage](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jdbc-tests-diagram-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jdbc-tests-diagram-01.svg)

_Release README: [`exposed/jdbc-tests/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/jdbc-tests/README.md)_

### JDBC test lifecycle

[![JDBC test lifecycle](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jdbc-tests-sequence-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jdbc-tests-sequence-01.svg)

_Release README: [`exposed/jdbc-tests/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/jdbc-tests/README.md)_

<!-- release-readme-diagrams:end -->

## Sources {#sources}

- [Test module build](../../../../exposed/jdbc-tests/build.gradle.kts)
- [`withDb`](../../../../exposed/jdbc-tests/src/main/kotlin/io/bluetape4k/exposed/tests/WithDB.kt)
- [`withTables`](../../../../exposed/jdbc-tests/src/main/kotlin/io/bluetape4k/exposed/tests/WithTables.kt)
