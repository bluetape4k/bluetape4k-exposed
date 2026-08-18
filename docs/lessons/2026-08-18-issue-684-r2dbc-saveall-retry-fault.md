# Issue #684: R2DBC `saveAll` 재시도 fault-injection 증거

## 상황

Exposed 1.4.0의 top-level R2DBC transaction은 `R2dbcException`을 받으면
transaction block 전체를 다시 실행할 수 있다. 공용 `exposed/r2dbc-tests`
fixture는 일반 회귀 테스트의 격리를 위해 `maxAttempts = 1`을 사용하므로,
`saveAll(Flow)`와 `saveAll(Iterable)`이 retry attempt를 어떻게 처리하는지는
기존 테스트에서 실행되지 않았다.

## 결정

- ToxiProxy나 새 dependency를 추가하지 않고, 테스트 소스의
  `ConnectionFactory` adapter가 transaction block과 accumulator가 완료된 뒤
  첫 `commitTransaction()`을 `R2dbcTransientResourceException`으로 한 번만
  실패시킨다. statement fluent chain은 건드리지 않아 driver 동작을 보존한다.
- `R2dbcDatabaseConfig`에는 해당 test database에만 `defaultMaxAttempts = 2`와
  zero retry delay를 지정한다. 공용 fixture와 production `DatabaseConfig`는
  변경하지 않는다.
- H2와 PostgreSQL에서 delegate connection의 begin/rollback/close/cancellation을
  그대로 사용해 실제 Exposed retry와 새 connection을 실행한다.
- Flow는 retry 시 두 번째 collect가 가능한 replayable builder를 사용하고,
  Iterable은 두 번째 `iterator()` 호출을 계수한다. side effect가 있는 입력의
  재시도 안전성을 보장하는 것은 아니다.

## 결과

현재 #650 merge의 production 구현은 Flow와 Iterable 모두
`val results = inTransaction { buildList { ... } }`로 attempt-local 결과를
만든다. 따라서 실패 attempt의 provisional ID/row가 최종 emission에 남지 않고,
이번 slot에는 production diff가 필요하지 않았다.

회귀 테스트는 다음을 고정한다.

- fault 주입 1회와 connection 2회
- begin callback 2회 이상(드라이버 callback 진단값), commit 2회, rollback 2회, close 2회(저장 retry 경계)
- Flow/Iterable 입력 재수집 또는 재순회 2회
- 저장 결과 2건, 입력 순서 유지, 서로 다른 ID
- 최종 DB row 2건(실패 attempt rollback 후 성공 attempt만 유지)
- commit 이후 결과를 방출하는 기존 #650 계약과 outer transaction ownership 유지

## 검증

- RED calibration — `BLUETAPE_R2DBC_SAVE_ALL_MAX_ATTEMPTS=1`로 custom
  `maxAttempts = 1` 경로를 선택한 H2 클래스 테스트 38건 중
  Flow/Iterable retry 2건이 `R2dbcTransientResourceException: one-shot commit
  retry fault`로 실패했다(`FAILURE: Executed 38 tests in 4.1s (2 failed)`).
- GREEN — test-only config를 `maxAttempts = 2`로 지정한 뒤 H2 클래스 테스트
  38/38 통과했다.
- 대표 backend — PostgreSQL Testcontainers를 H2와 함께 순차 실행해 클래스 테스트
  76/76 통과했다.
- 모듈 전체 회귀 — H2 `125/125`, PostgreSQL `214/214`를 각각 순차 실행해
  모두 통과했다(`--no-parallel --max-workers=1`).
- PostgreSQL 전체 회귀의 첫 시도는 테스트 진입 전 Gradle daemon이 사라져
  종료되었지만, 동일 조건에 `--no-daemon`을 추가해 재실행한 결과 `214/214`와
  `BUILD SUCCESSFUL`을 확인했다. 테스트 실패나 container 오류로 분류하지 않는다.
- `:bluetape4k-exposed-spring-boot-r2dbc:detekt`: BUILD SUCCESSFUL.
- `git diff --check`: 통과.

## 놓치기 쉬운 점

`maxAttempts=1` fixture를 그대로 사용한 성공 테스트는 retry 계약을 증명하지
않는다. `BLUETAPE_R2DBC_SAVE_ALL_MAX_ATTEMPTS=1` calibration 경로로 fault를
재현할 수 있어야 하며, fault source가 transaction block 완료 뒤 `commitTransaction()`에서
`R2dbcException`을 내고, 첫 attempt의 rollback과 두 번째 connection/입력
재실행을 각각 관찰해야 하며, 첫 emission 시점의 commit call counter가 2이고
`failureCount=1`인지 확인해야 한다. `closeAndUnregister`로 test database manager를
매 테스트 정리하며, cleanup 예외는 원래 fault/cancellation에 suppressed로 추가한다.
또한 retry 입력은 replayable·side-effect-free여야 하며, 외부 transaction의
commit/rollback 경계는 repository가 소유하지 않는다.

## 다음 guard

비-H2 driver의 네트워크/timeout fault 의미와 heavy driver matrix는 #674 및 별도
CI 정책 이슈의 범위로 유지한다. `saveAll`을 chunked streaming write로 바꾸는
것은 메모리·atomicity·외부 side effect 순서를 새로 설계해야 하므로 #644에서
별도로 다룬다.
