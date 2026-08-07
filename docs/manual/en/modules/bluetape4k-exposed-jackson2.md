---
manualId: "bluetape4k-exposed-jackson2"
id: "bluetape4k-exposed-jackson2"
title: "Exposed Jackson 2 Serialization"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-exposed-jackson2"
sourceDir: "exposed/jackson2"
releaseRef: "1.12.1"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-jackson2
---

# Exposed Jackson 2 Serialization

> Library module

## Problem {#problem}

This module maps Kotlin values to Exposed JSON and JSONB columns with Jackson 2. It provides typed `ResultRow`/R2DBC `Readable` access and dialect-aware JSON expressions while leaving document-version compatibility to the application.

## When to use it {#when-to-use}

Choose it when the application already uses Jackson 2 annotations, modules, and `com.fasterxml.jackson` types. It is the lower-risk choice for an existing Jackson 2 codebase. Do not mix Jackson 2 and Jackson 3 types in one column contract.

## Coordinates {#coordinates}

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-jackson2")
}
```

## Core concepts {#concepts}

- `jackson<T>` maps the dialect JSON type; `jacksonb<T>` maps JSONB.
- `DefaultJacksonSerializer` is the module default; overloads accept a custom Jackson 2 serializer.
- `getJackson`/`getJsonNode` read typed or tree values from JDBC and R2DBC rows.
- `contains`, `exists`, and `extract<T>` are SQL expressions whose support is dialect-specific.

## Quick start {#quick-start}

```kotlin
data class Preferences(val locale: String = "en", val digest: Boolean = true)

object Users : LongIdTable("users") {
    val preferences = jackson<Preferences>("preferences")
}

transaction {
    Users.insert { it[preferences] = Preferences("ko", false) }
    val value = Users.selectAll().single()[Users.preferences]
}
```

## API by task {#api-by-task}

| Task | Stable 1.11 API |
| --- | --- |
| JSON/JSONB | `jackson`, `jacksonb`, `JacksonColumnType`, `JacksonBColumnType` |
| Typed read | `getJackson`, `getJacksonOrNull` |
| Tree read | `getJsonNode`, `getJsonNodeOrNull` |
| Conditions | `contains`, `exists` |
| Extraction | `extract<T>` |

## Recommended patterns {#patterns}

Freeze naming, Kotlin module, date/time, polymorphism, and unknown-property policy per persisted column. For an incompatible change, introduce a tolerant reader, backfill data, then remove the old shape. Keep JSON domain models separate from API DTOs when their evolution schedules differ.

## Integrations {#integrations}

The module exposes Exposed core column types and optional JDBC/DAO/R2DBC readers. JSON query operators delegate to the active dialect. Test PostgreSQL JSONB behavior separately from H2 feedback tests.

## Configuration {#configuration}

Pass a custom `JacksonSerializer` to the column or reader overload when defaults are unsuitable. Serializer settings are persisted-data behavior, not cosmetic runtime configuration. A changed subtype id or property naming strategy can invalidate existing rows.

## Failure modes {#failures}

- Missing required constructor data, unknown subtypes, or incompatible scalar types fail during deserialization.
- A non-null mapping whose serializer returns `null` fails immediately.
- Unsupported driver value types fail at the column boundary.
- Unsupported JSON paths/operators fail in generated SQL or at execution.
- Replacing this module with Jackson 3 without a compatibility test can strand stored JSON.

## Operations {#operations}

Record table, column, record id, and exception class for decode failures without logging sensitive documents. Observe failures while rolling out a new reader. Review JSONB indexes and query plans independently of serializer choice.

## Testing {#testing}

Round-trip old and new payload fixtures, defaults, unknown fields, nullable values, polymorphic values, and malformed JSON. Run every JSON predicate against each production dialect.

```bash
./gradlew :bluetape4k-exposed-jackson2:test
```

## Workshops and learning path {#workshops}

Use the [serialization and encryption guide](../guides/serialization-and-encryption.md) to compare codecs and migration cost. Continue with module tests for JSON/JSONB and row readers, then apply the selected mapping inside the repository path described by [transaction boundaries](../guides/transaction-boundaries.md).

## Limitations {#limitations}

The module does not version documents, migrate rows, select indexes, or make dialect JSON functions portable. Jackson 2 and Jackson 3 have different package/type ecosystems; source similarity is not proof of stored-data or application compatibility.

<!-- release-readme-diagrams:start -->
## Release diagrams {#release-diagrams}

These diagrams are loaded directly from README assets published with the `1.12.1` release and pinned to its immutable commit. They describe this manual's released structure and runtime flows, not later Snapshot changes. Select a preview to open the SVG at the same release commit.

### Jackson 2 JSON column boundary

[![Jackson 2 JSON column boundary](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jackson2-diagram-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jackson2-diagram-01.svg)

_Release README: [`exposed/jackson2/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/jackson2/README.md)_

### Jackson 2 JSON round trip

[![Jackson 2 JSON round trip](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jackson2-flow-02.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jackson2-flow-02.svg)

_Release README: [`exposed/jackson2/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/jackson2/README.md)_

<!-- release-readme-diagrams:end -->

## Sources {#sources}

- [Gradle build file](../../../../exposed/jackson2/build.gradle.kts)
- [JSON column type](../../../../exposed/jackson2/src/main/kotlin/io/bluetape4k/exposed/core/jackson/JacksonColumnType.kt)
- [JSONB column type](../../../../exposed/jackson2/src/main/kotlin/io/bluetape4k/exposed/core/jackson/JacksonBColumnType.kt)
- [Row readers](../../../../exposed/jackson2/src/main/kotlin/io/bluetape4k/exposed/core/jackson/ResultRowExtensions.kt)
- [Column tests](../../../../exposed/jackson2/src/test/kotlin/io/bluetape4k/exposed/core/jackson/JacksonColumnTest.kt)
