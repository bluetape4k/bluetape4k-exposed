# Issue #643 R2DBC 코루틴 QBE/FluentQuery 설계

## 문서 상태

- 대상 이슈: [#643](https://github.com/bluetape4k/bluetape4k-exposed/issues/643)
- 대상 모듈: `:bluetape4k-exposed-spring-boot-r2dbc`
- 대상 릴리스: `1.13.0`
- 설계 결정: 코루틴 네이티브 QBE/FluentQuery API만 지원
- 제외 결정: `ReactiveQueryByExampleExecutor`, Reactor 기반 facade, 코루틴·Reactor 이중 facade
- 설계 검토: 6관점 P0 0건, P1 0건

## 문제 정의

`ExposedR2dbcRepository`는 코루틴 CRUD, Exposed `Op<Boolean>` 조회, 페이징을 제공하지만 Spring Data `Example<T>` 기반 동적 조회는 제공하지 않는다. JDBC 모듈에는 같은 도메인의 QBE/FluentQuery 기능이 있으나, R2DBC 모듈은 실행 모델과 자원 수명주기가 다르므로 JDBC 실행기를 그대로 재사용할 수 없다.

Spring Data Commons 4.1.0은 `ReactiveQueryByExampleExecutor`와 `ReactiveFluentQuery`를 제공하지만 코루틴 전용 QBE 계약은 제공하지 않는다. Reactor facade로 우회하면 `Mono`/`Flux` 구독 경계가 Exposed의 현재 코루틴 트랜잭션 문맥과 분리될 수 있다. 이 기능의 핵심 계약은 외부 `suspendTransaction` 재사용, 구조화된 취소 전파, cold `Flow`의 안전한 재수집이므로 코루틴 API를 독립적으로 정의한다.

## 목표

1. `Example<T>`로 단건·다건·개수·존재 여부를 코루틴 방식으로 조회한다.
2. 정렬, 제한, 페이지, 슬라이스, 폐쇄형 projection을 불변 fluent plan으로 조합한다.
3. suspend terminal과 `Flow` 수집은 Exposed 1.4.0의 top-level/default outer transaction 규칙을 따르며 외부 트랜잭션의 connection과 rollback 경계를 보존한다.
4. JDBC QBE의 관찰 가능한 matcher 의미는 맞추되 R2DBC 실행기와 projection mapper는 분리한다.
5. 기존 CRUD, derived query, declared query, 공개 4인자 `SimpleExposedR2dbcRepository` 생성자를 보존한다.

## 비목표

- `ReactiveQueryByExampleExecutor`, `ReactiveFluentQuery`, `Mono`, `Flux` API
- 같은 기능의 코루틴·Reactor 이중 facade
- raw `@Query` 지원 또는 Issue #324 범위
- 연관 관계 순회, nested property, open projection, SpEL projection
- arbitrary type conversion 또는 공개 mapping SPI
- JDBC 구현체의 predicate compiler, executor, result stream 재사용
- repository별 `R2dbcDatabase` 주입 또는 #637에서 기각한 Spring transaction manager bridge
- `docs/manual/**`의 안정 릴리스 `1.12.1` 문서 변경

## 검토한 대안

### 채택: 코루틴 네이티브 계약

기존 `ExposedR2dbcRepository` ABI는 바꾸지 않고, 이를 상속하는 opt-in `ExposedR2dbcQueryByExampleRepository`를 추가한다. API는 `suspend`와 `Flow`만 노출한다. Exposed의 coroutine context를 그대로 사용하며 Reactor 변환 계층을 두지 않는다.

### 기각: Spring Data reactive 계약 직접 구현

표준 API라는 장점은 있으나 반환형과 구독 수명주기가 모듈의 `CoroutineCrudRepository` 계약과 충돌한다. Reactor context 전파는 Exposed coroutine transaction context의 동일성을 보장하지 않는다.

### 기각: 코루틴·Reactor 이중 facade

동일 기능에 서로 다른 취소, 구독, 트랜잭션 규칙이 생긴다. 테스트 조합과 문서 표면도 두 배가 되므로 Issue #643에서는 지원하지 않는다.

## 공개 API

공개 이름은 구현 단계에서 기존 패키지 명명 규칙과 ABI 검사로 최종 확인하되, 의미와 시그니처는 다음 계약을 따른다.

```kotlin
interface ExposedR2dbcQueryByExampleRepository<R: Any, ID: Any>:
    ExposedR2dbcRepository<R, ID>,
    ExposedCoroutineQueryByExampleExecutor<R>

interface ExposedCoroutineQueryByExampleExecutor<T: Any> {
    suspend fun findOne(example: Example<T>): T?
    fun findAll(example: Example<T>): Flow<T>
    fun findAll(example: Example<T>, sort: Sort): Flow<T>
    suspend fun count(example: Example<T>): Long
    suspend fun exists(example: Example<T>): Boolean

    suspend fun <Q> findBy(
        example: Example<T>,
        queryFunction: suspend (ExposedCoroutineFluentQuery<T>) -> Q,
    ): Q
}

interface ExposedCoroutineFluentQuery<T: Any> {
    fun sortBy(sort: Sort): ExposedCoroutineFluentQuery<T>
    fun limit(limit: Int): ExposedCoroutineFluentQuery<T>
    fun <R: Any> asType(resultType: KClass<R>): ExposedCoroutineFluentQuery<R>
    fun project(vararg properties: String): ExposedCoroutineFluentQuery<T>

    suspend fun one(): T?
    suspend fun first(): T?
    fun all(): Flow<T>
    suspend fun page(pageable: Pageable): Page<T>
    suspend fun slice(pageable: Pageable): Slice<T>
    suspend fun count(): Long
    suspend fun exists(): Boolean
}
```

사용자는 기존 repository interface의 부모 타입만 opt-in 타입으로 바꾼다.

```kotlin
interface UserRepository : ExposedR2dbcQueryByExampleRepository<UserDto, Long> {
    override val table: IdTable<Long> get() = Users
    override fun extractId(entity: UserDto): Long? = entity.id
    override fun toDomain(row: ResultRow): UserDto = UserDto(
        id = row[Users.id].value,
        name = row[Users.name],
    )
    override fun toPersistValues(domain: UserDto): Map<Column<*>, Any?> =
        mapOf(Users.name to domain.name)
}

interface UserNameView {
    val name: String
}

val nameContainingMatcher: ExampleMatcher = ExampleMatcher.matchingAll()
    .withMatcher("name", ExampleMatcher.GenericPropertyMatchers.contains())
    .withIgnorePaths("id")

suspend fun collectUserNames(
    userRepository: UserRepository,
    r2dbcDatabase: R2dbcDatabase,
    consume: suspend (UserNameView) -> Unit,
) {
    val example = Example.of(UserDto(name = "al"), nameContainingMatcher)
    val names: Flow<UserNameView> = userRepository.findBy(example) { query ->
        query
            .asType(UserNameView::class)
            .project("name")
            .sortBy(Sort.by("name"))
            .limit(20)
            .all()
    }

    suspendTransaction(r2dbcDatabase) {
        names.collect(consume) // 외부 transaction의 미커밋 row를 보려면 그 안에서 수집한다.
    }
}
```

다음 사용은 SQL 실행 전에 실패하거나 생성 시점의 transaction을 재사용하지 않는다.

```kotlin
suspend fun verifyInvalidProjection(userRepository: UserRepository, example: Example<UserDto>) {
    val failure = runCatching {
        userRepository.findBy(example) { query ->
            query.project("name").one()
        }
    }.exceptionOrNull()
    check(failure is InvalidDataAccessApiUsageException)
}

suspend fun collectOutsideCreationTransaction(
    userRepository: UserRepository,
    example: Example<UserDto>,
    r2dbcDatabase: R2dbcDatabase,
    consume: suspend (UserDto) -> Unit,
) {
    val escaped = suspendTransaction(r2dbcDatabase) {
        userRepository.findAll(example)
    }
    escaped.collect(consume) // 생성 시점의 outer transaction이 아니라 수집 시점의 transaction을 사용한다.
}
```

Spring Data의 `<S : T>` probe 형태는 채택하지 않는다. 현재 repository mapper는 고정 도메인 `T`를 생성하므로 임의 하위 타입 probe를 안전하게 materialize할 수 없다. `Example<T>`만 받는 것이 실제 역량과 일치한다.

`asType(KClass<R>)`는 Kotlin 우선 API다. Java record도 `RecordType::class`로 지정한다. Java 전용 `Class<R>` overload는 ABI 표면을 늘리므로 별도 요구가 생기기 전까지 추가하지 않는다.

`sortBy`는 기존 정렬 뒤에 새 정렬을 누적한다. `limit`과 `asType`은 마지막 호출이 앞선 값을 대체하며 `limit(0)`은 제한을 해제한다. `project`는 마지막 호출의 집합으로 교체하고 빈 호출은 projection type의 필수 입력 자동 선택으로 되돌린다. non-empty `project`는 반드시 domain type이 아닌 `asType` projection 뒤에 사용하며 projection의 필수 source property 집합과 정확히 같아야 한다. domain type에 대한 partial `project`는 SQL 실행 전에 거부한다.

## Fluent plan과 수명주기

`ExposedCoroutineFluentQuery`의 변경 메서드는 원본을 수정하지 않고 새 `R2dbcFluentQueryPlan`을 반환한다. 진입점은 domain/property 구조와 matcher 지원 여부를 먼저 검증한 뒤 probe getter와 transformer를 각각 한 번만 실행한다. plan은 원본 `Example`이나 mutable probe를 보관하지 않고 canonical property, matcher, null 정책, transformer 결과, detached bind value로 구성한 `R2dbcExampleSnapshot`을 저장한다. sort와 projection property도 같은 resolver로 canonicalize한다. 활성 트랜잭션, `ResultRow`, `R2dbcResult`는 저장하지 않는다.

`R2dbcBindValueSnapshotter`는 `String`, primitive wrapper, `BigInteger`, `BigDecimal`, `UUID`, enum, Java/Kotlin time처럼 열거된 immutable scalar만 그대로 보관한다. primitive/object array, `ByteBuffer`, collection, map은 bind type을 보존하는 defensive deep copy로 바꾸고 그 내부 값에도 같은 규칙을 재귀 적용한다. 안전하게 복제할 수 없는 `Number` 하위 타입, custom mutable type, transformer 결과는 SQL 전에 `InvalidDataAccessApiUsageException`으로 거부한다. 공개 snapshot SPI는 이번 범위에 추가하지 않는다.

`findBy` callback에 전달한 fluent 객체는 callback 범위에서만 유효하다. callback 종료 뒤 fluent 객체의 변경 또는 terminal 호출은 `InvalidDataAccessApiUsageException`으로 실패한다. 단, `all()`이 callback 안에서 만든 `Flow`는 immutable plan snapshot을 보유하므로 callback 종료 뒤에도 수집할 수 있다.

각 `Flow` 수집은 새 쿼리와 R2DBC result를 만든다. outer transaction이 없으면 새 top-level transaction/connection 점유 구간을 만들고, 지원되는 outer transaction 안에서는 그 transaction과 connection을 사용한다. 같은 `Flow`를 두 번 수집하면 두 번 독립 실행하며 그만큼 DB round trip도 반복한다. row나 cursor를 callback 밖으로 노출하거나 single-consumption 결과를 공유하지 않는다. 구현은 `flow {}`에서 result를 직접 순차 수집·방출하고 별도 producer coroutine이나 기본 `channelFlow` buffer를 만들지 않는다.

executor는 terminal 실행 lease를 outer transaction context별로 관리한다. 같은 callback에서 실행한 두 terminal, callback에서 반환한 Flow와 terminal, 또는 두 Flow 수집이 같은 outer transaction의 connection을 병렬 사용하려 하면 `InvalidDataAccessApiUsageException`으로 조기 거부한다. lease는 성공, 일반 예외, 취소 모두 `finally`에서 해제한다. 서로 독립된 top-level transaction의 병렬 수집은 허용하며, 같은 cold Flow의 순차 재수집과 취소 뒤에도 유효한 caller-owned outer transaction의 후속 순차 실행을 허용한다.

## 트랜잭션과 취소 계약

- 모든 terminal은 기존 CRUD와 같이 공개 `suspendTransaction()` 경로를 사용한다. Exposed 1.4.0에서 outer transaction이 있고 `useNestedTransactions=false`이면 `newTransaction`은 outer `R2dbcTransaction` 객체와 connection을 그대로 반환하며 nested commit/close를 수행하지 않는다.
- Exposed 1.4.0에서 `useNestedTransactions=true`이면 same-connection wrapper/savepoint를 만들지만 nested `suspendTransaction` 경로가 wrapper를 `close()`하지 않아 savepoint release를 보장하지 않는다. QBE는 upstream transaction을 직접 close하거나 보정하지 않는다. 따라서 outer transaction과 `useNestedTransactions=true`의 조합은 SQL 전에 `InvalidDataAccessApiUsageException`으로 거부한다. top-level 호출과 기본값 `false`인 outer transaction은 지원한다.
- 현재 트랜잭션이 없으면 Exposed의 primary/default `R2dbcDatabase`로 top-level transaction을 연다. repository는 database를 소유하거나 주입받지 않는다.
- #637의 계약을 그대로 적용한다. 다중 DB caller는 `suspendTransaction(targetDatabase) { ... }` 안에서 terminal을 호출하거나 `Flow`를 수집한다. QBE 기능은 Spring `transactionManagerRef` bridge를 다시 도입하지 않는다.
- `all()`과 `findAll(...)`은 cold `Flow`다. Flow 생성은 SQL이나 transaction을 시작하지 않으며 선택과 실행은 수집 시점에 이뤄진다.
- 외부 트랜잭션의 미커밋 row는 그 transaction 안에서 terminal을 호출하거나 `Flow`를 수집할 때만 관찰한다. transaction 밖으로 Flow를 반환해 나중에 수집하면 새 top-level transaction을 사용한다.
- `CancellationException`을 잡거나 wrapping하지 않고 동일 객체로 상위 coroutine에 전파한다. top-level transaction/result/connection 정리는 Exposed의 `NonCancellable` cleanup에 맡긴다. 지원되는 `useNestedTransactions=false` outer 호출은 caller-owned transaction/connection을 commit하거나 닫지 않는다. QBE result 수집과 execution lease만 `finally`에서 정리한다.
- callback이 반환한 임의 `Flow`나 lazy 값의 수명주기는 이 API가 보장하지 않는다. 이 API가 보장하는 탈출 가능한 lazy 결과는 `all()`이 생성한 `Flow`뿐이다.
- `all()` Flow는 row-by-row로 materialize하고 방출한다. 이 Flow에 collection 시점에 적용하는 `map`/`filter`는 허용하지만, callback 안에서 임의로 만든 다른 Flow나 transaction-bound 값을 반환하는 것은 지원하지 않는다.
- `page`의 content와 필요한 count는 하나의 logical transaction에서 실행한다. `READ COMMITTED`에서는 statement별 snapshot이 달라질 수 있으므로 total과 content의 동시 변경 정합성을 보장하지 않는다. 더 강한 snapshot이 필요하면 caller가 database transaction isolation을 설정한다.

## terminal 의미

| Terminal | 의미 |
| --- | --- |
| `one()` | fluent `limit`을 무시하고 최대 2건을 읽어 0건은 `null`, 1건은 값, 2건 이상은 `IncorrectResultSizeDataAccessException` |
| `first()` | 정렬·필터 적용 후 `limit 1`; 없으면 `null` |
| `all()` | cold `Flow`; 각 수집마다 fresh query/result 생성 |
| `page(pageable)` | paged sort/size가 fluent sort·limit을 대체한다. unpaged/마지막 short page는 content 크기로 total을 추론하고, 필요한 경우에만 count를 실행한다. |
| `slice(pageable)` | count 없이 `pageSize + 1`건을 읽어 다음 페이지 여부 계산 |
| `count()` | projection, sort, limit을 무시하고 predicate만 적용 |
| `exists()` | projection, sort, limit을 무시하고 `table.id`만 `LIMIT 1`로 조회 |

`first()`는 fluent limit과 무관하게 한 건만 읽는다. `page()`와 `slice()`는 paged `Pageable`의 size를 사용하고 fluent limit을 무시한다. unpaged `Pageable`은 pageable sort로 fluent sort를 교체하되 fluent limit은 유지한다. `limit`은 음수를 거부하고 `0`을 제한 없음으로 해석한다. 빈 결과는 각각 `null`, empty `Flow`, empty page/slice, `0`, `false`로 반환한다.

## QBE matcher 규칙

R2DBC compiler는 JDBC QBE가 이미 제공하는 관찰 가능한 의미를 맞춘다.

- `matchingAll`과 `matchingAny`
- `NullHandler.IGNORE`와 `NullHandler.INCLUDE`
- property transformer
- `DEFAULT`, `EXACT`, `CONTAINING`, `STARTING`, `ENDING`
- case-sensitive 문자열 비교
- 명시적으로 ignore된 property

다음은 명시적으로 거부한다.

- `REGEX`
- global 또는 property별 ignore-case
- nested property
- 알 수 없거나 모호한 property
- 임의 `ConversionService` coercion

문자열 LIKE 값은 repository의 `LikePattern` 규칙으로 literal escape하고 Exposed parameter binding을 사용한다. 사용자 값을 SQL fragment로 연결하지 않는다.

구조, property ambiguity, matcher 지원 여부를 먼저 검증한 다음 probe getter와 transformer를 각각 한 번만 실행한다. transformer가 empty를 반환하면 non-null property는 제외한다. raw 값이 null이고 `NullHandler.INCLUDE`이면 transformer 결과가 empty여도 JDBC 계약과 같이 `IS NULL` predicate를 유지한다. reflection/projection/transformer 예외는 domain 또는 target type과 property만 포함한 안전한 Spring Data `MappingException`으로 변환한다. 단, `CancellationException`과 `Error`는 sanitization/wrapping 대상에서 제외하고 원래 객체를 그대로 전파한다. 원래 일반 예외의 cause와 suppressed graph는 붙이지 않으며 probe/row/bind 값과 내부 reflection stack을 QBE가 생성하는 메시지나 로그에 노출하지 않는다.

case-sensitive matcher는 SQL에 lower/upper 변환을 추가하지 않는다는 뜻이다. 실제 대소문자 비교 결과는 database column collation에 영향을 받을 수 있으며 이 기능이 collation을 덮어쓰지 않는다. LIKE hostile input `%`, `_`, escape 문자 자체를 모두 literal로 검증한다.

## property와 column 해석

resolver는 repository domain type과 명시적 `IdTable`에 결박한다. property는 정확한 Kotlin/Java property 이름을 먼저 찾고, 그다음 camelCase와 snake_case의 유일한 대응만 허용한다. 후보가 둘 이상이면 모호한 것으로 거부한다. sort의 unknown/ambiguous/nested property, `Sort.Order.ignoreCase`, 비기본 null handling도 SQL 실행 전에 거부한다. matcher/sort/project에서 받은 진단용 property token은 JDBC `safeDiagnosticValue`와 같은 규칙으로 ISO control 및 format/line/paragraph separator를 제거하고 128자로 제한한다. operation과 metric label은 고정 enum 값만 사용한다.

factory-created 경로는 repository 수명 동안 `(domainType, table)` resolver map과 `(domainType, resultType, property set)` projection shape/constructor accessor를 캐시한다. direct 4인자 생성자 경로는 첫 `Example`의 probe runtime type을 고정하고 이후 다른 domain type을 거부하며 같은 캐시를 재사용한다. constructor projection은 순서가 고정된 accessor/value 배열을 사용하고 closed interface projection에서 `ProjectionFactory`가 요구할 때만 source map을 만든다.

`id`는 `table.id`에 대응한다. R2DBC repository는 DAO `Entity`가 아니라 일반 DTO를 다루므로 non-null ID는 matcher가 무시하지 않는 한 predicate에 포함한다. null ID는 기본 `NullHandler.IGNORE` 규칙을 따른다. 이는 DAO identity cache 의미를 사용하는 JDBC 경로와 의도적으로 구분한다.

## projection 계약

projection을 지정하지 않으면 기존 `toDomain(ResultRow)`와 전체 row 선택을 그대로 사용한다. projection을 지정하면 별도의 `R2dbcProjectionMapper`가 필요한 column만 선택하고 materialize한다. 선택된 부분 row를 기존 full-domain mapper에 전달하지 않는다. `project` property는 projection 객체의 이름이 아니라 source domain property 이름이며, non-empty 집합은 target projection의 필수 입력과 정확히 같아야 한다.

지원 범위는 다음과 같다.

- closed interface projection: Spring `ProjectionFactory`
- Kotlin data class: primary constructor의 이름 있는 parameter
- Java record 또는 이름 있는 preferred constructor
- 정확히 지정한 scalar property 집합
- `EntityID`의 raw ID 값 변환
- boxed primitive와 Kotlin nullability 검증

다음은 거부한다.

- open projection과 SpEL
- nested projection
- domain type 자체에 대한 partial projection
- 알 수 없거나 모호한 property
- transformed/composite property
- 이름을 안정적으로 얻을 수 없는 constructor

projection 값은 트랜잭션 안에서 detached 값으로 완전히 materialize한다. `ResultRow`, `R2dbcResult`, cursor, transaction-bound lazy 값을 반환하지 않는다. null을 non-null Kotlin parameter에 넣어야 하면 property와 target type을 포함한 명확한 mapping 예외로 실패한다.

## 내부 구조

R2DBC 전용 내부 구성 요소를 둔다.

```text
ExposedR2dbcQueryByExampleRepository proxy
  -> SimpleExposedR2dbcRepository
          -> R2dbcFluentQueryExecutor
          -> R2dbcExampleSnapshot
          -> R2dbcBindValueSnapshotter
          -> R2dbcExamplePredicateCompiler
          -> R2dbcPersistentPropertyResolver
          -> R2dbcProjectionMapper
          -> Exposed R2DBC query/suspendTransaction
```

- `R2dbcFluentQueryPlan`: immutable query description
- `R2dbcExampleSnapshot`: 검증·canonicalize된 matcher와 detached probe 값
- `R2dbcBindValueSnapshotter`: 허용된 immutable scalar와 defensive deep copy를 생성하고 안전하지 않은 custom mutable 값을 거부
- `R2dbcPersistentPropertyResolver`: domain property와 table column의 엄격한 대응
- `R2dbcExamplePredicateCompiler`: `ExampleMatcher`를 bound Exposed `Op<Boolean>`로 변환
- `R2dbcProjectionMapper`: selected column을 detached projection으로 생성
- `R2dbcFluentQueryExecutor`: transaction, cardinality, paging, streaming 담당

JDBC 모듈과 공유하는 것은 공개 matcher 의미와 공통 오류 용어뿐이다. executor, compiler, stream, row mapper를 교차 의존하지 않는다. 새 외부 dependency는 추가하지 않는다.

## Spring Data factory 통합

현재 direct factory proxy는 먼저 repository 구현체의 정확한 메서드를 찾고, 그다음 interface default, 마지막으로 derived/declared query로 분기한다. opt-in repository의 QBE 메서드는 `SimpleExposedR2dbcRepository`가 직접 구현하게 하고 proxy가 derived query 분석 전에 이를 찾도록 회귀 테스트로 잠근다.

factory는 기존에 확보한 domain type, table, mapper와 Spring `ProjectionFactory`를 ABI를 바꾸지 않는 internal collaborator로 R2DBC fluent executor에 전달한다. public 4인자 constructor는 첫 probe runtime type과 기본 `SpelAwareProxyProjectionFactory`를 사용하는 호환 경로를 제공한다. 두 경로 모두 database를 주입받지 않으며 #637의 명시적 outer `suspendTransaction(database)` 계약을 따른다. custom repository fragment와 derived query의 기존 우선순위는 변경하지 않는다.

## ABI와 호환성

`ExposedR2dbcRepository`는 현재 JVM bytecode에서 abstract 메서드만 가진 공개 interface이므로 그대로 유지한다. 새 기능은 opt-in `ExposedR2dbcQueryByExampleRepository`와 `ExposedCoroutineQueryByExampleExecutor`에만 추가한다. 기존 external concrete implementor와 기존 repository interface는 재컴파일이나 새 메서드 구현이 필요 없다.

호환성 정책은 다음과 같다.

1. 공개 `SimpleExposedR2dbcRepository(table, toDomainMapper, persistValuesProvider, idExtractor)` 4인자 생성자를 그대로 유지한다.
2. `SimpleExposedR2dbcRepository`는 새 opt-in repository를 구현하고, factory 전용 collaborator는 public overload가 아닌 private/internal factory 경로로 주입한다.
3. 기존 repository interface 선언, CRUD 호출, derived query, declared query는 변경 없이 동작해야 한다.
4. QBE가 필요한 사용자는 repository 부모 타입을 `ExposedR2dbcQueryByExampleRepository`로 바꾼다. 기존 `ExposedR2dbcRepository` 직접 구현체는 변경하지 않아도 되며, 새 기능까지 제공하려면 opt-in interface와 executor 메서드를 직접 구현하거나 `SimpleExposedR2dbcRepository`로 위임한다.
5. `javap` 기반 signature fixture와 Kotlin/Java consumer compile fixture로 기존 base interface와 생성자가 변하지 않았는지, 새 opt-in API가 의도한 signature인지 고정한다.

이 변경은 `1.13.0` opt-in 기능으로 제공한다. base interface와 직접 concrete 구현체의 기존 ABI를 보존한다.

## 오류 모델

| 조건 | 결과 |
| --- | --- |
| null 입력, 음수 limit | `IllegalArgumentException` |
| 지원하지 않는 matcher/projection shape | `UnsupportedOperationException` |
| unknown/ambiguous/nested property, 지원하지 않는 sort, callback 종료 후 사용, terminal 병렬 실행 | `InvalidDataAccessApiUsageException` |
| `one()` 결과가 2건 이상 | `IncorrectResultSizeDataAccessException` |
| getter/transformer/projection constructor/nullability 불일치 | `CancellationException`과 `Error`를 제외하고 target type과 property만 포함하며 cause graph를 제거한 Spring Data `MappingException` |
| coroutine 취소 | 원래 `CancellationException`을 그대로 전파 |

QBE가 생성하는 오류 메시지와 로그는 probe/row/bind 값, entity ID, SQL/raw driver message, database URL·credential, 전체 reflection stack을 포함하지 않는다. QBE 진단에는 operation, dialect family, transaction ownership(`top-level`/`nested`), cancellation/timeout 분류처럼 값이 아닌 저카디널리티 정보만 허용한다. Exposed 1.4.0 자체 logger는 오류 시 statement와 driver message를 기록할 수 있는 caller-controlled 경계이며 이 기능이 가로채거나 재작성하지 않는다. 애플리케이션은 Exposed logger level과 sink redaction 정책을 별도로 설정해야 한다. QBE executor는 새 metric registry나 timeout 설정을 소유하지 않고 Exposed/database의 기존 query timeout과 logging 경계를 따른다.

## 운영과 롤백 경계

- schema migration, 새 외부 dependency, 별도 connection pool, background coroutine, metric registry를 추가하지 않는다.
- 기존 `ExposedR2dbcRepository` 사용자는 opt-in하지 않는 한 실행 경로와 공개 ABI가 바뀌지 않는다.
- 기능 롤백은 새 opt-in interface, executor, factory dispatch, README/KDoc 예제를 함께 되돌리는 방식이며 기존 repository 선언이나 data migration은 요구하지 않는다.
- 구현 PR은 H2 기본 검증과 PostgreSQL/MySQL 순차 검증의 실행 명령, 실행된 테스트 수, backend 미가용 skip을 구분해 증거로 남긴다.
- QBE 자체 운영 진단은 저카디널리티 분류만 허용하며 probe, row, bind value, entity ID, raw SQL, database URL·credential을 로그나 metric tag에 넣지 않는다. 기존 Exposed logger의 statement/driver message 정책은 caller가 설정한다.

## 주요 실패 모드와 방어

1. **Reactor 변환으로 transaction context 손실**: Reactor facade를 제공하지 않고 `suspendTransaction`과 `Flow`를 직접 사용한다.
2. **callback 밖으로 cursor가 탈출하거나 Flow를 한 번만 수집 가능**: plan snapshot만 탈출시키고 수집마다 query/result를 새로 만든다.
3. **selected row를 full-domain mapper에 전달해 누락 column 예외 발생**: projection 전용 select와 mapper를 분리한다.
4. **proxy가 QBE 메서드를 derived query로 오인**: 구현체 direct dispatch가 PartTree 분석보다 먼저임을 factory 테스트로 검증한다.
5. **camelCase/snake_case 충돌이 잘못된 column을 선택**: 유일한 대응만 허용하고 모호성은 즉시 거부한다.
6. **projection nullability 위반이 늦게 드러남**: 트랜잭션 안에서 constructor parameter별로 검증하고 즉시 실패한다.
7. **취소를 일반 예외로 wrapping해 rollback 의미 훼손**: `CancellationException`을 별도로 잡지 않고 Exposed cleanup에 맡긴다.
8. **mutable probe가 Flow 재수집 사이에 변경**: 진입점에서 getter/transformer 결과를 canonical snapshot으로 복사하고 원본 `Example`을 plan에 남기지 않는다.
9. **같은 transaction의 R2DBC connection을 병렬 terminal이 공유**: outer transaction context별 terminal lease로 중첩·병렬 실행을 조기 거부한다.
10. **기존 base repository implementor ABI 파손**: base interface는 유지하고 opt-in 하위 interface에만 QBE 계약을 둔다.
11. **Exposed 1.4.0 nested savepoint 미해제**: outer transaction에서 `useNestedTransactions=true`이면 SQL 전에 실패하고 upstream transaction을 직접 close하지 않는다.
12. **mutable bind 값이 Flow 재수집 사이에 변경**: 허용된 immutable scalar 또는 defensive deep copy만 snapshot에 저장하고 안전하지 않은 custom type은 거부한다.

## 검증 전략

### 순수 단위 테스트

- immutable plan: canonical example snapshot, `ByteArray`/array/collection/map deep copy, custom mutable type 거부, transformer 결과 변이 격리, sort, limit, projection, pageable 우선순위
- resolver: exact, camel/snake, id, unknown, ambiguous, nested
- compiler: all/any, null handler, transformer empty와 raw null `INCLUDE` 우선순위, 문자열 matcher, LIKE escape
- unsupported matcher와 ignore-case 거부
- projection mapper: interface, data class, Java record, ID unwrap, nullability, unknown property
- callback scope token, terminal lease의 성공/예외/취소 `finally` 해제, `all()` snapshot, mutable probe 변경 격리
- getter/transformer/callback의 `CancellationException` 동일 객체 전파와 `Error` wrapping 금지
- matcher/sort/project 진단 token의 control/format 문자 제거와 128자 제한
- resolver/projection shape cache key와 cache 재사용

### H2 R2DBC 통합 테스트

- `findOne`, `findAll`, sort, limit, count, exists
- one/first cardinality와 빈 결과
- page 동일 logical transaction, 조건부 count SQL 횟수, slice `pageSize + 1`
- cold Flow 재수집 시 독립 쿼리
- 외부 `suspendTransaction(database)` 안/밖 수집의 uncommitted row 차이와 #637 DB 선택 계약
- top-level transaction, outer `useNestedTransactions=false`의 transaction 객체/connection 재사용과 caller-owned close 금지, `true`의 SQL 전 fail-fast/savepoint 미생성
- 수집 중 취소와 rollback/result/connection/lease cleanup, 이후 pool 또는 유효한 caller-owned outer transaction 재사용
- timeout과 caller cancellation의 구분, query timeout 설정 비소유
- 동일 callback/outer transaction에서 terminal 병렬 실행 조기 거부
- projection별 실제 selected column
- `exists()`의 ID-only projection과 `LIMIT 1`
- `%`, `_`, escape 문자를 포함한 LIKE literal 입력, QBE 자체 redacted cause/suppressed graph, caller-controlled Exposed logger 경계
- direct factory dispatch가 QBE를 PartTree로 보내지 않음
- 기존 CRUD, derived query, declared query 회귀

### 다중 데이터베이스 검증

PR 기본 검증은 H2 R2DBC를 실행한다. PostgreSQL과 MySQL R2DBC Testcontainers 테스트는 각각 독립 명령으로 `--no-parallel --max-workers=1`을 사용해 순차 실행한다. LIKE escape, null predicate, count/exists, paging, transaction nesting, cancellation, pool 재사용을 검증한다. 실행 대상 테스트 artifact가 없거나 0건이면 성공으로 간주하지 않으며, backend 미가용 skip과 테스트 실패를 구분해 기록한다.

### ABI와 문서 검증

- 기존 `ExposedR2dbcRepository` interface와 4인자 `SimpleExposedR2dbcRepository` 생성자 유지
- 새 `ExposedR2dbcQueryByExampleRepository`와 suspend/Flow signature 고정
- Kotlin repository interface와 Java/Kotlin projection consumer compile fixture
- README EN/KO 사용 예제 parity
- 한국어 KDoc과 지원·비지원 matcher 명시
- `docs/manual/**` 변경 없음 확인

## 인수 조건

- [ ] 공개 API에는 `Mono`, `Flux`, `ReactiveQueryByExampleExecutor`, `ReactiveFluentQuery`가 없다.
- [ ] suspend terminal과 외부 transaction 안의 Flow 수집이 Exposed 1.4.0의 top-level 및 `useNestedTransactions=false` outer transaction/connection/rollback 규칙을 따른다.
- [ ] outer transaction의 `useNestedTransactions=true` 조합은 SQL·savepoint 생성 전에 명시적으로 실패한다.
- [ ] `all()`은 cold이고 매 수집마다 fresh query/result를 만든다.
- [ ] mutable probe를 바꿔도 이미 만든 Flow의 canonical predicate는 달라지지 않는다.
- [ ] mutable bind/transformer 결과는 deep copy되거나 SQL 전에 거부되어 Flow 재수집 결과를 바꾸지 않는다.
- [ ] callback 밖 fluent 객체 사용은 실패하고 callback 안에서 만든 `all()` Flow는 안전하게 수집된다.
- [ ] 같은 transaction에서 terminal 병렬 실행은 SQL 전에 실패한다.
- [ ] matcher 지원·거부 표가 테스트와 README/KDoc에 일치한다.
- [ ] projection은 detached 값만 반환하며 full-domain mapper와 경로가 분리된다.
- [ ] direct factory proxy가 QBE 메서드를 derived query로 오인하지 않는다.
- [ ] `exists()`가 ID-only `LIMIT 1` SQL을 사용하고 page count는 필요한 경우에만 실행된다.
- [ ] 기존 CRUD·derived·declared query 회귀 테스트가 통과한다.
- [ ] H2, PostgreSQL, MySQL R2DBC 검증이 순차 실행으로 통과한다.
- [ ] ABI fixture가 기존 base interface/공개 생성자 보존과 새 opt-in interface signature를 검증한다.
- [ ] 안정 릴리스 매뉴얼 `1.12.1`은 변경하지 않는다.

## 문서와 릴리스 범위

구현 PR은 모듈 README의 영어·한국어 예제와 public KDoc을 갱신한다. 문서에는 코루틴 전용 API, transaction/Flow 수명주기, 지원 matcher, projection 범위, 직접 concrete 구현체 migration을 포함한다. 현재 배포 버전의 안정 문서인 `docs/manual/**`는 변경하지 않으며 `1.13.0` 실제 배포 승격 절차에서만 갱신한다.

## 완료 정의

- 설계 문서 6관점 검토에서 P0/P1이 0건이다.
- 구현 계획이 이 설계의 인수 조건과 테스트를 1:1로 추적한다.
- Kotlin patterns, coroutine 구조화 동시성, Exposed R2DBC transaction 규칙을 검증한다.
- 구현·검토·CI 증거는 별도 승인된 구현 단계에서 수집한다.

## 근거

- Spring Data Commons 4.1.0 `QueryByExampleExecutor`/`ReactiveQueryByExampleExecutor` 및 FluentQuery API
- JetBrains Exposed 1.4.0 R2DBC `suspendTransaction` 구현과 coroutine transaction context 규칙
- `spring-boot/r2dbc/.../ExposedR2dbcRepository.kt`
- `spring-boot/r2dbc/.../SimpleExposedR2dbcRepository.kt`
- `spring-boot/r2dbc/.../ExposedR2dbcRepositoryFactory.kt`
- Issue #642 JDBC QBE/FluentQuery의 사용자 관찰 의미와 회귀 테스트
