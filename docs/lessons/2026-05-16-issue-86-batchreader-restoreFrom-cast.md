# ExposedJdbcBatchReader / ExposedR2dbcBatchReader: 안전하지 않은 restoreFrom Cast

**Date**: 2026-05-16
**Issue**: #86
**Module**: `exposed-batch`
**Files**: `ExposedJdbcBatchReader.kt`, `ExposedR2dbcBatchReader.kt`

## 근본 원인

`restoreFrom(checkpoint: Any)`는 검증되지 않은 단순 cast를 사용했습니다.

```kotlin
@Suppress("UNCHECKED_CAST")
override suspend fun restoreFrom(checkpoint: Any) {
    val key = checkpoint as K   // ClassCastException with no context
```

`checkpoint`가 잘못된 type일 때(예: schema 변경이나 deserialization mismatch 뒤에
Long key에 대한 serialized String) `ClassCastException`에는 keyColumn name,
actual type, expected type 같은 진단 정보가 없었습니다.

## 수정

`ClassCastException`을 catch하고 전체 context를 담은 `IllegalArgumentException`으로
다시 던집니다.

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

## keyClass.isInstance()를 사용하지 않는 이유

`keyClass: Class<K>`를 constructor parameter로 추가하면 기존 caller 전체가
깨집니다. concrete key type (Long, Int, String, UUID)은 JVM이 `as K` 지점에서
cast를 확인하므로 `ClassCastException`을 catch하면 API disruption 없이 동등한
보호를 제공합니다.

type erasure된 generic type(예: `K = List<String>`)에서는 runtime에 cast가
통과하지만 keyset pagination의 일반적이지 않은 key type이므로 이 수정 범위에서는
허용합니다.

## 향후 지침

- 단순 unchecked cast는 항상 value, actual type, expected type, column/field name
  같은 진단 context를 담은 try/catch로 감쌉니다.
- 내부에서 cast하는 `Any` parameter를 받는 public API는 기존 caller를 깨지 않고
  `isInstance` 검사를 할 수 있도록 reified companion `invoke` factory를 통해
  typed class parameter (`keyClass: Class<K>`)를 추가하는 방식을 우선합니다.
