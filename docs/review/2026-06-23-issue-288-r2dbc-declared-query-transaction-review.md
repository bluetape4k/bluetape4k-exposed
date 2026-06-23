# Code Review - Issue #288 R2DBC Declared Query Transaction Boundary

## Scope

- Issue: #288 `fix(r2dbc): open transaction boundary for declared @Query methods`
- Module: `:bluetape4k-exposed-spring-boot-r2dbc`
- Files reviewed:
  - `spring-boot/r2dbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/r2dbc/repository/query/DeclaredExposedR2dbcQuery.kt`
  - `spring-boot/r2dbc/src/test/kotlin/io/bluetape4k/spring/data/exposed/r2dbc/DeclaredExposedR2dbcQueryTest.kt`

## Verdict

APPROVE

## Findings

- P0: None
- P1: None
- P2: None
- P3: None

## Evidence

- RED reproduction: the new outside-transaction declared query test failed before the production fix with `DeclaredExposedR2dbcQuery 'findByEmailNative' must be called within an active R2DBC suspendTransaction { }`.
- Production fix:
  - `DeclaredExposedR2dbcQuery.executeSuspending` reuses `TransactionManager.currentOrNull()` when an active transaction exists.
  - It opens `suspendTransaction { ... }` only when no active transaction exists, matching PartTree/base repository transaction boundaries.
- Test coverage:
  - Outside active transaction: `@Query native - active transaction 없이 호출해도 자체 transaction 에서 조회된다`
  - Inside active transaction: `@Query native - active transaction 내부에서는 미커밋 row 를 조회한다`
- Local verification:
  - `./gradlew :bluetape4k-exposed-spring-boot-r2dbc:test --tests 'io.bluetape4k.spring.data.exposed.r2dbc.DeclaredExposedR2dbcQueryTest'`: `BUILD SUCCESSFUL`
  - `./gradlew :bluetape4k-exposed-spring-boot-r2dbc:test`: `206 passing`, `BUILD SUCCESSFUL`
  - `./gradlew :bluetape4k-exposed-spring-boot-r2dbc:build detekt`: `BUILD SUCCESSFUL`, `:detekt NO-SOURCE`
  - `git diff --check`: passed
- Independent review:
  - Native `code-reviewer` lane returned `APPROVE`.
  - P0/P1/P2/P3 findings: none.

## Residual Risk

- The outside-transaction test temporarily sets `TransactionManager.defaultDatabase` because Exposed resolves `suspendTransaction {}` from the current/default R2DBC database when no transaction exists. The test restores the previous default in `finally`, and the full module test passed across the enabled dialect set.
