# Issue #646 cache loader keyset·streaming 구현 계획

> **준수 표면:** `$bluetape-workflow`, `$bluetape-kotlin-patterns`,
> `$bluetape-writer`, `test-driven-development`.
> 설계와 이 계획은 사용자 승인 후 작성했으며, 구현은 RED를 확인한 뒤 시작한다.

## 목표와 범위

- 대상 이슈: [#646](https://github.com/bluetape4k/bluetape4k-exposed/issues/646)
- Epic/stack: [#659](https://github.com/bluetape4k/bluetape4k-exposed/issues/659) Slot 2
- base: `develop` `1ae33ae8c8eef9d6ff2fa3b2fbb3705bf3b0e1f1`
- branch/worktree: `perf/cache-loader-keyset` /
  `.worktrees/issue-646-cache-loader-keyset`
- 구현 대상:
  1. JDBC Lettuce `ExposedEntityMapLoader`
  2. R2DBC Lettuce `R2dbcExposedEntityMapLoader`와 additive Flow API
  3. JDBC Redisson suspended `SuspendedExposedEntityMapLoader`
- 제외: JDBC Lettuce suspended, JDBC Redisson synchronous, R2DBC Redisson,
  virtual-thread parallel query, cache data model/serialization, `docs/manual/**`

## 파일 책임

1. `exposed/jdbc-lettuce/src/main/kotlin/io/bluetape4k/exposed/lettuce/map/ExposedEntityMapLoader.kt`
   - PK ASC keyset page query와 지원 목록 밖 custom ID의 legacy offset fallback을 구현한다.
   - `loadAllKeys()`를 lazy page-backed `Iterable`로 override한다.
   - ambient caller-owned transaction이 없을 때 iterator page마다 transaction을 열고,
     활성 transaction이 있으면 Exposed의 ambient 재사용 규칙을 따른다. Exposed
     `Query`/connection은 transaction 밖으로 반환하지 않는다.
2. `exposed/r2dbc-lettuce/src/main/kotlin/io/bluetape4k/exposed/r2dbc/lettuce/map/R2dbcEntityMapLoader.kt`
   - 기존 List API를 보존한다.
   - additive `loadAllKeysFlow(): Flow<ID>` surface와 KDoc를 추가한다.
3. `exposed/r2dbc-lettuce/src/main/kotlin/io/bluetape4k/exposed/r2dbc/lettuce/map/R2dbcExposedEntityMapLoader.kt`
   - keyset page를 Flow로 emit한다.
   - List API와 Flow API의 순서/ID set parity를 유지한다.
   - cancellation을 재전파하고, ambient caller-owned transaction이 없을 때만
     page-owned transaction을 닫는다.
4. `exposed/jdbc-redisson/src/main/kotlin/io/bluetape4k/exposed/redisson/map/SuspendedExposedEntityMapLoader.kt`
   - 기존 channel/AsyncIterator/back-pressure/error boundary를 유지한다.
   - producer의 offset query를 keyset query로 바꾼다.
   - scope 취소와 `CancellationException` 전파를 보존한다.
5. `exposed/jdbc-redisson/src/main/kotlin/io/bluetape4k/exposed/redisson/map/SuspendedEntityMapLoader.kt`
   - 기존 channel cause를 `AsyncIterator.hasNext`/`next`의 exceptional completion으로
     전달해 DB/cache 오류를 정상 종료로 마스킹하지 않는다.
6. 테스트 파일
   - `exposed/jdbc-lettuce/src/test/kotlin/.../ExposedEntityMapLoaderTest.kt`
     : ordering, sparse ID, lazy iterator, page counter/query evidence
   - `exposed/r2dbc-lettuce/src/test/kotlin/.../R2dbcExposedEntityMapLoaderTest.kt`
     : Flow/List parity, sparse ID, cancellation, query/page evidence
   - `exposed/jdbc-redisson/src/test/kotlin/.../SuspendedExposedEntityMapLoaderTest.kt`
     : AsyncIterator ordering, producer error/timeout cause, scope cancellation
   - 기존 loader/cache test는 regression만 수행하고 unrelated assertion은 바꾸지 않는다.
7. 공개 문서
   - touched KDoc와 세 adapter README EN/KO에 keyset, bounded streaming,
     weak consistency, fallback, cancellation 계약을 동일하게 기록한다.
   - README가 없는 adapter는 module README의 기존 cache loader section을 먼저
     확인하고 필요한 최소 영역만 추가한다.
8. `docs/lessons/2026-08-19-issue-646-cache-loader-keyset-streaming.md`
   - 최종 query/page evidence, transaction boundary, scope gap, virtual-thread
     follow-up과 재발 방지 guard를 기록한다.

## 구현 순서

### Task 0 — preflight와 baseline

- canonical `develop`과 승인 worktree가 서로 다른 변경을 갖지 않는지 확인한다.
- 기존 `TEST_APPLY_PATCH_TMP.txt`는 canonical 소유 파일이므로 건드리지 않는다.
- baseline targeted 결과를 fresh XML에서 재확인하고 test-spec에 고정한다.
- `git diff --check`가 baseline에서 깨끗한지 확인한다.

### Task 1 — RED 테스트

- T1~T8 test-spec에 맞는 test-only recorder와 fixture를 추가한다.
- 현재 구현에서 다음 RED를 각각 관찰한다.
  - offset predicate가 keyset boundary를 사용하지 않음
  - first iterator consumption 전에 전체 ID가 materialize됨
  - R2DBC Flow API가 없음
  - AsyncIterator producer cancellation/error contract가 새 assertion을 만족하지 않음
- 테스트가 현재 API를 직접 호출하지 못하는 경우 test helper를 최소 수정하되,
  production code는 아직 건드리지 않는다.

### Task 2 — JDBC Lettuce keyset/lazy 구현

- raw ID column을 기준으로 `lastId` 경계를 구성한다.
- `ID` generic bound를 넓히지 않고 보수적인 표준 scalar capability를 검사한다.
- 지원되는 표준 scalar PK에는 keyset, 그 밖의 custom ID에는 기존 offset fallback을 선택한다.
- lazy `Iterable` iterator는 page를 하나만 보유하고 다음 `next()`에서 필요할 때
  다음 transaction/page를 읽는다.
- `!!`, `synchronized`, blocking call, ID/payload 로그를 추가하지 않는다.

### Task 3 — R2DBC Lettuce Flow 구현

- abstract base에 additive `loadAllKeysFlow()`를 추가하고 기본 구현은 호환용으로
  기존 List 수집을 사용한다.
   - concrete loader는 page 단위 `suspendTransaction`과 keyset predicate를 사용해
     Flow를 구성한다. ambient caller-owned transaction이 있으면 Exposed의 재사용 규칙을
     따른다.
- `catch`에서 `CancellationException`을 삼키지 않고, downstream cancellation 뒤
  새 transaction을 열지 않음을 테스트한다.
- 기존 `loadAllKeys(): List<ID>` signature와 결과 순서를 유지한다.

### Task 4 — JDBC Redisson suspended keyset·error boundary 구현

- `SuspendedEntityMapLoader`의 outer `suspendTransaction`, channel capacity,
  error/cause 전달을 유지하되, channel 방출 재생을 막기 위해 producer transaction의
  `maxAttempts = 1`을 명시한다.
- concrete producer에서 `lastId`를 갱신하며 keyset page를 조회한다.
- producer 오류가 channel cause로 전달되고 일반 예외가 caller-owned 일반 `Job`으로
  재전파되지 않는지 확인한다. producer/receiver child가 정상·오류·취소 뒤 terminal
  상태로 끝나는지와 `channel.send` 실패, scope cancellation, DB exception/timeout cause를
  각각 확인한다.
- 공통 `SuspendedEntityMapLoader`가 `ChannelResult.exceptionOrNull()`을
  `CompletionException`으로 재전파하는지 회귀 테스트로 확인한다.

### Task 5 — 문서 parity와 lesson 초안

- KDoc는 current implementation과 제안 계약을 구분하지 않고 실제 동작만 설명한다.
- EN/KO README의 keyset/streaming/fallback/weak consistency 문장을 같은 의미로
  맞춘다. `docs/manual/**`는 안정 릴리스 경계 때문에 수정하지 않는다.
- benchmark 수치를 측정하지 못하면 성능 향상 표현을 삭제하고 query/page evidence만
  기록한다.

### Task 6 — GREEN 및 검증

- 세 module targeted tests를 순차 fresh 실행한다.
- 세 module full test를 `--no-parallel --max-workers=1`로 실행하고 XML tests,
  failures, errors, skipped를 합산한다.
- affected compile, detekt, `git diff --check`를 실행한다.
- public class와 file-facade baseline/candidate `javap -public -s`를 비교해 additive
  Flow/Iterable 외 descriptor drift가 없는지 확인한다. `@JvmSynthetic` capability helper와
  compiler-generated `access$` synthetic method는 지원 ABI 비교에서 제외한다.
- README EN/KO parity와 touched KDoc Korean register를 확인한다.

### Task 7 — 독립 review 및 후속 issue

- 7-Tier review에서 P0/P1 `0`을 확인한다.
- 명시 범위 밖 세 loader와 virtual-thread parallel enumeration의 후속 issue #689/#690을
  중복 확인 후 등록하고 Epic #659에 연결한다. issue/label/milestone/assignee를
  live read-back한다.
- P2는 이 PR에서 해결하거나 후속 issue 번호와 근거를 lesson에 기록한다.

## 검증 명령

```bash
./gradlew \
  :bluetape4k-exposed-jdbc-lettuce:test \
  :bluetape4k-exposed-r2dbc-lettuce:test \
  :bluetape4k-exposed-jdbc-redisson:test \
  --tests '*ExposedEntityMapLoaderTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache \
  --no-parallel --max-workers=1 --console=plain

./gradlew \
  :bluetape4k-exposed-jdbc-lettuce:test \
  :bluetape4k-exposed-r2dbc-lettuce:test \
  :bluetape4k-exposed-jdbc-redisson:test \
  --no-parallel --max-workers=1 --console=plain

./gradlew detekt --no-parallel --max-workers=1 --console=plain
git diff --check
```

R2DBC/Redisson Testcontainers backend은 H2 증거와 분리해 순차 실행한다. Docker나
driver를 사용할 수 없으면 0 test를 성공으로 세지 않고 raw log와 N/A 사유를 기록한다.

## 롤백과 재실행

- RED가 assertion/fixture 오류이면 test-only 변경만 고치고 같은 selector로 RED를
  다시 확인한다.
- keyset helper가 UUID/Exposed column type에서 compile 또는 SQL 오류를 내면 public
  bound 변경 없이 지원 판정/fallback 설계를 다시 검토한다.
- Flow cancellation에서 connection이 남거나 AsyncIterator producer가 종료되지
  않으면 GREEN을 주장하지 않고 transaction/scope 경계를 먼저 수정한다.
- full test의 backend/container 오류는 코드 실패와 분리해 원시 로그, 실제 test 수,
  재현 명령을 보존한다.
- PR 전에는 branch commit을 revert할 수 있지만 canonical `develop`, 기존
  worktree, manual 1.12.1 source를 destructive command로 바꾸지 않는다.

## DoD

- [x] 승인된 설계와 test-spec에 맞는 RED evidence가 fresh output으로 기록됐다.
- [x] 세 명시 loader가 keyset-paged streaming 계약으로 GREEN이다.
- [x] ordering/sparse/duplicate/concurrent mutation/cancellation/error/fallback이
  테스트와 KDoc/README에 일치한다.
- [x] affected full tests, compile/detekt, ABI, parity, diff check가 통과했다.
- [ ] lesson과 7-Tier independent review가 완료됐다.
- [x] 후속 issue #689/#690이 중복 확인과 metadata read-back을 거쳤다.
- [ ] PR/CI/merge/sync/cleanup은 별도 fresh exact-head authority gate다.

상태: `REVIEW_READY` — RED, targeted/full regression, ABI/parity, lesson과 후속 issue
등록까지 완료했으며 독립 7-Tier review와 PR authority gate를 진행한다.

## Writer DoD (SPW-01~05)

- [x] SPW-01 — 대상 독자, Issue/Epic, 기준 head, module, commands와 stable manual
  경계를 고정했다.
- [x] SPW-02 — 파일 책임, dependency order, RED/GREEN, rollback, verification,
  후속 issue와 approval gate를 포함했다.
- [x] SPW-03 — 한국어 technical register와 keyset/paging/streaming 용어를 유지하고
  code token과 commands를 보존했다.
- [x] SPW-04 — 승인 설계, test-spec, current loader/base source와 baseline evidence를
  대조했다.
- [x] SPW-05 — Markdown read-back으로 code fence, 목록, 링크와 unchecked DoD를
  확인했다.
