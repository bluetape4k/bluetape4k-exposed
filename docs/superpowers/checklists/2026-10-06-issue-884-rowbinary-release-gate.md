# ClickHouse RowBinary 릴리스 gate

상태: PENDING — exact candidate SHA의 Nightly 증거가 필요합니다.

소유자: `bluetape4k-exposed` release train

목적: ClickHouse JDBC V2의 RowBinary 직렬화 통합 테스트가 실제 ClickHouse 서버에서
실행된 exact release candidate를 확인합니다. 기본 PR 검사는 Docker 통합 테스트를
실행하지 않으므로 이 체크리스트의 증거를 대체하지 않습니다.

## 릴리스 전 필수 증거

- [ ] **ROWBINARY-01 — exact candidate run**
  - release candidate SHA를 checkout한 `Nightly Tests`의 `workflow_dispatch` (`scope=full`)
    또는 해당 exact SHA가 기본 브랜치에 반영된 뒤의 주간 `schedule` 실행을 확인합니다.
  - 기록: candidate SHA, workflow run URL, `Test / exposed-clickhouse` job URL.
- [ ] **ROWBINARY-02 — 테스트 실행 확인**
  - `Test / exposed-clickhouse` job이 성공해야 합니다.
  - `test-results-clickhouse` artifact의
    `TEST-io.bluetape4k.exposed.clickhouse.ClickHouseRowBinaryIntegrationTest.xml`에서
    `executed > 0`, `skipped = 0`, `failures = 0`, `errors = 0`을 확인합니다.
  - 기록: artifact URL과 JUnit 결과 수치.
- [ ] **ROWBINARY-03 — hold 해제**
  - ROWBINARY-01과 ROWBINARY-02가 모두 채워지기 전까지 release preflight를 막아 둡니다.
  - workflow dispatch는 별도의 명시적 권한이 있을 때 실행합니다.

## 확인 기록

- Candidate SHA: 미확인
- Workflow run: 미실행
- Job / artifact URL: 미확인
- JUnit 수치: 미확인
