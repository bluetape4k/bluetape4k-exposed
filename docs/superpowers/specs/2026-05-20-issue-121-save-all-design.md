# Issue 121 SaveAll Repository API Design

## Context

GitHub issue #121 asks for `saveAll(entities: Iterable<E>)` on the core
`JdbcRepository` and `R2dbcRepository` contracts for milestone 1.8.1.

Current source differs from the issue wording:

- `JdbcRepository` and `R2dbcRepository` do not expose a core single-entity
  `save(entity)` contract today.
- Repository test fixtures implement ad-hoc `save()` methods because the core
  repository interfaces only know `table`, `extractId`, and `toEntity`.
- A generic batch insert cannot be implemented from `E` alone without a
  repository-provided column binding hook.

Claude advisor/review is intentionally not used for this work. The user stated
that Claude Code review is unavailable because the subscription was lowered.
Codex CLI review is also not run as an external process; this Codex session owns
implementation, review, and verification.

IDE reference/diagnostic tools are unavailable for this worktree because
IntelliJ currently has `bluetape4k-workshop` open, not `bluetape4k-exposed`.
Fallback evidence is repo search plus targeted Gradle compile/tests.

## API Decision

Add a small binding hook plus a default `saveAll` implementation:

```kotlin
fun BatchInsertStatement.bindSave(entity: E)
fun saveAll(entities: Iterable<E>): List<ID>
```

The hook is a member extension on each repository interface. Implementations
that want the default `saveAll` override it and assign table columns from the
entity. The default hook throws `UnsupportedOperationException` with a clear
message so existing repositories remain source-compatible and fail explicitly
only when calling `saveAll` without a binding.

`saveAll` materializes the input iterable once, returns `emptyList()` for empty
input, calls Exposed `batchInsert`, and returns generated primary key values in
insert order.

## Scope

- Add `saveAll(Iterable<E>): List<ID>` to `JdbcRepository`.
- Add `suspend saveAll(Iterable<E>): List<ID>` to `R2dbcRepository`.
- Add the same `BatchInsertStatement.bindSave(entity)` hook to both contracts.
- Cover normal repositories with 100+ row and 10k+ row bulk insert tests.
- Cover auditable repository variants with bulk insert tests that verify audit
  defaults are still produced by the table defaults.

## Non-Goals

- Do not add `save(entity)` to the core interfaces. Existing test repositories
  already define `save(entity): E`; adding a same-parameter method with a
  different return type would create source conflicts.
- Do not infer entity-to-column mappings through reflection.
- Do not change Spring Data `ExposedJdbcRepository` /
  `ExposedR2dbcRepository`; they already inherit or implement Spring Data
  `saveAll`.
- Do not add new dependencies.

## Verification

- Compile affected modules:
  - `./gradlew :bluetape4k-exposed-jdbc:compileKotlin`
  - `./gradlew :bluetape4k-exposed-r2dbc:compileKotlin`
- Run focused repository tests:
  - JDBC normal/auditable saveAll tests
  - R2DBC normal/auditable saveAll tests
- Run final diff review in this Codex session and record Claude/Codex external
  review gaps in the DoD.
