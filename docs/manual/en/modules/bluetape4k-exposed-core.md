---
manualId: "bluetape4k-exposed-core"
id: "bluetape4k-exposed-core"
title: "Exposed Core Library"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-exposed-core"
sourceDir: "exposed/core"
releaseRef: "1.12.1"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-core
---

# Exposed Core Library

> Shared table, identifier, result mapping, audit, paging, and column-type primitives for both JDBC and R2DBC paths.

## Problem {#problem}

Exposed supplies its DSL and table model. Production projects still repeat generated-ID declarations, `ResultRow` conversions, paging values, audit columns, soft-delete flags, and specialized column types. This module centralizes those cross-runtime conventions without opening a database connection or transaction.

## When to use it {#when-to-use}

Use it as the common foundation for `bluetape4k-exposed-jdbc` or `bluetape4k-exposed-r2dbc`. It is also useful when a shared schema module must compile independently of the final driver path.

## Coordinates {#coordinates}

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-core")
}
```

## Core concepts {#concepts}

- ID table families use client-side generators through `clientDefault`.
- `ExposedPage` carries page metadata and content; repository `findPage` obtains count and content separately.
- `AuditableIdTable` fills `createdBy` on insert. Only audited repository update methods guarantee `updatedAt` and `updatedBy`.
- `UserContext` resolves `ScopedValue`, then thread-local state, then `system`.
- Soft delete is a table convention; ordinary repository reads do not silently filter deleted rows.

## Quick start {#quick-start}

```kotlin
object Events : UlidTable("events") {
    val payload = varchar("payload", 400)
}
```

The generated value is a client default, so application code can build an insert without delegating ID generation to a database sequence.

## API by task {#api-by-task}

| Task | Stable 1.11 API |
|---|---|
| Generated IDs | `KsuidTable`, `KsuidMillisTable`, `UlidTable`, `SnowflakeIdTable`, `TimebasedUUIDTable` |
| Soft-delete schema | `SoftDeletedIdTable` |
| Audit schema/context | `AuditableIdTable`, `AuditableIntIdTable`, `AuditableLongIdTable`, `AuditableUUIDTable`, `UserContext` |
| Paging value | `ExposedPage` |
| Row/column helpers | `ResultRowExtensions`, `ColumnExtensions`, `ExposedColumnSupports` |
| Specialized data | compressed, serialized, INET, phone, and blob column helpers |

## Recommended patterns {#patterns}

Keep table declarations and pure row mappers in a shared persistence model. Let a service or framework transaction boundary choose JDBC or R2DBC. Use one ID strategy per aggregate and record its ordering and storage properties in the schema decision.

## Integrations {#integrations}

The JDBC, R2DBC, DAO, serialization, measurement, cache, and database-adapter modules build on this foundation. Optional column types require their corresponding runtime libraries.

## Configuration {#configuration}

Core has no connection configuration. Configure ID generators and optional codecs in application code, and bind `UserContext` at the request or job boundary.

## Failure modes {#failures}

- Reading a generated ID as database-generated can produce wrong migration assumptions.
- Calling a generic update on an auditable table does not populate the audited update fields.
- Relying on thread-local user state across coroutine dispatcher hops loses context; use `withCoroutineUser`/`asContextElement`.
- Treating `findPage` count and content as one snapshot is unsafe unless the surrounding transaction/isolation supplies that guarantee.

## Operations {#operations}

Persist audit timestamps in UTC, inspect ID index locality for high-write tables, and make soft-delete retention/purge an explicit operational policy.

## Testing {#testing}

Test table defaults against the actual dialects you deploy. The repository uses JDBC test support for core integration tests, including H2, MariaDB, MySQL, PostgreSQL, and pgjdbc-ng coverage where applicable.

## Workshops and learning path {#workshops}

Read [Entity and ID model](bluetape4k-exposed-core/entity-id-model.md) and [Mapping conventions](bluetape4k-exposed-core/mapping-conventions.md), then continue to [transaction boundaries](../guides/transaction-boundaries.md).

## Limitations {#limitations}

Core does not own transactions, connections, Spring beans, driver selection, or repository lifecycle. Release 1.11 documentation must not rely on later develop-only DDD APIs.

<!-- release-readme-diagrams:start -->
## Release diagrams {#release-diagrams}

These diagrams are loaded directly from README assets published with the `1.12.1` release and pinned to its immutable commit. They describe this manual's released structure and runtime flows, not later Snapshot changes. Select a preview to open the SVG at the same release commit.

### Auditable UML Class Diagram

[![Auditable UML Class Diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-core-diagram-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-core-diagram-01.svg)

_Release README: [`exposed/core/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/core/README.md)_

### Column Type Pipeline Map

[![Column Type Pipeline Map](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-core-diagram-02.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-core-diagram-02.svg)

_Release README: [`exposed/core/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/core/README.md)_

### IdTable Selection Matrix

[![IdTable Selection Matrix](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-core-diagram-03.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-core-diagram-03.svg)

_Release README: [`exposed/core/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/core/README.md)_

### ExposedPage Data Model

[![ExposedPage Data Model](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-core-diagram-04.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-core-diagram-04.svg)

_Release README: [`exposed/core/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/core/README.md)_

### UserContext — Managing the Current User diagram

[![UserContext — Managing the Current User diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-core-sequence-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-core-sequence-01.svg)

_Release README: [`exposed/core/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/core/README.md)_

<!-- release-readme-diagrams:end -->

## Sources {#sources}

- [Core build](../../../../exposed/core/build.gradle.kts)
- [Column extensions](../../../../exposed/core/src/main/kotlin/io/bluetape4k/exposed/core/ColumnExtensions.kt)
- [User context](../../../../exposed/core/src/main/kotlin/io/bluetape4k/exposed/core/auditable/UserContext.kt)
- [Auditable table](../../../../exposed/core/src/main/kotlin/io/bluetape4k/exposed/core/auditable/AuditableIdTable.kt)
