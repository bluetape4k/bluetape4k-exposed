# #805 Spring Batch multi-row writer 검증 교훈

## 트랜잭션 소유자를 기준으로 검증한다

- 대상: `spring-boot/batch-exposed`의 `ExposedItemWriter`. #803의 일반 batch writer와 달리
  Spring Batch가 청크 트랜잭션을 소유한다.
- 결정: writer 안에서 `transaction`, commit, 청크 분할, retry를 추가하지 않는다.
  기존 JVM 생성자를 유지하고 필수 Boolean을 받는 새 생성자로만 opt-in을 제공한다.
- 검증: `ExposedItemWriterMultiRowValuesTest`는 실제 `StepBuilder`와 `SpringTransactionManager`를
  사용한다. 청크 크기는 2이며, 첫 청크 성공 후 두 번째 청크의 중복 SQL 또는 writer 이후 실패를
  유발하여 첫 청크만 남는지 확인한다. `rollback-only`도 별도로 검증한다.
- 경계: 테스트의 `ResourcelessJobRepository`는 테스트별로 새로 생성한다. 메타데이터 영속성·재시작이나
  병렬 공유를 검증하는 도구가 아니며, 실제 청크 처리와 JDBC 트랜잭션 검증에만 사용한다.

## 실패 원인은 wrapper 밖이 아니라 원인 체인에서 확인한다

- 반증: 최초 after-write 테스트는 최상위 예외 메시지를 직접 검사하여 실패했다.
  Spring Batch 6.0.3은 원래 `IllegalStateException`을 `FatalStepExecutionException`으로 감싼다.
- 수정: `failureExceptions`의 원인 체인에서 주입한 예외와 객체 동일성을 확인한다.
  중복 쓰기 실패도 원인 체인의 `SQLException.sqlState == "23505"`를 확인한다.
- 재발 방지: 실패 상태만 검사하면 관계없는 예외로도 테스트가 통과한다. 의도한 실패 원인과
  새 트랜잭션에서 읽은 최종 저장 상태를 함께 검증한다.
- API 확인: Spring Batch 6의 step execution은 `JobRepository.createStepExecution`으로 생성한다.
  이전 버전의 `JobExecution.createStepExecution`을 가정하지 말고 현재 dependency 소스를 확인한다.

## SQL 관측값과 네트워크 성능을 구분한다

- H2/PostgreSQL에서 2행 입력의 기존 경로는 `StatementContext` 2개, opt-in은
  4개 bind 인자를 가진 VALUES SQL의 `StatementContext` 1개였다. 두 경로 모두 생성 키 요청은 false이다.
- 이것은 Exposed SQL 형태의 검증이지 JDBC 네트워크 왕복 횟수나 처리량 측정이 아니다.
  driver의 batch rewrite 설정과 실제 실행 호출을 측정하지 않고 성능 배수를 주장하지 않는다.
- 한도는 전체 테이블 컬럼 수를 곱한 보수적 추정치다. 3컬럼 테이블의 21,845행 성공과 다음 행의
  바인더 호출 0·INSERT 관측 0을 검증한다. SQLite/MySQL/Oracle 신규 경로는 미검증으로 남긴다.

## 검증 기록

- 기존 H2 writer baseline: 3/3 통과.
- RED: 새 opt-in JVM 생성자 존재 검증 1건 실패 후 호환 생성자 추가.
- 전체 H2 모듈: 40/40 통과, 실패·오류·생략 0.
- H2/PostgreSQL writer 회귀: 22/22 통과(신규 16, 기존 6), 실패·오류·생략 0.
- 명령: `EXPOSED_TEST_DB=POSTGRESQL ./gradlew :bluetape4k-exposed-spring-boot-batch:cleanTest :bluetape4k-exposed-spring-boot-batch:test --tests '*ExposedItemWriter*Test' --no-configuration-cache --no-build-cache --max-workers=1`.
- ABI baseline은 기존 항목 삭제 없이 새 생성자 1행만 추가한다. 갱신과 검사는 별도 호출한다.
- 이 기록은 로컬 구현 검증 시점이다. exact-head CI·PR·머지 상태는 연결된 PR의 마지막 `DoD Status`에서 확인한다.
