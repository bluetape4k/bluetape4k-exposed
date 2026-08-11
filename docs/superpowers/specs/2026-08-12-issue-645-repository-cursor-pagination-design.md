# 이슈 #645 JDBC·R2DBC 저장소 cursor pagination 설계

## 배경

이슈 #645는 기존 offset 기반 `findPage`를 유지하면서 깊은 페이지 조회와
동시성 경계에 적합한 keyset/cursor 조회 계약을 추가한다.

현재 `JdbcRepository`와 `R2dbcRepository`의 `findPage`는 다음 순서로
동작한다.

- `countBy(predicate)`로 전체 개수를 조회한다.
- 기본 키를 `sortOrder`로 정렬하고 `pageNumber * pageSize` offset을 적용한다.
- 두 결과를 `ExposedPage`에 담는다.

따라서 깊은 페이지에서는 offset을 건너뛰기 위한 비용이 커지고, 개수와
내용 조회 사이의 삽입·삭제로 두 결과가 서로 다른 시점을 나타낼 수 있다.
이 설계는 기존 API의 ABI와 동작을 바꾸지 않고, 동일한 `IdTable.id` 정렬
계약을 재사용하는 별도 cursor API를 정의한다.

## 목표

- JDBC와 R2DBC 저장소에 동일한 typed cursor API를 제공한다.
- cursor 조회에서는 전체 count query를 실행하지 않는다.
- 기본 키의 유일성과 안정성을 이용해 sparse ID에서도 중복·누락을 줄인다.
- 삽입·삭제가 페이지 사이에 발생해도 cursor 이후 경계를 결정론적으로
  유지한다.
- ASC와 DESC의 forward-only semantics를 명시한다.
- 기존 `findPage`의 시그니처, ABI, count/offset 동작을 변경하지 않는다.
- cursor 직렬화 및 transport 보안은 caller가 소유하도록 의존성 추가를
  피한다.

## 목표가 아닌 사항

- opaque `String` cursor의 발급·파싱·서명·암호화.
- 임의 정렬 컬럼이나 다중 컬럼 keyset tuple.
- 이전 페이지를 위한 reverse cursor 또는 양방향 navigation.
- `findPage`의 제거, 기본 동작 변경, 또는 offset API deprecation.
- `spring-boot/batch-exposed`의 `ExposedKeysetItemReader` 변경.
- `CompositeID`의 자연 순서 정의 또는 복합 키 비교 로직.
- Spring Data의 `Slice` API와 직접 결합.

## 검토한 접근 방식

### A. typed primary-key cursor slice (선택)

core에 `ExposedCursorPage<T, C>`를 추가하고 JDBC/R2DBC 패키지에
`ID : Comparable<ID>`를 요구하는 top-level `findCursorPage` extension을
추가한다. `C`는 repository의 `ID`와 같으며, 결과의 `nextCursor`는
마지막으로 반환된 행의 기본 키 값이다.

장점:

- 이미 모든 repository가 기본 키로 정렬하므로 별도 정렬 정책이 없다.
- 새 codec, serializer, secret 정책, 의존성이 필요 없다.
- JDBC와 R2DBC가 같은 경계·결과 타입을 공유한다.
- caller가 REST token, protobuf field, opaque signed token을 각 transport
  정책에 맞게 선택할 수 있다.

비용:

- repository 호출자는 `ID`를 transport 표현으로 변환해야 한다.
- `ID`가 `Comparable`이 아니면 extension이 컴파일되지 않는다.
- 기본 키 이외의 정렬 요구는 별도 API가 필요하다.

이 접근 방식을 선택한다.

### B. repository-owned opaque String cursor

repository가 기본 키를 문자열로 인코딩하고 다시 파싱한다.

transport 사용성은 좋지만 generic `ID`의 직렬화 규칙, 형식 버전, 위변조
방지, 실패 예외를 repository가 소유하게 된다. 새 codec 의존성 또는
타입별 분기 없이 안전한 구현을 만들기 어렵고, JDBC/R2DBC 중립 계약에도
불필요한 정책을 넣게 되므로 기각한다.

### C. 다중 컬럼 keyset cursor

정렬 컬럼 목록과 tie-breaker를 cursor tuple으로 보관한다.

표현력은 높지만 nullable 비교, 컬럼 타입별 연산, tuple serialization,
복합 인덱스 문서화, public ABI가 크게 늘어난다. #645의 기본 키 cursor를
검증한 뒤 별도 이슈로 다루는 것이 안전하므로 기각한다.

## 공개 API 계약

### core 결과 DTO

```kotlin
data class ExposedCursorPage<T, C : Comparable<C>>(
    val content: List<T>,
    val nextCursor: C?,
    val hasNext: Boolean,
)
```

계약은 다음과 같다.

- `content`는 최대 `pageSize`개이며 결과 DTO는 내부 mutable 상태를
  보유하지 않는다.
- `hasNext == false`이면 `nextCursor == null`이다.
- `hasNext == true`이면 `content`는 비어 있지 않고 `nextCursor`는 마지막
  content 행의 `extractId` 결과다.
- 첫 호출은 `cursor = null`로 표현한다.
- `nextCursor`는 마지막 행의 typed primary-key position token이다. 이를
  다른 sort order나 다른 predicate에 재사용하는 것은 caller 책임이며,
  문서와 예제에서 같은 조건을 유지해야 한다.

### JDBC extension

```kotlin
fun <ID : Comparable<ID>, E : Any> JdbcRepository<ID, E>.findCursorPage(
    pageSize: Int,
    cursor: ID? = null,
    sortOrder: SortOrder = SortOrder.ASC,
    predicate: () -> Op<Boolean> = { Op.TRUE },
): ExposedCursorPage<E, ID>
```

### R2DBC extension

```kotlin
suspend fun <ID : Comparable<ID>, E : Any> R2dbcRepository<ID, E>.findCursorPage(
    pageSize: Int,
    cursor: ID? = null,
    sortOrder: SortOrder = SortOrder.ASC,
    predicate: () -> Op<Boolean> = { Op.TRUE },
): ExposedCursorPage<E, ID>
```

extension은 JVM static method로 추가되며 기존 `JdbcRepository`/`R2dbcRepository`
인터페이스와 구현체의 ABI를 변경하지 않는다. 기존 `findPage`와 이름이
다르므로 source/behavior 호환성도 유지한다. Java caller는 생성되는
extension static method를 사용할 수 있지만, Kotlin처럼 extension import를
명시해야 한다.

## 조회 알고리즘

1. `pageSize`는 양수이고 `Int.MAX_VALUE - 1` 이하인지 검증한다. 한 번의
   조회에서 `pageSize + 1`개를 요청해야 하므로 overflow를 허용하지 않는다.
2. extension의 `ID : Comparable<ID>` bound가 cursor의 비교 가능성을
   compile time에 보장한다. 따라서 `CompositeID`나 비교 불가능한 custom ID는
   첫 페이지부터 API 호출 자체가 컴파일되지 않는다.
3. 기본 predicate와 cursor 경계를 AND로 결합한다.
   - `ASC`, `ASC_NULLS_FIRST`, `ASC_NULLS_LAST`: `table.id > cursor`
   - `DESC`, `DESC_NULLS_FIRST`, `DESC_NULLS_LAST`: `table.id < cursor`
   기본 키는 null이 아니므로 null placement 변형은 방향만 결정한다.
4. `table.id`를 동일한 `sortOrder`로 정렬하고 `limit(pageSize + 1)`을
   적용한다.
5. JDBC는 행을 즉시 매핑하고, R2DBC는 `Flow`를 현재 suspend transaction
   안에서 `toList()`로 수집한다. 두 adapter 모두 같은 결과 경계를 만든다.
6. 행이 `pageSize + 1`개이면 마지막 sentinel을 제거하고 `hasNext = true`,
   `nextCursor = rows[pageSize - 1][table.id].value`로 반환한다. 그렇지
   않으면 전체 행을 반환하고 `hasNext = false`, `nextCursor = null`로
   반환한다. cursor는 매핑된 엔티티의 임의 필드가 아니라 실제 정렬 컬럼의
   raw ID에서 추출한다.

cursor query는 `countBy` 또는 offset을 호출하지 않는다. 페이지 사이의
삭제로 cursor 행 자체가 사라져도 strict 비교가 다음 경계를 유지한다.
cursor 뒤에 삽입된 행은 이후 페이지에 포함될 수 있고, 이미 cursor 앞에
삽입된 행은 현재 순회의 과거 경계에 포함되지 않는다. 이는 snapshot
보장이 아니라 stable-position 순회 계약이다.

## ID 비교 및 제한

repository interface의 `ID: Any` bound를 `Comparable`로 바꾸지 않는다.
그 변경은 기존 구현체의 source/ABI 호환성을 깨뜨리기 때문이다. 대신
extension의 `ID : Comparable<ID>` bound와 private
`EntityIdCursorAdapter`가 다음 순서로 경계를 만든다.

1. `table.id.columnType`을 `EntityIDColumnType<ID>`로 확인하고 underlying
   `idColumn`을 꺼낸다.
2. compiler가 보장한 `ID : Comparable<ID>` cursor를 사용한다.
3. underlying `Column<ID>`와 cursor에만 국소적인 checked cast를 적용해
   Exposed `greater`/`less` bound expression을 만든다.

이 adapter는 public API가 아니며 repository마다 comparator를 주입하지
않는다. `Long`, `Int`, `String`, `java.util.UUID`, Kotlin `Uuid`처럼
underlying ID가 자연 순서를 제공하는 타입만 지원한다. `CompositeID`와
비교 불가능한 사용자 ID는 compile-time에 제외된다.

`Long`, `Int`, `String`, `java.util.UUID`, Kotlin `Uuid` 같은 기본 키는
기존 Exposed 비교 연산을 사용할 수 있다. `CompositeID`나 사용자 정의
비교 불가능 타입은 cursor extension이 제공되지 않으며, caller는 기존
`findAll`/`findPage` 또는 별도 predicate를 사용해야 한다. 이 제한은
README와 KDoc에 명시한다. repository 구현체는 `extractId(entity)`가
`ResultRow[table.id].value`와 동일한 값을 반환하도록 유지해야 하며,
cursor 순회 중 기본 키를 변경하지 않아야 한다. 이 invariant를 어기면
`nextCursor`와 SQL 정렬 경계가 달라져 중복·누락이 발생할 수 있다.

## 오류 및 취소 계약

- `pageSize <= 0` 또는 `pageSize == Int.MAX_VALUE`는 기존 support validation
  예외 규칙에 따라 즉시 거부한다.
- 비교 불가능한 ID는 extension type bound에서 거부되며, SQL 실행 전에
  별도 runtime fallback을 두지 않는다.
- JDBC 예외는 현재 transaction 예외 경계를 그대로 따른다.
- R2DBC suspend 함수는 취소 예외를 삼키거나 변환하지 않고 기존
  `suspendTransaction`/Flow 계약을 따른다.

## 테스트 계획

core:

- 결과 DTO의 empty/last/hasNext invariants
- `hasNext == false`일 때 `nextCursor == null`

JDBC와 R2DBC 각각:

- 첫 페이지와 다음 cursor 페이지가 sparse ID에서 연속되고 중복이 없음
- ASC와 DESC 경계가 각각 `>`/`<`로 동작함
- predicate가 cursor 조건과 AND 결합됨
- 결과가 정확히 page size이면 다음 sentinel을 판정함
- 마지막 페이지와 빈 결과
- `pageSize` 0, 음수, overflow 경계
- `Long`/`Int`/`String`/`UUID` 경계 compile 및 integration coverage와
  `CompositeID`/비교 불가능 custom ID의 compile-time 제외
- 페이지 사이 행 삭제 후 다음 cursor 조회
- 페이지 사이 새 행 삽입의 stable-position semantics
- cursor 경로가 count query를 수행하지 않는 구조적/SQL 로그 검증
- 기존 `findPage` 회귀 및 ABI compatibility 검증(interfaces unchanged,
  extension static method addition only)
- R2DBC 매핑 중 coroutine 취소 시 `CancellationException` 재전파,
  transaction rollback, connection release 검증

무거운 PostgreSQL/Testcontainers 경로는 JDBC와 R2DBC를 순차 실행한다.
단위/통합 테스트는 저장소의 기존 JUnit 5, Kluent/bluetape4k assertion,
`runSuspendIO` 규칙을 따른다.

## 문서 및 예제

- `exposed/core/README.md`와 `README.ko.md`에 DTO와 cursor invariants를
  추가한다.
- `exposed/jdbc/README.md`와 `README.ko.md`에 `findCursorPage` 예제를
  추가한다.
- `exposed/r2dbc/README.md`와 `README.ko.md`에 suspend 사용 예제를
  추가한다.
- `docs/manual/en|ko/modules/bluetape4k-exposed-jdbc/repository-patterns.md`
  및 R2DBC 동등 manual에 같은 계약을 추가한다.
- typed cursor를 HTTP token으로 사용할 때 caller가 encode/decode하는
  예를 짧게 제시한다.
- `SoftDeletedJdbcRepository`/`SoftDeletedR2dbcRepository`는 별도
  `findActiveCursorPage`를 추가하지 않고 base extension에 active predicate를
  전달한다. 기존 `findActivePage` convenience API는 변경하지 않는다.
- 기존 offset `findPage`와 Spring Batch keyset reader는 별도 계약임을
  migration note에서 명확히 한다.
- 이번 변경은 diagram geometry를 바꾸지 않으므로 diagram asset 생성은
  하지 않는다. 텍스트 locale parity는 검증한다.

## 이슈 acceptance criteria 매핑

| #645 기준 | 설계 대응 |
| --- | --- |
| 안정적인 정렬과 cursor 형식 | `IdTable.id` + typed `ID` position token + raw ID extraction |
| forward/previous semantics | forward-only next cursor, ASC/DESC 명시 |
| count 분리 | cursor query에서 count 미실행 |
| JDBC/R2DBC 일치 | 동일 DTO·인자·경계·테스트 |
| sparse/concurrent regression | 삽입·삭제·sparse ID 테스트 |
| 기존 findPage 보존 | 별도 method, 기존 구현 미변경 |
| 문서·예제·migration | core/JDBC/R2DBC EN/KO 문서와 범위 명시 |

## 알려진 위험

- 비정상적으로 큰 `pageSize`는 `pageSize + 1` 메모리와 DB limit 비용을
  키우므로 상한은 overflow 방지에만 두고 caller가 운영 상한을 정해야 한다.
- ID 비교 가능성은 Kotlin 타입 시스템으로 강제하지 않으므로 runtime
  validation과 문서가 함께 필요하다.
- snapshot isolation을 제공하지 않으므로 동일 순회 중 데이터 변경의
  가시성은 DB transaction isolation과 caller의 책임이다.
