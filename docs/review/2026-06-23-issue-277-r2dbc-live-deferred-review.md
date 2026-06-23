# Review - Issue #277 R2DBC Live Deferred

Date: 2026-06-23
Issue: #277
Module: `:bluetape4k-exposed-r2dbc`

## Finding

`virtualThreadTransactionAsync` returned `Deferred<T>` but wrapped `async` in `coroutineScope`. Because `coroutineScope` waits for its children, callers received the `Deferred` only after the transaction work had already completed.

## Root Cause

The implementation mixed two concurrency contracts: structured waiting inside a suspend function and caller-controlled completion through a returned `Deferred`. The KDoc promised the latter, while the `coroutineScope` implementation enforced the former.

## Fix

Capture the caller coroutine context and create a child `async` on the configured virtual-thread dispatcher from that context. The returned `Deferred` remains tied to caller cancellation but is live when returned.

## Verification

- Added a barrier regression test proving multiple `Deferred` values can be created before the first transaction is released.
- Verified the regression test timed out against the previous implementation.
- Verified `:bluetape4k-exposed-r2dbc:test` passes after the fix.
