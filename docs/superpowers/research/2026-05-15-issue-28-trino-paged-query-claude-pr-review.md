# Claude PR 검토 요청 - Issue #28 / PR #67

- PR: https://github.com/bluetape4k/bluetape4k-exposed/pull/67
- Local checkout: `/Users/debop/work/bluetape4k/bluetape4k-exposed/.worktrees/issue-28-trino-paged-query`
- 요청 범위: Kotlin public API, DB/Exposed 동작, Trino limit/offset SQL 생성,
  `pagedQueryFlow` 취소/리소스 동작, 트랜잭션 경계, 테스트, README 주장에 대한
  CRITICAL/HIGH 정확성 문제만 검토한다.

## 결과

Claude Code CLI를 다시 호출하고 5분 넘게 실행하도록 두었다. 실행이 완료된 뒤
HIGH 발견 사항 2개와 정보성 테스트 메모 1개를 보고했다. 세션 중 원시 stdout을
`/tmp/claude-pr67-review.out`에 캡처했다.

## 반영한 발견 사항

- HIGH: Exposed의 PostgreSQL function provider를 `TrinoFunctionProvider`로
  교체하면 `OFFSET ... LIMIT`은 수정되지만 `groupConcat`과 `locate`의 기존
  매핑이 사라진다.
  - 수용.
  - `groupConcat`에는 Trino 호환 `ARRAY_JOIN(ARRAY_AGG(...), separator)`를,
    `locate`에는 `POSITION(substring IN expr)`를 사용해 수정했다.
  - 두 함수에 실제 Trino Testcontainers 검증을 추가했다.
- HIGH: 이 PR에서 public API와 사용 문서를 구현했는데도 README Phase 2
  Roadmap에는 `pagedQueryFlow`가 여전히 향후 작업으로 남아 있었다.
  - 수용.
  - 영문과 한글 README에서 오래된 roadmap 행을 제거했다.
- 정보성: `.take(3)` 취소 테스트는 collector의 단락 실행과 잘라낸 뒤 추가
  페이지를 가져오지 않는 동작을 입증하지만 job 취소 의미 체계까지 입증하지는
  않는다.
  - 문구/테스트 범위 메모로 수용.
  - 테스트는 추가 페이지를 가져오지 않는 동작에 집중하도록 유지했으며,
    취소 응답성을 위한 `ensureActive()` 보호 로직은 production flow에 남겼다.

## 검토 상태

- `pagedQueryFlow`는 JDBC `ResultSet` 접근을 페이지 단위 Exposed 트랜잭션 안에
  유지한다.
- Trino `OFFSET ... LIMIT ...` SQL 순서는 실제 Trino Testcontainers 테스트로
  검증한다.
- Claude 검토 수정 후 Trino `groupConcat`과 `locate` 매핑을 실제 Trino
  Testcontainers 테스트로 검증한다.
- collector 측 `take(3)` 테스트로 취소 동작을 검증하며, 세 번째 페이지를
  요청하지 않는다는 사실을 입증한다.
- README 주장은 애플리케이션 `pageSize` 메모리 한도와 spooling 같은 Trino
  JDBC/cluster 처리량 메커니즘을 구분하도록 조정했다.
