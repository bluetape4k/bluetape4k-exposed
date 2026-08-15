# 이슈 #639 R2DBC 데모 readiness·lifecycle 교훈

## 상황

`examples/r2dbc-demo`의 `DataInitializer`가 `ApplicationReadyEvent`에서 독립적인
`SupervisorJob + Dispatchers.IO` coroutine을 fire-and-forget으로 시작했다. 초기화
실패·취소가 readiness에 반영되지 않았고, 통합 테스트는 `/products` polling과
`Thread.sleep`으로 seed 완료를 추측했다.

## 결정

- `DataInitializerLifecycle`이 시작 중복, 초기화 상태, 완료 신호, readiness 이벤트,
  shutdown 취소·대기를 한 곳에서 소유한다.
- `ApplicationReadyEvent`는 애플리케이션 context가 일치할 때만 lifecycle을 시작하며,
  `AtomicBoolean`으로 중복 ready event를 무시한다.
- schema/seed가 끝날 때까지 `REFUSING_TRAFFIC`을 유지하고, 성공 시에만
  `ACCEPTING_TRAFFIC`으로 전환한다. 실패는 `awaitReady()` 예외와 `/readyz`의
  `503 DOWN`으로 관찰한다.
- dispatcher는 기존 `databaseCoroutineDispatcher` bean을 주입해 production과
  `StandardTestDispatcher`를 분리한다. `AutoCloseable.close()`의 `runBlocking`은
  non-suspend Spring destruction bridge이며 child cancellation 완료를 기다리는
  유일한 동기 경계로 제한한다.
- seed는 기존 빈 테이블 조건을 유지해 재실행 시 중복 행을 만들지 않는다. 일반
  소비자 애플리케이션의 lifecycle 정책이나 새 dependency는 추가하지 않는다.

## 결과와 검증

- lifecycle 단위 테스트가 중복 시작의 단일 실행, 실패 시 예외·readiness 상태,
  child cancellation 완료 대기를 고정한다.
- WebFlux 통합 테스트가 `awaitReady()`를 동기화 지점으로 사용하고 `/readyz`의
  `200 UP`을 검증하며, `/products` polling과 `Thread.sleep`을 제거했다.
- seed 재실행 테스트가 상품 3개 불변식을 검증한다.
- `:exposed-spring-boot-r2dbc-demo:test --no-build-cache`: 30 tests, 0 failures,
  0 errors, 0 skipped.
- targeted lifecycle/controller 테스트: 10 tests 통과.
- `detekt`와 `:exposed-spring-boot-r2dbc-demo:detekt`: 통과(`NO-SOURCE` 포함).
- `git diff --check`: 통과. migration README parity validator는 이 모듈 README에
  migration marker가 없어 적용 대상이 아니므로 `N/A`로 기록한다.

## 놓치기 쉬운 점

`ApplicationReadyEvent` 자체가 readiness 성공을 의미하지 않는다. 초기화 coroutine이
완료되기 전에 외부 readiness 이벤트가 들어올 수 있으므로, 아직 준비되지 않은
`ACCEPTING_TRAFFIC`은 lifecycle이 다시 `REFUSING_TRAFFIC`으로 되돌려야 한다. 실패를
HTTP 본문에 노출하지 않고 상태 코드만 유지하면 데모의 오류 세부 정보 누출도 피할 수
있다.

## 다음 guard

새로운 예제 startup 작업을 추가할 때 다음을 함께 검증한다.

1. 시작 event가 중복되어도 작업이 한 번만 실행되는가?
2. 성공·실패·취소가 명시적인 상태 또는 health/readiness 경로로 관찰되는가?
3. shutdown이 child job을 취소한 뒤 완료까지 기다리는가?
4. 통합 테스트가 polling과 고정 sleep 대신 명시적인 completion signal을 사용하는가?
5. EN/KO README가 실제 lifecycle·readiness 계약과 같은 예제를 설명하는가?

## Writer gate

- `SPW-01`: PASS — issue, source 범위, 실행 결과와 lesson 범위를 고정했다.
- `SPW-02`: PASS — 상황, 결정, 결과, 검증, 놓치기 쉬운 점, 다음 guard를 기록했다.
- `SPW-03`: PASS — 한국어 technical register를 사용하고 API·상태·명령 토큰을 보존했다.
- `SPW-04`: PASS — targeted/full test, detekt, diff, 문서 parity 결과를 대조했다.
- `SPW-05`: PASS — Markdown read-back과 EN/KO 문서 구조 parity를 완료했다.
