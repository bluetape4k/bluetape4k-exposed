# DeclaredExposedQuery.coerceIdValue: 단순 cast → 검증된 예외

**Date**: 2026-05-16
**Issue**: #82
**Module**: `exposed-spring-boot-jdbc`
**File**: `DeclaredExposedQuery.coerceIdValue`

## 근본 원인

`coerceIdValue`의 `else` branch는 검증 없이 `rawId as ID` cast를 사용했습니다.

```kotlin
else -> rawId as ID  // ClassCastException with no context
```

`rawId`가 Number가 아닐 때 Number branch도 `rawId as ID`로 흘러갔습니다.

```kotlin
Long::class.java -> if (rawId is Number) rawId.toLong() as ID else rawId as ID
```

두 경우 모두 entity type, ID type, 실패를 유발한 raw value 정보를 전혀 주지 않는
`ClassCastException`이 호출 지점에서 멀리 떨어진 곳에서 발생했습니다.

## 수정

진단 정보를 담은 `IllegalStateException`으로 단순 cast를 교체합니다.

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

최상위 `idType.isInstance(rawId)` guard가 일반적인 경우를 이미 올바르게
처리하므로 `when` branch는 type이 일치하지 않을 때만 실행됩니다.

## 향후 지침

- `ID`가 type erasure된 generic type parameter일 때는 단순 `rawId as ID` cast를
  사용하지 않습니다. `idType.isInstance(rawId)` guard와 명시적 cast를 사용하거나
  설명적인 error를 던집니다.
- error message에는 raw value, 실제 type, 기대 type, 그리고 가능하면 문제 위치를
  찾을 수 있는 entity name을 포함합니다.
