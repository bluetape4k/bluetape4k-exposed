# Issue #101 — completionAttempts Column: Nullable → Non-Nullable with Default 0

**Date**: 2026-05-16  
**Branch**: fix/issue-101  
**PR**: (pending)

## Root Cause

`ExposedEventPublicationTable.completionAttempts` was declared as `nullable()`:

```kotlin
val completionAttempts = integer("COMPLETION_ATTEMPTS").nullable()
```

A counter that starts at 0 and only increments should never be null. The nullable
declaration forced every arithmetic expression to use `COALESCE` or `?: 0` fallbacks,
adding accidental complexity at every call site.

## Decisions

1. **Change to `default(0)`** — removes the null domain entirely:
   ```kotlin
   val completionAttempts = integer("COMPLETION_ATTEMPTS").default(0)
   ```

2. **`markResubmitted` becomes atomic in one step** — with a non-nullable column
   the increment no longer needs `COALESCE`:
   ```kotlin
   row[table.completionAttempts] = table.completionAttempts + 1
   ```
   This replaces the previous non-atomic SELECT + UPDATE pattern. The single UPDATE
   expression is evaluated atomically by the DB engine.

3. **`toPublication` `?: 0` fallback removed** — the column is `Int`, not `Int?`,
   so the fallback is compile-time unnecessary and would never have triggered.

4. **`import org.jetbrains.exposed.v1.core.plus`** is required — Exposed's `+`
   operator for `ExpressionWithColumnType<T>` lives there and is not auto-imported.

## Schema Migration Note

This is a column type change (`NULL`→`NOT NULL DEFAULT 0`). For `initialize-schema: true`
(test/local) users `SchemaUtils.create` handles the new DDL automatically. Production
deployments using Flyway or Liquibase need a migration:

```sql
ALTER TABLE EVENT_PUBLICATION
  ALTER COLUMN COMPLETION_ATTEMPTS SET NOT NULL;
ALTER TABLE EVENT_PUBLICATION
  ALTER COLUMN COMPLETION_ATTEMPTS SET DEFAULT 0;

-- Repeat for EVENT_PUBLICATION_ARCHIVE if used
```

For H2 the ALTER syntax differs; use `SchemaUtils.createMissingTablesAndColumns()`
or drop/recreate for local dev.

## Verification

```
./gradlew :exposed-spring-modulith:test
# 12 tests passing (H2, PostgreSQL, MySQL) — 0 failed
```

## Supersedes

This fix supersedes the `COALESCE` workaround introduced in PR #100 (issue #84)
for `markResubmitted`. When both PRs merge, the COALESCE import and usage will be
replaced by the direct arithmetic expression from this PR.
