# Issue #29 Trino Batch Write Lessons

## Context

`exposed-trino` needed a safer documented path for batch writes without
pretending Trino connector writes are uniformly transactional or uniformly
optimized.

## Lessons

- Trino `INSERT` is a SQL-level feature, but real write support is determined
  by the configured catalog connector.
- Client-side Exposed `batchInsert` is not the same thing as connector-side bulk
  loading or connector `write.batch-size` tuning. Keep those contracts separate
  in docs and API names.
- Generated-key retrieval is not a reliable default for Trino writes. A
  Trino-specific helper should default `shouldReturnGeneratedValues=false`.
  Still test the explicit `true` path because Exposed can return inserted rows
  even when Trino does not expose database-generated keys.
- Chunking improves caller control and bounds each JDBC batch, but it increases
  the need to document partial-write behavior: if a later chunk fails, earlier
  chunks may already be visible.
- In this shell, `qmdq` is a `~/.zshrc` function, not a standalone binary.
  Use `source ~/.zshrc; qmdq ...` or `qmd query ... --no-rerank` directly.

## Verification

- qmd lookup confirmed the existing Trino autocommit/transaction warning in the
  local knowledge base.
- Official Trino docs confirmed `INSERT` syntax and connector-dependent SQL
  statement support.
- First targeted `InsertTest` passed with 7 tests.
- A local code-review subagent reported an `InsertTest --rerun-tasks` failure
  caused by Trino connection EOF/connection errors in its sandbox.
- Leader reran `./gradlew :bluetape4k-exposed-trino:test --tests "io.bluetape4k.exposed.trino.insert.InsertTest" --rerun-tasks --console=plain`; it passed with 7 tests before Claude review integration.
- Claude advisor reported no P0/P1 blockers and recommended follow-up coverage
  for `shouldReturnGeneratedValues=true`, a simpler chunk loop, and stronger
  partial-write assertions.
- Integrated those review findings.
- `./gradlew :bluetape4k-exposed-trino:test --tests "io.bluetape4k.exposed.trino.insert.InsertTest" --rerun-tasks --console=plain` passed with 8 tests after review integration.

## Follow-up Guidance

- Do not document `trinoBatchInsert` as a bulk-loader protocol. It is a bounded
  wrapper around Exposed JDBC `batchInsert`.
- If a future connector exposes a real bulk write protocol, add a separate
  connector-specific API instead of expanding this helper's semantics.
