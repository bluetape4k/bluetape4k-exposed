# Issue #752: detached R2DBC loader의 fixture DB 격리

## 상황

R2DBC Redisson loader의 기본 producer scope는 호출자의 transaction
context를 상속하지 않는다. 따라서 `withTables(TestDB.H2, ...)` 안에서 만든
loader가 parameterless `suspendTransaction`을 실행하면, 전역
`TransactionManager.defaultDatabase`가 직전에 초기화된 다른 dialect를
가리킬 때 H2 fixture가 아닌 DB에서 조회할 수 있다. 이 문제는 테스트 순서와
공유 인메모리 DB 상태에 따라 간헐적인 missing-table 또는 빈 결과로 보인다.

## 실패한 가정과 교정

- 실패한 가정/판단: 호출자의 `withTables` transaction context가 detached
  producer에도 자동으로 전달된다고 보았다.
- 발견 증거 또는 교정: `TestDB.H2_MYSQL`을 먼저 초기화해 default를 바꾼 뒤
  H2 fixture에서 loader를 실행한 RED 회귀 테스트가 `r2dbc_entity_map_loader_test`
  missing-table 오류로 실패했다.
- 수정 결정: 테스트 helper가 producer 생성부터 `closeAndJoin` 완료까지
  지정한 `TestDB`를 `TransactionManager.defaultDatabase`로 임시 고정하고,
  종료 시 이전 default를 복원한다. 각 loader 호출은 `useLoader(TestDB.H2)`로
  fixture 소유 DB를 명시한다.
- 향후 예방 확인: detached coroutine producer를 사용하는 새 R2DBC fixture는
  caller transaction 의존 여부를 먼저 확인하고, 다른 dialect를 선행 초기화한
  negative-order 테스트와 producer 종료 후 default 복원 검증을 포함한다.

## 결과

`R2dbcLoaderTestSupport.useLoader(TestDB, ...)`가 전역 default DB의 범위를
producer lifecycle과 동일하게 유지한다. 기존 no-argument helper는 custom
loader 및 caller-owned ambient transaction 테스트를 위해 그대로 보존한다.
실패 시에는 cancellation을 그대로 재전파하고, 일반 예외에만 안전한
`TestDB` 식별자를 suppressed 진단으로 추가해 원인과 예외 타입을 보존한다.

## 검증

- RED — detached loader가 `H2_MYSQL` default를 사용하는 상태에서 H2 table을
  조회하는 회귀 테스트가 missing-table 오류로 실패했다.
- GREEN — targeted loader tests 25/25 통과.
- 오류 진단 — 원인 예외 identity와 `R2DBC loader fixture database=H2`
  suppressed 진단을 회귀 테스트로 고정했다.
- lifecycle — 정상·일반 예외·cancellation 경로 모두에서 `closeAndJoin` 후
  이전 default DB를 복원하고, cancellation 원인 객체를 그대로 재전파하는
  계약을 테스트로 고정했다.
- 모듈 전체 — `./gradlew :bluetape4k-exposed-r2dbc-redisson:test
  --no-configuration-cache --no-daemon --no-build-cache --rerun-tasks
  --console=plain`을 동일 조건으로 두 번 실행해 각각 `239 tests`, `0
  failures`, `3 skipped`, `BUILD SUCCESSFUL`을 확인했다.
- `git diff --check` 통과.

## 다음 guard

`TransactionManager.defaultDatabase`는 프로세스 전역 상태이므로 병렬 테스트
실행을 새로 허용할 때는 이 helper만으로 충분하다고 가정하지 않는다. 병렬
실행 정책이 바뀌면 DB를 loader에 명시적으로 주입할 수 있는 API 또는 테스트
별 격리 프로세스를 먼저 설계하고, 현재 직렬 JUnit 실행 가정을 검증한다.
