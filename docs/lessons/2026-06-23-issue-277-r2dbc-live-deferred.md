# Lessons Learned - R2DBC Live Deferred (2026-06-23)

Issue: #277
Module: `:bluetape4k-exposed-r2dbc`

## L1: A returned Deferred must not be created inside a waiting scope

### Problem

`coroutineScope { async { ... } }` looks like it returns asynchronous work, but the scope waits for the child before returning. That makes a `Deferred` API misleading because the caller cannot schedule multiple jobs before the first one finishes.

### Lesson

When a suspend API intentionally returns `Deferred`, create the child from the caller-owned coroutine context rather than a transient waiting scope. Keep cancellation tied to the caller, and use a barrier test that proves the function returns while the child is still suspended.
