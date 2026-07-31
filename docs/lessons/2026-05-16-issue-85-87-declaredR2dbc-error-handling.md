# DeclaredExposedR2dbcQuery: toSqlArg 및 ID Column Fallback의 무음 오류 무시

**Date**: 2026-05-16
**Issues**: #85, #87
**Module**: `exposed-spring-boot-r2dbc`
**File**: `DeclaredExposedR2dbcQuery.kt`

## Issue #85 — toSqlArg가 resolveColumnType 오류를 조용히 무시함

### 근본 원인

```kotlin
val columnType = runCatching {
    resolveColumnType(value::class as KClass<Any>, defaultType = TextColumnType())
}.getOrElse { TextColumnType() }  // exception silently dropped
```

`resolveColumnType`에서 발생한 모든 exception이 log 없이 무시되어 type mapping
failure를 진단할 수 없었습니다.

### 수정

fallback 전에 warning을 남기는 try/catch로 교체합니다.

```kotlin
val columnType = try {
    resolveColumnType(value::class as KClass<Any>, defaultType = TextColumnType())
} catch (e: Exception) {
    log.warn(e) { "Cannot resolve column type for ${value::class.simpleName}, falling back to TextColumnType" }
    TextColumnType()
}
```

**참고**: lambda 형태의 `log.warn(e) { "..." }`를 사용하려면
`import io.bluetape4k.logging.coroutines.KLoggingChannel` 외에
`import io.bluetape4k.logging.warn`도 필요합니다. 없으면 compiler가 SLF4J
`warn(String, Throwable)`로 resolve하여 lambda form이 실패합니다.

## Issue #87 — ID Column Fallback의 광범위한 Exception Catch

### 근본 원인

```kotlin
try {
    row.get(idColumnName, Any::class.java)
} catch (_: Exception) {  // catches ALL exceptions, including unexpected ones
    row.get(0, Any::class.java)
}
```

광범위한 `Exception` catch는 connection failure와 serialization error를 포함한
심각한 error를 조용히 무시하고 ordinal 0으로 fallback하여 실제 문제를 숨겼습니다.

### 수정

`IllegalArgumentException`(이름으로 column을 찾지 못함)만 catch하고 나머지는
다시 던집니다.

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

## 향후 지침

- log 없는 `runCatching { }.getOrElse { default }`는 사용하지 말고 warning이 있는
  try/catch를 사용합니다.
- failure case를 다루는 가장 좁은 exception type만 catch하고 알 수 없는 error는
  다시 던집니다.
- `KLoggingChannel`에서 `log.warn(e) { }` form이 동작하려면
  `io.bluetape4k.logging.warn` extension을 import해야 합니다. 그렇지 않으면
  compiler가 SLF4J method로 resolve합니다.
