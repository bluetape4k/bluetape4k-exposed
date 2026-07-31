# 이슈 #287 - Batch 원자적 소유권 획득

## 배경

`BatchJob.run()`은 `findOrCreateJobExecution`에서 활성 `JobExecution`을 재사용하고
즉시 step을 실행했습니다. 두 scheduler가 같은 job과 params를 동시에 trigger하면 두
runner가 같은 `RUNNING` 실행을 보고 같은 step pipeline을 실행할 수 있었습니다.

## 결정

- 실행 조회와 실행 소유권을 분리합니다.
- `findOrCreateJobExecution`과 `findOrCreateStepExecution`은 자체적으로 작업을
  획득하지 않는 lookup 또는 insert 연산으로 유지합니다.
- `claimJobExecution`과 `claimStepExecution`을 owner/lease/version CAS gate로
  추가합니다.
- 기존 active-row uniqueness 전략이 하나의 restart 후보를 식별하도록 활성 행은
  `RUNNING`에 유지합니다.
- 두 번째 runner가 claim 경쟁에서 지면 공유 실행을 `FAILED`로 완료하지 않고
  `BatchExecutionAlreadyClaimedException`을 반환합니다.

## 구현 메모

Job 및 step execution 행은 이제 다음을 갖습니다.

- `owner_id`
- `lease_until`
- `version`

JDBC와 R2DBC repository는 version이 일치하고 행이 유효한 owner lease 없는 `FAILED`,
`STOPPED`, `RUNNING`일 때만 행을 원자적으로 claim합니다. 완료되면 owner와 lease
메타데이터를 비웁니다. checkpoint 쓰기는 runner 경로의 claim-aware
`saveCheckpoint(StepExecution, Any)` overload를 사용합니다.

`InMemoryBatchJobRepository`는 테스트와 단순 로컬 사용에서 같은 계약을 따릅니다.
또한 claim 중에 등록하여 runner unit 테스트에서 직접 생성한 job execution도 수용합니다.

## 결과

동시 JDBC 및 R2DBC `BatchJob.run()` 호출은 이제 하나의 runner만 writer를 열고 chunk를
처리하게 합니다. 두 번째 runner는 `BatchExecutionAlreadyClaimedException`이 담긴 실패
report를 반환합니다.

## 검증

- RED: 수정 전에는 두 runner가 writer를 열어 동시 JDBC/R2DBC integration 테스트가
  실패했습니다(`Expected <2> to equal to <1>`).
- `./gradlew :bluetape4k-exposed-batch:test --tests '*ExposedJdbcBatchIntegrationTest.동시 실행*' --tests '*ExposedR2dbcBatchIntegrationTest.동시 실행*' --no-build-cache`
  - 결과: H2/PostgreSQL/MySQL_V8 JDBC 및 R2DBC case가 통과했습니다.
- `./gradlew :bluetape4k-exposed-batch:test --no-build-cache`
  - 결과: 346개 통과, 7개 pending.
- `./gradlew :bluetape4k-exposed-batch:build detekt --no-build-cache`
  - 결과: 346개 통과, 7개 pending; `:detekt NO-SOURCE`; build 성공.
- `git diff --check`
  - 결과: pass.

## 향후 메모

이후 batch restart 변경도 같은 분리 계약을 유지합니다. lookup은 작업을 소유하지 않고
claim이 작업을 소유합니다. 새 checkpoint 또는 lease-renewal 기능은 id-only update가
아니라 claim owner field를 사용해야 합니다.
