# Review: issue #290 Tink associated data binding

## Scope

- Module: `:bluetape4k-exposed-tink`
- Change: bind AEAD/DAEAD encrypted column ciphertext to associated data.
- Files reviewed: Tink column types, table extension helpers, tests, and README docs.

## Findings

### P1: direct public column constructors kept silent empty associated data

The first implementation bound `Table.tinkAead*` and `Table.tinkDaead*` helper paths to table+column associated data, but retained public no-AD column constructors that silently used empty associated data. That kept the original replay/decrypt weakness reachable for manual `registerColumn(..., Tink*ColumnType(...))` users.

Resolution:

- Marked no-AD column constructors as deprecated and migration-only.
- Documented the direct-constructor behavior and preferred helper/explicit-AD paths.
- Added a direct `registerColumn` regression test that passes explicit associated data and rejects cross-column ciphertext replay.

## Verdict

Approved after fix. P0/P1 findings resolved.

## Validation Evidence

- RED: new cross-column/table replay tests failed before implementation with `Expected Exception but no exception was thrown`.
- Targeted after fix: 9 associated-data tests passed across H2, PostgreSQL, and MySQL V8.
- Full module: `./gradlew :bluetape4k-exposed-tink:test :bluetape4k-exposed-tink:build detekt --no-build-cache`
  - `157 passing`
  - `BUILD SUCCESSFUL`
  - `:detekt NO-SOURCE`
- Patch hygiene: `git diff --check` passed.
