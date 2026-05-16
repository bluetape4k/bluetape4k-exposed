# ClickHouseDatabase.connect: Close Exception Replaces Original

**Date**: 2026-05-16  
**Issue**: #83  
**Module**: `exposed-clickhouse`  
**File**: `ClickHouseDatabase.kt`

## Root Cause

Both `connect()` overloads used `raw.runCatching { close() }.onFailure { closeEx -> log.warn(...) }`
when `ClickHouseConnectionWrapper(raw)` construction failed:

```kotlin
raw.runCatching { close() }.onFailure { closeEx ->
    log.warn("Connection close failed after wrapper creation error: ${closeEx.message}")
}
throw e
```

If `raw.close()` itself throws, that exception was only logged — the original wrapper
creation failure `e` was still rethrown. However, the close failure was silently discarded
from the exception chain, making diagnosis harder when both operations fail.

## Fix

Use `e.addSuppressed(closeEx)` to attach the close failure as a suppressed exception on
the original error, preserving full diagnostic context:

```kotlin
runCatching { raw.close() }.onFailure { closeEx ->
    e.addSuppressed(closeEx)
}
throw e
```

This follows the standard Java/Kotlin idiom for try-with-resources: if cleanup fails after
a primary failure, attach the cleanup exception as suppressed rather than replacing or
silently dropping it.

## Future Guidance

- When a cleanup operation (close, disconnect, rollback) fails inside an error handler,
  always use `primaryException.addSuppressed(cleanupException)` rather than logging only.
- `Throwable.addSuppressed()` is the standard mechanism; debuggers and logging frameworks
  display suppressed exceptions automatically.
- Never silently swallow cleanup exceptions — at minimum attach them as suppressed.
