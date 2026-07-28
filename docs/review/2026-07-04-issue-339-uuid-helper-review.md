# Issue 339 UUID Helper 리뷰

## 범위

- Issue: #339 `refactor: replace direct UUID.randomUUID usages with ecosystem ID helpers`
- Branch: `feat/issue-339-uuid-helpers`
- Review type: Type B 6-R lite, Tier 4 code correctness + Tier 5 test/evidence

## 근거

- `rg -n "UUID\.randomUUID|randomUUID\(" --glob '*.kt'`: `benchmark/exposed-benchmark/src/benchmark/kotlin/io/bluetape4k/exposed/benchmark/id/CustomIdTableBenchmark.kt:168`만 남았습니다.
- `git diff --check`: clean.
- 수정 module의 `./gradlew ... compileTestKotlin`: `BUILD SUCCESSFUL`, 53 actionable tasks.
- targeted non-cache test: `BUILD SUCCESSFUL`, 355 passing, 7 pending.
- `--no-parallel` cache module serial test: `BUILD SUCCESSFUL`, `jdbc-redisson` 439 passing 및 `r2dbc-redisson` 203 passing 포함.

## 발견 사항

| Severity | Finding | Evidence | Status |
|---|---|---|---|
| P0 | 없음 | diff, grep, compile, targeted test 검토 | PASS |
| P1 | 없음 | UUID-valued path는 `Uuid.V7.nextId()`를, string suffix path는 `Base58.randomString(8)`을 사용합니다 | PASS |
| P2 | benchmark는 `UUID.randomUUID()` 유지 | `CustomIdTableBenchmark`는 Java UUID client default generation을 benchmark 동작으로 비교합니다 | 허용된 예외 |

## 판정

P0/P1 = 0. PR 생성 가능한 상태입니다.
