# 이슈 #642 JDBC `FluentQuery` projection SQL pushdown 설계

> Issue #642 · Epic #658 slot 1 · Type A · milestone `1.13.0`

## 문제

`ExposedJdbcRepository`는 Spring Data `QueryByExampleExecutor`를 상속하지만,
현재 `findBy(Example, queryFunction)` 구현은 조건에 맞는 DAO Entity 전체를 먼저
목록으로 만든 뒤 `SimpleFluentQuery`에 전달한다. 이 때문에 표준
`FluentQuery` 계약과 실제 실행 경로가 분리되어 있다.

- `as(projectionType)`은 Entity를 projection 타입으로 직접 cast한다.
- `project(properties)`는 입력을 무시한다.
- sort, page, count, exists는 이미 만든 목록을 대상으로 실행한다.
- `oneValue()`는 결과가 여러 건일 때 Spring Data 계약의
  `IncorrectResultSizeDataAccessException` 대신 `null`을 반환한다.
- `Example` predicate는 Exposed delegated property가 아니라 Java field를
  찾으므로 조건을 읽지 못하고 전체 조회로 퇴행할 수 있다.

현재 근거는 다음과 같다.

- `ExposedJdbcRepository`가 `QueryByExampleExecutor<E>`를 공개한다.
- `SimpleExposedJdbcRepository.findBy`가 `EntityClass.find(...).toList()`를
  query function보다 먼저 실행한다.
- `ExposedMappingContext`와 `DefaultExposedPersistentProperty`가 flat
  camelCase/snake_case property-to-column 해석을 제공한다.
- Exposed 1.4.0의 `EntityClass.searchQuery(op)`는 custom `dependsOnTables`와
  `dependsOnColumns`를 반영한 `Query`를 반환하고, `Query.adjustSelect`로 선택
  expression을 제한할 수 있다.
- Spring Data Commons 4.1.0의 `ProjectionFactory`는 property map을 source로
  받는 closed interface projection을 지원한다. `ReturnedType`은 class DTO의
  input property 이름을 제공하고 preferred constructor는 공개
  `PreferredConstructorDiscoverer`로 탐색한다.

## 목표

- 기존 `ExposedJdbcRepository.findBy(Example) { ... }` 진입점에서 closed
  interface와 constructor DTO projection을 지원한다.
- projection과 `project(properties)`가 요구하는 column만 SQL `SELECT`에
  포함한다.
- predicate, sort, limit, page, count, exists를 DB에 pushdown한다.
- DAO Entity 반환은 기존 identity cache를 보존한다. root-table filter-only custom
  `EntityClass.searchQuery` 의미는 보존하고 cardinality가 불명확한 shape는
  fail-fast 한다.
- 기존 public repository API와 한 인자
  `SimpleExposedJdbcRepository(ExposedEntityInformation)` JVM constructor를
  유지한다.
- JDBC와 후속 R2DBC #643이 같은 projection shape, cardinality, ID/nullability,
  unsupported-shape 계약을 공유할 수 있게 한다.

## 목표가 아닌 사항

- 일부 column만 포함한 DAO Entity 또는 `EntityClass.wrapRow` 생성.
- nested association/path, join aggregate, scalar, raw `@Query` projection.
- SpEL 기반 open interface projection.
- 임의 property 이름과 Exposed column을 연결하는 새 public mapper/metadata SPI.
- PartTree derived query, CRUD, `findAll(Pageable)`, DSL `Op` 확장 경로의 재작성.
- JDBC와 R2DBC가 같은 executor 또는 stream 구현을 공유하는 것.
- caller-owned transaction 계약 없이 cursor-backed JDBC `Stream`을 반환하는 것.

## 검토한 접근 방식

### 접근 1 — 기존 `findBy` 뒤에 내부 row-query engine을 둔다 (선택)

immutable query plan이 `Example` predicate, projection type, selected property,
sort, limit를 보관하고 terminal operation에서 SQL을 만든다. Entity 결과는
기존 DAO 경로를 사용하고, DTO/interface 결과만 selected `ResultRow`를 직접
매핑한다.

장점:

- 별도 repository API 없이 Spring Data 표준 진입점을 바로 고친다.
- public API 추가 없이 SQL pushdown을 제공한다.
- partial Entity를 만들지 않아 Exposed identity cache를 보존한다.
- 후속 #643이 observable contract만 재사용하고 R2DBC executor는 별도로
  구현할 수 있다.

비용:

- Entity materialization과 row projection의 두 실행 경로가 필요하다.
- DTO constructor, ID unwrap, nullability를 검증하는 내부 mapper가 필요하다.
- 현재 QBE property 추출을 함께 바로잡아야 한다.

### 접근 2 — public projection mapper/metadata SPI를 추가한다

사용자가 property-to-expression과 `ResultRow` mapper를 등록하도록 한다.
renamed/transformed property까지 지원할 수 있지만 JDBC `ResultRow`와 Column
의미를 장기 public API로 고정하고, JDBC 모듈을 API 의존하는 R2DBC 소비자에게도
해당 타입이 전이 노출된다. #642에서 필요한 범위보다 크므로 보류한다.

### 접근 3 — 별도 repository fragment를 추가한다

`findProjectedByExample` 같은 API를 추가하면 mapping과 transaction 계약을
독립적으로 정의하기 쉽다. 그러나 이미 공개된 표준 `findBy`의 cast/no-op
동작이 남고 predicate/sort/page API도 중복된다. #642의 주 해결책으로
사용하지 않는다.

## 선택한 구조

### `JdbcFluentQueryPlan`

내부 immutable plan은 다음 상태를 갖는다.

- 원본 `Example<E>`와 domain/result type
- 호출 시 immutable snapshot으로 복사한 명시적 property set
- 호출 순서대로 누적한 `Sort`
- 마지막 `limit()` 호출이 대체한 limit. `0`은 unlimited이다.
- `ProjectionFactory`
- domain `ExposedPersistentEntity` metadata
- callback scope token, owner thread, 생성 시점의 `JdbcTransaction` identity

`sortBy`, `limit`, `as`, `project`는 현재 인스턴스를 바꾸지 않고 새 plan을
반환한다. `Example`은 terminal validation이 끝날 때까지 `Op<Boolean>`으로
compile하지 않는다. terminal operation에서만 query를 만들고 실행한다.

`findBy` callback은 plan의 유일한 사용 scope이다. callback 종료 시 `finally`에서
token을 폐기하며, 모든 mutator와 terminal은 active token, owner thread, 동일한
현재 Exposed transaction을 SQL 생성 전에 확인한다. callback 밖으로 반환하거나
다른 thread에 저장한 plan은 `InvalidDataAccessApiUsageException`으로 실패한다.
`stream()`이 반환한 cursor lease만 아래의 별도 lifetime 계약을 따른다.

호출 상태 전이는 다음과 같이 고정한다.

| 호출 | 상태 변화 | 비고 |
| --- | --- | --- |
| `as(T)` | result type을 `T`로 대체 | 반복 호출은 마지막 타입이 이긴다. `as(domainType)`은 Entity 경로로 복귀한다. |
| `project(P)` | property collection을 복사해 대체 | 반복 호출은 마지막 snapshot이 이긴다. 빈 collection은 result type의 required input을 자동 선택한다. |
| `sortBy(S)` | 기존 sort 뒤에 append | `Sort.unsorted()`는 no-op, `null`은 `IllegalArgumentException`이다. |
| `limit(n)` | limit을 `n`으로 대체 | 음수는 거부하고 `0`은 unlimited이다. |

`as()`와 `project()`는 호출 순서와 무관하게 terminal 시점의 최종 result type과
property snapshot으로 동일한 plan을 만든다. DTO와 closed interface의 명시적
non-empty property set은 required input property set과 정확히 같아야 한다. 누락과
불필요 property를 모두 SQL 전에 거부한다. Entity result에 non-empty
`project()`를 적용하면 partial Entity를 만들지 않고 projection 사용법을 포함한
예외를 반환한다.

### 두 실행 경로

Entity result 경로는 검증된 query에 sort, limit, offset을 적용하고 모든 DAO
의존 column을 읽은 뒤 `EntityClass.wrapRow`를 사용한다. 이 경로만 기존 identity
cache를 조회·갱신한다. `project(properties)`를 Entity result에 적용하면 partial
Entity를 만들지 않고 SQL 전에 `InvalidDataAccessApiUsageException`을 반환한다.

DTO/closed-interface 경로는 다음 순서로 실행한다.

1. result type에서 required input property를 계산하고 명시적 property set과
   exact match를 검증한다.
2. strict resolver가 `ExposedPersistentProperty.getColumn()`으로 각 flat
   property와 sort property를 해석한다.
3. matcher 구조와 custom `searchQuery` shape를 값 접근 전에 검증한다.
4. probe property를 각각 한 번만 읽고 변환해 bound `Op<Boolean>`을 compile한다.
5. `entityClass.searchQuery(op)`에서 선택 expression을 필요한 column으로만
   바꾸고 sort/limit/offset을 적용한다.
6. `ResultRow`를 transaction-bound 값이 없는 property map 또는 constructor
   argument 배열로 변환한다.

selected-row 경로는 `EntityClass.wrapRow`를 호출하거나 Entity cache를 갱신하지
않는다.

custom `searchQuery`는 root table을 source로 사용하고 entity당 정확히 한 row를
반환하는 filter-only shape만 지원한다. join, `groupBy`, `having`, aggregate,
`distinct`/`distinctOn`, union, custom order/limit/offset, `forUpdate`는 content와
count cardinality를 안전하게 보존할 수 없으므로 모든 FluentQuery terminal에서
SQL 전에 거부한다. 따라서 count는 joined row가 아니라 root entity row를
의미한다. 기존 CRUD/PartTree/DSL query의 custom search behavior는 변경하지
않는다.

### interface projection

`ProjectionFactory.getProjectionInformation(resultType)`이 closed projection으로
판정한 interface만 지원한다. input property별 row 값을 map으로 만들고
`ProjectionFactory.createProjection(resultType, map)`을 호출한다.

open/SpEL projection은 expression에서 실제로 읽는 property를 정적으로 확정할
수 없으므로 SQL 실행 전에 `UnsupportedOperationException`으로 거부한다.
Kotlin/Java의 getter-only closed interface와 inherited getter는 지원한다. Spring
Data가 open으로 판정하는 `@Value`/SpEL accessor, default method가 추가 source
property를 요구하는 shape는 거부한다. 모든 required getter 값은 projection proxy를
만들기 전에 target type과 nullability에 맞게 eager validation한다.

### DTO/class projection

Spring Data `ReturnedType.of(resultType, domainType, projectionFactory)`는 public
`getInputProperties()`로 input property 이름을 확인하는 데만 사용한다. 실제
constructor는 공개 `PreferredConstructorDiscoverer.discover(resultType)`로 별도
탐색한다. 다음 조건을 모두 충족하는 class만 지원한다.

- named parameter가 있는 단일 preferred constructor를 가진다.
- 모든 constructor parameter가 flat domain property와 column으로 해석된다.
- 명시적 non-empty `project(properties)`가 constructor parameter set과 정확히
  일치한다.
- row 값이 parameter type으로 변환 가능하고 nullability를 만족한다.

Kotlin data class와 Java record를 대표 지원 shape로 검증한다. constructor를
고를 수 없거나 parameter 이름이 없으면 명확한 예외로 거부한다. 기본값을
사용하기 위해 parameter를 생략하는 동작은 이번 범위에 포함하지 않는다.

### property와 값 변환

- property 해석은 exact 이름과 기존 camelCase/snake_case convention만
  지원한다.
- unknown, ambiguous, renamed, nested, association property는 조기에 거부한다.
- `IdTable.id`에서 읽은 `EntityID<ID>`는 projection source에서 raw `ID`로
  unwrap한다.
- nullable column은 `null`을 유지한다.
- assignable 값, boxed primitive, exact enum/temporal 값만 허용한다. 임의
  application `ConversionService`, 문자열 coercion, narrowing numeric conversion은
  실행하지 않는다.
- Kotlin non-null type, Java primitive, nullable reference를 projection 생성 전에
  검증한다. 실패 시 row index, property, expected type만 포함한 Spring Data
  `MappingException`을 반환한다.
- transformed/composite expression은 새 SPI 없이 추론하지 않는다.
- projection source에는 `ResultRow`, `EntityID`, DAO Entity 또는 transaction-bound
  lazy 값이 남지 않는다.

### QBE predicate

하나의 내부 `ExamplePredicateCompiler`가 `findOne`, 모든 `findAll` overload,
`count`, `exists`, `findBy` terminal에 동일하게 사용된다. probe 값은 Java field가
아니라 domain `PersistentPropertyAccessor`와 Exposed property metadata로 읽는다.

이 repository의 domain type은 Exposed DAO Entity이므로 #642의 QBE probe는
일반적인 ad-hoc POJO가 아니다. 같은 transaction에서 이미 조회되어 attached된
persisted DAO Entity만 허용한다. `EntityClass.new`로 만든 신규 Entity, 다른
transaction에서 로드한 Entity, detached Entity는 SQL 전에 거부한다. ID는 현재
동작과 같이 predicate에서 항상 제외한다. 이 제약을 없애는 value-probe
adapter/public API는 별도 기능이며 #642 범위가 아니다.

compiler는 다음 순서로 동작한다.

1. ignored/nested path, null handler, matcher option 등 구조를 값 접근 전에
   검증한다.
2. attached 상태를 검증하고 probe accessor를 연결하되 getter를 아직 호출하지
   않는다.
3. ignored path를 제외한 각 property를 정확히 한 번 읽는다.
4. null include/ignore를 적용한다.
5. property-specific matcher 또는 default matcher와 transformer를 적용한다.
6. exact/containing/starting/ending predicate를 bound parameter로 compile한다.

LIKE pattern에서는 `%`, `_`, escape character를 literal로 escape한다. 모든
property가 ignored/null-ignore이면 `Op.TRUE`를 반환한다. `findOne`과
`oneValue`는 최대 2건을 읽고 다건이면
`IncorrectResultSizeDataAccessException`을 반환한다.

지원 범위:

- flat ignored path
- `matchingAll`/`matchingAny`
- exact, containing, starting, ending string matcher
- null ignore/include
- property value transformer

regex, nested path, ignore-case 전체, primitive/string 이외의 string matcher는
조용히 무시하지 않고 `UnsupportedOperationException`으로 거부한다. 지원 범위,
attached probe 획득 예제, 신규/detached probe 금지는 KDoc과 module README에
기록한다.

### terminal operation

- `firstValue`: plan limit과 무관하게 `LIMIT 1`
- `oneValue`: plan limit과 무관하게 `LIMIT 2`로 조회하고 2건이면
  `IncorrectResultSizeDataAccessException`
- `all`: positive plan limit을 적용한 query를 한 번 실행해 목록 반환
- `page`: content query와 fresh count query를 사용하고
  `PageableExecutionUtils.getPage`로 불필요한 count를 피한다. paged `Pageable`의
  offset/pageSize/sort가 plan limit/sort를 대체한다. unpaged이면 plan의 positive
  limit은 유지하고 `Pageable.sort`를 사용한다.
- `count`: selected fields, sort, limit, offset이 없는 fresh root-row count query
- `exists`: selected projection와 sort 없이 root ID만 선택하는 fresh `LIMIT 1`
  query
- `stream`: predicate, sort, positive plan limit, selected column을 적용한
  cursor-backed single-use `Stream`을 반환하고 upfront list materialization을 하지
  않는다.

반복 `sortBy`는 append한다. flat property의 ASC/DESC와 native null handling만
지원하며 `ignoreCase`와 `NULLS_FIRST`/`NULLS_LAST`는 SQL 전에 거부한다. unknown
property도 무시하지 않는다. `Pageable` sort에도 같은 검증을 적용한다.

cursor stream은 callback plan과 분리된 lease를 갖는다. 생성 thread와 동일한
active `JdbcTransaction`에서만 소비할 수 있고, caller가 outer transaction
안에서 try-with-resources/`use`로 닫아야 한다. factory-created repository가 새
Spring transaction을 시작한 호출은 method 반환과 함께 transaction이 닫히므로
cursor를 열기 전에 거부한다. 이미 존재하는 outer Spring transaction에 참여한
factory 경로와 caller-owned Exposed `transaction {}` 안의 direct-constructor
경로만 허용한다.

cursor executor는 Exposed `Query.iterator()`를 사용하지 않는다. 해당 iterator는
driver의 `supportsMultipleResultSets=false`에서 `.toList()`로 materialize하기
때문이다. 대신 공개 `JdbcTransaction.execQuery(query) { resultSet -> ... }`로
statement를 실행하고 callback에서 반환받은 JDBC `ResultSet`과
`resultSet.statement`를 단일 lease가 직접 소유한다. `ResultRow` 생성에 필요한
Exposed `ResultRow.create`/column type metadata 접근은 한 파일의
`@OptIn(InternalApi::class)` adapter로 격리한다. 이 opt-in은 Exposed 1.4.0
implementation compatibility risk이며 upgrade test의 고정 점검 대상이다.

이 경로는 `supportsMultipleResultSets` 값과 무관하게 upfront materialization을
하지 않는다. exhaustion, `Stream.close()`, mapper 예외 중 먼저 발생한 시점에
idempotent하게 `ResultSet`과 statement를 닫고 lease를 폐기한다. 각 advance는
owner thread와 transaction identity를 확인한다. transaction이 먼저 닫히거나 다른
thread에서 소비하면 안전하게 자원을 닫고 SQL/value를 노출하지 않는
`InvalidDataAccessApiUsageException`을 반환한다. Exposed transaction 종료도 남은
executed statement를 최종 안전망으로 닫지만 정상 소유권은 stream lease에 있다.
`supportsMultipleResultSets=false` driver는 같은 transaction의 다음 statement가
열린 cursor를 닫을 수 있으므로 stream 소비 중 중첩 repository/Exposed SQL 실행을
지원하지 않는다. cursor를 모두 소비하거나 닫은 뒤 다음 SQL을 실행해야 한다.

## Factory와 ABI

`ExposedJdbcRepositoryFactory`는 자신이 소유한 `ProjectionFactory`를 새 내부
engine 경로에 전달한다. `SimpleExposedJdbcRepository`는 private primary
constructor와 명시적인 public one-argument delegating constructor를 사용해 기존
`(ExposedEntityInformation)` JVM descriptor를 그대로 유지한다. factory 전용
생성 경로는 `@JvmSynthetic internal` companion factory를 통해 private
constructor에 접근하며 Java/Kotlin public overload를 추가하지 않는다.

direct constructor는 `SpelAwareProxyProjectionFactory`를 기본값으로 사용하고
Spring AOP transaction proxy를 만들지 않는다. caller-owned Exposed
`transaction {}`가 필수이다. factory-created repository는 factory-owned
`ProjectionFactory`와 Spring transaction proxy를 사용한다. 두 경로의 parity는
동일한 active transaction 안에서 standard closed interface/data-class projection의
query, mapping, exception 결과로 한정한다. custom factory/converter parity는
목표가 아니다.

새 `JdbcFluentQueryPlan`, `ExamplePredicateCompiler`, strict property resolver,
interface mapper, DTO constructor mapper, terminal executor는 internal/private 책임으로
분리한다. mutable Exposed `Query` 생성과 변경은 terminal executor 한 곳에
한정한다. `ExposedJdbcRepository`와 `ExposedPersistentProperty`에 새 abstract
method를 추가하지 않는다.

직접 constructor를 사용하는 기존 테스트와 consumer는 계속 컴파일·실행되어야
한다. Kotlin/Java compile fixture와 `javap`/binary compatibility 검증으로 public
one-argument descriptor와 public API surface를 변경 전후 비교한다.

## 오류 처리

값 접근과 SQL 실행 전에 다음 taxonomy로 실패한다.

| 조건 | 예외 |
| --- | --- |
| `null` sort/property collection, 음수 limit | `IllegalArgumentException` |
| escaped callback, inactive/wrong transaction, detached/new probe, unknown/ambiguous/nested property, project mismatch, unsupported sort option | `InvalidDataAccessApiUsageException` |
| open/SpEL projection, unsupported DTO/custom query/matcher shape | `UnsupportedOperationException` |
| projection null/type/constructor mapping 실패 | Spring Data `MappingException` |
| `findOne`/`oneValue` 다건 | `IncorrectResultSizeDataAccessException` |

메시지는 operation, result type, offending property/option, expected type, 지원
대안만 포함한다. Entity에 `project()`를 호출하면 `as(ClosedProjection::class.java)`
사용법을, detached probe에는 같은 Exposed transaction에서 이미 로드된 probe가
필요하다는 remediation을 제공한다. probe/DTO/bind 값, entity ID, table/column의
물리 이름, raw SQL, driver message는 메시지와 repository 기본 로그에 포함하지
않는다. nested cause는 안전한 exception type만 보존하고 민감한 message를 그대로
전파하지 않는다. control character를 제거하고 길이를 제한한다.

## 실패 모드와 대응

1. **partial Entity가 identity cache를 오염한다.** Entity result는 항상
   `EntityClass.find` 전체 row 경로를 사용하고 selected-row mapping은
   DTO/interface에만 허용한다.
2. **custom `EntityClass.searchQuery`의 join이 row/count cardinality를 바꾼다.**
   root-table filter-only shape만 허용하고 join/group/distinct/aggregate/locking 및
   custom paging/sort shape를 SQL 전에 거부한다.
3. **QBE 조건이 delegated property를 읽지 못해 전체 조회한다.** mapping
   accessor 기반 compiler와 실제 `Example` 회귀 테스트를 먼저 추가한다.
4. **DTO parameter 순서나 ID 타입이 어긋난다.** named preferred constructor,
   parameter별 column resolution, `EntityID.value` unwrap을 실행 전에 검증한다.
5. **`oneValue`가 다건 결과를 숨긴다.** 최대 2건을 조회하고 Spring Data 표준
   예외를 검증한다.
6. **unknown property가 조용히 빠진다.** projection 전용 strict resolver로
   SQL 실행 전에 실패시킨다.
7. **JDBC cursor가 닫힌 transaction을 참조한다.** caller-owned outer transaction
   과 동일 thread를 검증하고 exhaustion/close/mapper failure에서 cursor와
   statement를 닫는다. repository가 새 transaction을 소유한 호출은 stream을
   만들기 전에 거부한다.
8. **대표 DB에서 select/count SQL shape가 달라진다.** H2, PostgreSQL,
   MySQL V8을 순차 실행하고 `SqlLogger`로 selected column과 terminal query를
   검증한다.
9. **callback 밖으로 plan이 escape한다.** scope token을 `finally`에서 폐기하고
   모든 mutator/terminal이 owner thread와 transaction identity를 검증한다.
10. **오류가 민감한 probe 값을 노출한다.** 구조 정보만 포함하는 redacted
    exception을 만들고 민감 값/control character 회귀 테스트를 둔다.

## 운영성과 관측 경계

- 새 production metric, logger, dependency, public configuration surface는
  #642에 추가하지 않는다. Exposed의 기존 statement count/duration,
  `warnLongQueriesDuration`, datasource 관측을 그대로 사용할 수 있다.
- repository는 raw SQL 또는 bind/projection 값을 새로 로그하지 않는다.
- 테스트 hook은 terminal별 statement budget을 검증한다. `first`/`one`/`all`/
  `count`/`exists`는 1 query, `page`는 최대 2 query이며 lazy count가 생략되는
  case를 별도로 검증한다.
- cursor resource는 test double과 실제 H2에서 exhaustion, explicit close,
  short-circuit, mapper failure별 close count가 정확히 1인지 확인한다.
- rollout은 1.13.0의 `CHANGELOG.md`와 `WIP.md`에 기존 eager cast/no-op behavior,
  새 fail-fast 경계, attached-probe 및 cursor transaction 계약을 기록한다. 안정
  release manual은 배포 전까지 변경하지 않는다.
- PR CI는 pure mapper/resolver와 H2 전체 semantic matrix를 실행한다.
  PostgreSQL/MySQL 대표 integration은 repo의 기존 순차 Testcontainers lane에서
  실행하고, 의도적 skip과 실제 실패를 최종 aggregation에서 구분한다.

## 문서와 호환성

- `spring-boot/jdbc/README.md`와 `README.ko.md`에 동일한 interface/data class
  projection 예제, 지원/거부 shape 표, fail-fast 경계를 추가한다.
- attached probe를 같은 transaction에서 조회하는 실제 예제와 신규/detached
  Entity가 ad-hoc probe가 될 수 없다는 경고를 첫 QBE 예제에 둔다.
- `as`/`project`의 합법·불법 순서, 반복 sort 누적, `Pageable` override,
  `firstValue`/`oneValue`/`all`/`page`, 다건 예외를 예제로 고정한다.
- getter-only Kotlin/Java closed interface와 거부되는 `@Value`/SpEL open
  interface를 나란히 보여준다.
- cursor `stream`은 outer transaction 안에서 같은 thread로 `use`/try-with-
  resources 소비·종료해야 함을 예제로 제공하고 대량 처리에는 paging도 함께
  안내한다.
- factory-created repository를 일반 caller의 권장 경로로 표시하고 direct
  constructor는 transaction을 제공하지 않는다는 경고를 KDoc에 둔다.
- reader-facing KDoc은 한국어로 작성한다.
- `docs/manual/**`의 현재 안정 release 문서는 1.13.0 배포 전이므로 변경하지
  않는다.
- 새 dependency, module, catalog/BOM/Kover/workflow 등록은 없다.
- 기존 CRUD, PartTree, DAO transaction, one-argument constructor의 source/binary
  계약을 유지한다.

## 테스트 전략

테스트는 TDD로 다음 순서에 따라 추가한다.

1. pure plan/resolver/mapper 단위 테스트로 state transition, exact property set,
   null/type/ID unwrap, exception redaction을 고정한다.
2. 실제 `Example` predicate가 같은 transaction에서 조회한 attached DAO probe를
   읽고 모든 QBE terminal에서 같은 filter를 만든다. ID 제외, all-ignored
   `Op.TRUE`, 신규/다른 transaction/detached probe 실패도 검증한다.
3. closed interface projection이 필요한 column만 선택하고 projection getter를
   transaction 종료 후에도 읽을 수 있다.
4. Kotlin data class와 Java record/DTO constructor projection이 값을 매핑한다.
   constructor 없음/모호함/parameter-name 누락도 SQL 전에 실패한다.
5. `as`/`project` 호출 순서와 반복 호출이 정의된 final plan을 만들며 missing/
   extra/Entity property set을 SQL 전에 거부한다.
6. sort append, unsupported order option, limit 0/replacement, first, one, page,
   count, exists가 SQL과 cardinality/query-count 계약을 지킨다.
7. Entity 반환이 전체 DAO row와 identity cache 의미를 유지하고 selected-row
   projection은 cache를 변경하지 않는다.
8. filter-only custom query는 동일 content/count 의미를 유지하고 join/group/
   distinct/custom paging shape는 SQL 전에 실패한다.
9. callback escape, wrong thread/transaction, open/nested/unknown/unsupported
   matcher 입력이 probe getter와 SQL보다 먼저 실패한다.
10. cursor stream이 upfront materialization 없이 한 행씩 매핑하고 exhaustion,
    explicit close, short-circuit, mapper failure에서 자원을 한 번 닫는다. 새
    factory-owned transaction과 transaction 밖 direct path는 cursor open 전에
    실패한다. `supportsMultipleResultSets=false` test double에서도 list fallback이
    발생하지 않음을 검증하고, cursor 소비 중 중첩 SQL이 거부되거나 driver가
    cursor를 닫는 동작을 명확한 예외로 진단하는 회귀 테스트를 추가한다.
11. factory-created repository와 direct constructor가 active transaction에서
    standard projection 결과를 공유하고, Java/Kotlin compile fixture와 `javap`가
    기존 one-argument constructor descriptor를 보존한다.
12. H2에서 전체 semantic matrix를 실행하고 PostgreSQL/MySQL V8에서는 selected
    column, LIKE escape, null/conversion, limit/offset/count, cursor close의 대표
    subset을 순차 실행한다.

SQL logger assertion은 literal quoting 차이를 피하고 selected column 목록과
`LIMIT`/count/exists의 구조만 dialect-aware하게 검증한다.

## 수용 기준

- [ ] closed interface projection이 `ProjectionFactory`로 안전하게 생성된다.
- [ ] Kotlin data class와 대표 Java DTO/class projection이 named constructor로
      생성된다.
- [ ] `project(properties)`가 실제 SQL selected column을 제한한다.
- [ ] unsupported projection/property/matcher shape가 SQL 실행 전에 실패한다.
- [ ] predicate, sort, limit, first, one, page, count, exists가 DB에 pushdown된다.
- [ ] 모든 QBE terminal이 동일 compiler와 attached-probe/ID/null/matcher 계약을
      사용한다.
- [ ] `oneValue`가 다건 결과에서 `IncorrectResultSizeDataAccessException`을
      반환한다.
- [ ] Entity 결과는 partial row를 만들지 않고 기존 identity cache 의미를
      유지한다.
- [ ] filter-only custom query만 허용하고 row/count cardinality를 바꾸는 shape는
      SQL 전에 거부한다.
- [ ] callback-scoped plan은 escape할 수 없고 cursor stream은 동일 outer
      transaction/thread에서 한 행씩 소비되며 모든 종료 경로에서 자원을 닫는다.
- [ ] 예외와 기본 로그가 probe/bind/DTO 값, raw SQL, 물리 table/column 정보를
      노출하지 않는다.
- [ ] 기존 한 인자 constructor와 public repository API/ABI를 유지한다.
- [ ] QBE 지원 범위, direct-constructor transaction, cursor stream 제약이 KDoc
      및 EN/KO README에 일치한다.
- [ ] H2, PostgreSQL, MySQL V8의 관련 테스트와 module test, Detekt,
      `git diff --check`가 통과한다.

## DoD

- 승인된 설계와 구현 plan의 수용 기준 추적성이 완결되어야 한다.
- 각 behavior는 RED/GREEN 증거를 가져야 한다.
- spec, plan, implementation, pre-PR review의 최신 결과가 P0=0/P1=0이어야 한다.
- public API/ABI와 EN/KO 문서 parity를 검증해야 한다.
- Lore commit, lesson, PR 본문 `## DoD Status`, exact-head CI/review 증거를
  수집해야 한다.
- merge는 merge-ready 보고 이후 fresh 사용자 승인을 별도로 받아야 한다.

## Writer gate

- `SPW-01`: PASS — Issue #642, Epic #658 slot 1, `spring-boot/jdbc`, Exposed
  1.4.0과 Spring Data Commons 4.1.0 근거, cursor-stream transaction/resource
  경계를 고정했다.
- `SPW-02`: PASS — 문제, 목표·비목표, 세 접근, 선택 구조, 오류, 열 개 실패
  모드, 호환성, 테스트, 수용 기준, DoD를 포함했다.
- `SPW-03`: PASS — 한국어 기술 문체를 적용하고 API, identifier, command,
  exception 이름을 보존했다.
- `SPW-04`: PASS — repository/factory/mapping context와 upstream source 계약을
  claim별로 대조하고 수용 기준으로 연결했다.
- `SPW-05`: PASS — Markdown heading, 목록, code span, checklist 구조와
  `docs/manual/**` 제외 경계를 최종 확인했다.
