# Issue #690 Virtual Thread 기반 JDBC key enumeration 설계

## 문서 상태

- 대상 이슈: [#690](https://github.com/bluetape4k/bluetape4k-exposed/issues/690)
- Epic/stack: [#659](https://github.com/bluetape4k/bluetape4k-exposed/issues/659) Slot 4
- 선행 slot: #689 / merged PR #693
- 대상 릴리스: `1.13.0` 개발선
- 기준 base: `develop` `b334586863695fa7f020149477099f269d77a971`
- 구현 branch: `perf/virtual-thread-key-enumeration`
- worktree: `.worktrees/issue-690-virtual-thread-key-enumeration`
- 설계 상태: 사용자 승인 계획에 따라 구현 착수

## 문제 정의

Issue #646은 cache loader의 기본 경로를 단일 PK keyset page와 lazy
`Iterable`로 정리했다. 이 경로는 메모리와 page 사이 mutation 의미를 보수적으로
유지하지만, 하나의 keyset cursor가 이전 page의 마지막 ID에 의존하므로 page를
그대로 병렬화할 수 없다.

Issue #690은 이 순차 경로를 기본값으로 바꾸지 않고, 호출자가 명시한 PK range를
독립 JDBC transaction으로 조회하는 opt-in 경로를 추가한다. 각 range는 별도
connection을 사용하고, 결과는 range 선언 순서로 병합한다. 따라서 병렬성의 이득과
connection-pool 비용을 호출자가 함께 선택한다.

## 현재 구현 근거

- JDBC Lettuce `ExposedEntityMapLoader`는
  `exposed/jdbc-lettuce/src/main/kotlin/io/bluetape4k/exposed/lettuce/map/ExposedEntityMapLoader.kt`
  에서 keyset page를 lazy `Iterable`로 반환한다.
- JDBC Redisson `ExposedEntityMapLoader`는
  `exposed/jdbc-redisson/src/main/kotlin/io/bluetape4k/exposed/redisson/map/ExposedEntityMapLoader.kt`
  에서 동일한 keyset/offset fallback을 transaction 안에서 순차 수집한다.
- `exposed/jdbc/src/main/kotlin/io/bluetape4k/exposed/jdbc/VirtualThreadJdbcTransaction.kt`
  의 `virtualThreadJdbcTransactionAsync`는 지정한 `Database`에서 독립 JDBC
  transaction을 실행하고 `VirtualFuture`를 반환한다.
- 기존 `VirtualFuture.awaitAll()`은 결과를 입력 순서로 반환하지만 실행 중인 모든
  작업을 먼저 제출하므로 DB pool 상한을 표현하지 않는다. 이 설계에서는 range 제출
  자체를 `maxConcurrency`로 제한하고, 실패 시 모든 child future를 취소한다.
- `docs/superpowers/specs/2026-08-19-issue-646-cache-loader-keyset-streaming-design.md`
  는 Virtual Thread 병렬화를 독립 range partition·connection·merge·읽기 일관성
  계약이 필요한 후속 범위로 명시한다.

## 용어와 계약

| 용어 | 의미 |
| --- | --- |
| range | `[lowerInclusive, upperExclusive)` 형태의 PK 구간 |
| range partition | 서로 겹치지 않고 선언 순서로 정렬된 range 목록 |
| active transaction | range 하나를 읽는 독립 JDBC transaction |
| bounded concurrency | 동시에 제출·실행하는 range 작업 수가 `maxConcurrency` 이하인 상태 |
| weak consistency | range 사이의 insert/delete가 하나의 읽기 일관성 기준으로 고정되지 않는 관찰 의미 |

`JdbcKeyRange<ID>`는 다음 public value object로 추가한다.

```kotlin
data class JdbcKeyRange<ID: Any>(
    val lowerInclusive: ID? = null,
    val upperExclusive: ID? = null,
)
```

두 경계가 모두 `null`인 range는 허용하지 않는다. 첫 range의 lower bound와 마지막
range의 upper bound만 각각 생략할 수 있다. 양쪽 경계가 있으면 comparator 기준으로
`lowerInclusive < upperExclusive`여야 한다.

`JdbcParallelKeyEnumerationOptions<ID>`는 다음 선택을 고정한다.

```kotlin
data class JdbcParallelKeyEnumerationOptions<ID: Any>(
    val maxConcurrency: Int = 4,
    val executor: ExecutorService? = VirtualThreadExecutor,
    val database: Database? = null,
    val transactionIsolation: Int? = null,
    val readOnly: Boolean = true,
    val comparator: Comparator<in ID>? = null,
)
```

`maxConcurrency`는 양수여야 한다. `executor`가 `null`이면 기존 공유
`VirtualThreadExecutor`를 사용하고, 호출자가 넘긴 executor는 이 API가 닫지 않는다.
`database`가 `null`이면 현재 Exposed transaction의 database, 그 다음
`TransactionManager.defaultDatabase`를 사용한다. child transaction은 caller의
outer transaction을 재사용하지 않는다.

기본 comparator는 ID가 `Comparable`인 경우에만 사용한다. `Comparable`을 구현한 custom
ID도 DB PK 정렬과 일치하는 `Comparator`를 호출자가 명시할 수 있으며, 비교할 수 없는
경계는 조기에 거부한다. 기존 loader의
`ID: Any` generic bound나 constructor는 변경하지 않는다.

Exposed의 `greaterEq`/`less` 바인딩은 이 helper에서도 `Comparable` PK 값을 요구한다.
따라서 comparator만 제공해 non-`Comparable` custom ID를 새로 지원한다고 약속하지
않으며, 해당 column binding은 별도 설계 범위로 남긴다.

## 채택한 구조

### 1. 공용 JDBC helper

`exposed/jdbc/src/main/kotlin/io/bluetape4k/exposed/jdbc/JdbcParallelKeyEnumeration.kt`
에 다음 public 함수와 value object를 둔다.

```kotlin
fun <ID: Any> parallelJdbcKeyEnumeration(
    table: IdTable<ID>,
    ranges: List<JdbcKeyRange<ID>>,
    options: JdbcParallelKeyEnumerationOptions<ID> = JdbcParallelKeyEnumerationOptions(),
): List<ID>
```

helper는 다음 순서로 동작한다.

1. `maxConcurrency`, database, executor 상태와 range 경계를 검증한다.
2. range 목록이 선언 순서로 정렬되고 인접 range가 겹치지 않는지 comparator로
   확인한다. 이전 range의 upper bound가 `null`인데 다음 range가 있으면 거부한다.
3. 호출자가 제공한 executor에 range task를 최대 `maxConcurrency`개까지만 제출한다.
   각 task는 `virtualThreadJdbcTransactionAsync`와 같은 `transaction(db, ...,
   readOnly)` 의미로 독립 transaction을 연다.
4. 각 transaction에서 raw ID column에 lower/upper predicate를 적용하고 PK ASC로
   해당 range의 ID를 materialize한다.
5. 모든 future를 range 선언 순서로 await하고 `List<ID>`로 병합한다.
6. 하나라도 실패하거나 caller thread가 interrupt되면 모든 child future에
   `cancel(true)`를 요청하고 종료를 기다린 뒤 원래 원인을 다시 던진다.

실패·sibling 취소를 결정론적으로 검증하기 위해 `exposed/jdbc` test source set에서만
접근하는 `internal` range-reader overload를 둔다. public overload는 실제 Exposed
ID query reader를 사용하고, test overload도 동일한 transaction/future lifecycle을
유지한 채 한 range의 reader만 실패시킨다. 이 test hook은 public ABI와 loader API에
노출하지 않는다.

각 range의 결과는 병렬 완료 순서가 아니라 입력 range 순서로 병합한다. range가
겹치지 않는다는 검증이 있으므로 helper가 임의의 `distinct()`를 수행하지 않는다.
이를 통해 잘못된 partition을 조용히 숨기지 않고 caller가 즉시 수정하게 한다.

### 2. JDBC Lettuce loader

`ExposedEntityMapLoader`에 다음 additive method를 추가한다.

```kotlin
fun loadAllKeysInParallel(
    ranges: List<JdbcKeyRange<ID>>,
    options: JdbcParallelKeyEnumerationOptions<ID> = JdbcParallelKeyEnumerationOptions(),
): List<ID>
```

기존 `loadAllKeys(): Iterable<ID>`는 변경하지 않는다. 기본 경로는 여전히 lazy
keyset paging이고, 병렬 경로는 호출자가 명시적으로 선택할 때만 전체 결과를
materialize한다.

### 3. JDBC Redisson loader

동일한 additive method를
`exposed/jdbc-redisson/.../ExposedEntityMapLoader.kt`에 추가한다. Redisson의
기존 `MapLoader.loadAllKeys(): Iterable<ID>?` 호출 경로와 query timeout 및 logging
계약은 변경하지 않는다. 병렬 method는 Redisson callback이 아니라 공용 JDBC helper를
호출하므로 cache adapter와 transaction ownership을 분리한다.

### 4. 적용하지 않는 adapter

- R2DBC loader: JDBC connection/Virtual Thread 계약의 대상이 아니므로 변경하지 않는다.
- JDBC Redisson suspended `AsyncIterator`: producer scope와 back-pressure를 병렬
  range 결과와 결합하면 별도 cancellation/close 계약이 필요하므로 이번 slot에서
  제외한다.
- JDBC Lettuce suspended loader 및 기타 offset fallback loader: #692의 driver·custom
  ID parity 범위로 남긴다.

## 일관성·resource 계약

- **읽기 일관성**: range별 독립 transaction이므로 전체 결과에 동일한 읽기 일관성 기준을
  보장하지 않는다. page/range 사이 mutation은 관찰될 수도, 관찰되지 않을 수도 있다.
- **pool**: helper가 동시에 제출하는 range 수를 `maxConcurrency` 이하로 제한한다.
  caller는 Hikari/JDBC pool의 최대 connection 수보다 큰 값을 선택하지 않아야 한다.
- **executor**: 기본 executor는 공유 virtual-thread executor이며 helper가 종료하지
  않는다. custom executor의 생성·종료 책임은 caller에게 있다.
- **메모리**: 병렬 결과는 range별 `List`와 최종 병합 `List`를 보유한다. 메모리 제한이
  우선이면 기존 sequential streaming API를 사용해야 한다.
- **outer transaction**: child는 caller transaction의 uncommitted write를
  공유하지 않는다. 호출자가 명시한 `Database`에서 읽은 committed state를 기준으로
  동작한다.
- **취소/실패**: `CancellationException` 또는 interrupt를 정상 완료로 바꾸지 않는다.
  sibling future를 취소하고 await한 뒤 원인을 보존한다. JDBC driver가 interrupt를
  즉시 query cancel로 변환하지 않는 경우에도 transaction close를 기다린다.

## 실패 모드와 방어

| 실패 모드 | 방어와 검증 |
| --- | --- |
| overlapping 또는 역순 range | comparator로 조기 `require` 실패; 중복을 `distinct()`로 숨기지 않음 |
| `maxConcurrency`가 pool보다 큼 | 제출 수를 상한으로 제한하고 KDoc/README에 caller pool 책임 명시 |
| 한 range query 실패 | 모든 sibling future에 `cancel(true)` 후 종료 대기, 원래 예외 재전파 |
| caller interrupt | interrupt flag 복원, child 취소·정리 후 `InterruptedException` 재전파 |
| range 사이 insert/delete | weak consistency를 문서화하고 단일 읽기 일관성 기준을 보장하지 않음 |
| custom `Comparable` ID comparator 누락 | natural `Comparable`이 없으면 조기 거부; 기존 sequential fallback은 #692에서 검증 |
| empty range 목록 | side effect 없이 빈 list 반환; DB transaction을 열지 않음 |

## 검토한 대안

### A. caller-supplied disjoint range + bounded helper (채택)

range 경계와 pool 비용이 public contract에 드러나고 generic ID arithmetic을 새로
도입하지 않는다. 기존 constructor와 sequential API를 보존하며 H2에서 독립적으로
검증할 수 있다.

### B. loader가 min/max를 조회해 자동 partition

호출 코드는 짧아지지만 empty table, sparse ID, signed overflow, UUID/custom ID의
분할 기준, min/max와 실제 page mutation 사이의 읽기 일관성 의미가 모두 숨겨진다.
range partition 계약을 먼저 고정하는 #690의 목적과 맞지 않아 채택하지 않는다.

### C. JDK 25 `StructuredTaskScope`

structured cancellation과 owner join 모델은 매력적이지만 JDK 25 API가 preview이고,
현재 모듈의 안정적인 public API에 preview 의존성을 추가한다. 첫 구현은 기존
`VirtualFuture`와 명시적 child cancellation을 사용하고, preview API를 정식화할 때
별도 migration issue로 재평가한다. [Oracle JDK 25 StructuredTaskScope API](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/StructuredTaskScope.html)

## 검증 및 benchmark

### 테스트

- `exposed/jdbc` helper: empty range, one range, sparse Long ID, disjoint ordering,
  overlap/reverse rejection, custom comparator, `maxConcurrency` active transaction
  counter, executor shutdown rejection, failure sibling cancellation, interrupt cleanup.
- JDBC Lettuce loader: sequential result와 parallel result parity, range boundary와
  duplicate 없음, default `loadAllKeys()` unchanged, loader API KDoc example compile.
- JDBC Redisson loader: 동일 parity와 `MapLoader.loadAllKeys()` regression.
- source/ABI: 기존 constructor/method descriptor 보존과 additive method만 확인한다.

### benchmark

`benchmark/exposed-benchmark`에 `JdbcKeyEnumerationBenchmark`와 `jdbcKeyEnumeration`
configuration을 추가한다. H2에서 동일 row count와 range 수에 대해 sequential
keyset, parallel `maxConcurrency=2`, parallel `maxConcurrency=4`를 각각 측정한다.
결과 JSON은 세 번의 순차 실행에서 중앙값을 선택하고, loader/parallelism 그룹을
분리한 SVG/PNG chart와 분석 README를 함께 기록한다. 단위가 다른 기존 benchmark와
하나의 전역 순위를 만들지 않는다.

benchmark 결과는 H2의 단일 환경 증거일 뿐이며, PostgreSQL/MySQL driver, pool 크기,
mutation contention, 읽기 격리 수준의 일반적 우열을 주장하지 않는다.

## 수용 기준

1. caller-supplied disjoint range, ordering, weak consistency, 단일 읽기 일관성 기준 비보장과
   pool 책임이 설계·KDoc·README에서 동일하게 설명된다.
2. Lettuce/Redisson synchronous JDBC loader가 additive opt-in API로 helper를 사용하고
   기본 sequential path와 기존 public constructor/descriptor를 유지한다.
3. range 결과가 sequential keyset 결과와 동일하며, sparse ID·empty table·boundary에서
   중복이 없다.
4. active transaction 수가 `maxConcurrency`를 넘지 않고, query 실패·interrupt·child
   cancellation 뒤 모든 future와 transaction이 종료된다.
5. benchmark raw JSON, chart pair, 결과 분석이 재현 명령과 한계를 포함한다.
6. affected test, compile, detekt, ABI, README EN/KO parity, `git diff --check`가
   fresh evidence로 통과한다.

## 비목표

- 기본 sequential enumeration을 병렬 경로로 교체하지 않는다.
- R2DBC, suspended AsyncIterator, custom ID offset fallback을 이번 slot에서 확장하지 않는다.
- cache data model, serialization, `get`, `getAll`, write-behind, repository transaction을
  변경하지 않는다.
- `docs/manual/**`의 안정 릴리스 `1.12.1` 내용을 변경하지 않는다.
- JDK preview API 또는 새 외부 dependency를 추가하지 않는다.

## SPW DoD

- [x] SPW-01 — Issue/Epic, base/head, source anchors, release/manual boundary와
  승인 범위를 고정했다.
- [x] SPW-02 — 문제, API/transaction 구조, alternatives, failure modes, acceptance와
  non-goals를 포함했다.
- [x] SPW-03 — 한국어 technical register와 `range`, `keyset`, `read consistency`,
  `bounded concurrency`, `weak consistency` 용어를 일관되게 사용했다.
- [x] SPW-04 — 현재 loader, `VirtualFuture`, #646 설계, JDK 25 primary source와
  pool/cancellation 경계를 대조했다.
- [x] SPW-05 — Markdown read-back으로 code token, 링크, 표, scope boundary와
  문장 자연스러움을 확인했다.
