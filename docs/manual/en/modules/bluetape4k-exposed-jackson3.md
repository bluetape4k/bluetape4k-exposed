---
manualId: "bluetape4k-exposed-jackson3"
id: "bluetape4k-exposed-jackson3"
title: "Exposed Jackson 3 Serialization"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-exposed-jackson3"
sourceDir: "exposed/jackson3"
releaseRef: "1.12.1"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-jackson3
---

# Exposed Jackson 3 Serialization

> Library module

## Problem {#problem}

This module maps Kotlin values to Exposed JSON and JSONB columns with the Jackson 3 APIs present in release 1.12.1. It mirrors the JSON column, row-reader, and dialect-expression roles of the Jackson 2 module under the `io.bluetape4k.exposed.core.jackson3` package.

## When to use it {#when-to-use}

Choose it when the application has adopted Jackson 3 and `tools.jackson` types. For an existing Jackson 2 service, migrate application modules and stored JSON deliberately instead of changing this dependency in isolation.

## Coordinates {#coordinates}

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-jackson3")
}
```

## Core concepts {#concepts}

- `jackson<T>` and `jacksonb<T>` use the stable `core.jackson3` package.
- `DefaultJacksonSerializer` supplies the Jackson 3 default; overloads accept a custom serializer.
- `getJackson`/`getJsonNode` support JDBC `ResultRow` and R2DBC `Readable`.
- `contains`, `exists`, and `extract<T>` delegate SQL rendering to the active dialect.

## Quick start {#quick-start}

```kotlin
import io.bluetape4k.exposed.core.jackson3.jacksonb

data class Profile(val displayName: String, val labels: List<String> = emptyList())

object Members : LongIdTable("members") {
    val profile = jacksonb<Profile>("profile")
}

transaction {
    Members.insert { it[profile] = Profile("Ada", listOf("admin")) }
    val value = Members.selectAll().single()[Members.profile]
}
```

## API by task {#api-by-task}

| Task | Stable 1.11 API |
| --- | --- |
| JSON/JSONB | `core.jackson3.jackson`, `jacksonb`, `JacksonColumnType`, `JacksonBColumnType` |
| Typed read | `getJackson`, `getJacksonOrNull` |
| Tree read | `getJsonNode`, `getJsonNodeOrNull` |
| Conditions | `contains`, `exists` |
| Extraction | `extract<T>` |

## Recommended patterns {#patterns}

Use only APIs verified in the 1.12.1 tag; later develop additions are outside this manual. Freeze property naming, modules, subtype ids, and unknown-field policy per column. Prove old rows can be read before switching a writer from Jackson 2 to Jackson 3.

## Integrations {#integrations}

The module integrates with Exposed core and optional JDBC/DAO/R2DBC row paths. It does not bridge `com.fasterxml.jackson` and `tools.jackson` tree/model types. JSON SQL behavior remains database-specific.

## Configuration {#configuration}

Pass the Jackson 3 `JacksonSerializer` explicitly when the default mapper policy is unsuitable. Keep the mapper configuration with the persistence boundary and version it like schema. A serializer change can be a data migration even when generated DDL does not change.

## Failure modes {#failures}

- Importing the Jackson 2 package produces the wrong types even when function names match.
- Missing constructor values, subtype ids, or modules fail during decode.
- A non-null mapping that decodes to `null` fails immediately.
- Unsupported JSON functions or paths fail per dialect.
- A writer migration without old-row fixtures can introduce irreversible incompatibility.

## Operations {#operations}

Track decode failures during migration and retain rollback capability until old rows and writers are covered. Log identifiers, not document bodies. Review JSONB indexes and query plans using production-shaped data.

## Testing {#testing}

Test the 1.12.1 Jackson 3 imports at compile time. Round-trip current and legacy fixtures, Kotlin defaults, unknown fields, nullable values, tree reads, malformed JSON, and every database-side JSON expression.

```bash
./gradlew :bluetape4k-exposed-jackson3:test
```

## Workshops and learning path {#workshops}

Read the [serialization and encryption guide](../guides/serialization-and-encryption.md), then compare the Jackson 2 and Jackson 3 module tests with your stored fixtures. Continue with [transaction boundaries](../guides/transaction-boundaries.md) before integrating the mapping into repositories.

## Limitations {#limitations}

This module does not automate a Jackson 2 migration, rewrite stored JSON, or make the two package ecosystems interchangeable. It does not version documents or make dialect JSON functions portable. This 1.11 manual intentionally excludes APIs added only after the tag.

<!-- release-readme-diagrams:start -->
## Release diagrams {#release-diagrams}

These diagrams are loaded directly from README assets published with the `1.12.1` release and pinned to its immutable commit. They describe this manual's released structure and runtime flows, not later Snapshot changes. Select a preview to open the SVG at the same release commit.

### Jackson 3 JSON column boundary

[![Jackson 3 JSON column boundary](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jackson3-diagram-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jackson3-diagram-01.svg)

_Release README: [`exposed/jackson3/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/jackson3/README.md)_

### Jackson 3 JSON round trip

[![Jackson 3 JSON round trip](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jackson3-flow-02.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jackson3-flow-02.svg)

_Release README: [`exposed/jackson3/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/jackson3/README.md)_

<!-- release-readme-diagrams:end -->

## Sources {#sources}

- [Gradle build file](../../../../exposed/jackson3/build.gradle.kts)
- [JSON column type](../../../../exposed/jackson3/src/main/kotlin/io/bluetape4k/exposed/core/jackson3/JacksonColumnType.kt)
- [JSONB column type](../../../../exposed/jackson3/src/main/kotlin/io/bluetape4k/exposed/core/jackson3/JacksonBColumnType.kt)
- [Row readers](../../../../exposed/jackson3/src/main/kotlin/io/bluetape4k/exposed/core/jackson3/ResultRowExtensions.kt)
- [Stable tests](../../../../exposed/jackson3/src/test/kotlin/io/bluetape4k/exposed/core/jackson3/JacksonColumnTest.kt)
