# Issue #692 cache loader custom ID·driver parity 실행 계획

> **실행 규칙:** 이 계획은 승인된 설계와 `$bluetape-workflow`,
> `$bluetape-kotlin-patterns`, `$bluetape-writer`, `$test-driven-development`를
> 적용한다. 설계·계획 승인 전에는 구현 worktree, production source, PR을 만들지
> 않는다. 이번 slot은 test-only conformance와 문서 정합성 보정이며 public API/ABI,
> cache data model, stable manual은 변경하지 않는다.

## 목표와 현재 기준

- Issue: [#692](https://github.com/bluetape4k/bluetape4k-exposed/issues/692)
- Epic: [#659](https://github.com/bluetape4k/bluetape4k-exposed/issues/659) 후속
  stacked train slot 7
- 선행: #690 merge와 현재 `develop` `736f07d5be05f17ff2e057586e40d27d59cd0c20`
- 예정 branch: `test/cache-loader-driver-parity`
- 대상 릴리스: `1.13.0` 개발선
- non-H2 최소 driver: PostgreSQL (`TestDB.POSTGRESQL`, 기존 Testcontainers)
- MySQL 8: #698이 소유하므로 이번 계획에서는 `N/A`로 기록
- 안정 manual: `docs/manual/**`의 `1.12.1` ref를 유지

## 현재 검증 결과 (2026-08-20)

최종 구현 worktree에서 fresh XML과 명령 결과를 집계했다. PostgreSQL selector는
`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE`가 Colima socket을 덮어쓰지 않도록 해당
환경 변수만 제거하고 실행했다. production timeout 값/단위는 변경하지 않았으며,
그 계약은 [Issue #699](https://github.com/bluetape4k/bluetape4k-exposed/issues/699)에
남겼다. #699가 statement-timeout 값·단위·cleanup 구현을 소유하고, #692는
custom fixture와 R2DBC 60초 enumeration evidence를 제공한다.

| 범위 | tests | failures | errors | skipped | 명령/근거 |
|---|---:|---:|---:|---:|---|
| JDBC Lettuce H2 full | 892 | 0 | 0 | 73 | `EXPOSED_TEST_DB=H2 ...:bluetape4k-exposed-jdbc-lettuce:cleanTest ...:test` |
| JDBC Redisson H2 full | 630 | 0 | 0 | 1 | `EXPOSED_TEST_DB=H2 ...:bluetape4k-exposed-jdbc-redisson:cleanTest ...:test` |
| R2DBC Redisson H2 full | 220 | 0 | 0 | 2 | `EXPOSED_TEST_DB=H2 ...:bluetape4k-exposed-r2dbc-redisson:cleanTest ...:test` |
| JDBC Lettuce PostgreSQL custom selector | 6 | 0 | 0 | 0 | `EXPOSED_TEST_DB=POSTGRESQL ...:bluetape4k-exposed-jdbc-lettuce:test --tests '*Issue692CustomIdLoaderTest'` |
| JDBC Redisson PostgreSQL custom selector | 4 | 0 | 0 | 0 | `EXPOSED_TEST_DB=POSTGRESQL ...:bluetape4k-exposed-jdbc-redisson:test --tests '*Issue692CustomIdLoaderTest'` |
| R2DBC Redisson H2+PostgreSQL custom selector + 60초 timeout | 12 | 0 | 0 | 1 | `EXPOSED_TEST_DB=POSTGRESQL EXPOSED_ISSUE_692_TIMEOUT_TEST=true ...:bluetape4k-exposed-r2dbc-redisson:test --tests '*Issue692CustomIdLoaderTest'` |

affected module detekt 3개는 모두 `BUILD SUCCESSFUL`, `git diff --check`와 terminology
audit(설계·계획·review·lesson·EN/KO README 7개 대상, finding 0)도 통과했다. EN/KO
README 이미지 수와 module-local 링크는 일치하며, JDBC Lettuce의 기존
`../../infra/lettuce` cross-repository reference 2건은 현재 tree에서 대상이 없어
`N/A`로 남겼다. Type A lesson은
[`docs/lessons/2026-08-20-issue-692-cache-loader-driver-parity.md`](../../lessons/2026-08-20-issue-692-cache-loader-driver-parity.md)에
보존했다. PostgreSQL 전체 모듈 회귀는 이 slot의 targeted conformance와
분리해 nightly/CI에서 수행한다.

### 최종 산출물

| 책임 | 예정 파일 | 변경 내용 |
|---|---|---|
| JDBC Lettuce custom fixture | `exposed/jdbc-lettuce/src/test/kotlin/io/bluetape4k/exposed/lettuce/map/Issue692CustomIdFixture.kt` 및 `Issue692CustomIdLoaderTest.kt` | test-local `IdTable<CustomId>`, suspended `List`의 H2/PostgreSQL parity, SQL/page/cancellation 증거 |
| JDBC Redisson custom fixture | `exposed/jdbc-redisson/src/test/kotlin/io/bluetape4k/exposed/redisson/map/Issue692CustomIdFixture.kt` 및 `Issue692CustomIdLoaderTest.kt` | synchronous loader의 offset/mutation/단일 transaction materialization 경계 |
| R2DBC Redisson custom fixture | `exposed/r2dbc-redisson/src/test/kotlin/io/bluetape4k/exposed/r2dbc/redisson/map/Issue692CustomIdFixture.kt` 및 `Issue692CustomIdLoaderTest.kt` | `AsyncIterator` offset, cancellation, PostgreSQL timeout/producer fault, top-level/ambient retry |
| 문서 정합성 | 세 모듈 `README.md`/`README.ko.md`와 해당 loader KDoc | 실제 adapter별 fallback·timeout·retry·connection/cancellation 정책을 EN/KO로 정렬 |

새 production helper, 외부 dependency, workflow, benchmark/chart, BOM/catalog,
`docs/manual/**`는 변경 목록에 들어가면 즉시 중단하고 범위를 재검토한다.
JDBC Lettuce synchronous와 JDBC Redisson suspended는 optional evidence로만 남기며,
해당 surface를 구현하기 위한 추가 production 변경이나 필수 DoD를 만들지 않는다.

## Task 0 — authority·worktree·baseline 고정

**상태:** 완료 (2026-08-20)

1. [Issue #699](https://github.com/bluetape4k/bluetape4k-exposed/issues/699)로 등록한
   Exposed `queryTimeout` 단위/값 production bug를 이 계획과 향후 PR에 연결한다.
   #699가 live 상태로 확인되지 않으면 구현과 PR handoff를 시작하지 않는다. #692의
   test-only 범위에는 production timeout 수정이 들어오지 않는다.
2. canonical `develop`에서 사용자 소유의 `TEST_APPLY_PATCH_TMP.txt`와 기존 worktree를
   보존한 채 다음 worktree를 만든다.

   ```bash
   git worktree add .worktrees/issue-692-cache-loader-driver-parity \
     -b test/cache-loader-driver-parity develop
   ```

3. worktree에서 `git rev-parse HEAD`, `git status --short --branch`, `git diff --check`를
   기록한다. 기준 head가 `736f07d5be05f17ff2e057586e40d27d59cd0c20`과 다르면 live
   `develop`를 다시 읽고 계획의 base를 갱신한다.
4. live Issue #692/#659와 선행 PR #695/#696, 기존 #689 lesson을 다시 읽는다. GNO는
   보조 검색으로만 사용하고, 현재 GitHub metadata와 source가 우선한다.
5. 다음 H2 baseline을 각 모듈에서 순차 실행한다. XML의 tests/failures/errors/skipped를
   기록하고 `BUILD SUCCESSFUL`만으로 대체하지 않는다.

   ```bash
   ./gradlew :bluetape4k-exposed-jdbc-lettuce:test \
     --tests '*ExposedEntityMapLoaderTest' --rerun-tasks --no-build-cache \
     --no-configuration-cache --no-parallel --max-workers=1 --console=plain
   ./gradlew :bluetape4k-exposed-jdbc-redisson:test \
     --tests '*ExposedEntityMapLoaderTest' --rerun-tasks --no-build-cache \
     --no-configuration-cache --no-parallel --max-workers=1 --console=plain
   ./gradlew :bluetape4k-exposed-r2dbc-redisson:test \
     --tests '*ExposedEntityMapLoaderTest' --rerun-tasks --no-build-cache \
     --no-configuration-cache --no-parallel --max-workers=1 --console=plain
   ```

Rollback: 이 task는 canonical branch를 변경하지 않는다. Docker가 없으면 H2 baseline과
   PostgreSQL `N/A`를 분리해 기록한다.

## Task 1 — test-local custom ID fixture와 RED/shape 검증

**상태:** 완료 (2026-08-20)

각 모듈의 fixture 파일에 다음을 구현한다.

- `data class CustomId(val value: String)` — `Comparable`을 구현하지 않는다.
- `varchar("id", 32).transform(::CustomId, CustomId::value).entityId()`로
  `ColumnWithTransform` 기반 ID column을 구성한다.
- `IdTable<CustomId>`의 transformed `id`와 explicit `PrimaryKey`.
- module별 고유 table name과 `name` column.
- `insert { it[id] = CustomId("a01") }`를 사용하고 `EntityID.value`를 assertion에서
  `CustomId.value`로 비교한다.

공용 `withTables`/`withTablesSuspending` 또는 R2DBC `withTables`를 재사용한다. setup
transaction을 commit한 뒤 page mutation을 시작해야 하므로, 해당 test helper에는
`setup`, `runLoader`, `cleanup` 단계를 분리한다. writer는 같은 URL/credential의
별도 connection을 쓰며, Exposed transaction이 반환된 뒤 connection을 닫는다.

다음 test selector를 먼저 작성한다.

```text
custom ID fallback emits ordered bounded pages
custom ID fallback SQL uses OFFSET and never keyset predicate
custom ID page mutation records weak-consistency observation
custom ID empty/partial pages keep cardinality and termination
```

RED/shape 확인은 “현재 code가 실패해야 한다”는 뜻이 아니라, keyset mutant,
전체 materialization mutant, duplicate를 `distinct()`로 숨기는 mutant가 assertion을
통과하지 못하는지 확인하는 방식으로 수행한다. 테스트만으로 현재 구현이 GREEN이면
production 변경을 추가하지 않는다.

## Task 2 — 세 module 대표 surface의 정적 parity 증거

**상태:** 완료 (2026-08-20)

### 2-A. JDBC Lettuce suspended

- `batchSize=2`, `a01..a05`를 소비해 ASC, size 5, distinct, SELECT 3을 확인한다.
- 각 page row 상한과 전체 suspended transaction/materialization 경계를 확인한다.
- `withTimeout` 취소가 `CancellationException` 전파, partial list 미반환, 후속
  재조회를 보존하는지 확인한다. low-level transaction/connection close event는
  driver-specific 후속 검증이며 page-level downstream cancellation은 `N/A`다.

### 2-B. JDBC Redisson synchronous

- synchronous loader는 `loadAllKeys()!!.toList()`의 ASC/size/distinct와 `OFFSET`
  SQL을 확인한다.
- page mutation은 첫 data SELECT 뒤 별도 writer connection에서 수행하고,
  weak-consistency 관찰값과 다음 page query 순서를 확인한다.
- source의 `queryTimeout=30_000`은 Exposed 단위가 초임을 기록하는 정합성 evidence로만
  남긴다. 30초 query timeout 동작은 후속 production bug의 `N/A`다.

### 2-C. R2DBC Redisson

- `AsyncIterator`로 동일 custom ID를 수집하고 `OFFSET`, ASC, size 5, distinct,
  page row 상한을 확인한다.
- `R2dbcEntityMapLoader`의 rendezvous channel과 기존 `hasNext`/`next` cause
  propagation 테스트를 concrete table에서도 재현한다.
- top-level 전체 enumeration timeout은 실제 `withTimeoutOrNull(60_000 ms)` 원인을
  iterator에 전달하는 PostgreSQL nightly test로 확인한다.
- top-level/ambient retry는 실제 transient `R2dbcException`을 producer lambda에서
  첫 ID 방출 뒤 발생시키고 `await()`로 관찰한다. ambient 검증에서는 collector를
  caller-owned outer `suspendTransaction { ... }` 안에 두어 outer transaction이
  channel cause를 받아 전체 collector block을 재시도하게 한다. emitted ID를
  보존하는 sink는 transaction block 밖에 둔다. top-level은 한 번만 시도하고
  ambient `maxAttempts=2`는 외부 sink에 partial ID를 재방출할 수 있음을 고정한다.
- `queryTimeout=30_000`의 “30초” 해석은 하지 않는다. Exposed 단위(초)와 실제
  60초 전체 enumeration timeout을 분리해 문서화한다.

SQL recorder는 `SELECT`만 남기고 dialect quoting을 정규화하지 않는다. assertion은
`OFFSET`/`LIMIT`/`>`의 존재와 query 수에만 의존해 H2/PostgreSQL 표기 차이를
허용한다. heap 전체 크기를 acceptance 숫자로 삼지 않는다.

## Task 3 — page mutation·caller cancellation·producer fault

**상태:** 완료 (2026-08-20)

### 3-A. READ_COMMITTED page mutation

각 adapter에서 첫 page barrier 후 별도 writer connection으로 다음을 한 transaction에
수행한다.

```text
DELETE a03
INSERT a99
COMMIT
```

bounded barrier를 adapter별로 적용한다.

- JDBC Lettuce suspended와 JDBC Redisson synchronous: test-only `SqlLogger`가
  첫 data SELECT에서 barrier를 열고 writer 완료를 기다린다.
- R2DBC Redisson: test-only `SqlLogger`가 첫 data SELECT를 관찰한 뒤 writer를
  실행한다. rendezvous channel back-pressure와 writer 완료 barrier로 다음 page
  query 이전에 mutation이 끝나도록 한다.

기대 관찰값은 `[a01, a02, a04, a05, a99]`이며, 삭제된 `a03` 재방출과 duplicate는
실패다. 이 test는 `READ_COMMITTED`에만 적용한다. MySQL `REPEATABLE_READ`,
SERIALIZABLE, 별도 읽기 기준/isolation 정책은 해당 driver issue에서 별도로 검증한다.

### 3-B. cancellation

- JDBC Lettuce suspended: test-only blocking page barrier에서 `withTimeout`이
  `CancellationException`을 재전파하고 partial list를 반환하지 않으며 후속
  재조회가 가능한지 확인한다. `List` API의 downstream page cancellation과
  low-level connection close event는 `N/A`로 보고한다.
- R2DBC Redisson: caller가 주입한 scope를 첫 page 이후 취소하고 bounded join으로
  취소가 완료되며 다음 page SELECT가 실행되지 않는지 확인한다. producer 오류의
  root cause/exceptional completion은 별도 3-C 테스트에서 검증한다.

`CancellationException`을 정상 빈 결과로 바꾸거나 `runCatching`으로 삼키는 assertion은
허용하지 않는다. 모든 latch/await에는 bounded timeout과 cleanup `finally`가 있어야 한다.

### 3-C. producer error

첫 page 뒤 test-only writer/fixture가 table을 제거하거나 fault를 발생시켜 다음
SELECT를 실패시킨다. R2DBC는 일반 `IllegalStateException`이 아니라
`R2dbcTransientResourceException` 같은 실제 `R2dbcException`을 producer lambda에서
발생시키고, `.get()`이 아닌 coroutine `await()`로 `hasNext`/`next` exceptional
completion과 root cause를 확인한다. top-level `maxAttempts=1`은 첫 partial ID 뒤
한 번만 실패해야 하며, ambient `maxAttempts=2`는 caller-owned retry로 partial ID가
외부 sink에 재방출될 수 있음을 expected risk로 기록한다. ambient test의 emitted ID
목록은 transaction block 밖의 mutable sink에 보존해 retry 전후를 비교한다. JDBC
loader의 직접 오류 표면은 이번 slot의 필수 acceptance가 아니며, partial ID가 정상
종료로 오인되거나 top-level이 재시도하면 P1이다.

## Task 4 — PostgreSQL driver conformance

**상태:** 완료 (2026-08-20)

실행 조건:

```bash
env -u TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE EXPOSED_TEST_DB=POSTGRESQL \
TESTCONTAINERS_RYUK_DISABLED=true \
./gradlew :bluetape4k-exposed-jdbc-lettuce:test \
  --tests '*Issue692CustomIdLoaderTest' --rerun-tasks --no-build-cache \
  --no-configuration-cache --no-parallel --max-workers=1 --console=plain
```

같은 selector를 `:bluetape4k-exposed-jdbc-redisson:test`,
`:bluetape4k-exposed-r2dbc-redisson:test`에 각각 순차 실행해 모두 통과했다. Docker/
Testcontainers가 없는 환경에서는 해당 command, 환경, 원인을 `N/A`로 남긴다. H2
pass를 PostgreSQL pass로 대체하지 않으며, hosted/nightly PostgreSQL 전체 회귀 PASS
전에는 PR DoD와 Issue #692를 `PENDING`으로 유지하고 merge하지 않는다.

PostgreSQL에서 다음을 증명한다.

1. 세 required surface의 custom ID ordering/OFFSET/page mutation/cardinality.
2. R2DBC 전체 enumeration의 실제 `withTimeoutOrNull(60_000 ms)` timeout 원인 전달.
3. producer가 첫 ID를 방출한 뒤 실제 `R2dbcTransientResourceException`을 발생시키고,
   coroutine `await()`가 root cause를 보존하는지 확인한다.
4. top-level은 `maxAttempts=1`로 한 번만 시도하고, caller context를 보존한 ambient
   R2DBC transaction의 `maxAttempts=2`는 외부 sink에서 partial ID를 재방출할 수 있음을
   expected caller-owned risk로 기록한다.
5. `queryTimeout=30_000`은 Exposed 단위가 초이므로 “30초” 동작은 주장하지 않는다.
   JDBC Lettuce의 명시적 timeout 없음과 JDBC Redisson/R2DBC의 실제 30초 query timeout은
   `N/A`로 남긴다. statement-timeout 값·단위·cleanup 구현은 #699가 소유하고,
   #692는 custom fixture와 R2DBC 60초 enumeration evidence를 제공한다.

PostgreSQL fault는 table/statement fault로 한정한다. network proxy와 TCP reset은
별도 후속 issue로 분리한다.

## Task 5 — EN/KO README·KDoc 정합성

**상태:** 완료 (2026-08-20)

다음 문서를 실제 결과에 맞춰 최소 수정한다.

- `exposed/jdbc-lettuce/README.md`
- `exposed/jdbc-lettuce/README.ko.md`
- `exposed/jdbc-redisson/README.md`
- `exposed/jdbc-redisson/README.ko.md`
- `exposed/r2dbc-redisson/README.md`
- `exposed/r2dbc-redisson/README.ko.md`
- 위 세 모듈의 `ExposedEntityMapLoader.kt`, `SuspendedExposedEntityMapLoader.kt`,
  `R2dbcEntityMapLoader.kt`, `R2dbcExposedEntityMapLoader.kt` KDoc(실제 모순이 있는
  파일만 수정)

EN/KO에 같은 의미를 유지한다.

- scalar keyset과 custom ID offset fallback
- READ_COMMITTED page mutation의 weak consistency와 단일 읽기 기준 비보장
- suspended `List`의 전체 transaction/connection 경계
- R2DBC의 rendezvous back-pressure, cancellation, producer error와 top-level/ambient retry
- adapter별 `queryTimeout`과 `maxAttempts` 차이
- MySQL은 #698, network fault는 별도 후속 범위

문서 작업 중 stable `docs/manual/**` 링크·버전은 수정하지 않는다. 변경 후 다음
terminology audit를 실행하고 모든 finding을 문맥별로 해결한다.

```bash
node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs \
  docs/superpowers/specs/2026-08-20-issue-692-cache-loader-driver-parity-design.md \
  docs/superpowers/plans/2026-08-20-issue-692-cache-loader-driver-parity-plan.md \
  docs/review/2026-08-20-issue-692-cache-loader-driver-parity-review.md \
  docs/lessons/2026-08-20-issue-692-cache-loader-driver-parity.md \
  exposed/jdbc-lettuce/README.ko.md exposed/jdbc-redisson/README.ko.md \
  exposed/r2dbc-redisson/README.ko.md
```

`docs/review/...`는 independent 7-Tier review를 실제로 기록할 때 생성한다. 리뷰
artifact가 생성되지 않았다면 command에서 해당 path를 제거하고, 최종 DoD에는
review evidence를 `PENDING`으로 남긴다.

Type A lesson gate는 다음 파일을 committed output으로 유지한다.

```text
docs/lessons/2026-08-20-issue-692-cache-loader-driver-parity.md
```

## Task 6 — regression·정적 검증

**상태:** 완료 (2026-08-20)

Testcontainers-backed 검증은 repository guard에 따라 모듈별·backend별 순차 실행한다.

1. 세 module의 new selector H2 targeted test.
2. 세 module full test.
3. PostgreSQL targeted selector를 세 affected module에서 순차 실행. PostgreSQL full
   module 회귀는 nightly/CI 범위로 분리한다.
4. 각 affected module detekt와 Kotlin compile.
5. `git diff --check`와 Markdown/source link·EN/KO parity 검사. module-local 링크와
   이미지 pair는 통과하고, 기존 cross-repository `../../infra/lettuce` 2건은 N/A로
   기록한다.
6. `git diff --name-only`로 production behavior/public API/ABI, catalog/BOM, workflow,
   benchmark/chart, `docs/manual/**`가 바뀌지 않았고, main Kotlin 변경이 KDoc/comment-only임을
   확인한다.

예시 명령:

```bash
./gradlew :bluetape4k-exposed-jdbc-lettuce:test \
  --tests '*Issue692CustomIdLoaderTest' --rerun-tasks --no-build-cache \
  --no-configuration-cache --no-parallel --max-workers=1 --console=plain
./gradlew :bluetape4k-exposed-jdbc-redisson:test \
  --tests '*Issue692CustomIdLoaderTest' --rerun-tasks --no-build-cache \
  --no-configuration-cache --no-parallel --max-workers=1 --console=plain
./gradlew :bluetape4k-exposed-r2dbc-redisson:test \
  --tests '*Issue692CustomIdLoaderTest' --rerun-tasks --no-build-cache \
  --no-configuration-cache --no-parallel --max-workers=1 --console=plain
./gradlew :bluetape4k-exposed-jdbc-lettuce:detekt \
  :bluetape4k-exposed-jdbc-redisson:detekt \
  :bluetape4k-exposed-r2dbc-redisson:detekt \
  --no-parallel --max-workers=1 --console=plain
git diff --check
```

fresh XML에서 `tests`, `failures`, `errors`, `skipped`를 읽어 DoD 표에 기록한다.
PostgreSQL이 실행되지 않는 환경에서는 command·환경·원인을 `N/A`로 분리해 기록하고,
비-H2 acceptance 항목을 unchecked로 남긴다. 현재 결과에서는 세 adapter의
PostgreSQL targeted selector가 통과했지만, full module nightly/CI 증거 전 merge gate는
`Blocked: 1`이며 `N/A`를 성공으로 합산하지 않는다.

## Task 7 — independent review·PR handoff gate

**상태:** 완료 (P0=0/P1=0/P2=3, WATCH); PR handoff는 별도 승인 필요

- [x] 7-Tier reviewer가 custom column binding, connection ownership, retry/cancellation,
  PostgreSQL evidence, EN/KO parity를 읽기 전용으로 검토한다.
- [x] `docs/review/2026-08-20-issue-692-cache-loader-driver-parity-review.md`에
  P0/P1/P2와 `CLEAR/WATCH/BLOCK`을 기록하고 설계·계획과 source line을 대조한다.
- [ ] PR body는 한국어로 작성하고 마지막 heading은 정확히 `## DoD Status`로 둔다.
  Required checks 합계와 `N/A`/`Blocked`를 XML·CI와 일치시킨다.
- [ ] PR 생성 후 exact head, base `develop`, labels/milestone/assignee, linked Issue
  #692, review thread, required CI, mergeability를 다시 읽는다.
- [ ] merge는 별도 fresh approval 전까지 실행하지 않는다. green CI만으로 merge하지 않는다.

## 롤백·중단 조건

- test-only fixture가 flaky하거나 connection/transaction leak를 보이면 해당 test
  파일만 제거·수정하고 production source를 우회 수정하지 않는다.
- custom ID가 keyset으로 선택되거나, duplicate/lost row, swallowed cancellation,
  producer error masking, top-level retry 재방출이 발견되면 P0/P1로 승격하고 PR을
  만들지 않는다. ambient outer retry의 partial ID 재방출은 caller-owned expected
  risk이므로 문서·테스트에서 명시하고 자체적으로 blocker로 올리지 않는다.
- PostgreSQL/Testcontainers가 동일한 외부 환경 조건으로 세 번 실패하면 code defect로
  분류하지 않고 명령·환경·로그·후속 CI issue를 남긴다.
- MySQL, network proxy, Virtual Thread parallel API가 diff에 나타나면 해당 후속 범위로
  되돌리고 현재 slot을 중단한다.
- canonical `develop`, 사용자 파일 `TEST_APPLY_PATCH_TMP.txt`, unrelated worktree는
  삭제·reset·정리하지 않는다.

## 계획 DoD

- [x] 세 required adapter surface의 실제 custom `IdTable` OFFSET/order/cardinality/
  duplicate evidence
- [x] READ_COMMITTED page mutation과 adapter별 cancellation/producer error evidence
- [x] PostgreSQL ordering, 실제 60초 전체 timeout, producer error, top-level/ambient
  retry evidence. full module nightly evidence는 별도 merge gate로 남긴다.
- [x] EN/KO README·KDoc parity와 stable manual/API/ABI 경계 확인
- [x] affected tests, detekt, compile, diff-check, terminology audit fresh 결과
- [x] independent 7-Tier review와 review artifact를 source line·fresh evidence와
  함께 완료했다.
- [ ] PR DoD, CI, exact-head merge gate는 PR 생성·별도 승인 이후 보고한다.

최종 구현·독립 리뷰 상태는 `DONE`이다. PR DoD·hosted CI·exact-head merge gate는
아직 실행하지 않았으므로 delivery 상태는 `PENDING/WATCH`다. 설계·계획 승인,
worktree 고정, test-first 실행, 구현, 검증 순서를 모두 이번 실행에서 완료했다.

## SPW-01~05 및 자연스러움 확인

- [x] SPW-01 — Issue/Epic/선행 head, audience(구현자·reviewer), test-only 목적,
  source paths, PostgreSQL/#698 및 network follow-up 경계와 미확인 external evidence를 고정했다.
- [x] SPW-02 — task dependency, exact files, RED/GREEN shape, backend commands,
  expected evidence, rollback, stop condition, approval/merge gates를 포함했다.
- [x] SPW-03 — 한국어 technical register와 `OFFSET`, `keyset`, `weak consistency`,
  `N/A`, `PENDING` token을 보존하고 문장·표·명령을 자연스럽게 정리했다.
- [x] SPW-04 — 설계 문서, live Issue/Epic, loader/base source, TestDB helper,
  #689 lesson과 acceptance mapping을 대조했다.
- [x] SPW-05 — 최종 Markdown read-back으로 heading/table/code fence/commands와
  계획 DoD·approval boundary를 확인했다.
