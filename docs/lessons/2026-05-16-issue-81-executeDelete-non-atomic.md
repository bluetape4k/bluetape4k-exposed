# executeDelete: SELECT+DELETE 비원자적 처리 → 단일 deleteWhere

**Date**: 2026-05-16
**Issue**: #81
**Module**: `exposed-spring-boot-jdbc`
**File**: `PartTreeExposedQuery.executeDelete`

## 근본 원인

기존 구현은 일치하는 모든 entity를 가져온 뒤 각각 삭제했습니다.

```kotlin
// BEFORE — non-atomic, N+1 operations
private fun executeDelete(op: Op<Boolean>): Long {
    val entities = entityClass.find { op }.toList()   // SELECT *
    entities.forEach { it.delete() }                   // N × DELETE by PK
    return entities.size.toLong()
}
```

문제는 다음과 같습니다.

1. **비원자적 처리**: SELECT와 DELETE 사이에 concurrent INSERT가 발생하면 삭제되지 않습니다.
2. **N+1 query**: SELECT 한 번과 개별 DELETE N번을 실행합니다.
3. **실제 delete count가 아닌 fetch count를 반환**합니다.

## 수정

단일 `DELETE WHERE` SQL expression을 사용합니다.

```kotlin
// AFTER — atomic, 1 operation, returns actual delete count
private fun executeDelete(op: Op<Boolean>): Long =
    entityInformation.table.deleteWhere { op }.toLong()
```

R2DBC counterpart (`PartTreeExposedR2dbcQuery`)는 이미 이 pattern을 사용하고
있었습니다.

```kotlin
partTree.isDelete -> table.deleteWhere { op }.toLong()
```

## 검증

- 수정 후 `exposed-spring-boot-jdbc` test suite가 모두 통과했습니다.
- `deleteWhere` API mismatch 없이 compile되었습니다.

## 향후 지침

- DAO lifecycle hook (예: `Entity.delete()` override)이 필요하지 않다면 DAO의
  `find + forEach { delete() }`보다 `table.deleteWhere { condition }`를
  우선합니다.
- JDBC counterpart는 항상 R2DBC 구현을 참조해야 합니다. 둘의 불일치는 한쪽의
  bug 징후입니다.
