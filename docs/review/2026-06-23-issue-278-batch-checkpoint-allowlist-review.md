# Review - Issue #278 Batch Checkpoint Class Allowlist

Date: 2026-06-23
Issue: #278
Module: `:bluetape4k-exposed-batch`

## Finding

`CheckpointJson.read` trusted persisted `className` values and called `Class.forName` before handing the payload to Jackson. JDBC and R2DBC repositories both restore DB checkpoints through this path.

## Root Cause

The typed checkpoint envelope preserved the original runtime class to recover scalar types, but the restore path treated the stored class name as authority. A tampered DB row could make restore attempt arbitrary classes on the classpath.

## Fix

Introduce a checkpoint class registry. The default registry allows common scalar and collection checkpoint types, while custom checkpoint data classes must be passed explicitly to `CheckpointJson.jackson3(...)`. Reads resolve only registered class names and writes reject unregistered checkpoint objects.

## Verification

- Added JSON tamper tests for unknown, disallowed, and unexpected checkpoint classes.
- Added JDBC and R2DBC repository tamper tests that mutate persisted checkpoint rows before `loadCheckpoint`.
- Verified the new API produced a RED compile failure before implementation.
- Verified `:bluetape4k-exposed-batch:test` passes after the fix.
