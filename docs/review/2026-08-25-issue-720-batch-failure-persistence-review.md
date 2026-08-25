# Issue #720 batch failure persistence 7-Tier review

## 검토 범위와 기준

- 대상 branch: `fix/batch-failure-persistence`
- 기준 base: `origin/develop` `1242e5eb990a1f362233dba9542aa6e4d7192730`
- module slice: `utils/batch` (`:bluetape4k-exposed-batch`)
- 검토 대상: `BatchJob`, `BatchStepRunner`, repository completion 계약,
  `BatchFailurePersistenceTest`, README/KDoc, lesson
- 기준: `$bluetape-kotlin-patterns`, Bluetape4k assertions/logging, 7-Tier
  performance/stability/security/ops/developer/user/integration 관점

## Findings

| Tier | P0 | P1 | P2 | P3 | 결과 |
| --- | ---: | ---: | ---: | ---: | --- |
| 1. Performance | 0 | 0 | 0 | 0 | completion 실패 시 bounded error logging만 수행하며 retry/outbox/queue를 추가하지 않음 |
| 2. Stability | 0 | 0 | 0 | 0 | `NonCancellable`, cancellation 재던짐, suppressed cause와 JDBC/R2DBC lease 보호를 검증 |
| 3. Security | 0 | 0 | 0 | 0 | 새 입력·secret·권한 경계 없음; 예외 메시지는 기존 실행 식별 정보만 사용 |
| 4. Operator/Ops | 0 | 0 | 1 | 0 | error logger로 저장 실패를 전달하나 자동 복구/outbox는 caller/ops 계약으로 남김 |
| 5. Developer/API | 0 | 0 | 0 | 0 | public ABI 변경 없이 repository KDoc과 Kotlin null/error 흐름을 보강 |
| 6. User/Caller | 0 | 0 | 1 | 0 | `BatchReport.Failure`는 원인 예외를 유지하고, 저장 실패는 suppressed에서 조회 가능 |
| 7. Integration/Delivery | 0 | 0 | 0 | 0 | focused/H2/JDK25/정적 검사와 H2·PostgreSQL·MySQL full matrix가 통과; external exact-head CI는 별도 gate |

## 핵심 판정

production 변경은 상태 저장 실패를 삼키던 경로를 제거하고, 원래 실패의
인과관계를 보존한다. 특히 `CancellationException`을 일반 실패로 바꾸지 않으며,
저장 실패 예외 자체를 error logger에 throwable로 전달한다. `bluetape4k-assertions`
검증은 새 회귀 테스트와 backend conformance에 사용했다.

README와 KDoc 예시는 `KLogging`/`log.info`/`log.error`를 사용하며
`println`·`System.out`·`System.err`를 포함하지 않는다.

## Evidence

- production 수정 전 focused test에서 suppressed cause 관련 3건 RED
- `./gradlew :bluetape4k-exposed-batch:test --tests io.bluetape4k.batch.core.BatchFailurePersistenceTest --no-daemon --console=plain`
  → 16/16, BUILD SUCCESSFUL. 실제 `CoroutineScope.async` 취소에서 checkpoint 1회,
  STOPPED 저장과 checkpoint payload를 확인하고, completion cancellation 전파도 고정했다.
- `EXPOSED_TEST_DB=H2 ./gradlew :bluetape4k-exposed-batch:test --no-daemon --console=plain`
  → 255/255 통과, 4 skipped
- `./gradlew :bluetape4k-exposed-batch:test --rerun-tasks --no-build-cache --no-daemon --console=plain`
  → H2/PostgreSQL/MySQL 379/379 통과, 7 skipped
- `./gradlew :bluetape4k-exposed-batch:detekt :bluetape4k-exposed-batch:checkKotlinAbi --no-daemon --console=plain`
  → BUILD SUCCESSFUL
- Java `25.0.4`, Gradle `9.7.0`, Kotlin `2.4.0`
- `git diff --check` 및 `utils/batch` 출력 API scan 통과
- full matrix의 동시 claim 테스트와 JDBC/R2DBC conformance는 fresh rerun에서
  모두 통과했다. 이전 단일 실행의 방언 변동은 재현되지 않았으므로 현재 known
  blocker로 승격하지 않고 계속 관찰한다.

## Delivery gate

**Local implementation: CLEAR. External delivery: PENDING.**

P0/P1은 없다. local full matrix와 독립 7-Tier 재검토를 통과했지만, PR exact-head
CI와 GitHub review 상태는 delivery 단계에서 fresh-read한 뒤 #720을 닫을 수 있다.

## Reviewer DoD

- [x] 7-Tier 각 관점의 P0/P1/P2/P3와 처분을 기록했다.
- [x] source, focused 16/16, H2 255/255, full matrix 379/379, detekt, ABI, JDK25 evidence를 대조했다.
- [x] `bluetape4k-assertions`와 logging 사용을 확인했다.
- [x] full matrix의 7 skipped selector를 별도 범위로 기록하고, fresh rerun 성공을 확인했다.
- [x] issue/PR/CI exact-head 확인은 delivery 단계의 별도 gate로 분리했다.
