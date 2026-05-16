# BigQueryQueryExecutor.convertValue: NumberFormatException Without Column Context

**Date**: 2026-05-16  
**Issue**: #89  
**Module**: `exposed-bigquery`  
**File**: `BigQueryQueryExecutor.kt`

## Root Cause

`convertValue` performed numeric/date conversions with no exception context:

```kotlin
return when (column.columnType) {
    is DecimalColumnType -> BigDecimal(s)           // NumberFormatException: no context
    is JavaInstantColumnType -> Instant.ofEpochMilli((s.toDouble() * 1000).toLong())  // same
    else -> column.columnType.valueFromDB(s)        // any exception: no context
} as T?
```

When BigQuery returns an unexpected format (e.g., a NUMERIC column with "N/A"), the
`NumberFormatException` or `valueFromDB` exception message contained only the raw string
with no column name, no type, and no query context — making debugging very slow.

## Fix

Wrap the entire `when` block in a try/catch that rethrows with column name and raw value:

```kotlin
return try {
    when (column.columnType) {
        is DecimalColumnType -> BigDecimal(s)
        is JavaInstantColumnType -> Instant.ofEpochMilli((s.toDouble() * 1000).toLong())
        else -> column.columnType.valueFromDB(s)
    } as T?
} catch (e: Exception) {
    throw IllegalArgumentException(
        "Failed to convert BigQuery value '$s' for column '${column.name}' " +
            "(type: ${column.columnType::class.simpleName})",
        e
    )
}
```

## Future Guidance

- Every type conversion that can throw should include: raw value, column name, column type.
- Wrap at the function level (not per-branch) to avoid duplication — one try/catch covers all.
- The original exception is preserved as the cause so the stack trace is not lost.
