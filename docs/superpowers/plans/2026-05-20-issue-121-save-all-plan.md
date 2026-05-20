# Issue 121 SaveAll Repository API Plan

## Goal

Implement milestone 1.8.1 issue #121 by adding batch `saveAll` APIs to the core
JDBC and R2DBC repository interfaces without breaking existing repository
implementations.

## Steps

1. Add API hooks.
   - Add `BatchInsertStatement.bindSave(entity: E)` to `JdbcRepository`.
   - Add `saveAll(entities: Iterable<E>): List<ID>` using `table.batchInsert`.
   - Mirror the same contract in `R2dbcRepository` with `suspend saveAll`.

2. Update representative repository fixtures.
   - Override `bindSave` in JDBC `EdgeCaseRepository` and `ActorJdbcRepository`.
   - Override `bindSave` in R2DBC `ActorR2dbcRepository`.
   - Override `bindSave` in auditable JDBC/R2DBC test repositories.

3. Add tests.
   - JDBC `saveAll` inserts 100+ rows and returns generated IDs.
   - JDBC `saveAll` inserts 10k rows on an in-memory dialect.
   - R2DBC `saveAll` inserts 100+ rows and returns generated IDs.
   - R2DBC `saveAll` inserts 10k rows on an in-memory dialect.
   - Auditable JDBC/R2DBC saveAll tests verify inserted rows and audit default
     fields.

4. Verify.
   - Run targeted compile first.
   - Run focused tests for changed test classes.
   - If container-backed dialects are too slow or unavailable, keep H2 proof and
     record the environment gap.

5. Finish.
   - Run local diff review in this Codex session.
   - Add `docs/lessons/2026-05-20-issue-121-save-all.md`.
   - Commit with Lore trailers.
   - Push branch and create a draft PR assigned to `debop` if local verification
     reaches the DoD bar.

## Known Gaps

- Claude advisor/review is skipped by user instruction because Claude Code is no
  longer available under the current subscription.
- IntelliJ diagnostics are skipped unless the exposed worktree becomes available
  in the IDE project list.
