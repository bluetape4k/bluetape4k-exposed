# Review - Issue #279 Trino String Literal Escaping

Date: 2026-06-23
Issue: #279
Module: `:bluetape4k-exposed-trino`

## Finding

`TrinoFunctionProvider` rendered `groupConcat` separators and `locate` substrings by manually opening a quoted SQL literal, appending caller-provided text, and closing the literal.

## Root Cause

The implementation bypassed Exposed string literal rendering. A value containing `'` could terminate the generated SQL literal, while control-like text such as `)`, `--`, or `;` remained in the surrounding SQL stream.

## Fix

Render both values through Exposed `stringLiteral` so the configured column type escapes text before it is appended to Trino SQL.

## Verification

- Added regression tests for quote-containing and control-like separator/substrings.
- Verified the new tests fail against the previous direct-append implementation.
- Verified `:bluetape4k-exposed-trino:test` passes after the fix.

