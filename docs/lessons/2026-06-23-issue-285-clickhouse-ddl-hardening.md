# Issue 285 ClickHouse DDL Hardening Lessons

Date: 2026-06-23
Issue: #285

## Lesson

ClickHouse engine clauses are table-local DDL, not normal query expressions. Typed engine DSL rendering must not blindly reuse query-style column rendering when that produces `table.column` output.

## Guidance

- Prefer `ClickHouseTable` engine overrides declared after column properties when engine clauses should reference typed columns.
- Keep constructor-time raw fragments only for compatibility or expressions that cannot be modeled yet, and make the raw boundary explicit with `unsafeRaw...`.
- Treat setting names separately from setting values: safe paths should be allowlisted or typed, while arbitrary setting names belong behind an unsafe API.
- Add schema-level create/drop coverage for typed engine declarations, not only `toClause()` string tests.

## Follow-up

When adding a new ClickHouse expression helper that should be valid inside engine DDL, add it to the dedicated engine expression renderer and cover it with `MergeTreeDslTest`.
