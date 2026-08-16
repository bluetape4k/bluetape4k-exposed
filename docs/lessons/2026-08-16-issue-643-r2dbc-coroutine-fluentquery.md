# Issue #643: R2DBC coroutine Query by Example 경계

## 배경

Spring Data R2DBC repository에 Query by Example과 `FluentQuery`를 추가하되,
기존 suspend/`Flow` 실행 경계와 Exposed R2DBC transaction 소유권을 보존해야 했다.
이번 slot은 Reactor API를 재노출하지 않고 coroutine-native 계약만 제공하는 범위로
고정했다.

## 결정

- `ExposedCoroutineQueryByExampleExecutor`와 `ExposedCoroutineFluentQuery`를 public
  coroutine API로 둔다.
- `findOne`과 fluent `one()`은 strict cardinality를 공유한다. 0건은 `null`, 1건은
  결과, 2건 이상은 `IncorrectResultSizeDataAccessException`이다.
- QBE probe와 matcher는 SQL 실행 전에 immutable snapshot으로 만들고, mutable bind 값은
  defensive copy한다. 지원 matcher는 exact/default, `CONTAINING`, `STARTING`, `ENDING`이다.
- `Flow`는 cold이며 collect 시점에 query와 transaction을 연다. 활성 outer transaction은
  재사용하고, `useNestedTransactions=true`이면 SQL 전에 fail-fast한다.
- fluent plan은 sort/limit/type/projection을 immutable하게 누적한다. 빈 `project()`는
  projection required property 자동 선택으로 되돌린다.
- projection은 closed interface, Kotlin primary constructor, Java record만 지원하고
  open/SpEL·partial domain projection은 거부한다.

## 검증

- API/ABI reflection 및 Kotlin/Java consumer fixture: 통과.
- pure snapshot/plan/compiler/resolver/lease 단위군: 통과.
- H2 통합군: 4개 테스트 통과(직접 terminal, cold Flow 재수집, projection/page/slice,
  strict cardinality, nested transaction fail-fast).
- `R2dbcFluentQueryMultiDbTest` matrix: `EXPOSED_TEST_DB=H2`는 1개 테스트를
  실행했고, `EXPOSED_TEST_DB=POSTGRESQL`은 H2 + PostgreSQL 2개,
  `EXPOSED_TEST_DB=MYSQL_V8`는 H2 + MySQL V8 2개를 순차 실행했다. 세 명령 모두
  exit status 0, XML `tests>0`, `failures=0`, `errors=0`, `skipped=0`을 확인했다.
- PostgreSQL과 MySQL V8 parameterized case는 각각 실제 backend에서 1개씩 통과했고,
  H2 전용 edge-case 통합군과 분리해 backend 의미 검증을 대체하지 않는다.
- 기존 Spring R2DBC 회귀군: 210개 실행, 2개 skip, 실패 없음.
- module detekt: 통과.
- EN/KO README parity validator: 5개 테스트, 10개 assertion 통과.

## 남은 경계

`docs/manual/**`는 아직 배포되지 않은 `1.13.0` 안정판 계약을 오염시키지 않도록
변경하지 않았다. PR/CI에서는 exact head, required checks, 독립 pre-PR review를 다시
확인한 뒤에만 merge 단계로 이동한다.
