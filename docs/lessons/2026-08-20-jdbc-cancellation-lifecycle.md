# Issue #697 JDBC 취소와 transaction lifecycle lesson

## Context

`parallelJdbcKeyEnumeration`은 range마다 독립 Exposed JDBC transaction을 만들고,
호출자가 선택한 executor에서 결과를 range 선언 순서로 합친다. 기존 구현은
`Future.cancel(true)` 직후 취소된 future의 완료를 관찰했지만, Java Future의 취소 상태가
worker의 실제 transaction rollback·connection 반환 완료를 뜻하지 않는다는 경계를
검증하지 않았다.

## Decision or Finding

- child마다 `VirtualFuture`와 transaction wrapper 바깥의 `CountDownLatch`를 함께 둔다.
  worker가 `transaction {}`에서 반환한 뒤에만 permit을 반환하고 latch를 완료한다.
- 실패·caller interrupt에서는 먼저 모든 child에 취소를 요청하고, latch를
  uninterruptible하게 join한다. join 중 interrupt는 cleanup 뒤 parent thread에 복원한다.
- `NEW → RUNNING → COMPLETED/CANCELLED` CAS로 아직 시작하지 않은 child의 latch가
  영원히 열리지 않는 race를 막는다.
- public API, `VirtualFuture`, caller-owned executor, sequential loader, R2DBC와 안정
  `docs/manual/**`(`1.12.1`)는 건드리지 않았다.
- H2 RED는 의도한 active-child 조기 반환을 `10 tests, 2 failures`로 재현했고, fix 뒤
  `10/0/0`으로 통과했다. 첫 PostgreSQL 실행의 latch 문제는 test fixture의 read가
  `try/finally` 밖에 있던 것이 원인이어서 fixture만 보정했다.

## Outcome

| 검증 | tests | failures | errors | skipped | 결과 |
| --- | ---: | ---: | ---: | ---: | --- |
| JDBC H2 targeted lifecycle | 10 | 0 | 0 | 0 | `BUILD SUCCESSFUL` |
| JDBC H2 full module | 211 | 0 | 0 | 23 | `BUILD SUCCESSFUL` |
| 공유 JDBC test module | 72 | 0 | 0 | 5 | `BUILD SUCCESSFUL` |
| PostgreSQL Testcontainers targeted | 7 | 0 | 0 | 0 | `BUILD SUCCESSFUL` |
| MySQL Testcontainers targeted | 11 | 0 | 0 | 0 | `BUILD SUCCESSFUL` |

affected JDBC detekt와 `git diff --check`도 성공했다. PostgreSQL/MySQL 테스트는 Docker가
있는 로컬에서 순차 실행했으며, 각 fixture의 `active == 0`과 caller executor 생존을
확인했다. 변경 파일 8개 terminology audit도 `findings=0`으로 통과했다.

## Future Guidance

1. JDBC 병렬 작업에서 `Future`가 취소되었다는 사실을 transaction·connection cleanup
   완료로 해석하지 않는다. 별도 lifecycle barrier를 두고 실패 반환 직전에 join한다.
2. `permit.release()`는 statement가 끝난 시점이 아니라 Exposed transaction wrapper가
   반환한 뒤 실행한다. 그렇지 않으면 논리 동시성 제한과 실제 pool lease 수가 갈라진다.
3. caller-owned executor를 종료하거나 임의 timeout으로 join을 끊지 않는다. 무한히
   interrupt를 무시하는 외부 driver의 강제 abort는 driver별 별도 계약과 이슈로 분리한다.
4. pre-start cancellation 테스트는 `cancel(true)`와 worker의 시작 CAS를 동시에
   검증해야 latch 누수와 permit 누수를 잡을 수 있다. H2 synthetic test와 최소 한
   PostgreSQL/MySQL Hikari fixture를 함께 유지한다.

## Verification boundary

- hosted exact-head CI와 nightly 전체 driver 행렬은 PR delivery gate이며 local PASS로
  대체하지 않는다.
- production ABI 자동 task가 이 저장소에 구성되어 있지 않아 ABI 실행은 N/A다. public
  signature diff가 없다는 정적 확인만 기록한다.
- interrupt를 영원히 무시하는 driver를 강제 종료하는 동작은 구현하지 않았다. 이 경우
  실제 종료까지 기다리는 것이 이번 이슈의 명시적 계약이다.

## Writer DoD (SPW-01~05)

- [x] SPW-01 — Issue/Epic/선행 slot, root cause와 변경 범위를 고정했다.
- [x] SPW-02 — lifecycle decision, RED/GREEN, backend evidence와 rollback 경계를 기록했다.
- [x] SPW-03 — 한국어 technical register와 API·command·driver token을 보존했다.
- [x] SPW-04 — source diff, H2/공유 module/PostgreSQL/MySQL 결과와 detekt를 대조했다.
- [x] SPW-05 — Markdown read-back, 수치 ledger, N/A/PENDING 경계를 완료했다.
