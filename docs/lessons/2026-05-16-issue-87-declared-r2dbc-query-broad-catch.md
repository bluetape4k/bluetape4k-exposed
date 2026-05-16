# Issue #87 — DeclaredExposedR2dbcQuery: broad catch swallows real errors

**Date**: 2026-05-16
**Branch**: fix/issue-87
**PR**: (pending)

## Root Cause

When extracting an ID value from a result row by column name, the code caught
all `Exception` types and silently fell back to ordinal 0:

```kotlin
try {
    row.get(idColumnName, Any::class.java)
} catch (_: Exception) {  // catches everything including fatal errors
    row.get(0, Any::class.java)
}
```

Connection failures, R2DBC driver errors, and out-of-bounds access were all
silently masked, producing wrong-column reads instead of failing fast.

## Fix

Narrow the catch to `IllegalArgumentException` (column-not-found) and rethrow
anything else wrapped with context:

```kotlin
try {
    row.get(idColumnName, Any::class.java)
} catch (e: IllegalArgumentException) {
    log.debug(e) { "Column '$idColumnName' not found by name, falling back to ordinal 0" }
    row.get(0, Any::class.java)
} catch (e: Exception) {
    throw IllegalStateException(
        "Unexpected error extracting ID column '$idColumnName' from result row", e
    )
}
```

## Why IllegalArgumentException

R2DBC SPI and Spring Data R2DBC both throw `IllegalArgumentException` when a
column name is not present in the row. Other exception types indicate
infrastructure or driver problems that should not be silently absorbed.

## Verification

```
./gradlew :exposed-spring-boot-r2dbc:compileKotlin
# BUILD SUCCESSFUL
```
