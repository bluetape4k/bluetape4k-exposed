# ExposedEventPublicationRepository.insertArchive: TOCTOU Existence-Check-Then-Insert

**Date**: 2026-05-16  
**Issue**: #88  
**Module**: `exposed-spring-modulith`  
**File**: `ExposedEventPublicationRepository.kt`

## Root Cause

`insertArchive` used a SELECT + INSERT two-step pattern:

```kotlin
val exists = archiveTable.selectAll()
    .where { archiveTable.id eq archiveId }
    .empty()
    .not()

if (exists) return  // TOCTOU window: another thread can INSERT between check and insert
archiveTable.insert { ... }
```

Between the SELECT and the INSERT, another transaction can archive the same publication,
causing a unique constraint violation on the second INSERT. The SELECT guard provides no
atomicity guarantee.

## Fix

Remove the existence check. Attempt the INSERT directly and catch SQL state `23xxx`
(integrity constraint violation — unique key already exists) as an idempotent condition:

```kotlin
try {
    archiveTable.insert { archive -> ... }
} catch (e: ExposedSQLException) {
    // SQL state 23xxx = integrity constraint violation (duplicate key)
    if (e.sqlState?.startsWith("23") == true) return
    throw e
}
```

SQL state prefixes:
- `23505` — unique_violation (PostgreSQL, H2)
- `23000` — integrity constraint violation (MySQL)

Both start with `23`, so `startsWith("23")` covers all supported databases portably.

## Future Guidance

- Never guard an INSERT with a SELECT to check existence — this is always a TOCTOU race.
- Prefer INSERT with duplicate-key handling (SQL state 23xxx, `ON CONFLICT DO NOTHING`,
  or DB-specific `INSERT IGNORE`) for idempotent archive/upsert operations.
- `ExposedSQLException` extends `java.sql.SQLException` and exposes `getSQLState()` via
  the standard JDBC interface — use it for portable constraint-violation detection.
