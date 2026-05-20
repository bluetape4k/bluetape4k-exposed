# Issue 121 saveAll lesson

## Context

Milestone 1.8.1 issue #121 requested batch `saveAll` APIs for the core JDBC and
R2DBC repository contracts.

## Decision

The core repositories do not own a generic entity-to-column mapper. Add a
repository hook, `BatchInsertStatement.bindSave(entity)`, and let the default
`saveAll` implementation call Exposed `batchInsert`.

Avoid adding `save(entity)` to the core interfaces because existing repository
fixtures already expose `save(entity): E`; a return-type-only overload would not
be source compatible.

## Outcome

`JdbcRepository` and `R2dbcRepository` now expose default `saveAll` APIs.
Repository implementations that need the default behavior override `bindSave`
with table-specific insert assignments.

## Verification

- `./gradlew :bluetape4k-exposed-jdbc:compileKotlin :bluetape4k-exposed-r2dbc:compileKotlin`
- `./gradlew :bluetape4k-exposed-jdbc:test --tests "io.bluetape4k.exposed.jdbc.repository.ActorJdbcRepositoryTest" --tests "io.bluetape4k.exposed.jdbc.repository.AuditableJdbcRepositoryEdgeCaseTest"`
- `./gradlew :bluetape4k-exposed-r2dbc:test --tests "io.bluetape4k.exposed.r2dbc.repository.ActorR2dbcRepositoryTest" --tests "io.bluetape4k.exposed.r2dbc.repository.AuditableR2dbcRepositoryTest"`
- `git diff --check`

## Follow-up Guidance

When adding generic persistence helpers to these repositories, first check
whether the repository contract has enough table mapping information. Prefer a
small explicit binding hook over reflection or implicit mapper assumptions.

Claude advisor/review and external Codex CLI review were skipped by user
instruction; this session performed local implementation, review, and
verification.
