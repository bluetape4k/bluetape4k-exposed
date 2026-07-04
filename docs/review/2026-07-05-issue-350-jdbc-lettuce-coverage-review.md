# Review - Issue 350 JDBC Lettuce Coverage (2026-07-05)

## Scope

- Issue: #350
- Module: `:bluetape4k-exposed-jdbc-lettuce`
- Focus: `ExposedLettuceSuspendedLoadedMap`

## Findings

- P0: none.
- P1: none.

## Evidence

- Baseline Kover XML showed `jdbc-lettuce` instruction coverage at 74.78%.
- The largest missed source file was `ExposedLettuceSuspendedLoadedMap.kt`.
- Added direct Redis-backed tests for suspended map read-through, write-through,
  write-behind close, suspend close, and write failure handling.

## Verification

- New focused test class: PASS, 5 passing.
- Module test plus Kover XML/log: PASS, 803 passing and 72 pending.
- Final `jdbc-lettuce` instruction coverage: 85.08%.
- Final Kover line coverage: 84.6626%.

