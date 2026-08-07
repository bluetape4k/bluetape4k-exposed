---
manualId: "bluetape4k-exposed-fastjson2"
id: "bluetape4k-exposed-fastjson2"
title: "Exposed Fastjson2 Serialization"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-exposed-fastjson2"
sourceDir: "exposed/fastjson2"
releaseRef: "1.12.1"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-fastjson2
---

# Exposed Fastjson2 Serialization

> Library module

## Problem {#problem}

This module maps Kotlin values to Exposed JSON and JSONB columns with Fastjson2. It also supplies typed row readers and dialect-aware `contains`, `exists`, and `extract` expressions. The database stores JSON, not the Kotlin type or serializer configuration, so the application must own that compatibility contract.

## When to use it {#when-to-use}

Use it when Fastjson2 is already the application's JSON stack and database rows must be read as domain values. Prefer Jackson 2 or 3 when their annotation/module ecosystem is the stronger compatibility requirement. Do not select a codec from a generic performance claim; measure the payloads and configuration used in production.

## Coordinates {#coordinates}

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-fastjson2")
}
```

## Core concepts {#concepts}

- `fastjson<T>` uses the dialect JSON type; `fastjsonb<T>` uses JSONB and reports binary format.
- `DefaultFastjsonSerializer` delegates to the shared Fastjson2 serializer.
- `ResultRow` and R2DBC `Readable` extensions deserialize typed values, objects, and arrays.
- `contains`, `exists`, and `extract` render through the active Exposed dialect; supported paths differ by database.

## Quick start {#quick-start}

```kotlin
data class Settings(val theme: String, val alerts: Boolean)

object Accounts : LongIdTable("accounts") {
    val settings = fastjson<Settings>("settings")
}

transaction {
    Accounts.insert { it[settings] = Settings("dark", true) }
    val stored = Accounts.selectAll().single()[Accounts.settings]
}
```

## API by task {#api-by-task}

| Task | Stable 1.11 API |
| --- | --- |
| JSON/JSONB column | `fastjson`, `fastjsonb`, `FastjsonColumnType`, `FastjsonBColumnType` |
| Typed row read | `getFastjson`, `getFastjsonOrNull` |
| Dynamic tree read | `getFastjsonObject`, `getFastjsonArray` and nullable variants |
| JSON predicates | `contains`, `exists` |
| Path extraction | `extract<T>` |

## Recommended patterns {#patterns}

Keep one serializer policy for every writer and reader of a column. Add fields with defaults when old rows must remain readable. For breaking shape or type changes, deploy a reader that accepts both shapes, backfill rows, and remove the old reader only after the migration is complete.

## Integrations {#integrations}

The column DSL works with Exposed core and can be read from JDBC `ResultRow` or R2DBC `Readable`. JSON SQL operators depend on the selected database dialect; test every predicate against the production database rather than assuming PostgreSQL behavior elsewhere.

## Configuration {#configuration}

Pass a custom `FastjsonSerializer` when naming, polymorphism, date/time, or unknown-field behavior differs from the default. Treat that configuration as persisted-data schema: changing it can make existing rows unreadable even when the SQL column is unchanged.

## Failure modes {#failures}

- A serializer returning `null` for non-null `T` raises `IllegalArgumentException`.
- Malformed or incompatible JSON fails while reading the row.
- An unexpected driver value type raises `IllegalStateException`.
- A JSON path or operator unsupported by the dialect fails at SQL generation or execution.
- A Kotlin rename without an alias/default can break old rows.

## Operations {#operations}

Log codec failures with table, column, and record identity, but never dump sensitive JSON. Track decode failures during a dual-reader migration. JSONB may improve database-side querying, but it does not remove the need to plan indexes and validate query plans.

## Testing {#testing}

Round-trip representative payloads, missing and unknown fields, nullable columns, malformed JSON, and the oldest supported stored shape. Run dialect tests for JSON and JSONB plus each `contains`/`exists`/`extract` query used by the application.

```bash
./gradlew :bluetape4k-exposed-fastjson2:test
```

## Workshops and learning path {#workshops}

Start with the [serialization and encryption guide](../guides/serialization-and-encryption.md). Then inspect the module tests for JSON/JSONB round trips and row-reader behavior, and continue with [transaction boundaries](../guides/transaction-boundaries.md) before placing codec calls in a repository.

## Limitations {#limitations}

The module does not version JSON documents, migrate stored rows, choose indexes, or guarantee that JSON functions behave identically across dialects. Fastjson2, Jackson 2, and Jackson 3 modules are alternatives for a column contract; switching one for another requires stored-data compatibility proof.

<!-- release-readme-diagrams:start -->
## Release diagrams {#release-diagrams}

These diagrams are loaded directly from README assets published with the `1.12.1` release and pinned to its immutable commit. They describe this manual's released structure and runtime flows, not later Snapshot changes. Select a preview to open the SVG at the same release commit.

### Fastjson2 JSON column boundary

[![Fastjson2 JSON column boundary](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-fastjson2-diagram-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-fastjson2-diagram-01.svg)

_Release README: [`exposed/fastjson2/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/fastjson2/README.md)_

### Fastjson2 JSON round trip

[![Fastjson2 JSON round trip](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-fastjson2-flow-02.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-fastjson2-flow-02.svg)

_Release README: [`exposed/fastjson2/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/fastjson2/README.md)_

<!-- release-readme-diagrams:end -->

## Sources {#sources}

- [Gradle build file](../../../../exposed/fastjson2/build.gradle.kts)
- [JSON column type](../../../../exposed/fastjson2/src/main/kotlin/io/bluetape4k/exposed/core/fastjson2/FastjsonColumnType.kt)
- [JSONB column type](../../../../exposed/fastjson2/src/main/kotlin/io/bluetape4k/exposed/core/fastjson2/FastjsonBColumnType.kt)
- [JSON expressions](../../../../exposed/fastjson2/src/main/kotlin/io/bluetape4k/exposed/core/fastjson2/JsonFunctions.kt)
- [Round-trip tests](../../../../exposed/fastjson2/src/test/kotlin/io/bluetape4k/exposed/core/fastjson2/FastjsonColumnTest.kt)
