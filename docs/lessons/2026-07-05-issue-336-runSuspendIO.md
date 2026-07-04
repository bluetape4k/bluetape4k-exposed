# Lesson — Issue #336 runSuspendIO migration

## Decision

Use `runSuspendIO` for Exposed DB/cache/R2DBC/JDBC suspend tests that touch real IO, containers, cache clients, or repository/database boundaries. Keep `runTest` for pure coroutine semantics, virtual-time behavior, cancellation/unit mocks, and non-IO codec tests.

## Why

`runTest` is optimized for virtual-time coroutine tests. DB/cache/Testcontainers paths need real dispatcher and timeout behavior so they do not accidentally rely on virtual time or test scheduler behavior.

## Future guardrail

When adding suspend tests in Exposed modules:

1. If it calls Exposed DB/R2DBC, Redis/Redisson/Lettuce, cache repository, Spring repository, or Testcontainers-backed helper: use `runSuspendIO`.
2. If it validates coroutine-only cancellation, virtual-time delays, retry timing, or pure in-memory codec behavior: `runTest` may remain, but add/keep a clear test-local reason.
3. Run affected modules serially with `--no-parallel` when Testcontainers or shared cache services are involved.
