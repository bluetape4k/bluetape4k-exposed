# Issue #642 JDBC FluentQuery projection 구현 교훈

## 배경

`ExposedJdbcRepository.findBy(Example) { ... }`의 projection과 terminal을
in-memory 후처리에서 SQL pushdown으로 바꾸면서 Spring Data, Exposed DAO,
Spring transaction, JDBC cursor의 수명 계약을 동시에 맞춰야 했다.

## 재사용할 결정

### API 상태와 실행 상태를 분리한다

`JdbcFluentQueryPlan`은 `Example`, result type, property snapshot, sort, limit,
callback scope만 불변 값으로 보관한다. Mutable Exposed `Query`는 terminal마다 새로
만들어 `count`/`exists`가 projection, sort, limit의 영향을 받지 않게 한다.

Paged `Pageable`은 기존 fluent sort와 limit을 모두 대체한다. Unpaged
`Pageable`은 자신의 sort를 사용하면서 fluent positive limit을 유지한다. 이 경계는
Spring Data Javadoc과 별도 SQL-shape 테스트로 고정한다.

### QBE는 구조 검증 후 값을 읽는다

Unsupported matcher, nested/unknown path, ignore-case를 먼저 검증하고, 그 다음 probe가
현재 transaction의 identity cache에 연결됐는지 확인한다. Property getter와
transformer는 마지막에 정확히 한 번만 호출한다. Default string matcher도 property별
specifier와 별도로 조기 검증해야 한다.

### Custom EntityClass는 filter-only shape만 허용한다

`EntityClass.searchQuery` override를 보존하되 root table의 전체 column selection과
WHERE filter만 허용한다. Join, partial selection, aggregate, distinct, grouping, custom
sort/limit/offset, locking은 SQL 전에 거부해야 entity result와 projection/count result의
cardinality가 갈라지지 않는다.

### Cursor는 callback과 별도 lease를 갖는다

Exposed `Query.iterator()`는 `supportsMultipleResultSets=false`에서 결과를 list로
materialize하므로 사용하지 않는다. `JdbcTransaction.execQuery`로 받은 `ResultSet`과
statement를 single-use lease가 직접 소유하고 exhaustion, explicit close, mapper/action
failure에서 idempotent하게 닫는다. Statement 조회 자체가 실패해도 `ResultSet.close()`는
계속 실행해야 한다.

Cursor-backed라는 API 모양만으로 bounded memory를 보장할 수 없다. Exposed 1.4.0은
`Query.fetchSize`가 없으면 `DatabaseConfig.defaultFetchSize`를 사용하지만 기본값은
`null`이다. Stream terminal은 positive application 설정을 존중하고 미설정·비양수
값에는 bounded fallback을 적용해야 한다. PostgreSQL은 active transaction과 positive
fetch size가 필요하고, MySQL Connector/J은 추가로 `useCursorFetch=true`가 필요하므로
실제 driver 검증 URL과 문서 계약도 함께 고정한다.

JDBC driver는 `next()`, `getObject()` 기반 row materialization, `ResultSet.close()`,
`Statement.close()`에서 서로 독립적으로 실패할 수 있다. 예외 message만 검사하지
말고 cause와 suppressed를 포함한 전체 throwable graph에서 raw SQL·payload가 제거됐는지
검증한다. Cleanup 실패는 모든 resource를 시도한 뒤 안전한 SQLState/vendor code만
보존하며, 열린 가능성이 있으면 nested-SQL guard를 유지하고 transaction을 종료한다.

Factory repository의 cursor는 caller-owned outer Spring transaction에 참여한 호출만
허용한다. Direct repository는 caller-owned Exposed transaction을 요구한다. Cursor
advance마다 owner thread와 exact transaction identity를 확인한다. Wrong-thread
consumption은 그 thread에서 Exposed interceptor나 JDBC resource를 닫지 않고 실패시킨
뒤, owner transaction의 명시적 close가 lease를 정리하도록 한다.
Java `Stream.close()`는 close handler가 실패해도 pipeline을 closed로 표시하므로
wrong-thread close 뒤에는 owner close를 재시도할 수 없다. Same-thread close 계약을
위반한 경우 transaction을 종료해야 하며, 이를 완전히 막으려면 모든 intermediate
`Stream`을 owner-aware wrapper로 감싸는 별도 설계가 필요하다.

### 신규 Entity는 cache identity만으로 판별하지 않는다

Exposed `EntityCache.find`는 insert 예약 entity도 반환한다. `_readValues`와 cache identity만
검사하면 `EntityClass.new {}` probe가 영속 probe로 오인될 수 있다. ID column snapshot이
실제 read row에 있고 ID column이 write-set에 남아 있지 않은지 함께 확인해 property
getter나 SQL 전에 거부한다.

### Kotlin constructor 변경은 JVM ABI를 먼저 확인한다

Private primary constructor와 public delegating constructor는 Kotlin default-argument
synthetic constructor를 만들 수 있고, companion의 private mutable state 접근은 synthetic
accessor를 만들 수 있다. 기존 public one-argument constructor를 보존해야 할 때는
reflection baseline, Java/Kotlin consumer compile fixture, `javap -s`를 함께 실행한다.
이번 구현은 factory collaborator를 internal weak-key registry로 한 번 전달하고 첫 lazy
resolve에서 제거해 public descriptor 추가를 피했다.

## 검증 순서

1. Pure plan/resolver/mapper/compiler 테스트
2. H2 SQL shape, cardinality, cursor resource 테스트
3. 기존 CRUD, QBE, PartTree 회귀 테스트
4. PostgreSQL과 MySQL V8의 대표 dialect 테스트를 순차 실행
5. Module test, Detekt, reflection/Java/Kotlin fixture, `javap -s`
6. EN/KO README parity와 `docs/manual/**` 무변경 guard

## 피해야 할 회귀

- Paged `Pageable`에 fluent limit 또는 unsorted 이전 sort가 남는 동작
- Default REGEX를 probe attachment나 getter 접근 뒤에 거부하는 동작
- Custom `searchQuery`의 partial selection을 filter-only로 오인하는 동작
- Cursor 종료 시 `resultSet.statement` 조회 실패가 `ResultSet.close()`를 막는 동작
- `ResultSet.getObject()` 또는 reflection getter의 nested cause가 raw payload를 노출하는 동작
- cleanup 실패 뒤 nested-SQL guard를 먼저 해제하거나 다른 thread에서 interceptor를 수정하는 동작
- positive fetch size 없이 cursor-backed라는 이름만 제공하는 동작
- MySQL driver 검증에서 `useCursorFetch=true` 없이 server-side cursor를 주장하는 동작
- insert 예약 Entity를 current transaction의 영속 QBE probe로 허용하는 동작
- ABI 확인 없이 constructor visibility/default argument를 바꾸는 동작
