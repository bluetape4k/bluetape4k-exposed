---
manualId: "bluetape4k-exposed-dao"
id: "bluetape4k-exposed-dao"
title: "Exposed DAO Extensions"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-exposed-dao"
sourceDir: "exposed/dao"
releaseRef: "1.12.1"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-dao
---

# Exposed DAO Extensions

> Identity, string-ID, generated-ID, and audit conventions for Exposed DAO entities.

## Problem {#problem}

Exposed DAO entities are transaction-bound objects. Equality, string rendering, generated-ID entity classes, and audit updates are easy to implement inconsistently. This module provides a small shared convention layer above Exposed DAO.

## When to use it {#when-to-use}

Use it when the domain persistence model intentionally uses Exposed `Entity`/`EntityClass`. Prefer record/DTO mapping through the JDBC or R2DBC repositories when values must leave the transaction boundary.

## Coordinates {#coordinates}

`io.github.bluetape4k.exposed:bluetape4k-exposed-dao`, managed by `io.github.bluetape4k:bluetape4k-dependencies:<version>`.

## Core concepts {#concepts}

- `idEquals`, `idHashCode`, and entity string builders make identity handling explicit.
- `StringEntity` and generated-ID entity families pair with core ID tables.
- `AuditableEntity` sets actor fields, but `updatedAt` is guaranteed only by audited JDBC repository updates.
- A DAO entity remains attached to the active Exposed transaction.

## Quick start {#quick-start}

```kotlin
class Customer(id: EntityID<String>) : StringEntity(id) {
    companion object : StringEntityClass<Customer>(Customers)
    var name by Customers.name
}
```

Read lazy relations and convert the entity to a detached DTO before the transaction closes.

## API by task {#api-by-task}

| Task | API |
|---|---|
| Identity equality/hash | `idEquals`, `idHashCode` |
| Diagnostic text | `toStringBuilder`, `entityToStringBuilder` |
| String IDs | `StringEntity`, `StringEntityClass` |
| Generated IDs | KSUID, ULID, Snowflake, time-based UUID entity families |
| Audit actor fields | `AuditableEntity` and typed variants |

## Recommended patterns {#patterns}

Keep DAO access and lazy traversal inside one caller-owned JDBC transaction. Map to immutable output values at that boundary. Avoid returning an `Entity` from a service and touching it later.

## Integrations {#integrations}

DAO builds on core and Exposed DAO, with JDBC available at runtime. The JDBC repository module can share the same table declarations.

## Configuration {#configuration}

No standalone configuration exists. Configure Exposed's database/transaction layer and bind `UserContext` where audit actor fields are needed.

## Failure modes {#failures}

Detached/lazy access can fail after the transaction closes. Generic entity updates do not guarantee `updatedAt`. Identity comparison before an entity has a stable ID must be treated carefully.

## Operations {#operations}

Keep transaction duration bounded and avoid logging lazy properties that trigger unexpected queries. Monitor query count when traversing relations.

## Testing {#testing}

Use a real transaction and database fixture. Assert detached DTO values after mapping, audit actor behavior, and ID-family persistence across the dialects you support.

## Workshops and learning path {#workshops}

Read [Mapping conventions](bluetape4k-exposed-core/mapping-conventions.md), then choose the [JDBC repository path](bluetape4k-exposed-jdbc.md). Exposed DAO is not the R2DBC entity model.

## Limitations {#limitations}

This module does not make DAO entities detached, reactive, or safe outside a transaction. It does not own transaction creation.

<!-- release-readme-diagrams:start -->
## Release diagrams {#release-diagrams}

These diagrams are loaded directly from README assets published with the `1.12.1` release and pinned to its immutable commit. They describe this manual's released structure and runtime flows, not later Snapshot changes. Select a preview to open the SVG at the same release commit.

### AuditableEntity UML Class Diagram

[![AuditableEntity UML Class Diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-dao-diagram-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-dao-diagram-01.svg)

_Release README: [`exposed/dao/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/dao/README.md)_

### Generated-ID DAO Support Matrix

[![Generated-ID DAO Support Matrix](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-dao-diagram-02.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-dao-diagram-02.svg)

_Release README: [`exposed/dao/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/dao/README.md)_

### Entity Helper Pairing Map

[![Entity Helper Pairing Map](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-dao-diagram-03.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-dao-diagram-03.svg)

_Release README: [`exposed/dao/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/dao/README.md)_

### Automatic field assignment diagram

[![Automatic field assignment diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-dao-sequence-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-dao-sequence-01.svg)

_Release README: [`exposed/dao/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/dao/README.md)_

<!-- release-readme-diagrams:end -->

## Sources {#sources}

- [DAO build](../../../../exposed/dao/build.gradle.kts)
- [Entity extensions](../../../../exposed/dao/src/main/kotlin/io/bluetape4k/exposed/dao/EntityExtensions.kt)
- [Auditable entity](../../../../exposed/dao/src/main/kotlin/io/bluetape4k/exposed/dao/auditable/AuditableEntity.kt)
