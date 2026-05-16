# DeclaredExposedR2dbcQuery: Silent Error Swallowing in toSqlArg and ID Column Fallback

**Date**: 2026-05-16  
**Issues**: #85, #87  
**Module**: `exposed-spring-boot-r2dbc`  
**File**: `DeclaredExposedR2dbcQuery.kt`

## Issue #85 — toSqlArg Silently Swallows resolveColumnType Errors

### Root Cause

```kotlin
val columnType = runCatching {
    resolveColumnType(value::class as KClass<Any>, defaultType = TextColumnType())
}.getOrElse { TextColumnType() }  // exception silently dropped
```

Any exception from `resolveColumnType` was swallowed with no log, making it impossible
to diagnose type mapping failures.

### Fix

Replace with try/catch that logs a warning before falling back:

```kotlin
val columnType = try {
    resolveColumnType(value::class as KClass<Any>, defaultType = TextColumnType())
} catch (e: Exception) {
    log.warn(e) { "Cannot resolve column type for ${value::class.simpleName}, falling back to TextColumnType" }
    TextColumnType()
}
```

**Note**: `log.warn(e) { "..." }` requires `import io.bluetape4k.logging.warn` in addition to
`import io.bluetape4k.logging.coroutines.KLoggingChannel`. Without it, the compiler resolves
to SLF4J `warn(String, Throwable)` and the lambda form fails.

## Issue #87 — Broad Exception Catch in ID Column Fallback

### Root Cause

```kotlin
try {
    row.get(idColumnName, Any::class.java)
} catch (_: Exception) {  // catches ALL exceptions, including unexpected ones
    row.get(0, Any::class.java)
}
```

A broad `Exception` catch silently swallows serious errors (connection failures,
serialization errors) and falls back to ordinal 0, masking the real problem.

### Fix

Catch only `IllegalArgumentException` (column not found by name), rethrow others:

```kotlin
try {
    row.get(idColumnName, Any::class.java)
} catch (e: IllegalArgumentException) {
    row.get(0, Any::class.java)
} catch (e: Exception) {
    throw IllegalStateException(
        "Failed to read id column '$idColumnName' from result row in '${queryMethod.name}'", e
    )
}
```

## Future Guidance

- Never use `runCatching { }.getOrElse { default }` without a log — use try/catch with warning.
- Catch the narrowest exception type that covers the failure case; rethrow unknowns.
- `io.bluetape4k.logging.warn` extension must be imported for `log.warn(e) { }` form with
  KLoggingChannel to work; without it the compiler resolves to the SLF4J method instead.
