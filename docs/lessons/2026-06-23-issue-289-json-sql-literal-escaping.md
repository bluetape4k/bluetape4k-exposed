# Issue #289 JSON SQL Literal Escaping

## Context

JSON column types rendered serialized JSON by interpolating `notNullValueToDB(value)` directly inside single quotes. That bypassed Exposed's string literal escaping for single quotes, carriage returns, and line feeds.

## Decision

Reuse Exposed `TextColumnType.nonNullValueToString` for the serialized JSON body, then add only the dialect-specific JSON prefix where required.

## Why

- It avoids maintaining a separate escape table in each JSON module.
- It keeps JSON, JSONB default strings, and SQL literal rendering aligned with Exposed's core string literal semantics.
- It preserves existing H2 behavior by retaining the `JSON ` prefix around the escaped literal.

## Verification Notes

- Add regression tests at the column-type layer when literal rendering is the bug surface.
- Run module tests after unit tests because JSONB/default helpers inherit the JSON column-type rendering path.
