# Issue #87 — DeclaredExposedR2dbcQuery: 광범위한 catch가 실제 error를 숨김

**Date**: 2026-05-16
**Branch**: fix/issue-87
**PR**: (pending)

## 근본 원인

result row에서 column name으로 ID value를 꺼낼 때 code가 모든 `Exception` type을
catch하고 ordinal 0으로 조용히 fallback했습니다.

```kotlin
try {
    row.get(idColumnName, Any::class.java)
} catch (_: Exception) {  // catches everything including fatal errors
    row.get(0, Any::class.java)
}
```

connection failure, R2DBC driver error, out-of-bounds access가 모두 조용히
숨겨져 즉시 실패하는 대신 잘못된 column을 읽었습니다.

## 수정

catch를 `IllegalArgumentException`(column-not-found)으로 좁히고, 나머지는
context와 함께 다시 던집니다.

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

## IllegalArgumentException을 사용하는 이유

R2DBC SPI와 Spring Data R2DBC는 row에 column name이 없을 때 모두
`IllegalArgumentException`을 던집니다. 다른 exception type은 조용히 흡수하면 안 되는
infrastructure 또는 driver 문제를 뜻합니다.

## 검증

```
./gradlew :exposed-spring-boot-r2dbc:compileKotlin
# BUILD SUCCESSFUL
```
