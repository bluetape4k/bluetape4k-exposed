# #874 JDBC V2 RowBinary 실제 서버·배치 경계 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. 이 세션은 독립 lane dispatch 실패 시 inline fallback 규칙에 따라 주 세션에서 실행한다.

**Goal:** 실제 ClickHouse JDBC V2 서버에서 RowBinary writer·JDBC fallback·flush·cleanup 계약을 반복 검증하고 그 증적을 EN/KO 문서에 반영한다.

**Architecture:** production `ClickHouseRowBinaryExecutor`와 public provider는 변경하지 않는다. `ClickHouseRowBinaryIntegrationTest`의 실제 Testcontainers provider를 관찰 가능한 proxy로 확장하고, fixture unit test는 sentinel·before-first-byte·no-replay 계약을 계속 담당한다. 실제 wire benchmark는 재현 가능한 입력과 provenance가 확보될 때만 선택적으로 실행하며, fixture benchmark와 별도 artifact로 구분한다.

**Tech Stack:** Kotlin, JUnit 5, Exposed JDBC, ClickHouse JDBC V2 catalog alias, Testcontainers ClickHouse, Hikari observation, Markdown/JSON evidence.

---

## 승인 범위와 traceability

| 설계 수용 기준 | 구현 task |
|---|---|
| 실제 server writer/fallback 선택과 row count | Task 2 |
| empty/single/multi-flush, default/`NULL`, fallback/no-replay, sentinel, close/reuse | Task 1, Task 2 |
| driver/server/image/source SHA와 selector receipt | Task 3 |
| benchmark provenance와 chart 조건부 갱신 | Task 4 |
| EN/KO README 및 실행 예제 동기화 | Task 5 |
| targeted test, detekt/API, diff check, CI receipt | Task 6 |

## 파일 책임 지도

- Modify `exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseRowBinaryIntegrationTest.kt`: 실제 서버 행렬, statement/flush/close observation, 고정된 table lifecycle.
- Modify `exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseRowBinaryTest.kt`: in-memory failure/sentinel/no-replay 회귀가 새 통합 계약과 일치하는지 보강.
- Modify `exposed/clickhouse/README.md` and `exposed/clickhouse/README.ko.md`: 실제 selector와 fixture/wire provenance 설명.
- Create `docs/superpowers/verification/2026-09-13-issue-874-rowbinary-e2e.md`: 실행 command, source/driver/server/image metadata, 결과와 N/A 판정.
- Conditional modify `docs/benchmarks/clickhouse-v2-rowbinary/*`, `docs/images/readme-charts/*`: 실제 wire benchmark가 실행되고 기존 chart contract를 충족할 때만 변경.
- No production Kotlin, dependency catalog, workflow YAML, public API signature changes.

## Task 1: 관찰 가능한 실제 연결 provider를 먼저 고정한다

**Files:**
- Modify: `exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseRowBinaryIntegrationTest.kt`
- Test: same file

- [x] **Step 1: RED — flush/close receipt assertion을 추가한다.**

`RecordingProvider`에 `openedProfiles`, `preparedStatementClasses`, `executeBatchSizes`, `statementCloseCount`, `connectionCloseCount`를 노출하고, 기존 enabled/fallback test에서 `true`, `false`, `[2]`, `[1]`, close exactly-once를 기대한다. 구현 전에는 새 counters가 없으므로 compile failure가 아닌 assertion/기호 failure가 발생해야 한다.

- [x] **Step 2: RED 확인**

```bash
./gradlew :bluetape4k-exposed-clickhouse:test \
  --tests '*ClickHouseRowBinaryIntegrationTest' \
  -PclickhouseV2Integration=true \
  --no-parallel --max-workers=1 --no-daemon --console=plain
```

예상 결과: 새 receipt assertion이 아직 구현되지 않아 실패한다. Docker가 없으면 해당 환경 blocker를 기록하고 unit RED를 별도로 확인한다.

- [x] **Step 3: GREEN — JDBC proxy에 최소 observation을 구현한다.**

`recordingConnection`은 `prepareStatement` 반환 객체를 `PreparedStatement` proxy로 감싸고 `addBatch` 누적 수, `executeBatch` 시점의 chunk 크기, `close` exactly-once를 기록한다. connection proxy의 `close`도 `AtomicBoolean`으로 한 번만 센다. `invokeJdbc`로 delegate 예외를 원형 그대로 전달하고 credential/SQL payload를 receipt에 기록하지 않는다.

- [x] **Step 4: GREEN 확인**

```bash
./gradlew :bluetape4k-exposed-clickhouse:test \
  --tests '*ClickHouseRowBinaryIntegrationTest.enabled*' \
  -PclickhouseV2Integration=true \
  --no-parallel --max-workers=1 --no-daemon --console=plain
```

예상 결과: enabled/fallback statement class와 close/flush counters가 통과한다.

- [x] **Step 5: REFACTOR — provider helper의 소유권을 정리한다.**

DDL/read connection은 `use`로 닫고 executor가 빌린 connection만 provider가 추적한다. 기존 `RecordingProvider.open(rowBinaryEnabled)` contract와 실제 `DriverManager` profile property를 유지한다.

## Task 2: 실제 서버의 batch/flush/default/fallback 행렬을 확장한다

**Files:**
- Modify: `exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseRowBinaryIntegrationTest.kt`
- Test: `exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseRowBinaryTest.kt`

- [x] **Step 1: RED — 실제 table assertion을 행렬로 추가한다.**

고정 table에 `id Int32`와 `label String DEFAULT 'default-label'`을 만들고 다음 독립 test를 먼저 추가한다.

```kotlin
@Test
fun `실제 V2 writer는 다중 flush와 default를 보존한다`() {
    // maxRowsPerFlush=2, id 1..3, writer path, row count 3, default label count 3
}

@Test
fun `실제 V2 fallback은 unsupported SQL을 한 번만 실행한다`() {
    // INSERT ... SELECT, fallback path, PreparedStatementImpl, row count +1
}
```

`NULL`은 driver setter capability가 실제로 지원하는 경우에만 nullable table/row를 추가하고, 지원하지 않으면 before-first-byte fallback receipt와 N/A를 남긴다. unit fixture에서는 `ClickHouseRowBinaryResult.updateCounts`, `acceptedCount`, `acceptedCountMayBeIncomplete`와 `Statement.SUCCESS_NO_INFO`/`EXECUTE_FAILED`를 계속 검증한다.

- [x] **Step 2: RED 확인**

```bash
./gradlew :bluetape4k-exposed-clickhouse:test \
  --tests '*ClickHouseRowBinaryIntegrationTest' \
  -PclickhouseV2Integration=true \
  --no-parallel --max-workers=1 --no-daemon --console=plain
```

예상 결과: 새 test가 아직 행렬 helper/DDL/assertion을 갖지 않아 실패한다.

- [x] **Step 3: GREEN — 최소 행렬 구현**

각 test는 새 table을 만들지 않고 class-level 고정 이름에 suffix를 붙여 충돌을 방지한다. `executeBatch` 결과의 `path`, `updateCounts`, `acceptedCount`, `acceptedCountMayBeIncomplete`를 확인하고, 별도 read connection으로 `count()`와 `countIf`를 조회한다. `provider.statementClasses`에서 enabled에는 `WriterStatementImpl`, fallback에는 `PreparedStatementImpl`가 각각 존재해야 한다.

- [x] **Step 4: 실패 경계 회귀를 유지한다.**

`ClickHouseRowBinaryTest`의 fixture cases에 before-first-byte unsupported setter, first-byte failure no-replay, partial sentinel, empty input, close exactly-once가 모두 남아 있는지 확인한다. 실제 driver에서 강제할 수 없는 실패는 fixture로 증명하며 실제 server 성공으로 대체하지 않는다.

- [x] **Step 5: GREEN 확인**

```bash
./gradlew :bluetape4k-exposed-clickhouse:test \
  --tests '*ClickHouseRowBinaryTest' \
  --tests '*ClickHouseRowBinaryIntegrationTest' \
  -PclickhouseV2Integration=true \
  --no-parallel --max-workers=1 --no-daemon --console=plain
```

예상 결과: unit과 실제 integration의 failures/errors/skipped가 0이며, Docker-backed test는 이 명령에서 순차적으로만 실행된다.

## Task 3: provenance와 verification artifact를 남긴다

**Files:**
- Create: `docs/superpowers/verification/2026-09-13-issue-874-rowbinary-e2e.md`

- [x] **Step 1: test output과 환경을 수집한다.**

```bash
git rev-parse HEAD
git status --short --untracked-files=all
./gradlew :bluetape4k-exposed-clickhouse:test \
  --tests '*ClickHouseRowBinaryIntegrationTest' \
  -PclickhouseV2Integration=true \
  --no-parallel --max-workers=1 --no-daemon --console=plain
```

- [x] **Step 2: artifact를 작성한다.**

문서에는 기준/실행 SHA, dirty 여부, Gradle task와 selector, catalog driver coordinate/version, ClickHouse image tag/digest, repeat count, statement class, flush/update counts, row count/default/NULL 결과, cleanup counters, 실패 또는 N/A 사유를 기록한다. SQL credential과 token은 기록하지 않는다.

- [x] **Step 3: read-back과 diff check**

```bash
git diff --check
sed -n '1,260p' docs/superpowers/verification/2026-09-13-issue-874-rowbinary-e2e.md
```

예상 결과: artifact의 모든 주장이 같은 실행 receipt를 가리키고, 빈 값·미완성 표식·민감정보가 없다.

## Task 4: benchmark/chart를 조건부로 재현한다

**Files:**
- Conditional modify: `docs/benchmarks/clickhouse-v2-rowbinary/*`
- Conditional modify: `docs/images/readme-charts/exposed-clickhouse-rowbinary-issue-867.*`

- [x] **Step 1: 기존 fixture benchmark provenance를 확인한다.**

`container=not-run`인 세 process fixture 결과를 실제 server 결과와 합치지 않는다. 실제 wire 수치가 필요하고 Testcontainers 실행 비용을 감당할 수 있을 때만 별도 output 이름과 metadata schema를 사용한다.

- [x] **Step 2: 선택 실행 — N/A (actual wire throughput을 측정하지 않음)**

```bash
for run in 1 2 3; do
  ./gradlew :bluetape4k-exposed-clickhouse:test \
    --tests '*ClickHouseRowBinaryBenchmarkTest' \
    -PclickhouseV2Benchmark=true -PclickhouseV2BenchmarkRun="$run" \
    --no-parallel --max-workers=1 --no-daemon --console=plain
done
```

재현 가능한 actual wire artifact가 없으면 이 task를 `N/A`로 기록하고 chart를 변경하지 않는다. 수치가 생긴 경우에만 `bluetape-diagram`의 chart checklist, CairoSVG SVG/PNG pair, semantic ledger, full-size PNG inspection을 수행한다.

## Task 5: EN/KO module README를 동기화한다

**Files:**
- Modify: `exposed/clickhouse/README.md`
- Modify: `exposed/clickhouse/README.ko.md`

- [x] **Step 1: 실제 결과를 반영할 문장을 먼저 고정한다.**

기존 RowBinary example/API token은 보존하고, 통합 selector가 확인하는 writer/fallback·flush·cleanup 범위와 fixture-only benchmark의 한계를 같은 의미로 추가한다. 실제 수치가 없으면 성능 문장을 추가하지 않는다.

- [x] **Step 2: EN/KO parity 확인**

```bash
  rg -n "ClickHouseRowBinaryOptions|executeBatch|RowBinary|clickhouseV2Integration|maxRowsPerFlush|fixture|wire" \
  exposed/clickhouse/README.md exposed/clickhouse/README.ko.md
node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs --json \
  exposed/clickhouse/README.ko.md docs/superpowers/verification/2026-09-13-issue-874-rowbinary-e2e.md
```

영어와 한국어는 직역이 아니라 동일한 API·수치·제약·selector를 설명해야 하며, audit finding은 모두 수정하거나 문맥상 예외를 artifact에 기록한다.

## Task 6: 전체 검증과 pre-PR evidence를 수렴한다

**Files:**
- Inspect all changed files in this branch.

- [x] **Step 1: targeted compile/test**

```bash
./gradlew :bluetape4k-exposed-clickhouse:test \
  --tests '*ClickHouseRowBinaryTest' \
  --no-parallel --max-workers=1 --no-daemon --console=plain
```

실제 Docker integration은 별도 명령으로 한 번에 순차 실행하고, XML에서 `failures=0`, `errors=0`, `skipped=0`을 확인한다.

- [x] **Step 2: static/API checks**

```bash
./gradlew :bluetape4k-exposed-clickhouse:detekt \
  :bluetape4k-exposed-clickhouse:compileKotlin \
  :bluetape4k-exposed-clickhouse:compileTestKotlin \
  --no-parallel --max-workers=1 --no-daemon --console=plain
git diff --check
```

public signature, deprecated import, receiver shadowing, credential redaction, and no new dependency를 확인한다. 변경이 test/docs뿐이면 ABI 결과는 N/A로 기록하되 compile 결과는 필요하다.

- [x] **Step 3: inline 7-Tier review**

현재 런타임에서 독립 code-review lane이 실패/비가용이면 아래 관점을 주 세션에서 각각 수행하고 **비독립 inline fallback**으로 기록한다.

| Tier | 확인 항목 |
|---|---|
| 1 성능 | fixture와 wire provenance, blocking/Testcontainers 비용, chart claim |
| 2 안정성 | fallback/replay, close exactly-once, pool reuse, test flake |
| 3 보안 | SQL/table 식별자, credential/exception redaction |
| 4 운영 | selector, image/version/SHA receipt, rerun/N/A 절차 |
| 5 개발/API | existing provider/result contract, Kotlin idiom, no public drift |
| 6 사용자 | EN/KO example, unsupported capability wording |
| 7 통합 | P0/P1 dedup, issue `Closes #874`, lesson/PR DoD evidence |

P0/P1이 발견되면 수정 후 영향 관점과 통합 검토를 반복한다. 1인 개발자 human-review lane은 N/A이며, review artifact에 근거와 범위를 기록한다.

- [ ] **Step 4: commit — PR branch finalization pending**

```bash
git add exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseRowBinaryIntegrationTest.kt \
  exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseRowBinaryTest.kt \
  exposed/clickhouse/README.md exposed/clickhouse/README.ko.md \
  docs/superpowers/specs/2026-09-13-clickhouse-v2-rowbinary-e2e-design.md \
  docs/superpowers/plans/2026-09-13-clickhouse-v2-rowbinary-e2e-plan.md \
  docs/superpowers/reviews/2026-09-13-clickhouse-v2-rowbinary-e2e-spec-review.md \
  docs/superpowers/verification/2026-09-13-issue-874-rowbinary-e2e.md
git commit -m "실제 ClickHouse 서버에서 RowBinary 배치 경계를 반복 검증한다" \
  -m "Constraint: 기존 public API와 driver dependency를 유지하고 실제 wire 증거 없이는 throughput SLO를 주장하지 않는다.
Rejected: 별도 wire benchmark harness 전면 도입 | Testcontainers 비용과 fixture provenance 혼동을 불필요하게 키운다.
Confidence: high
Scope-risk: moderate
Directive: 실제 측정이 없으면 fixture-only N/A를 유지한다.
Tested: targeted RowBinary unit/integration, detekt, compile, terminology audit, git diff --check
Not-tested: hosted CI와 merge 후 검증은 PR 단계에서 수행한다."
```

## Rollback/rerun

- integration failure: 마지막 변경 commit 이전 상태로 branch-local revert하고 unit fixture를 먼저 재실행한다. 기존 root dirty worktree에는 revert를 적용하지 않는다.
- Docker/image/driver 환경 failure: source 변경 없이 verification artifact에 `PENDING`/`N/A`와 원인을 기록하고 동일 selector를 재실행한다.
- benchmark provenance mismatch: raw JSON/chart를 commit하지 않고 fixture-only 문서 상태로 되돌린다.

## Plan SPW-01~05 자체 점검

- **SPW-01 PASS**: plan 독자, 목표, 기준 spec, source/selector/driver/image evidence와 미입증 성능 범위를 고정했다.
- **SPW-02 PASS**: task별 파일·RED/GREEN 명령·expected evidence·rollback·DoD를 포함했다.
- **SPW-03 PASS**: 한국어 기술 register와 API/driver token 보존, `행`/`정리`/`증적` 용어를 확인했다.
- **SPW-04 PASS**: spec acceptance와 Task 1~6 traceability 및 repository hazard를 매핑했다.
- **SPW-05 PASS**: 전체 plan을 read-back하고 미완성 표식을 제거했다.
