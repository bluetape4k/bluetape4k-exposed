# BigQueryQueryExecutor.convertValue: Column context 없는 NumberFormatException

**Date**: 2026-05-16
**Issue**: #89
**Module**: `exposed-bigquery`
**File**: `BigQueryQueryExecutor.kt`

## 근본 원인

`convertValue`는 exception context 없이 numeric/date conversion을 수행했습니다.

```kotlin
return when (column.columnType) {
    is DecimalColumnType -> BigDecimal(s)           // NumberFormatException: no context
    is JavaInstantColumnType -> Instant.ofEpochMilli((s.toDouble() * 1000).toLong())  // same
    else -> column.columnType.valueFromDB(s)        // any exception: no context
} as T?
```

BigQuery가 예상하지 못한 format(예: `"N/A"`인 NUMERIC column)을 반환하면
`NumberFormatException` 또는 `valueFromDB` exception message에는 raw string만 있고
column name, type, query context가 없어 debugging이 매우 느렸습니다.

## 수정

전체 `when` block을 try/catch로 감싸 column name과 raw value를 포함해 다시 던집니다.

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

## 향후 지침

- exception이 발생할 수 있는 모든 type conversion에는 raw value, column name,
  column type을 포함합니다.
- branch마다 중복하지 않도록 function level에서 감쌉니다. 하나의 try/catch가
  모든 branch를 처리합니다.
- stack trace가 사라지지 않도록 원래 exception을 cause로 보존합니다.
