# Review - R2DBC Lettuce cache-only invalidate (2026-06-23)

Issue: #286 `fix(r2dbc-lettuce): make invalidate cache-only`

## Scope

- `exposed/r2dbc-lettuce/src/main/kotlin/io/bluetape4k/exposed/r2dbc/lettuce/repository/AbstractR2dbcLettuceRepository.kt`
- `exposed/r2dbc-lettuce/src/main/kotlin/io/bluetape4k/exposed/r2dbc/lettuce/repository/R2dbcLettuceRepository.kt`
- R2DBC Lettuce read-through and write-through scenario tests
- Stale JDBC Lettuce repository KDoc for the already cache-only implementations

## Findings

- P0: none
- P1: none
- P2/P3: none blocking

## Review Evidence

- Code-reviewer lane: APPROVE. No P0/P1 findings. Noted that `R2dbcLettuceRepositoryExtrasTest` was smoke-style; the assertion was strengthened after review.
- Architect lane: WATCH. No P0/P1 blockers. Noted stale JDBC Lettuce KDoc that described DB deletion despite cache-only implementation; the KDoc was corrected after review.
- Code graph affected flows: 0 flows reported for the seven changed files.

## Validation

- RED: `R2dbcLettuceWriteThroughCacheTest` failed under the old production code after changing the scenario to expect DB retention.
- GREEN: `./gradlew :bluetape4k-exposed-r2dbc-lettuce:test --tests "io.bluetape4k.exposed.r2dbc.lettuce.repository.R2dbcLettuceWriteThroughCacheTest"` passed with 48 tests.
- `./gradlew :bluetape4k-exposed-r2dbc-lettuce:test --tests "io.bluetape4k.exposed.r2dbc.lettuce.repository.R2dbcLettuceReadThroughCacheTest"` passed with 24 tests.
- `./gradlew :bluetape4k-exposed-r2dbc-lettuce:test` passed with 130 tests.
- `./gradlew :bluetape4k-exposed-r2dbc-lettuce:test :bluetape4k-exposed-jdbc-lettuce:compileKotlin :bluetape4k-exposed-jdbc-lettuce:compileTestKotlin` passed.
- `./gradlew detekt` passed with `:detekt NO-SOURCE`.
- `git diff --check` passed.

## Verdict

APPROVE. The implementation now uses cache eviction for `invalidate` and `invalidateAll`, preserving DB rows and matching the shared cache repository contract. P0/P1 is 0.
