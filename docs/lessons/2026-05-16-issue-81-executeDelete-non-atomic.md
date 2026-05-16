# executeDelete: SELECT+DELETE Non-Atomic → Single deleteWhere

**Date**: 2026-05-16  
**Issue**: #81  
**Module**: `exposed-spring-boot-jdbc`  
**File**: `PartTreeExposedQuery.executeDelete`

## Root Cause

The original implementation fetched all matching entities, then deleted them individually:

```kotlin
// BEFORE — non-atomic, N+1 operations
private fun executeDelete(op: Op<Boolean>): Long {
    val entities = entityClass.find { op }.toList()   // SELECT *
    entities.forEach { it.delete() }                   // N × DELETE by PK
    return entities.size.toLong()
}
```

Problems:
1. **Non-atomic**: A concurrent INSERT between SELECT and DELETE is not deleted.
2. **N+1 queries**: One SELECT + N individual DELETEs.
3. **Returns fetch count** (entities loaded) not actual delete count.

## Fix

Use a single `DELETE WHERE` SQL expression:

```kotlin
// AFTER — atomic, 1 operation, returns actual delete count
private fun executeDelete(op: Op<Boolean>): Long =
    entityInformation.table.deleteWhere { op }.toLong()
```

The R2DBC counterpart (`PartTreeExposedR2dbcQuery`) already used this pattern:
```kotlin
partTree.isDelete -> table.deleteWhere { op }.toLong()
```

## Verification

- `exposed-spring-boot-jdbc` test suite: all passing after fix
- Compile clean (no `deleteWhere` API mismatch)

## Future Guidance

- Prefer `table.deleteWhere { condition }` over DAO `find + forEach { delete() }` when the
  DAO lifecycle hooks (e.g. `Entity.delete()` override) are not needed.
- The R2DBC implementation should always be the reference for the JDBC counterpart;
  misalignment between the two hints at a bug in one of them.
