# Issue #690 Virtual Thread JDBC key enumeration Implementation Plan

> **For agentic workers:** 이 계획은 승인된 설계를 순서대로 실행한다. Kotlin 변경에는 `$bluetape-kotlin-patterns`와 `$test-driven-development`를 적용하고, benchmark/chart 변경에는 `$bluetape-diagram`을 적용한다.

**Goal:** 호출자가 명시한 서로 겹치지 않는 PK range를 bounded Virtual Thread JDBC transaction으로 조회하고, 기존 sequential cache loader 경로는 보존한다.

**Architecture:** `exposed/jdbc`에 range validation, bounded future lifecycle, ordered merge를 담당하는 공용 helper와 옵션/value object를 추가한다. JDBC Lettuce와 JDBC Redisson synchronous loader는 additive `loadAllKeysInParallel` API로 이를 호출하며, R2DBC와 suspended adapter는 이번 slot에서 변경하지 않는다.

**Tech Stack:** Kotlin, JetBrains Exposed JDBC, `VirtualFuture`, JDK 25 virtual threads, H2/JDBC test fixtures, JMH/kotlinx-benchmark, CairoSVG/PNG audit.

---

## 파일 구조와 책임

| 책임 | 파일 | 변경 |
| --- | --- | --- |
| 공용 range/options/helper | `exposed/jdbc/src/main/kotlin/io/bluetape4k/exposed/jdbc/JdbcParallelKeyEnumeration.kt` | 생성 |
| helper contract test | `exposed/jdbc/src/test/kotlin/io/bluetape4k/exposed/jdbc/JdbcParallelKeyEnumerationTest.kt` | 생성 |
| Lettuce opt-in API | `exposed/jdbc-lettuce/src/main/kotlin/io/bluetape4k/exposed/lettuce/map/ExposedEntityMapLoader.kt` | 수정 |
| Lettuce parity/lifecycle test | `exposed/jdbc-lettuce/src/test/kotlin/io/bluetape4k/exposed/lettuce/map/ExposedEntityMapLoaderParallelTest.kt` | 생성 |
| Redisson opt-in API | `exposed/jdbc-redisson/src/main/kotlin/io/bluetape4k/exposed/redisson/map/ExposedEntityMapLoader.kt` | 수정 |
| Redisson parity/regression test | `exposed/jdbc-redisson/src/test/kotlin/io/bluetape4k/exposed/redisson/map/ExposedEntityMapLoaderParallelTest.kt` | 생성 |
| benchmark | `benchmark/exposed-benchmark/src/benchmark/kotlin/io/bluetape4k/exposed/benchmark/jdbc/JdbcKeyEnumerationBenchmark.kt` | 생성 |
| benchmark config | `benchmark/exposed-benchmark/build.gradle.kts` | 수정 |
| benchmark analysis | `docs/benchmarks/exposed-benchmark-2026-08-19-issue-690/README.md` 및 raw JSON | 생성 |
| chart source/output | `docs/images/readme-charts/exposed-jdbc-key-enumeration-issue-690.{svg,png,semantic.json}` | 생성 |
| public usage docs | `exposed/jdbc-lettuce/README.md`, `README.ko.md`, `exposed/jdbc-redisson/README.md`, `README.ko.md` 및 touched KDoc | 최소 수정 |
| durable lesson | `docs/lessons/2026-08-19-issue-690-virtual-thread-key-enumeration.md` | 생성 |

`docs/manual/**`, settings/catalog, BOM, workflow, R2DBC loader, suspended loader는
변경하지 않는다. 새 dependency는 추가하지 않는다.

## Task 0: baseline과 issue/stack metadata 고정

**Files:**

- Inspect only: live #690/#659, `develop`, existing benchmark/readme/chart paths

- [x] `git status`, branch, worktree, exact base SHA를 기록한다.
- [x] baseline targeted test를 실행한다.

Run:

```bash
./gradlew :bluetape4k-exposed-jdbc:test \
  --tests 'io.bluetape4k.exposed.jdbc.VirtualThreadTransactionTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache \
  --no-parallel --max-workers=1 --console=plain
```

Expected: `SUCCESS`, 25 tests, failure/error 0. Loader baseline은 helper test를
추가하기 전 현재 `ExposedEntityMapLoaderTest` selector로 별도 실행한다.

Rollback: canonical `develop`와 기존 `TEST_APPLY_PATCH_TMP.txt`는 수정하지 않는다.

## Task 1: 공용 API의 RED 테스트 작성

**Files:**

- Test: `exposed/jdbc/src/test/kotlin/io/bluetape4k/exposed/jdbc/JdbcParallelKeyEnumerationTest.kt`

- [ ] `JdbcKeyRange`, `JdbcParallelKeyEnumerationOptions`,
  `parallelJdbcKeyEnumeration`을 호출하는 H2 fixture를 먼저 작성한다.
- [ ] 다음 테스트를 각각 작성한다.

```kotlin
@Test fun `disjoint ranges are merged in range order without duplicates`()
@Test fun `sparse IDs and open outer bounds are included once`()
@Test fun `overlap and reverse ranges are rejected before a transaction`()
@Test fun `custom comparator is honored for range validation`()
@Test fun `non-positive maxConcurrency is rejected`()
@Test fun `active transactions never exceed maxConcurrency`()
@Test fun `failed range cancels siblings and preserves the cause`()
@Test fun `shutdown executor is rejected without opening a transaction`()
```

- [ ] active transaction recorder는 range statement 안에서 `AtomicInteger`를
  증가·감소하고 최대값을 기록한다. sleep은 짧은 test-only delay로만 사용한다.
- [ ] 실패 test는 한 range의 query callback이 예외를 던지도록 H2 table/fixture를
  분리하고 sibling completion signal을 확인한다. 이를 위해 production public
  overload는 기본 range reader를 사용하고, 같은 `exposed/jdbc` test source set에서만
  호출하는 `internal` range-reader overload를 둔다. 이 overload는 실제 transaction
  lifecycle을 유지한 채 한 range의 reader를 실패시켜 sibling cancellation을
  검증하며 public ABI에는 노출하지 않는다.

Run:

```bash
./gradlew :bluetape4k-exposed-jdbc:test \
  --tests 'io.bluetape4k.exposed.jdbc.JdbcParallelKeyEnumerationTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache \
  --no-parallel --max-workers=1 --console=plain
```

Expected RED: production symbols are absent or tests fail at the intended missing
API/contract assertion, not at fixture compilation. Fix test-only errors until the
failure is feature-shaped before writing production code.

## Task 2: 공용 helper 최소 구현

**Files:**

- Create: `exposed/jdbc/src/main/kotlin/io/bluetape4k/exposed/jdbc/JdbcParallelKeyEnumeration.kt`

- [ ] `JdbcKeyRange`와 options에 Korean KDoc, positive `maxConcurrency`, executor
  ownership, database resolution, weak-consistency와 pool 책임을 기록한다.
- [ ] range validator는 empty list를 즉시 empty list로 반환하고, null outer bound,
  strict lower/upper ordering, adjacent non-overlap을 comparator로 검증한다.
- [ ] natural comparator는 `Comparable`을 확인하고 custom `Comparable` ID는 DB 정렬과
  일치하는 explicit comparator를 선택할 수 있게 한다. `!!`와 broad unsafe cast는
  사용하지 않는다.
- [ ] Exposed `greaterEq`/`less`의 `Comparable` bound 때문에 non-`Comparable` custom
  ID binding은 이번 slot의 지원 범위가 아님을 KDoc/README와 lesson에서 숨기지 않는다.
- [ ] database는 `options.database ?: TransactionManager.currentOrNull()?.db
  ?: TransactionManager.defaultDatabase` 순서로 결정하고 없으면 명확한
  `IllegalStateException`으로 거부한다.
- [ ] caller executor에 at most `maxConcurrency` range task만 제출한다. 각 task는
  `virtualThreadJdbcTransactionAsync`와 동일한 isolation/readOnly semantics로
  독립 transaction을 열고 raw ID column predicate와 PK ASC를 사용한다. internal
  test reader overload는 동일한 transaction wrapper 안에서만 reader를 바꾼다.
- [ ] 결과는 입력 range 순서로 await/flatten하며 `distinct()`로 계약 오류를 숨기지
  않는다.
- [ ] await/submit/interrupt failure 시 모든 future를 `cancel(true)`하고 종료를
  기다린 뒤 `ExecutionException` wrapper를 벗겨 원인을 재전파한다. interrupt flag는
  복원한다. helper는 custom executor를 close하지 않는다.

Run the Task 1 selector again. Expected GREEN: all helper tests pass and existing
`:bluetape4k-exposed-jdbc:test` remains green.

## Task 3: JDBC Lettuce loader additive API

**Files:**

- Modify: `exposed/jdbc-lettuce/src/main/kotlin/io/bluetape4k/exposed/lettuce/map/ExposedEntityMapLoader.kt`
- Create: `exposed/jdbc-lettuce/src/test/kotlin/io/bluetape4k/exposed/lettuce/map/ExposedEntityMapLoaderParallelTest.kt`

- [ ] `loadAllKeysInParallel(ranges, options)`를 공용 helper로 위임한다.
- [ ] 기존 `loadAllKeys(): Iterable<ID>`, `loadAllIds()`와 constructor를 변경하지
  않는다. parallel method가 반환하는 `List`는 opt-in materialization임을 KDoc로
  명시한다.
- [ ] test는 batchSize 2 H2 fixture에서 sequential IDs와 parallel IDs가 동일하고,
  `[null, 4)`, `[4, null)` 인접 range에 duplicate가 없음을 고정한다.
- [ ] empty table/empty range와 default sequential lazy test를 같은 fixture에서
  회귀 확인한다.

Run:

```bash
./gradlew :bluetape4k-exposed-jdbc-lettuce:test \
  --tests 'io.bluetape4k.exposed.lettuce.map.ExposedEntityMapLoaderParallelTest' \
  --tests 'io.bluetape4k.exposed.lettuce.map.ExposedEntityMapLoaderTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache \
  --no-parallel --max-workers=1 --console=plain
```

Expected: new and existing tests pass; SQL remains keyset/parallel opt-in only.

## Task 4: JDBC Redisson loader additive API

**Files:**

- Modify: `exposed/jdbc-redisson/src/main/kotlin/io/bluetape4k/exposed/redisson/map/ExposedEntityMapLoader.kt`
- Create: `exposed/jdbc-redisson/src/test/kotlin/io/bluetape4k/exposed/redisson/map/ExposedEntityMapLoaderParallelTest.kt`

- [ ] Lettuce와 동일한 method signature/KDoc 계약을 추가한다.
- [ ] existing Redisson `MapLoader.loadAllKeys(): Iterable<ID>?` callback, query timeout,
  logging, constructor descriptor는 변경하지 않는다.
- [ ] H2에서 sequential callback 결과와 parallel helper 결과를 비교하고, empty,
  sparse, overlap rejection과 `maxConcurrency`를 검증한다.
- [ ] 기존 `ExposedEntityMapLoaderTest`와 suspended loader test는 수정하지 않고
  regression selector로 실행한다.

Run:

```bash
./gradlew :bluetape4k-exposed-jdbc-redisson:test \
  --tests 'io.bluetape4k.exposed.redisson.map.ExposedEntityMapLoaderParallelTest' \
  --tests 'io.bluetape4k.exposed.redisson.map.ExposedEntityMapLoaderTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache \
  --no-parallel --max-workers=1 --console=plain
```

Expected: all selected tests pass with no R2DBC or suspended source changes.

## Task 5: benchmark and chart RED/GREEN

**Files:**

- Create: `benchmark/exposed-benchmark/src/benchmark/kotlin/io/bluetape4k/exposed/benchmark/jdbc/JdbcKeyEnumerationBenchmark.kt`
- Modify: `benchmark/exposed-benchmark/build.gradle.kts`, `benchmark/exposed-benchmark/README.md`, `benchmark/exposed-benchmark/README.ko.md`
- Create: `docs/benchmarks/exposed-benchmark-2026-08-19-issue-690/README.md` and raw JSON
- Create: `docs/images/readme-charts/exposed-jdbc-key-enumeration-issue-690.svg`, `.png`, `.semantic.json`

- [ ] benchmark fixture seeds deterministic H2 rows and creates the same disjoint
  ranges for sequential and parallel cases.
- [ ] JMH methods measure sequential keyset, `maxConcurrency=2`, and
  `maxConcurrency=4`; no benchmark method changes the default loader path.
- [ ] add `jdbcKeyEnumeration` configuration with 1 warmup/3 iterations/1 second,
  `reportFormat=json`, and restrict include to the new class.
- [ ] run three sequential benchmark invocations; preserve raw JSON and choose median
  values with environment, command, row count, range count, pool size, and caveats.
- [ ] load `$bluetape-diagram` chart/common/chart/semantic-ledger rules. Create a
  source-backed semantic ledger with unique series IDs and no connector topology.
  Use a static SVG bar chart because exact values and axes carry the meaning. Render
  with CairoSVG, run text/visual/asset-pair audits, open the full-size PNG, and record
  dimensions/occupancy.
- [ ] README EN/KO tables and chart embed use the same values and state that H2 does not
  prove PostgreSQL/MySQL superiority, 단일 읽기 일관성 기준, or pool-independent speed.

Expected: benchmark task compiles; chart is not published until raw JSON and PNG audit
evidence exist. If a run is too expensive, retain `N/A` with exact command/log rather
than inventing a performance claim.

## Task 6: public docs and lesson parity

**Files:**

- Modify only the existing cache-loader sections in
  `exposed/jdbc-lettuce/README.md`, `README.ko.md`, `exposed/jdbc-redisson/README.md`,
  `README.ko.md`, and KDoc in the changed source.
- Create: `docs/lessons/2026-08-19-issue-690-virtual-thread-key-enumeration.md`

- [ ] document range shape, `maxConcurrency`, executor/database ownership, ordered merge,
  materialization cost, weak consistency, and sequential fallback selection.
- [ ] state that R2DBC/suspended adapters and custom ID fallback are outside this slot.
- [ ] keep `docs/manual/**` at stable `1.12.1`; run a diff guard proving no manual path changed.
- [ ] lesson records design choice, actual benchmark median/evidence, failure/cancel proof,
  P2 follow-ups, and future guard. Run Korean terminology audit on every changed Korean
  artifact with `audit-korean-terms.mjs`.

Expected: EN/KO claims, KDoc, design, plan, benchmark analysis, and lesson agree with
the implementation; no unsupported speed claim remains.

## Task 7: compile, targeted/full validation, and ABI

**Files:**

- Inspect all changed files and generated reports; no source expansion without a new plan.

- [ ] run affected compile and targeted tests sequentially with `--rerun-tasks`.
- [ ] run full `:bluetape4k-exposed-jdbc:test`, `:bluetape4k-exposed-jdbc-lettuce:test`,
  `:bluetape4k-exposed-jdbc-redisson:test` sequentially; run Testcontainers backends
  separately when Docker is available.
- [ ] run `./gradlew detekt --no-parallel --max-workers=1 --console=plain` and
  `git diff --check`.
- [ ] compare `javap -public -s` baseline/candidate for helper and both loader classes;
  existing descriptors must remain and only additive methods/value objects may appear.
- [ ] inspect generated test XML totals, failures, errors, skipped, and benchmark/chart
  audit logs. A skipped or 0-test task is not counted as success.
- [ ] run manual inventory/readme source-link checks only if affected paths require it;
  `docs/manual/**` remains unchanged and is reported as intentional N/A.

## Task 8: design/plan/code review convergence

- [ ] run six review lenses for spec/plan and then final code: performance, stability,
  security, operator/Ops, developer/API, user/caller.
- [ ] integration table normalizes P0/P1/P2/P3, deduplicates findings, and maps every
  P0/P1 to a concrete repair and rerun. Do not proceed to PR with P0/P1.
- [ ] performance lens checks allocation of range lists, connection pool bound, query
  round trips, and benchmark evidence; stability lens checks failure/interrupt/future
  cleanup; API lens checks source/ABI/additive surface; caller lens checks misuse/errors
  and docs; Ops/security lenses record concrete N/A reasons.
- [ ] apply `$bluetape-writer` SPW-01..05 to the integrated review and lesson.

Expected: latest integrated review table has `P0=0`, `P1=0`; P2/P3 are fixed or linked
to a follow-up issue with evidence.

## Task 9: commit, PR, CI, and merge-ready DoD

- [ ] commit design/plan before production implementation, then implementation/docs/chart
  in small Lore-protocol commits. Every commit has intent, Constraint, Rejected,
  Confidence, Scope-risk, Directive, Tested, and Not-tested trailers as applicable.
- [ ] update live #690 and Epic #659 stack metadata only after implementation is green;
  read issue/labels/milestone/assignee back. Keep #692 after #690 and do not close #689.
- [ ] create PR targeting `develop` from `perf/virtual-thread-key-enumeration` only after
  final review and user-authorized PR delivery. PR body ends with exactly `## DoD Status`
  and reconciled check totals.
- [ ] wait for live CI/reviews; do not merge from green CI alone. Merge requires a fresh
  exact-head approval. After approval, verify merge SHA, sync canonical `develop`, and
  safely remove only the proven merged worktree/branch.

## Rollback and rerun points

- RED fixture/compile failure: change only test code and rerun the same selector until
  the intended missing contract is observed.
- range predicate/type failure: retain loader generic bound; repair raw column cast or
  comparator contract, then rerun helper and loader tests.
- cancellation/interrupt leaves active transaction: stop GREEN, inspect raw future/DB
  logs, repair cleanup, and rerun lifecycle tests before any benchmark.
- benchmark/chart failure: retain source JSON and mark metric N/A; never substitute a
  single best run for the three-run median.
- ABI/manual diff failure: revert only the offending additive surface or docs path; do
  not change canonical `develop` or stable manual sources destructively.

## Traceability

| Issue #690 acceptance | Plan task/evidence |
| --- | --- |
| range partition/order/read-consistency contract | Design, Task 2, Task 6 |
| bounded execution and pool cap | Task 1, Task 2, Task 7 |
| sequential parity and benchmark | Task 3, Task 4, Task 5, Task 7 |
| failure/cancellation cleanup | Task 1, Task 2, Task 7 |
| default path/API/docs parity | Task 3, Task 4, Task 6, Task 7 |

## Plan DoD

- [x] exact files, ownership, order, tests, docs, benchmark, hazards, rollback, and
  commands are listed.
- [x] no task depends on a later artifact; RED precedes production code.
- [x] R2DBC/suspended/manual/non-H2 limits are explicit.
- [ ] six-lens plan review and integrated P0/P1 convergence completed.
- [ ] implementation, verification, PR/CI, merge, sync, and cleanup completed.

상태: `EXECUTION_READY` — 승인된 설계와 첫 계획을 기준으로 다음 단계는 Task 1
RED 테스트 작성이다.
