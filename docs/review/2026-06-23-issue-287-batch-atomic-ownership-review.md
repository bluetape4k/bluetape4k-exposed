# 이슈 #287 배치 원자적 소유권 코드 리뷰

날짜: 2026-06-23
범위: `:bluetape4k-exposed-batch` 작업/스텝 실행 소유권, JDBC/R2DBC 저장소 및 동시성 통합 테스트 범위.
게이트: 구현 차이 리뷰

## 게이트 판정

- P0=0
- P1=0
- P2=0
- 게이트: PASS

## 리뷰 지적 사항

| 지적 사항 | 심각도 | 해결 |
|---|---:|---|
| 이전에는 `BatchJob.run()`이 `findOrCreateJobExecution` 직후 스텝을 실행했기 때문에 동시 실행자가 동일한 논리적 `RUNNING` 실행을 공유할 수 있었다. | P1 | 스텝 실행 전에 명시적인 `claimJobExecution` 소유자/임대/버전 CAS를 추가했다. 소유권 확보에 실패하면 공유 실행을 실패로 표시하지 않고 `BatchExecutionAlreadyClaimedException`을 반환한다. |
| 이전에는 스텝 실행과 체크포인트 쓰기가 ID만으로 소유권을 확인했다. | P1 | `claimStepExecution`과 소유권 인식 `saveCheckpoint(StepExecution, Any)` 경로를 추가했다. 소유권자가 있을 때 JDBC/R2DBC 완료 및 체크포인트 갱신에 소유자 검사를 포함한다. |
| 저장소 테스트는 `findOrCreate*`가 `FAILED/STOPPED` 행을 `RUNNING`으로 변경하는 기존 부수 효과를 전제로 했다. | P2 | 새로운 분리 계약을 검증하도록 테스트를 수정했다. `findOrCreate*`는 저장된 행을 반환하고 `claim*Execution`은 `RUNNING` 전이를 수행한다. |

## 위험 검토

| 영역 | 결과 | 근거 |
|---|---|---|
| 동시성 | PASS | JDBC 및 R2DBC 통합 테스트는 이제 두 개의 `BatchJob.run()` 호출을 동시에 실행하고, H2/PostgreSQL/MySQL_V8 전반에서 성공 1건, `BatchExecutionAlreadyClaimedException` 1건, writer 열기 횟수 `1`, 쓰기 횟수 `3`을 검증한다. |
| 재시작 동작 | PASS | `FAILED` 및 `STOPPED` 행은 재시작 후보로 유지되며, 소유권 확보 시 `RUNNING`으로 전이된다. 완료된 작업은 계속 새 실행을 생성한다. |
| 하위 호환성 | PASS | 기존 저장소 인터페이스 메서드는 유지된다. 새로운 소유권 확보 메서드는 단순 래퍼를 위한 기본 구현을 제공하며, 구체 저장소는 원자적 소유권을 위해 이를 재정의한다. |
| 영속성 스키마 | PASS | 작업 및 스텝 테이블에는 이제 null 허용 `owner_id`, null 허용 `lease_until`, 기본값이 `0L`인 `version`이 포함되며, 매퍼는 새 필드를 왕복 변환한다. |
| 검증 | PASS | 대상 RED 테스트는 구현 전에 writer 열기 횟수 `2`로 실패했으며, 최종 빌드는 통과한다. |

## 검증 근거

| 명령 | 결과 |
|---|---|
| `./gradlew :bluetape4k-exposed-batch:test --tests '*ExposedJdbcBatchIntegrationTest.동시 실행*' --tests '*ExposedR2dbcBatchIntegrationTest.동시 실행*' --no-build-cache` | 수정 전 RED: `Expected <2> to equal to <1>`; 수정 후 GREEN: 방언 사례 6개 통과. |
| `./gradlew :bluetape4k-exposed-batch:test --no-build-cache` | PASS: 346개 통과, 7개 보류. |
| `./gradlew :bluetape4k-exposed-batch:build detekt --no-build-cache` | PASS: 346개 통과, 7개 보류; `:detekt NO-SOURCE`; 빌드 성공. |
| `git diff --check` | PASS. |

## 잔여 참고 사항

첫 번째 `build detekt` 실행에서는 기존 통합 테스트 설정의 R2DBC PostgreSQL 시드 `batchInsert`
자동 증가 개수 불일치가 일시적으로 발생했다. 코드 변경 없이 같은 명령을 즉시 다시 실행하자
통과했다.
