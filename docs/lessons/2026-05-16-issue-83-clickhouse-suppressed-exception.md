# ClickHouseDatabase.connect: close 예외가 원래 예외의 진단 정보를 잃음

**Date**: 2026-05-16
**Issue**: #83
**Module**: `exposed-clickhouse`
**File**: `ClickHouseDatabase.kt`

## 근본 원인

두 `connect()` overload는 `ClickHouseConnectionWrapper(raw)` 생성에 실패했을 때
`raw.runCatching { close() }.onFailure { closeEx -> log.warn(...) }`를 사용했습니다.

```kotlin
raw.runCatching { close() }.onFailure { closeEx ->
    log.warn("Connection close failed after wrapper creation error: ${closeEx.message}")
}
throw e
```

`raw.close()` 자체가 예외를 던지면 해당 예외는 log에만 남고 원래 wrapper 생성 실패
`e`가 다시 던져졌습니다. 원래 예외를 대체하지는 않지만 close 실패가 exception chain에
포함되지 않아 두 작업이 모두 실패한 경우 진단이 어려웠습니다.

## 수정

close 실패를 원래 error의 suppressed exception으로 붙이는 `e.addSuppressed(closeEx)`를
사용해 전체 진단 context를 보존합니다.

```kotlin
runCatching { raw.close() }.onFailure { closeEx ->
    e.addSuppressed(closeEx)
}
throw e
```

이는 try-with-resources의 표준 Java/Kotlin 관용구를 따릅니다. primary failure 뒤에
cleanup이 실패하면 cleanup exception을 대체하거나 조용히 버리지 말고 suppressed로
첨부합니다.

## 향후 지침

- error handler 안에서 cleanup operation (close, disconnect, rollback)이 실패하면
  log만 남기지 말고 항상 `primaryException.addSuppressed(cleanupException)`를
  사용합니다.
- `Throwable.addSuppressed()`는 표준 mechanism이며 debugger와 logging framework가
  suppressed exception을 자동으로 표시합니다.
- cleanup exception을 조용히 삼키지 않습니다. 최소한 suppressed로 첨부합니다.
