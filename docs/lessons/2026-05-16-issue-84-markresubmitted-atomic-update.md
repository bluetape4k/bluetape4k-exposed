# ExposedEventPublicationRepository.markResubmitted: Non-Atomic Read-Modify-Write

**Date**: 2026-05-16  
**Issue**: #84  
**Module**: `exposed-spring-modulith`  
**File**: `ExposedEventPublicationRepository.kt`

## Root Cause

`markResubmitted` used a two-step read-modify-write pattern:

```kotlin
val attempts = table.selectAll()
    .where { table.id eq identifier.toKotlinUuid() }
    .firstOrNull()
    ?.get(table.completionAttempts)
    ?: 0

val updated = table.update({ ... }) { row ->
    row[table.completionAttempts] = attempts + 1
}
```

Under concurrent resubmission attempts for the same publication, two transactions can
read the same `attempts` value and both write `attempts + 1`, losing one increment.

## Fix

Replace with a single atomic UPDATE using a SQL column expression:

```kotlin
val updated = table.update({
    (table.id eq identifier.toKotlinUuid()) and (table.status neq Status.RESUBMITTED.name)
}) { row ->
    row[table.status] = Status.RESUBMITTED.name
    row[table.completionAttempts] = Coalesce(table.completionAttempts, intLiteral(0)) + 1
    row[table.lastResubmissionDate] = resubmissionDate
}
```

`COALESCE(completion_attempts, 0) + 1` handles the nullable column atomically in SQL.

## Implementation Note: Exposed API for Nullable Column Arithmetic

`completionAttempts` is `Column<Int?>` (nullable). In Exposed 1.2.0:
- `column + 1` fails because `plus` on `ExpressionWithColumnType<Int?>` requires `1: Int?`
  and the compiler cannot infer the nullable type from the integer literal.
- `Coalesce(column, intLiteral(0))` returns `ExpressionWithColumnType<Int>` (non-nullable),
  making `+ 1` unambiguous.
- The `plus` operator must be imported explicitly:
  `import org.jetbrains.exposed.v1.core.plus`
- `SqlExpressionBuilder` is deprecated at ERROR level in Exposed 1.2.0 — do not use `with(SqlExpressionBuilder)`.

## Future Guidance

- Never use SELECT + UPDATE for incrementing a counter — always use a SQL column expression.
- For nullable numeric columns in Exposed, wrap with `Coalesce(col, intLiteral(0))` before arithmetic.
- Import `org.jetbrains.exposed.v1.core.plus` explicitly when using arithmetic operators on Exposed expressions outside a DSL scope that provides them.
