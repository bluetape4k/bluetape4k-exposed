# Review - Issue 341 Cache Close Lifecycle (2026-07-05)

## Scope

- Issue: #341
- Modules:
  - `:bluetape4k-exposed-jdbc-lettuce`
  - `:bluetape4k-exposed-r2dbc-lettuce`
  - `:bluetape4k-exposed-jdbc-caffeine`
  - `:bluetape4k-exposed-r2dbc-caffeine`

## Findings

- P0: none.
- P1: none.

## Evidence

- Lettuce close paths now isolate near-cache and backing cache cleanup.
- Suspend near-cache close paths catch `CancellationException` separately and rethrow it.
- Caffeine close paths keep the existing bounded write-behind completion wait before post-flush cleanup.
- Caffeine cache invalidation failure no longer skips repository scope cancellation.

## Verification

- Baseline compile for affected modules: PASS.
- Post-change compile for affected modules: PASS.
- Focused close-lifecycle regression tests: PASS, 6 passing.
- Affected module tests, serial: PASS, 308 passing and 22 pending.

