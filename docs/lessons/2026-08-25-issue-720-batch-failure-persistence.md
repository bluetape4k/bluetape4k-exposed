# Issue #720 FAILED·STOPPED 영속화 실패 보존 lesson

## Context

`BatchJob`과 `BatchStepRunner`가 `FAILED`·`STOPPED` 상태를 저장하는 과정에서
`runCatching`으로 저장 예외를 감쌌다. 그 결과 실행 원인 예외는 반환되더라도 상태
저장 실패가 사라져 DB에는 `RUNNING`과 만료되지 않은 lease가 남을 수 있었다.
재시작 가능성, 운영 원인 추적, cancellation 전파를 하나의 계약으로 다시 고정할
필요가 있었다.

## Decision or Finding

- 완료 상태 저장은 더 이상 `runCatching`으로 숨기지 않는다. 저장 실패는
  `NonCancellable` 경계에서 잡고 원인 예외의 suppressed cause로 추가한다.
- 원래 `CancellationException`은 `STOPPED` 저장 시도 뒤 반드시 재던진다. 저장 실패가
  cancellation을 대체하지 않으며, FAILED 저장 중 발생한 cancellation은 원인 예외를
  suppressed로 보존한 뒤 전파한다.
- 저장 실패는 Bluetape4k logging의 `error(Throwable)`로 상태와 실행 식별 정보를
  남긴다. `println`, `System.out`, `System.err`는 production·test·README 예시에
  사용하지 않는다.
- 저장소 자동 재시도나 outbox는 이번 Type C 범위에 넣지 않는다. runner는 원인과
  저장 실패를 보존하고 로그로 전달하며, 재시도·outbox·운영 복구 정책은 caller와
  storage 운영 계층의 책임이다.
- JDBC, R2DBC, in-memory delegate를 같은 runner 계약 테스트로 묶고, 저장 실패 뒤
  재조회에서 `RUNNING`과 lease가 남아 replacement claim을 막는 사실을 검증한다.

## Verification

| 검증 | 결과 |
| --- | --- |
| RED 회귀 | production 수정 전 suppressed cause 부재로 3건 실패 |
| focused failure-persistence test | 16/16 통과: 실제 Job 취소 checkpoint, persistence cancellation, job/step FAILED·STOPPED, logger, JDBC, R2DBC |
| H2 전체 batch module | 255건 통과, 4건 기존 selector skip |
| JDK/Gradle | Java 25.0.4, Gradle 9.7.0, Kotlin 2.4.0 |
| detekt / Kotlin ABI | `:bluetape4k-exposed-batch:detekt` 및 `checkKotlinAbi` 통과 |
| 출력 경계 | `utils/batch` Kotlin/Markdown에서 `println`·`System.out/err` 0건 |
| H2/PostgreSQL/MySQL 전체 matrix | `--rerun-tasks --no-build-cache`로 379건 통과, 7건 기존 selector skip |

## Future Guidance

1. `completeJobExecution`·`completeStepExecution` 구현은 저장 오류를 호출자에게
   전파하고, runner가 원인 예외와 함께 보존하는지 확인한다.
2. cancellation 경로에서는 상태 보정이 필요하더라도 `NonCancellable` 안에서
   원래 cancellation을 재던지는지 먼저 검증한다.
3. 전체 matrix의 동시 claim 테스트는 scheduler timing 영향을 계속 관찰하고,
   재현되는 변동이 생기면 별도 issue로 분리한다. 이번 fresh matrix에서는 통과했다.

## Writer DoD

- [x] SPW-01 — Issue #720의 상태 저장·lease·재시작 경계를 고정했다.
- [x] SPW-02 — suppressed cause, cancellation 재던짐, error logging, no retry/outbox를
  production source와 test에 대조했다.
- [x] SPW-03 — Korean reader-facing prose와 `FAILED`, `STOPPED`, `NonCancellable`,
  `JDK25`, `suppressed cause` token을 일관되게 유지했다.
- [x] SPW-04 — RED, focused 16/16, H2 module, full 379/379 matrix, detekt, ABI,
  출력 scan evidence를 대조했다.
- [x] SPW-05 — 이 lesson을 다음 동시 claim 테스트 보강의 입력으로 남겼다.
