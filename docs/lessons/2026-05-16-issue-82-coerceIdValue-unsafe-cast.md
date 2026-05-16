# DeclaredExposedQuery.coerceIdValue: Bare Cast → Guarded Throw

**Date**: 2026-05-16  
**Issue**: #82  
**Module**: `exposed-spring-boot-jdbc`  
**File**: `DeclaredExposedQuery.coerceIdValue`

## Root Cause

The `else` branch in `coerceIdValue` used a bare `rawId as ID` unchecked cast:

```kotlin
else -> rawId as ID  // ClassCastException with no context
```

The Number branches also fell through to `rawId as ID` when `rawId` was not a Number:
```kotlin
Long::class.java -> if (rawId is Number) rawId.toLong() as ID else rawId as ID
```

In both cases, the `ClassCastException` would be thrown far from the call site with no
information about which entity type, ID type, or raw value caused the failure.

## Fix

Replace bare casts with `IllegalStateException` containing diagnostic information:

```kotlin
Long::class.java -> if (rawId is Number) rawId.toLong() as ID
    else throw IllegalStateException(
        "Cannot coerce id value '$rawId' (${rawId::class.java.simpleName}) to Long"
    )
// ...
else -> throw IllegalStateException(
    "Cannot coerce id value '$rawId' (${rawId::class.java.simpleName}) to entity id type " +
        "${idType.simpleName}. Add a coercion rule in DeclaredExposedQuery.coerceIdValue()."
)
```

The top-level `idType.isInstance(rawId)` guard already handles the common case correctly;
the `when` branches only fire when the types don't match.

## Future Guidance

- Never use bare `rawId as ID` where `ID` is an erased generic type parameter.
  Prefer `idType.isInstance(rawId)` guard + explicit cast, or throw descriptive error.
- Error messages should include: the raw value, its actual type, the expected type,
  and ideally the entity name to help locate the issue.
