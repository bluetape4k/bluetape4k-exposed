# Issue #351 r2dbc-lettuce Coverage Review

## Scope

- Issue: <https://github.com/bluetape4k/bluetape4k-exposed/issues/351>
- Module: `:bluetape4k-exposed-r2dbc-lettuce`
- Changed test surface:
  - `exposed/r2dbc-lettuce/src/test/kotlin/io/bluetape4k/exposed/r2dbc/lettuce/map/ExposedR2dbcLettuceSuspendedLoadedMapTest.kt`

## Review Result

- P0/P1 findings: 0
- Tier 4 correctness: PASS
- Tier 5 test adequacy: PASS
- Tier 7 evidence integrity: PASS

## Evidence

- Baseline Kover XML instruction coverage: `73.71%` (`covered=2868`, `missed=1023`, `total=3891`).
- Largest baseline gap: `ExposedR2dbcLettuceSuspendedLoadedMap.kt`, `59.7%` instruction coverage (`missed=726`, `covered=1077`).
- Added focused Redis-backed tests for direct map contracts:
  - cache miss loading through `get` and `getAll`.
  - pattern invalidation and full clear.
  - write-through Redis writes and eviction.
  - write-behind drain through both `suspendClose()` and blocking `close()`.
  - write-behind failure handling during shutdown.
- Focused command:
  - `./gradlew --no-parallel :bluetape4k-exposed-r2dbc-lettuce:compileTestKotlin :bluetape4k-exposed-r2dbc-lettuce:test --tests 'io.bluetape4k.exposed.r2dbc.lettuce.map.ExposedR2dbcLettuceSuspendedLoadedMapTest'`
  - Result: `5 passing`, `BUILD SUCCESSFUL`.
- Full module command:
  - `./gradlew --no-parallel :bluetape4k-exposed-r2dbc-lettuce:test :bluetape4k-exposed-r2dbc-lettuce:koverXmlReport :bluetape4k-exposed-r2dbc-lettuce:koverLog`
  - Result: `138 passing`, `4 pending`, `BUILD SUCCESSFUL`.
- Final Kover:
  - Line coverage: `85.9116%`.
  - XML instruction coverage: `88.33%` (`covered=3437`, `missed=454`, `total=3891`).
  - `ExposedR2dbcLettuceSuspendedLoadedMap.kt`: `91.3%` instruction coverage (`missed=157`, `covered=1646`).

## Notes

- No production behavior changed.
- Testcontainers-backed verification was run with `--no-parallel`.
- The new tests reuse existing module fixtures, `runSuspendIO`, and `bluetape4k-assertions`.
