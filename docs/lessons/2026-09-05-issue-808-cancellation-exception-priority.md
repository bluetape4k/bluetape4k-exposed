# #808 취소 원인 보존과 upstream 정리 계약

## 배경과 결정

Exposed `1.5.0`을 사용하는 기준 커밋 `4ca5446465ab157f6b2c64361a1ba24baf6f1bb6`에서
[#808](https://github.com/bluetape4k/bluetape4k-exposed/issues/808)의 취소 회귀 검증을 시작했다.
의존성 변경과 upstream 소스 복사는 승인 범위에서 제외했다.

Ktor의 `ApplicationCall.exposedR2dbcTransaction`은 취소를 잡은 뒤 메트릭을 기록한다.
이 기록이 실패하면 원래 `CancellationException` 대신 메트릭 예외가 전달됐다.
실패 경로의 메트릭 기록만 보호하고, 메트릭 예외를 주원인의 `suppressed`에 추가했다.
일반 예외의 `ExposedKtorTransactionException.cause`와 `Error` 재전파 계약도 유지한다.
공개 API와 정상 완료 경로는 변경하지 않았다.

## 재현과 검증

- 실제 자식 Job에서 `SELECT 1`을 실행하고 명시적 barrier 뒤 취소했다.
- 메트릭 실패를 주입하지 않은 대조군은 통과했고, 실패를 주입한 경우에만 수정 전 테스트가 실패했다.
- 수정 후 Ktor R2DBC 모듈의 H2 테스트 8개와 `detekt`, `checkKotlinAbi`가 통과했다.
- PostgreSQL 최초 실행은 8개 통과, 5개 실패였다. 새 테스트와 기존 readiness 테스트에서
  `Connection reset`이 발생했다. Colima는 정상 실행 중이었으며 재시작하지 않았다.
- PostgreSQL 재실행은 H2를 포함해 13개 모두 통과했고 `detekt`, `checkKotlinAbi`도 통과했다.
  최초 연결 실패의 원인은 확정하지 못했다. 이 재실행은 불안정성 해소나 전체 #808 통과 증거가 아니다.

검증 명령:

```bash
EXPOSED_TEST_DB=H2 ./gradlew :bluetape4k-exposed-ktor-r2dbc:test \
  :bluetape4k-exposed-ktor-r2dbc:detekt \
  :bluetape4k-exposed-ktor-r2dbc:checkKotlinAbi \
  --no-configuration-cache --no-build-cache --max-workers=1
EXPOSED_TEST_DB=POSTGRESQL ./gradlew :bluetape4k-exposed-ktor-r2dbc:test \
  --rerun-tasks --no-configuration-cache --no-build-cache --max-workers=1
```

## 예상과 달랐던 점

Coroutine stacktrace recovery는 표준 예외를 복사할 수 있다. 처음 작성한 동일 인스턴스
단언은 메트릭 실패가 없는 대조군에서도 실패했다. 추가 상태가 있는 테스트 전용 예외로
복사를 비활성화해 adapter의 재전파와 coroutine 진단 기능을 분리했다.
이 테스트를 모든 표준 예외의 인스턴스 동일성 보장으로 해석하면 안 된다.

R2DBC 연결 획득에 `Mono.never()`를 사용한 첫 테스트는 취소 후에도 종료되지 않았다.
테스트 JVM의 thread dump와 실제 사용 중인 `exposed-r2dbc-1.5.0.jar`의
`R2dbcConnectionImpl.withConnection` bytecode를 확인했다. 연결 획득 자체가
`NonCancellable`로 보호되므로 외부 `withTimeout`도 해당 대기를 중단하지 못했다.
이 테스트 프로세스만 SIGTERM으로 종료했으며 통과로 계산하지 않았다.

수정한 테스트는 acquisition 구독을 확인한 뒤 Job을 취소하고, 실제 연결이 전달되도록
barrier를 해제한다. 이후 연결이 한 번 닫히는지 확인한다. begin과 statement의
대기 Publisher는 별도로 취소 구독을 관측하며, fixture 자체에도 5초 제한을 둔다.
무한 대기 acquisition의 즉시 중단을 보장하는 테스트는 아니다.

JDBC는 실제 driver 실행 경계에 latch를 두고, driver 호출이 반환된 뒤 `ensureActive()`로
취소를 확인한다. SQL 실행을 coroutine 취소만으로 중단하거나 `Statement.cancel()`을
호출한다고 주장하지 않는다. statement와 connection의 close 횟수 및 정리 순서를 관측한다.

## upstream에서 확인한 한계

2026-09-05에 Exposed `1.5.0` 태그의 공식 원문을 직접 조회했다.

- [JDBC Transactions.kt](https://github.com/JetBrains/Exposed/blob/1.5.0/exposed-jdbc/src/main/kotlin/org/jetbrains/exposed/v1/jdbc/transactions/Transactions.kt#L423-L437):
  statement 정리 실패와 connection 정리 실패를 로그에 기록한다.
- [R2DBC Transactions.kt](https://github.com/JetBrains/Exposed/blob/1.5.0/exposed-r2dbc/src/main/kotlin/org/jetbrains/exposed/v1/r2dbc/transactions/Transactions.kt#L256-L269):
  정리 실패를 로그에 기록하며 R2DBC에는 `Statement.close()` 자체가 없다.

이 경로는 정리 예외를 원래 예외의 `suppressed`에 추가하지 않는다.
따라서 Ktor 메트릭 실패 보존과 upstream connection 정리 실패 보존은 별개 계약이다.
adapter가 받지 못한 upstream 정리 예외를 현재의 로컬 수정으로 복원할 수는 없다.
이는 소스 확인 결과이며, fault-injection driver로 정리 실패를 재현한 결과는 아니다.

## 남은 작업과 재발 방지

#808은 독립 리뷰와 CI가 완료될 때까지 미완료 상태로 유지한다.
JDBC acquisition/begin/statement 자원 반환 횟수, R2DBC Publisher 취소와 rollback/close 완료,
Spring repository 입력 Flow 취소 시 rollback·결과 미방출 테스트를 추가했다.
JDBC와 R2DBC의 H2·PostgreSQL 회귀 행렬은 각각 8개, Spring은 2개가 통과했다.
Ktor PostgreSQL 재검증은 기존 테스트를 포함해 13개가 통과했다.
Spring 전체 H2 모듈은 132개가 통과했다. #803/#805의 batch 검증을 이 증거 대신 사용하지 않았다.
JDBC 전체 H2 모듈은 206개 통과·25개 조건부 미실행, R2DBC는 211개 통과·7개 조건부 미실행이었다.
새 테스트의 정적 검사 실패는 JDBC 계측 함수 분리와 긴 줄 정리로 해소했으며,
해당 모듈의 `detekt`와 `checkKotlinAbi`도 통과했다. 조건부 미실행을 전체 DB 지원 증거로 계산하지 않는다.

초기 독립 리뷰는 `agent thread limit reached`와 응답 지연으로 완료하지 못했다.
이후 독립 설계 리뷰는 `CLEAR`로 완료했다. 코드 리뷰는 별도로 다시 시작했으나
최종 응답 확인에도 파일 근거나 판정이 없어 제한 시간 초과로 중단했다.
이 실패만으로 PR 진행을 반복해서 차단한 판단을 사용자가 수정했다.
독립 코드 리뷰 실행이 실패하면 추가 승인 없이 메인 세션의 inline 리뷰로 전환한다는
workspace 규칙을 메모리와 `bluetape-workflow`에 저장했다.
이번 변경본도 해당 규칙으로 inline 리뷰를 완료했다. 실패한 독립 리뷰를 성공으로
기록하지 않으며, 실제 P0/P1 지적·필수 테스트·CI·머지 승인 조건은 유지한다.
리뷰 범위와 판정은 [inline 리뷰 기록](../review/2026-09-05-issue-808-inline-review.md)에 남겼다.

최종 변경본의 대상 테스트를 H2와 PostgreSQL 순서로 다시 실행했다.
H2 단독은 13개, PostgreSQL 선택 시 H2를 포함한 행렬은 JDBC 8개·R2DBC 8개·Ktor 8개·Spring 2개로
총 26개가 통과했다. 네 모듈의 `detekt`와 `checkKotlinAbi`도 통과했다.
ABI 검증에 사용한 `checkLegacyAbi`는 실제로 `checkKotlinAbi`를 호출하는 deprecated 별칭이었다.
후속 검증은 정식 태스크 이름을 직접 사용한다.

사용자 승인에 따라 upstream 정리 예외 보존은 [#817](https://github.com/bluetape4k/bluetape4k-exposed/issues/817)로 분리했다.
upstream의 계약을 회귀 테스트로 고정하기 전에, 공개 API와 실제 구현이 이슈의 수용 기준을
지원하는지 확인해야 한다. 정상 후속 쿼리 실행만으로 정확히 한 번 반환을 주장하지 않는다.

## Assets

없음. 외부 원문은 복제하지 않고 판정에 필요한 내용만 요약했다.
