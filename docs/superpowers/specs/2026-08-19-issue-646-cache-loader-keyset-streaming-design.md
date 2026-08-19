# Issue #646 Exposed cache loader keyset·streaming 설계

## 문서 상태

- 대상 이슈: [#646](https://github.com/bluetape4k/bluetape4k-exposed/issues/646)
- Epic/stack: [#659](https://github.com/bluetape4k/bluetape4k-exposed/issues/659) Slot 2
- 대상 릴리스: `1.13.0` 개발선
- 기준 base: `develop` `1ae33ae8c8eef9d6ff2fa3b2fbb3705bf3b0e1f1`
- 구현 branch: `perf/cache-loader-keyset`
- 설계 상태: 사용자 승인 완료
- baseline: JDBC Lettuce 879, R2DBC Lettuce 144, JDBC Redisson 6 tests; failure/error 0

## 문제 정의

Exposed 기반 cache loader는 PK를 오름차순으로 읽으면서 `LIMIT/OFFSET` page를
반복한다. page 자체는 작지만 `buildList` 또는 `MutableList`에 모든 ID를 누적하므로
최종 메모리는 O(N)이다. offset이 커질수록 DB가 건너뛸 row도 늘어나며, page 사이에
행이 삽입·삭제되면 동일 row를 다시 읽거나 일부 row를 건너뛸 수 있다.

현재 명시 범위는 다음 세 concrete loader다.

- JDBC Lettuce `ExposedEntityMapLoader`
- R2DBC Lettuce `R2dbcExposedEntityMapLoader`
- JDBC Redisson suspended `SuspendedExposedEntityMapLoader`

Redisson `AsyncIterator`가 producer의 DB/channel 원인을 consumer에 전달하도록 공통
`SuspendedEntityMapLoader`의 경계 보정도 포함한다. 이 변경은 cache data model이나
public constructor를 바꾸지 않고, 이미 문서화된 오류 전파 계약을 실제로 구현한다.

JDBC Lettuce suspended, JDBC Redisson synchronous, R2DBC Redisson loader도 offset을
사용하지만 이번 slot의 명시 범위에는 넣지 않는다. 세 구현은 별도 후속 child issue로
등록한다.

## 용어와 선택 기준

| 용어 | 이 설계에서의 의미 |
| --- | --- |
| paging | 한 번의 DB 조회가 반환하는 row 수를 `batchSize`로 제한하는 조회 단위 |
| keyset paging | 이전 page의 마지막 raw PK를 다음 page의 `id > lastId` 경계로 사용하는 방식 |
| streaming | 호출자가 모든 ID를 먼저 materialize하지 않고 page 결과를 순차 소비하는 API 표면 |
| snapshot | 전체 열거 중 동일한 DB 관찰 시점을 유지한다는 별도 transaction/isolation 계약 |

streaming과 paging은 대체 관계가 아니다. 이번 구현은 **keyset paging을 transport로
삼고 streaming API로 소비**하는 하이브리드다.

## 채택한 설계

### 1. keyset page 조회

- 기본 정렬은 기존과 동일한 단일 PK ASC다.
- 첫 page는 predicate 없이 읽고, 이후 page는 raw primary key에
  `id > lastId` 경계를 적용한다.
- sparse ID에서는 실제 존재하는 다음 PK를 찾으므로 offset 보정이 필요 없다.
- 현재 public generic bound `ID: Any`를 `ID: Comparable<ID>`로 변경하지 않는다.
  지원되는 표준 scalar raw PK(`Long`, `Int`, `UUID`, 시간 타입 등)는 keyset을 사용하고,
  그 밖의 custom ID는 명시적으로 legacy offset fallback을 사용한다. fallback도 page
  단위로 읽어 전체 리스트 materialization은 하지 않는다. fallback은 weakly consistent한
  legacy 의미를 유지하므로 page 사이 삭제가 아직 관찰하지 않은 row를 건너뛸 수 있다.
- 정렬 조건을 추가하는 public API나 constructor parameter는 이번 slot에 넣지 않는다.

### 2. JDBC Lettuce `Iterable`

`MapLoader.loadAllKeys(): Iterable<ID>` 계약을 유지한다. concrete
`ExposedEntityMapLoader`가 lazy page-backed `Iterable`을 반환하고 ambient caller-owned
transaction이 없을 때 각 iterator의 page 조회를 `transaction` 경계에서 수행한다.
활성 transaction에서는 Exposed의 ambient 재사용 규칙을 따르며, 어느 경우에도
`Iterable` 소비가 끝나기 전에 Exposed `Query`나 JDBC connection이 반환된 iterator 뒤로
탈출하지 않는다.

기존 abstract `EntityMapLoader`의 사용자 subclass 계약은 건드리지 않는다. concrete
loader의 override만 변경해 source/ABI 범위를 좁힌다.

### 3. R2DBC Lettuce `Flow`

기존 `suspend loadAllKeys(): List<ID>`를 유지한다. `R2dbcEntityMapLoader`에 additive
`loadAllKeysFlow(): Flow<ID>`를 제공하고, concrete loader는 keyset page를 순차 조회해
emit한다. List API는 기존대로 전체 수집 결과를 반환하며, Flow API만 bounded consumer
메모리를 제공한다.

ambient caller-owned transaction이 없을 때 Flow page는 page 단위 `suspendTransaction`으로
읽고, 활성 transaction이 있으면 Exposed의 ambient 재사용 규칙을 따른다. downstream이 느리거나
취소되면 다음 page를 열지 않으며, ambient caller-owned transaction이 없을 때만
현재 page transaction이 취소 경로에서 닫힌다. ambient transaction의 수명은 caller가
소유한다. 따라서
Flow는 weakly-consistent enumeration이고, 완전한 snapshot은 보장하지 않는다.

### 4. JDBC Redisson suspended `AsyncIterator`

기존 RENDEZVOUS `Channel`과 Redisson `AsyncIterator`를 유지하고 producer의 page
predicate만 keyset으로 변경한다. 현재 loader base가 전체 producer를
`suspendTransaction`으로 감싸므로 이 adapter는 기존 transaction 경계를 유지한다.
channel 방출은 재생할 수 없는 외부 부작용이므로 producer transaction은
`maxAttempts = 1`로 고정하고, 재시도가 필요하면 호출자가 전체 열거를 다시 시작한다.
producer 오류는 channel cause에서 `hasNext`/`next`의 exceptional completion으로
전달하고 `CancellationException`은 삼키지 않는다. 일반 producer 오류는 child 밖으로
재전파하지 않아 caller-owned 일반 `Job`을 취소하지 않으며, producer/receiver child는
caller scope에 귀속되어 terminal 상태에서 종료된다.

Redisson `AsyncIterator`에는 `close()`가 없으므로 consumer가 조기 중단한 경우의
producer 취소는 caller-owned `CoroutineScope` 취소로 표현한다. 새 public close API는
추가하지 않는다.

### 5. Virtual Thread 병렬 질의

이번 slot의 기본 enumeration은 병렬화하지 않는다. keyset cursor는 이전 `lastId`에
의존하므로 page를 병렬화하려면 사전 range partition, 독립 JDBC connection, 결과
merge, ordering, snapshot, pool 상한을 새로 정의해야 한다. 기존
`virtualThreadJdbcTransactionAsync`는 독립 transaction 병렬 실행은 제공하지만
단일 Exposed transaction 또는 cursor를 thread-safe하게 만들지는 않는다.

Virtual Thread 병렬 enumeration은 fixed PK range benchmark와 opt-in API를 포함하는
별도 후속 이슈로 분리한다.

## 결과별 계약

| 상황 | 계약 |
| --- | --- |
| keyset 정상 page | PK ASC, page 간 duplicate 없음, 마지막 page에서 종료 |
| sparse ID | 존재하는 다음 PK를 keyset 경계로 사용 |
| page 사이 insert | 새 row의 PK가 `lastId`보다 크면 관찰될 수 있음 |
| keyset page 사이 delete | 삭제된 row는 건너뛰며 duplicate를 만들지 않음 |
| 지원 목록 밖의 custom ID | legacy offset fallback을 사용하고 KDoc/test로 명시; page 사이 삭제로 아직 관찰하지 않은 row를 건너뛸 수 있음 |
| downstream cancellation | Flow는 즉시 재전파하고 다음 page를 열지 않음; AsyncIterator는 scope 취소 필요 |
| DB/cache 오류 | producer/consumer 경계를 넘어 원인 예외를 전파하고 partial 성공을 정상 완료로 바꾸지 않음 |

## 검토한 대안

- **raw JDBC/R2DBC cursor를 반환한다**: Exposed transaction과 cursor lifetime이
  API 호출 밖으로 탈출한다. JDBC `Iterable`과 Redisson `AsyncIterator`에는 공통 close
  계약이 없어 resource leak 위험이 있으므로 채택하지 않는다.
- **모든 page를 한 transaction에서 읽는다**: snapshot에는 유리하지만 Flow의 느린
  consumer가 connection을 오래 점유한다. 기존 AsyncIterator adapter에는 유지하되,
  새 Flow/Iterable의 기본 경계로 일반화하지 않는다.
- **public `ID: Comparable<ID>` bound를 추가한다**: keyset 구현은 단순하지만 UUID와
  기존 custom ID source compatibility/ABI를 넓게 바꾼다. 보수적인 표준 scalar 지원
  판정과 fallback으로 기존 surface를 보존한다.
- **Virtual Thread로 page를 무조건 병렬 조회한다**: connection pool과 ordering,
  snapshot 계약을 새로 만들고 keyset의 순차 의존성을 우회한다. 성능 근거 없이
  기본값으로 채택하지 않는다.

## 검증 계약

- ordering, sparse ID, page boundary에서 duplicate 없음, empty table을 세 adapter에서
  검증한다.
- JDBC Lettuce `Iterable`은 첫 page만 소비해도 후속 page를 조회하지 않는 lazy
  behavior와 page 메모리 상한을 검증한다.
- R2DBC Lettuce `Flow`는 `take(1)`/실제 Job cancellation 후 producer와 transaction이
  종료되는지 검증하고 기존 List 결과와 동일한 ID set을 확인한다.
- JDBC Redisson suspended `AsyncIterator`는 page 경계를 넘는 순서, producer DB 오류,
  scope cancellation과 channel cause 전파를 검증한다. 공통 base의 `ChannelResult`
  원인 재전파도 회귀 테스트로 고정한다.
- SQL statement counter 또는 Exposed statement hook으로 page 수를 기록한다. large
  fixture에서는 heap 전체 크기 대신 page buffer cardinality와 query count를 측정해
  GC 노이즈를 피한다.
- 각 adapter의 keyset capability helper가 표준 scalar와 custom `Comparable` ID를
  구분하는지 단위 테스트로 고정하고, 100개 이상 fixture에서 `batchSize` 이하 page와
  예상 query count를 확인한다.
- 기존 public class의 `javap -public -s` baseline/candidate를 비교한다. additive
  Flow method 외에 constructor/generic bound/기존 method descriptor는 바뀌지 않아야
  한다. Kotlin compiler가 생성한 `access$` synthetic method의 변동은 지원 ABI에서
  제외하고, source-visible public surface만 판정한다.
- EN/KO README와 touched KDoc에 동일한 weak consistency, fallback, cancellation
  계약을 반영하고 `docs/manual/**`는 변경하지 않는다.

## 비목표와 경계

- 이번 slot에 JDBC Lettuce suspended, JDBC Redisson synchronous, R2DBC Redisson을
  수정하지 않는다. 후속 child issue 대상이다.
- Virtual Thread executor, parallelism 설정, range partition API, benchmark module을
  추가하지 않는다.
- cache data model, serialization, `get`, `getAll`, write-behind contract를 변경하지
  않는다.
- `docs/manual/**`는 안정 릴리스 `1.12.1` 기준을 유지한다.

## 승인 기준

1. 명시된 세 loader가 keyset page를 사용하고 기존 공개 API를 유지한다.
2. streaming 표면이 전체 ID materialization 없이 bounded page를 소비한다.
3. ordering, sparse ID, duplicate 방지, 동시 변경 의미, cancellation/error 전파가
   테스트와 KDoc/README에 일치한다.
4. 지원 목록 밖의 custom ID fallback이 명시되고 기존 source/ABI가 깨지지 않는다.
5. affected module tests, detekt/compile, ABI, README parity, `git diff --check`가
   fresh evidence로 통과한다.

## Writer DoD (SPW-01~05)

- [x] SPW-01 — Issue/Epic, 명시된 세 source path, 승인 범위, stable manual 경계와
  baseline evidence를 고정했다.
- [x] SPW-02 — 문제, 하이브리드 선택, adapter별 transaction/API 계약, fallback,
  동시성 경계, acceptance와 비목표를 포함했다.
- [x] SPW-03 — 한국어 technical register와 `keyset`, `paging`, `streaming`,
  `snapshot`, `fallback` 용어를 일관되게 사용했다.
- [x] SPW-04 — 현재 loader/base transaction, cursor pagination helper,
  virtual-thread helper, Issue #646 수용 기준을 대조했다.
- [x] SPW-05 — Markdown read-back으로 표, 링크, code token, scope boundary를
  확인했다.
