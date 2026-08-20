# Issue #692 cache loader custom ID·driver parity 설계

## 문서 상태

- 대상 이슈: [#692](https://github.com/bluetape4k/bluetape4k-exposed/issues/692)
- Epic/stack: [#659](https://github.com/bluetape4k/bluetape4k-exposed/issues/659) 후속 slot 7
- 선행 slot: #690 merge 완료, 현재 `develop` `736f07d5be05f17ff2e057586e40d27d59cd0c20`
- 대상 릴리스: `1.13.0` 개발선
- 예정 branch: `test/cache-loader-driver-parity`
- 분류: Type A — 세 cache adapter와 실제 PostgreSQL Testcontainers를 함께 검증하는
  multi-module conformance 작업
- 설계 범위: test-only fixture/assertion과 EN/KO README·KDoc 정합성 보정
- 제외: production API/ABI, cache serialization/data model, `docs/manual/**`(안정
  릴리스 `1.12.1`), Virtual Thread 병렬 API/benchmark(#690), MySQL 8 driver
  conformance(#698), network fault 정책은 별도 후속 범위

## 문제 정의

Issue #689는 JDBC Lettuce suspended, JDBC Redisson synchronous, R2DBC Redisson의
표준 scalar PK에 keyset page를 적용하고 지원 목록 밖 ID에는 offset fallback을
남겼다. 현재 테스트는 H2의 `LongIdTable`과 helper 수준의
`ComparableCustomId` 판정만 확인한다. 실제 custom `IdTable` column을 통해 fallback
SQL이 실행되는지, page 사이 변경이 각 transaction 경계에서 어떻게 관찰되는지,
PostgreSQL driver에서 timeout·producer 오류·retry 경계가 유지되는지는 아직 증거가
없다.

이 설계의 목적은 production 동작을 바꾸는 것이 아니라, 현재 계약을 실제 column과
지원 driver에서 반증 가능한 회귀 테스트로 고정하는 것이다. 테스트가 완전한 단일 읽기 기준,
모든 driver의 동일한 timeout, 또는 outer retry의 중복 방출 안전성을 암묵적으로
약속하지 않도록 adapter별 책임을 분리한다.

## 현재 구현과 근거

Issue가 요구하는 필수 범위는 세 module의 대표 surface다. JDBC Lettuce synchronous와
JDBC Redisson suspended는 구현상 존재하지만 이번 수용 기준의 필수 surface가 아니며,
같은 fixture를 재사용할 수 있을 때만 optional evidence로 기록한다. optional evidence가
없어도 #692의 세 module DoD를 미충족으로 판정하지 않는다.

| required adapter surface | 현재 source 경계 | 이번 검증 포인트 |
|---|---|---|
| JDBC Lettuce suspended | `exposed/jdbc-lettuce/.../SuspendedExposedEntityMapLoader.kt`가 기존 `List<ID>`를 하나의 `suspendedTransactionAsync`에서 materialize | 실제 custom ID `OFFSET`, page mutation, caller cancellation과 단일 connection 수명 |
| JDBC Redisson synchronous | `exposed/jdbc-redisson/.../ExposedEntityMapLoader.kt`의 단일 transaction 내부 page loop와 `queryTimeout=30_000` base | 실제 custom ID `OFFSET`, page mutation과 materialized transaction 경계 |
| R2DBC Redisson async | `exposed/r2dbc-redisson/.../R2dbcExposedEntityMapLoader.kt`와 `R2dbcEntityMapLoader.kt`의 page send, top-level `maxAttempts=1`, ambient retry 보존 | custom ID `OFFSET`, 실제 전체 timeout/producer 오류, top-level 대 ambient retry, channel cancellation |

세 concrete loader는 모두 `isKeysetScalar()`가 `Long`, UUID, 시간 타입 등만 허용하고
그 밖의 값은 offset 경로로 보낸다. `EntityIDColumnType`은 underlying `idColumn`을
보존하므로 test-only `ColumnWithTransform<String, CustomId>`를 `.entityId()`로
등록하면 실제 `IdTable<CustomId>`를 만들 수 있다. production에 custom type이나
comparator를 추가하지 않는다.

## 용어와 관찰 계약

| 용어 | 이 설계에서의 의미 |
|---|---|
| custom ID | `Comparable`을 구현하지 않은 test-only value object를 `VARCHAR` underlying column으로 저장하는 `EntityID<CustomId>` |
| offset fallback | 첫 page 이후 `OFFSET`을 증가시키며 PK ASC로 읽는 기존 경로. keyset `id > lastId`를 사용하지 않는다. |
| page mutation | 첫 page를 관찰한 뒤 별도 connection에서 아직 방문하지 않은 row를 삭제하고 더 큰 PK row를 삽입하는 동작 |
| weak consistency | page 사이 mutation을 하나의 읽기 기준으로 고정하지 않는 현재 관찰 의미 |
| top-level retry | loader가 자체적으로 연 transaction의 `maxAttempts` 정책 |
| ambient retry | caller transaction/context가 이미 열려 있을 때 caller가 소유하는 retry 정책 |

고정 fixture는 정렬 가능한 문자열을 underlying column에 저장하지만 `CustomId` 자체는
`Comparable`을 구현하지 않는다. 초기 row는 `a01`, `a02`, `a03`, `a04`, `a05`이고
`batchSize=2`를 사용한다. 첫 page 뒤 `a03`을 삭제하고 `a99`를 삽입하면
READ_COMMITTED에서 관찰 결과는 `[a01, a02, a04, a05, a99]`가 된다. 이 결과는
삭제된 row의 재방출과 중복이 없다는 것만 고정하며, 다른 isolation에서 같은 결과나
전체를 하나의 읽기 기준으로 고정했다고 주장하지 않는다.

## 채택한 설계

### 1. test-local custom `IdTable`

각 affected module의 test source set에만 다음 구조를 둔다.

```kotlin
data class CustomId(val value: String)

private object CustomLoaderTable : IdTable<CustomId>("issue_692_custom_loader") {
    private val rawId = varchar("id", 32)
    override val id = rawId.transform(::CustomId, CustomId::value).entityId()
    val name = varchar("name", 64)
    override val primaryKey = PrimaryKey(id)
}
```

실제 테스트에서는 모듈 간 table-name 충돌을 피하도록 module별 이름을 사용한다.
`insert { it[id] = CustomId("a01") }`는 Exposed `UpdateBuilder`의 EntityID 값
overload를 사용한다. fixture helper는 `withTables`/`withTablesSuspending`의 schema
생성·정리를 재사용하고, page mutation 테스트만 setup transaction을 먼저 commit한
뒤 loader와 writer가 별도 connection을 사용하도록 한다. 기존 공용 helper, raw
`GenericContainer`, production column helper는 새로 만들지 않는다.

### 2. 정적 parity와 SQL 증거

다음 세 required surface를 각 모듈의 dedicated test class에서 검증한다.

- JDBC Lettuce suspended `List`
- JDBC Redisson synchronous
- R2DBC Redisson `AsyncIterator`

각 fixture는 다섯 row를 `batchSize=2`로 읽고 다음을 함께 확인한다.

1. 반환 ID가 `CustomId.value` 기준 ASC다.
2. cardinality가 5이고 `distinct()` 결과를 별도 assertion으로 확인한다.
3. page SELECT 수가 3이다(마지막 partial page 뒤 불필요한 빈 page 없음).
4. page SQL에 `OFFSET`이 있고 keyset predicate `>`가 없다.
5. 각 page의 row 수가 `batchSize` 이하이며, first page를 소비하기 전 lazy/async
   surface에서 다음 page query가 실행되지 않는다.

SQL assertion은 dialect별 quoting 차이를 허용하되 `SELECT` statement만 골라
`OFFSET`/`>`/`LIMIT`과 query count를 판정한다. heap 전체 사용량은 측정하지 않고
page cardinality와 statement count를 재현 가능한 evidence로 남긴다.

### 3. page mutation과 cancellation

READ_COMMITTED fixture는 첫 page barrier를 연 뒤 별도 writer connection에서
`a03` 삭제와 `a99` 삽입을 commit한다. adapter별 barrier는 다음처럼 둔다.

- JDBC Lettuce suspended와 JDBC Redisson synchronous: test-only `SqlLogger`가
  첫 data SELECT를 관찰한 뒤 writer를 실행하고, 다음 page query가 같은 transaction
  경계에서 commit된 변경을 읽는지 확인한다.
- R2DBC Redisson: test-only `SqlLogger`가 첫 data SELECT를 관찰한 뒤 writer를
  실행한다. rendezvous channel의 back-pressure와 writer 완료 barrier가 다음 page
  query 이전에 mutation이 끝나도록 한다.

모든 barrier와 writer wait에는 bounded timeout을 둔다. `a03`이 사라지고 `a99`가
관찰되며 duplicate가 없다는 결과를 고정한다. SERIALIZABLE 또는 REPEATABLE_READ
환경은 이 assertion에 섞지 않고 `N/A`로 기록한다.

caller cancellation은 R2DBC channel surface에서 caller가 주입한 scope의 producer
취소와 다음 page query 미실행을 확인한다. 기본 shared `SupervisorJob`의 자동 전파나
channel close 원인까지 일반화하지 않는다. JDBC Lettuce suspended는 `List` API라
streaming cancellation을 약속하지 않는다. 이 경로에서는 test-only blocking page와
`withTimeout`으로 caller 취소 전파, partial list 미반환, 후속 재조회를 확인한다.
실제 JDBC/R2DBC connection close event 자체는 driver-specific 후속 검증으로 남기며,
page 단위 downstream cancellation은 `N/A`로 명시한다.

### 4. PostgreSQL conformance

MySQL 8은 후속 Issue #698이 driver·pool·isolation 범위를 소유하므로 이번 slot의
non-H2 evidence는 PostgreSQL 한 종류로 고정한다. 기존 `TestDB.POSTGRESQL`,
`Containers.Postgres`, JDBC/R2DBC TestDB helper를 재사용하고 새 image·credential·
container lifecycle을 추가하지 않는다.

PostgreSQL selector에서 다음을 순차 실행한다.

- 세 required surface의 custom ID ordering·OFFSET·cardinality
- READ_COMMITTED page mutation 결과
- R2DBC의 전체 enumeration `withTimeoutOrNull(60_000 ms)` 경계가 PostgreSQL
  nightly selector에서 timeout 원인을 iterator에 전달하는지 확인
- R2DBC producer fault가 실제 `R2dbcException`을 발생시키고 `AsyncIterator.await()`
  경로의 exceptional completion으로 전달되는지 확인
- top-level transaction은 첫 시도의 partial ID 뒤에 오류를 내고 재시도하지 않는지,
  ambient outer transaction은 caller의 `maxAttempts=2`로 전체 lambda를 재시도해
  외부 sink에 partial ID를 재방출할 수 있음을 확인한다. ambient 검증은 collector를
  caller-owned outer `suspendTransaction { ... }` 안에서 실행하고, transaction block
  밖의 sink로 emitted ID를 보존해 retry 전후를 비교한다. ambient 중복은 caller-owned
  documented risk이며 자체적으로 P1 실패가 아니다.

`queryTimeout`은 Exposed 1.4.0에서 초 단위다. 따라서 source의
`queryTimeout = 30_000`은 “30초”가 아니라 값 `30_000`초이며, 이번 issue는 이를
30초로 재해석하거나 production 값을 변경하지 않는다. JDBC Redisson과 R2DBC의
실제 30초 query timeout 보장은 `N/A`로 분리한다. statement-timeout 값·단위와
cleanup 구현은 production bug [Issue #699](https://github.com/bluetape4k/bluetape4k-exposed/issues/699)가
소유하며, #692는 custom fixture와 R2DBC의 실제 60초 전체 enumeration evidence를
제공한다. JDBC Lettuce에는 명시적 query timeout이 없으므로 동일하게 `N/A`다.

### 5. 문서 정합성

behavior evidence가 고정되면 세 모듈 EN/KO README와 loader KDoc을 다음 표로 정렬한다.

| adapter | 문서에 명시할 정책 |
|---|---|
| JDBC Lettuce suspended | 기존 `List`, 전체 loop의 suspended transaction/connection, caller cancellation 경계, 명시적 query timeout 없음 |
| JDBC Redisson sync | scalar keyset/custom offset, materialized result, source `queryTimeout=30_000`초 값은 #699 statement-timeout 범위 |
| R2DBC Redisson | rendezvous back-pressure, top-level `maxAttempts=1`, ambient retry의 외부 재방출 위험, 60초 전체 timeout |

문서는 custom ID offset fallback의 page mutation 의미를 단일 읽기 기준 보장으로 과장하지
않는다. `docs/manual/**`는 stable `1.12.1` ref를 유지한다.

## 실패 모드와 방어

| 실패 모드 | 방어 및 중단 기준 |
|---|---|
| custom ID가 keyset으로 잘못 선택됨 | SQL `OFFSET` 및 `>` 부재 assertion을 분리한다. P1로 분류하고 구현을 중단한다. |
| page mutation에서 duplicate/lost row를 단일 읽기 기준으로 오해 | fixture 결과와 weak-consistency 문구를 함께 고정한다. 전체를 하나의 읽기 기준으로 고정하는 assertion은 금지한다. |
| barrier가 같은 connection을 재사용함 | setup commit, 별도 writer connection, bounded latch를 검사한다. connection 식별을 증거에 남긴다. |
| producer 오류가 정상 종료로 변환됨 | `hasNext`/`next` exceptional completion과 root cause를 확인한다. P1이다. |
| retry가 이미 방출한 ID를 재생함 | 실제 transient `R2dbcException`을 `await()` 경로에서 발생시킨다. top-level 재시도/재방출은 P1이고, ambient caller-owned 재방출은 예상 위험으로 문서화한다. |
| queryTimeout 단위를 30초로 오해함 | Exposed 단위(초)를 source line과 테스트 evidence에 기록하고, statement-timeout 값·구현은 #699가 소유한다. |
| PostgreSQL/Testcontainers 부재 | H2 pass와 섞지 않고 명령·환경·원인을 `N/A`로 보고하며 hosted/nightly evidence가 없으면 PR DoD를 `PENDING`으로 둔다. |
| MySQL evidence 누락 | #698의 후속 범위로 링크하고 #692 완료 조건으로 주장하지 않는다. |
| network fault를 여기서 추가 | 이 slot은 DB/driver conformance만 다룬다. network fault injection은 별도 후속 issue로 분리하고 추가하지 않는다. |

## 검토한 대안

### A. test-local transformed custom ID + PostgreSQL (채택)

실제 `IdTable<ID>`와 underlying SQL type을 사용하면서 production surface를 바꾸지
않는다. H2와 PostgreSQL에서 같은 fixture를 재현할 수 있고, offset fallback을
단순한 helper 판정이 아닌 SQL evidence로 검증한다.

### B. 기존 `ComparableCustomId` 단위 판정만 확대

실제 `EntityIDColumnType`과 SQL ordering을 거치지 않아 acceptance의 custom
`IdTable` 요구를 충족하지 못한다. regression guard로는 부족하므로 채택하지 않는다.

### C. production에 comparator/새 custom ID keyset API 추가

public API/ABI와 DB ordering semantics를 넓히고 cache loader 범위를 기능 변경으로
확장한다. 이번 issue는 현재 fallback 계약을 증명하는 test-only 작업이므로 별도
feature issue로 분리한다.

### D. MySQL과 network fault를 같은 slot에 포함

MySQL pool/isolation은 #698이 이미 소유하고, network fault/timeout proxy는 별도
환경 검증 issue로 분리한다. 중복 환경을 이 issue에 넣으면 stacked train의 backend ownership과
실패 원인 분리가 흐려지므로 채택하지 않는다.

## 검증 계약과 수용 기준

1. 세 required adapter surface가 실제 custom `IdTable`에서 `OFFSET`, ASC order,
   page cardinality, duplicate 부재를 증명한다.
2. READ_COMMITTED page mutation 결과와 adapter별 transaction/materialization 경계가
   테스트와 문서에서 동일하다. cancellation은 취소 전파·partial list 미반환·후속
   재조회 가능성까지 검증하며, low-level connection close event는 별도 driver 검증으로
   남긴다. 전체를 하나의 읽기 기준으로 고정했다고 주장하지 않는다.
3. PostgreSQL R2DBC에서 ordering, 실제 60초 전체 timeout, producer error,
   top-level/ambient retry 결과 경계를 fresh evidence로 확보한다. JDBC Lettuce와
   `queryTimeout=30_000`의 실제 30초 의미는 `N/A` 및 후속 bug로 분리한다.
4. channel 기반 async surface의 caller cancellation과 producer error가 정상 종료나
   partial success로 변환되지 않는다. JDBC Lettuce suspended `List`의 page-level
   downstream cancellation은 명시된 `N/A` 경계를 지킨다.
5. EN/KO README와 KDoc이 실제 구현·테스트 결과와 일치하고 `docs/manual/**`, public
   API/ABI, cache data model/serialization은 변경하지 않는다.
6. affected module tests, PostgreSQL targeted/nightly tests(환경이 있을 때), detekt,
   `git diff --check`, 문서 terminology audit가 통과한다.

## 비목표

- production loader algorithm, constructor, public transaction API를 변경하지 않는다.
- custom ID comparator, composite ID keyset, isolation policy를 새로 제공하지 않는다.
- JDBC Lettuce sync와 JDBC Redisson suspended optional evidence는 이번 DoD에서
  제외하며, 필요하면 후속 parity issue로 처리한다.
- MySQL 8 driver/pool/isolation conformance는 #698에서 처리한다.
- network fault injection은 별도 후속 issue에서 처리한다.
- Virtual Thread parallel enumeration과 benchmark/chart는 #690 잔여 범위다.
- stable release manual `docs/manual/**`(`1.12.1`)는 수정하지 않는다.

## SPW-01~05 및 자연스러움 확인

- [x] SPW-01 — live #692/#659, current `develop` head, source/test paths, GNO가
  제공한 stale 보조 근거와 live GitHub 우선순위, PostgreSQL/#698 및 network follow-up
  경계를 기록했다.
- [x] SPW-02 — 문제, adapter별 계약, fixture, mutation/cancellation, driver matrix,
  문서, 실패 모드, 대안, 수용 기준과 비목표를 포함했다.
- [x] SPW-03 — `keyset`, `offset fallback`, `page`, `weak consistency`,
  `top-level retry`, `ambient retry`, `N/A`를 동일 의미로 사용하고 KO-01~KO-06을
  적용했다. 과장 표현과 번역투를 사용하지 않았다.
- [x] SPW-04 — Exposed 1.4.0 `EntityIDColumnType`/`ColumnWithTransform` source,
  세 loader/base transaction, TestDB/Testcontainers, 기존 #689 lesson과 live
  acceptance를 대조했다.
- [x] SPW-05 — 최종 Markdown read-back으로 표·코드 fence·링크·수치·scope boundary를
  확인했고, terminology audit 결과를 계획 문서와 함께 기록한다.
