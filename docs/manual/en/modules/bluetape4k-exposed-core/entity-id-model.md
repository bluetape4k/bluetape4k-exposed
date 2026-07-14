---
title: Entity and ID model
description: Choose a release-backed client-generated ID strategy and keep Exposed EntityID values inside the persistence boundary.
manualId: bluetape4k-exposed-core
chapterId: entity-id-model
---

# Entity and ID model

`bluetape4k-exposed-core` extends Exposed's `IdTable` model with identifiers that an application can generate before it sends an INSERT. This is useful when a service must know the ID while building an event, a cache key, or a related row. It is different from a database sequence: the column uses Exposed `clientDefault`, so the Kotlin process creates the value.

## Choose an identifier by its contract

| Type | Storage | Generation and ordering | Use when |
|---|---|---|---|
| `KsuidTable` | `varchar(27)` | KSUID generated on the client | A sortable string ID and broad system interoperability are useful |
| `KsuidMillisTable` | `varchar(27)` | Millisecond-oriented KSUID generated on the client | The repository's millisecond KSUID convention is already established |
| `UlidTable` | `varchar(26)` | Stateful monotonic ULID generated on the client | Lexical ordering within the same millisecond matters |
| `SnowflakeIdTable` | `long` | Global Snowflake generator | Compact numeric IDs are required |
| `TimebasedUUIDTable` | UUID | Time-based UUID generated on the client | The schema and driver already use UUID columns |
| `SoftDeletedIdTable` | User-defined | Adds `is_deleted = false`; it does not choose the ID | A table needs an explicit soft-delete marker |

The base types fix the ID column and primary key. For example, `KsuidTable` always declares its ID as a 27-character `varchar`, while `UlidTable` uses 26 characters and a shared monotonic generator.

```kotlin
object Events : UlidTable("events") {
    val eventType = varchar("event_type", 80)
    val payload = text("payload")
}

transaction {
    val id = Events.insertAndGetId {
        it[eventType] = "order.accepted"
        it[payload] = "{}"
    }.value
}
```

## Entity IDs and domain IDs

Exposed wraps an `IdTable` key in `EntityID<ID>`. Keep that wrapper inside the persistence model. Records returned by a repository should normally carry the raw `ID`; DAO entities can expose it with `idValue` while they are still inside a transaction.

`HasIdentifier<ID>` uses a nullable raw ID so the same record shape can represent an unsaved object and a persisted object. Do not interpret `null` as a database-generated-ID contract unless the repository implementation actually does so.

```kotlin
data class EventRecord(
    override val id: String? = null,
    val eventType: String,
) : HasIdentifier<String>
```

## Audit and soft-delete state are separate concerns

`AuditableIdTable` adds `created_by`, `created_at`, `updated_by`, and `updated_at`. It fills `createdBy` from `UserContext` and delegates `createdAt` to the database's `CURRENT_TIMESTAMP`. Updating ordinary columns does not automatically fill the update audit fields; use an audited JDBC or R2DBC repository method.

`SoftDeletedIdTable` only adds `isDeleted`. A generic repository query does not hide deleted rows. Use the matching soft-delete repository API or write an explicit predicate.

## Failure modes

- A migration that declares a database sequence while Kotlin also supplies a client ID has two competing authorities.
- Changing an ID family after rows are stored changes column width, ordering, and possibly foreign-key types. Treat it as a schema migration, not a refactor.
- Random-looking identifiers can still leak approximate creation order when the format is time based.
- `UserContext` falls back to `system`; bind an authenticated user at the request or job boundary when audit identity matters.

## Testing and operations

Test generated IDs for uniqueness, exact encoded length, and the ordering property you rely on. For a high-write table, observe index locality and page splits under the production database rather than assuming that a time-based format solves every index problem.

Continue with [Mapping conventions](mapping-conventions.md), then choose the [JDBC](../bluetape4k-exposed-jdbc.md) or [R2DBC](../bluetape4k-exposed-r2dbc.md) execution path.

## Sources

- [Column generation extensions](../../../../../exposed/core/src/main/kotlin/io/bluetape4k/exposed/core/ColumnExtensions.kt)
- [KSUID table](../../../../../exposed/core/src/main/kotlin/io/bluetape4k/exposed/core/dao/id/KsuidTable.kt)
- [ULID table](../../../../../exposed/core/src/main/kotlin/io/bluetape4k/exposed/core/dao/id/UlidTable.kt)
- [Soft-delete table](../../../../../exposed/core/src/main/kotlin/io/bluetape4k/exposed/core/dao/id/SoftDeletedIdTable.kt)
- [Identifier contract](../../../../../exposed/core/src/main/kotlin/io/bluetape4k/exposed/core/HasIdentifier.kt)
