# ExposedJdbcBatchReader / ExposedR2dbcBatchReader: Unsafe restoreFrom Cast

**Date**: 2026-05-16  
**Issue**: #86  
**Module**: `exposed-batch`  
**Files**: `ExposedJdbcBatchReader.kt`, `ExposedR2dbcBatchReader.kt`

## Root Cause

`restoreFrom(checkpoint: Any)` used a bare unchecked cast:

```kotlin
@Suppress("UNCHECKED_CAST")
override suspend fun restoreFrom(checkpoint: Any) {
    val key = checkpoint as K   // ClassCastException with no context
```

When `checkpoint` is the wrong type (e.g., a serialized String for a Long key after schema
change or deserialization mismatch), the `ClassCastException` would have no diagnostic
information: no keyColumn name, no actual type, no expected type.

## Fix

Catch `ClassCastException` and rethrow as `IllegalArgumentException` with full context:

```kotlin
val key = try {
    checkpoint as K
} catch (e: ClassCastException) {
    throw IllegalArgumentException(
        "restoreFrom: checkpoint type mismatch — expected type compatible with " +
            "keyColumn '${keyColumn.name}', got ${checkpoint::class.qualifiedName}",
        e
    )
}
```

## Why Not keyClass.isInstance()

Adding `keyClass: Class<K>` as a constructor parameter would break all existing callers.
For concrete key types (Long, Int, String, UUID), the JVM checks the cast at the `as K`
site, so catching `ClassCastException` provides equivalent protection without API disruption.

For erased generic types (e.g., `K = List<String>`), the cast passes at runtime — but this
is an unusual key type for keyset pagination and acceptable for this fix scope.

## Future Guidance

- Always wrap bare unchecked casts in try/catch with diagnostic context (value, actual type,
  expected type, and identifying name like column name or field name).
- For public API that accepts `Any` parameters and casts internally, prefer adding a typed
  class parameter (`keyClass: Class<K>`) via a reified companion `invoke` factory to enable
  `isInstance` checks without breaking existing callers.
