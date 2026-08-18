# Issue #646 keyset·streaming 테스트 명세

## 기준

- 설계: [Issue #646 설계](2026-08-19-issue-646-cache-loader-keyset-streaming-design.md)
- Issue/Epic: [#646](https://github.com/bluetape4k/bluetape4k-exposed/issues/646),
  [#659](https://github.com/bluetape4k/bluetape4k-exposed/issues/659)
- 기준 head: `1ae33ae8c8eef9d6ff2fa3b2fbb3705bf3b0e1f1`
- baseline 명령:

  ```bash
  ./gradlew \
    :bluetape4k-exposed-jdbc-lettuce:test \
    :bluetape4k-exposed-r2dbc-lettuce:test \
    :bluetape4k-exposed-jdbc-redisson:test \
    --tests '*ExposedEntityMapLoaderTest' \
    --rerun-tasks --no-build-cache --no-configuration-cache \
    --no-parallel --max-workers=1 --console=plain
  ```

- baseline 결과: JDBC Lettuce 879, R2DBC Lettuce 144, JDBC Redisson 6 tests;
  failure/error 0; skipped 73/4/0

## 공통 테스트 규칙

- JUnit 5 descriptive backtick test name과 Given/When/Then 구조를 사용한다.
- `bluetape4k-assertions` matcher와 `io.bluetape4k.assertions.assertFailsWith`를
  사용한다. 새 테스트에서 JUnit `assertThrows`, AssertJ, `!!`, 수동 continuation을
  사용하지 않는다.
- JDBC I/O는 `runSuspendIO` 또는 기존 `withTables` helper를 사용하고, R2DBC는
  `runSuspendIO`와 `withTables`를 사용한다.
- DB backend는 우선 H2에서 검증하고, Testcontainers backend는 모듈 정책에 따라
  순차 실행한다.
- cancellation은 실제 `Job.cancelAndJoin` 또는 scope 취소로 검증하며
  `CancellationException`을 삼키는 테스트를 허용하지 않는다.

## 테스트 케이스

### T1 — JDBC Lettuce keyset ordering과 sparse ID

대상: `ExposedEntityMapLoaderTest`

- fixture에 1, 100, 10_000처럼 간격이 있는 Long ID를 삽입한다.
- `batchSize=2`로 `loadAllKeys().toList()`를 소비한다.
- 기대값: PK ASC, 모든 ID 3개, duplicate 0.
- 기존 offset page를 유지한 mutant는 같은 결과를 만들 수 있으므로 query
  predicate/statement recorder로 `id > lastId` 경계를 확인한다.

### T2 — JDBC Lettuce lazy `Iterable`와 page memory

대상: `ExposedEntityMapLoaderTest`

- 5개 이상의 row를 `batchSize=2`로 준비한다.
- `iterator()`를 만들고 첫 ID만 소비한 뒤 page counter가 1인지 확인한다.
- 나머지를 소비한 뒤 page counter가 필요한 횟수로 증가하고 결과가 정렬되는지
  확인한다.
- 전체 ID를 constructor 시점 또는 `loadAllKeys()` 시점에 materialize한 mutant는
  첫 `next()` 전 page counter/loaded cardinality assertion을 통과할 수 없어야 한다.

### T3 — R2DBC Flow/List parity

대상: `R2dbcExposedEntityMapLoaderTest`

- `loadAllKeys()` 결과와 `loadAllKeysFlow().toList()` 결과의 ID set/order가 동일한지
  확인한다.
- `batchSize=2`, sparse ID, 빈 테이블을 포함한다.
- Flow에서 page마다 page buffer cardinality가 `batchSize` 이하인지 recorder로
  확인한다. List API의 최종 O(N) 반환은 기존 호환 계약으로 인정한다.

### T4 — R2DBC Flow downstream cancellation

대상: `R2dbcExposedEntityMapLoaderTest`

- 충분한 row를 준비하고 실제 collector `Job`으로 `loadAllKeysFlow().collect { ... }`를
  실행한 뒤 첫 emission에서 `cancelAndJoin()`한다. 기존 `take(1)` 경계 테스트도
  유지한다.
- collector Job이 취소된 뒤 producer가 종료되고 다음 page transaction을 열지 않는지
  확인한다.
- `CancellationException`이 정상 완료나 빈 결과로 변환되지 않아야 한다.

### T5 — JDBC Redisson suspended AsyncIterator

대상: `SuspendedExposedEntityMapLoaderTest` 신규 클래스

- `AsyncIterator.hasNext().await()`와 `next().await()`로 page 경계를 넘겨 소비한다.
- 기대값: PK ASC, sparse ID 포함, duplicate 0, `batchSize=2` 경계 통과.
- producer의 DB 예외는 `hasNext`/`next` CompletionStage에 원인과 함께 전달한다.
- 일반 `Exception`은 channel cause 단일 경계로 전달하고 fatal `Error`는 coroutine
  exception handler까지 재전파한다.
- caller-owned scope를 취소하면 producer transaction과 channel이 닫히고
  `CancellationException`이 재전파되며, terminal child가 남지 않는지 확인한다.

### T6 — 동시 변경 의미

대상: JDBC Lettuce, R2DBC Lettuce, JDBC Redisson suspended 각 loader

- page barrier에서 아직 방문하지 않은 큰 PK를 삽입하고 이미 방문한 row를 삭제한다.
- 기대값: 이미 방출한 ID의 duplicate가 없고, 삭제된 row는 다시 방출되지 않는다.
- 새로 삽입한 ID가 `lastId`보다 크면 관찰될 수 있음을 허용한다.
- 완전한 snapshot을 주장하는 assertion은 추가하지 않는다. adapter별 transaction
  경계를 설계 문서와 동일하게 유지한다.

현재 slot에서는 JDBC Lettuce와 R2DBC Lettuce에 page 사이 append/delete 회귀를
고정하고, Redisson은 outer producer transaction·AsyncIterator 경계를 별도 scope
취소/오류 테스트로 고정한다. 세 adapter의 독립 connection 동시 변경 fixture는
후속 parity issue #689의 driver 범위에서 보강한다.

### T7 — 지원 목록 밖 custom ID fallback

대상: keyset helper 단위 테스트 또는 concrete loader의 지원 판정 경로

- 표준 scalar 지원 목록 밖의 custom ID 경로를 준비할 수 있으면 legacy offset fallback이
  선택되는지 확인한다. 단순히 `Comparable`이라는 이유만으로 keyset을 선택해서는 안 된다.
- fallback에서도 page buffer는 bounded이고 정적 데이터의 order/duplicate 계약은 유지한다.
  page 사이 mutation에서는 legacy offset 의미에 따라 아직 관찰하지 않은 row가 누락될 수
  있으며, 완전한 snapshot이나 mutation 불변성을 주장하지 않는다.
- 해당 Exposed `IdTable`/column type을 H2에서 재현할 수 없으면, 지원 판정 helper의
  단위 테스트와 명시적 N/A 사유를 기록한다. 지원 목록 밖 타입을 silently keyset으로
  처리하는 테스트는 허용하지 않는다.

### T8 — query count와 memory evidence

대상: 세 loader 테스트 또는 module-local test utility

- 세 loader 각각 100개 이상 fixture와 작은 `batchSize`를 사용한다.
- statement counter로 keyset page query 횟수를 기록하고, 각 page SQL에 `LIMIT`이
  포함되어 `batchSize` bounded page를 유지하는지 확인한다.
- heap 전체 사용량을 단일 acceptance 숫자로 삼지 않는다. GC에 좌우되므로 page
  buffer cardinality와 query count를 재현 가능한 evidence로 남긴다.
- 기존 offset mutant와 비교해 query count/scan behavior가 개선되는지는 별도 benchmark
  근거가 없으면 정량 주장하지 않고, 이번 slot에서는 keyset predicate·page count·bounded
  cardinality만 회귀 증거로 기록한다.

### T9 — 기존 cache regression

대상: affected module 기존 map/repository test

- 단건 `load`, empty loader, `batchSize` validation, read-through cache, writer,
  serialization/data model을 회귀 실행한다.
- #626 mutex lifecycle와 write-behind 테스트는 이 issue에서 수정하지 않고,
  affected module full test로 regression만 확인한다.

## RED 단계

1. T1~T8에서 현재 offset/materialization 경로가 통과할 수 없는 assertion을 먼저
   추가한다. 특히 predicate recorder, first-page lazy counter, Flow cancellation,
   AsyncIterator error/cancellation을 production 변경 전에 실행한다.
2. 기대 RED는 keyset boundary 미검출, first page 전 materialization, Flow API 부재,
   producer cancellation 누락 또는 query/page cardinality assertion 실패다.
3. test-only helper가 현재 API를 직접 검증하지 못하면 helper를 먼저 수정하되,
   production loader가 여전히 RED임을 확인한다.

## GREEN 및 정적 검증

- RED 후 최소 production 변경으로 T1~T8을 GREEN으로 만든다.
- affected module targeted test를 fresh 실행한 뒤 세 module full test를 `--no-parallel`
  로 순차 실행한다.
- `detekt`, affected compile, `git diff --check`, public `javap -public -s` ABI 비교,
  EN/KO README parity를 실행한다.
- XML에서 실제 tests/failures/errors/skipped를 읽어 보고서에 기록한다. `BUILD
  SUCCESSFUL`만으로 test evidence를 대체하지 않는다.

## Stop conditions

- P0/P1: duplicate ID, lost ID, swallowed cancellation, producer error masking,
  transaction/connection leak, public ABI drift가 발견되면 즉시 중단한다.
- P2: unsupported fallback evidence 부족, statement counter의 backend N/A, stale
  documentation은 implementation scope 안에서 보완하거나 후속 issue로 기록한다.
- virtual-thread parallel query나 명시 범위 밖 loader를 구현하려는 변경은 이 test
  spec의 범위를 벗어나므로 별도 issue로 분리한다.

## Test-spec DoD

- [x] 대상 module, baseline command/result, test idiom과 backend 순서를 고정했다.
- [x] ordering, sparse ID, lazy/bounded memory, Flow/AsyncIterator cancellation,
  error, concurrent mutation, fallback, query evidence를 분리했다.
- [x] RED/GREEN 순서와 mutant-resistant assertion을 명시했다.
- [x] public ABI, README parity, diff check, full module regression의 종료 조건을
  포함했다.
