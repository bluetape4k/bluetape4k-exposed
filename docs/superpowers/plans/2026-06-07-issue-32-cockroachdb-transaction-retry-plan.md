# Issue #32 CockroachDB Transaction Retry 계획

설계 문서: `docs/superpowers/specs/2026-06-07-issue-32-cockroachdb-transaction-retry-design.md`

## 결정

Exposed의 일반 transaction retry를 전역으로 활성화하지 않고 CockroachDB
전용 retry wrapper를 구현한다. wrapper는 내부 Exposed transaction의 시도
횟수를 한 번으로 설정하고, CockroachDB가 문서화한 retry 가능 transaction
signature로 분류된 SQL 예외만 재시도한다.

## 작업

1. retry 지원 소스를 추가한다.
   - `CockroachTransactionRetryOptions`를 추가한다.
   - `Throwable.isCockroachRetryableTransactionError()`를 추가한다.
   - `withCockroachTransaction(...)`을 추가한다.
   - 가짜 SQLException 회귀 테스트를 위한 내부 retry executor를 추가한다.
   - bluetape4k 지원 헬퍼로 option을 검증한다.

2. 회귀 테스트를 추가한다.
   - 정확히 일치하는 경우, wrapping된 경우, 잘못된 SQLSTATE, 잘못된 메시지에
     대한 predicate 테스트를 추가한다.
   - 성공, 시도 소진, retry 불가능한 SQL, cancellation, interruption에 대한
     retry executor 테스트를 추가한다.
   - commit, rollback, 내부 Exposed `maxAttempts = 1`에 대한 CockroachDB
     Testcontainers transaction 헬퍼 smoke test를 추가한다.

3. 문서를 갱신한다.
   - `README.md`를 갱신한다.
   - `README.ko.md`를 갱신한다.
   - `CHANGELOG.md`를 갱신한다.

4. 로컬에서 검증한다.
   - 변경한 모듈을 compile한다.
   - `--rerun-tasks`로 모듈 테스트를 실행한다.
   - Kover XML report를 실행한다.
   - `git diff --check`를 실행한다.
   - GNO 명령으로 wiki 조사 메모를 검증한다.

5. 검토하고 전달한다.
   - `P0 = 0`, `P1 = 0`인 Step 6-R 최종 검토 근거를 추가한다.
   - `docs/lessons/2026-06-07-issue-32-cockroachdb-transaction-retry.md`를
     추가한다.
   - Lore protocol에 따라 커밋한다.
   - 브랜치를 push하고 `debop`에게 할당한 PR을 생성한다.
   - 가능하면 PR milestone `1.11.0`과 관련 label을 설정한다.
   - 라이브 PR 본문을 확인하고 마지막 `##` section이
     `## DoD Status`인지 검증한다.

## 위험과 통제

| 위험 | 통제 |
|---|---|
| Exposed 내부 retry가 분류 경계를 넓힌다. | 내부 transaction의 `maxAttempts = 1`로 설정한다. |
| retry 불가능한 SQL 오류를 재시도한다. | SQLSTATE/message가 CockroachDB retry 오류와 일치할 때만 재시도한다. |
| wrapping된 `ExposedSQLException`이 PostgreSQL 원인을 숨긴다. | cause chain을 순회한다. |
| 시도 소진 시 시도 근거가 사라진다. | 마지막 SQL 예외를 다시 던지고 이전 SQL 실패를 suppressed exception으로 첨부한다. |
| 테스트가 결정적이지 않은 CockroachDB 경합에 의존한다. | retry 메커니즘에는 가짜 SQLException 회귀 테스트를 사용하고, Testcontainers는 commit/rollback 동작의 smoke test에만 사용한다. |

## 검증 기대 사항

- 가짜 retry 테스트로 분류와 retry 메커니즘을 결정적으로 입증한다.
- Testcontainers smoke test로 공개 헬퍼가 실제 CockroachDB 및 Exposed
  JDBC와 함께 동작함을 입증한다.
- README 예제는 관련된 곳에서 bluetape4k ecosystem 헬퍼를 재사용한다.
