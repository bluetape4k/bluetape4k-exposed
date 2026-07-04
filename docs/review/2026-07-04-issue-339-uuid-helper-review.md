# Issue 339 UUID Helper Review

## Scope

- Issue: #339 `refactor: replace direct UUID.randomUUID usages with ecosystem ID helpers`
- Branch: `feat/issue-339-uuid-helpers`
- Review type: Type B 6-R lite, Tier 4 code correctness + Tier 5 test/evidence

## Evidence

- `rg -n "UUID\.randomUUID|randomUUID\(" --glob '*.kt'`: only `benchmark/exposed-benchmark/src/benchmark/kotlin/io/bluetape4k/exposed/benchmark/id/CustomIdTableBenchmark.kt:168` remains.
- `git diff --check`: clean.
- `./gradlew ... compileTestKotlin` for touched modules: `BUILD SUCCESSFUL`, 53 actionable tasks.
- Targeted non-cache tests: `BUILD SUCCESSFUL`, 355 passing, 7 pending.
- Cache module serial tests with `--no-parallel`: `BUILD SUCCESSFUL`, including `jdbc-redisson` 439 passing and `r2dbc-redisson` 203 passing.

## Findings

| Severity | Finding | Evidence | Status |
|---|---|---|---|
| P0 | None | Review of diff, grep, compile, targeted tests | PASS |
| P1 | None | UUID-valued paths use `Uuid.V7.nextId()`, string suffix paths use `Base58.randomString(8)` | PASS |
| P2 | Benchmark keeps `UUID.randomUUID()` | `CustomIdTableBenchmark` compares Java UUID client default generation as benchmark behavior | Accepted exception |

## Verdict

P0/P1 = 0. The implementation is ready for PR creation.
