# ExposedEventPublicationRepository.markResubmitted: 비원자적 읽기-수정-쓰기

**Date**: 2026-05-16
**Issue**: #84
**Module**: `exposed-spring-modulith`
**File**: `ExposedEventPublicationRepository.kt`

## 근본 원인

`markResubmitted`는 두 단계의 읽기-수정-쓰기 pattern을 사용했습니다.

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

같은 publication에 concurrent resubmission attempt가 발생하면 두 transaction이 같은
`attempts` 값을 읽고 모두 `attempts + 1`을 기록하여 increment 하나를 잃을 수
있습니다.

## 수정

SQL column expression을 사용하는 단일 atomic UPDATE로 바꿉니다.

```kotlin
val updated = table.update({
    (table.id eq identifier.toKotlinUuid()) and (table.status neq Status.RESUBMITTED.name)
}) { row ->
    row[table.status] = Status.RESUBMITTED.name
    row[table.completionAttempts] = Coalesce(table.completionAttempts, intLiteral(0)) + 1
    row[table.lastResubmissionDate] = resubmissionDate
}
```

`COALESCE(completion_attempts, 0) + 1`은 nullable column을 SQL에서 원자적으로
처리합니다.

## 구현 메모: Nullable Column 산술을 위한 Exposed API

`completionAttempts`는 nullable `Column<Int?>`입니다. Exposed 1.2.0에서는 다음을
유의합니다.

- `ExpressionWithColumnType<Int?>`의 `plus`는 `1: Int?`를 요구하고 compiler가
  integer literal에서 nullable type을 infer하지 못하므로 `column + 1`은 실패합니다.
- `Coalesce(column, intLiteral(0))`는 non-nullable
  `ExpressionWithColumnType<Int>`를 반환하므로 `+ 1`이 명확해집니다.
- `plus` operator는 명시적으로 import해야 합니다.
  `import org.jetbrains.exposed.v1.core.plus`
- Exposed 1.2.0에서 `SqlExpressionBuilder`는 ERROR level로 deprecated이므로
  `with(SqlExpressionBuilder)`를 사용하지 않습니다.

## 향후 지침

- counter를 increment할 때는 SELECT + UPDATE를 사용하지 않고 항상 SQL column
  expression을 사용합니다.
- Exposed의 nullable numeric column은 산술 전에
  `Coalesce(col, intLiteral(0))`로 감쌉니다.
- DSL scope 밖에서 Exposed expression에 산술 operator를 사용할 때는
  `org.jetbrains.exposed.v1.core.plus`를 명시적으로 import합니다.
