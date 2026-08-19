# Issue #694 PostgreSQL 첫 slot 실행계획 7-Tier review

검토 대상은 승인된 설계와
`docs/superpowers/plans/2026-08-20-issue-694-postgresql-driver-conformance-plan.md`이다.
구현 전 계획 검토이며, 현재 worktree의 fresh baseline은 H2 helper 8/8,
failure/error 0이다.

## Tier 판정

| Tier | 관점 | 판정 | 근거 |
| --- | --- | --- | --- |
| 1 | 요구사항·수용 기준 | PASS | correctness, pool 세 조합, pool failure, 두 isolation, empty range와 후속 N/A가 각각 test/명령/evidence로 연결된다. |
| 2 | 동시성·수명주기 | PASS | barrier/latch에 bounded timeout을 요구하고, tracking connection·child transaction·caller executor를 `finally`에서 회수한다. helper의 await 순서 때문에 pool failure는 첫 child delay 후 원인을 관찰하도록 계획했다. |
| 3 | Kotlin/API·ABI | PASS | production source를 변경하지 않고 test source 한 파일에 fixture/proxy를 한정한다. `!!`, `assertThrows`, 무한 wait, public API 확장을 계획하지 않는다. |
| 4 | 성능·자원 | PASS | pool size `1/2/4`와 `maxConcurrency=2`를 lease peak로 비교하며, benchmark/chart와 throughput 결론은 다음 slot으로 격리한다. |
| 5 | 안정성·환경 | PASS | Docker/Testcontainers 조건을 assumption과 `N/A`로 분리하고, PostgreSQL/공유 test source set 실행은 순차 명령으로 고정한다. credential/raw payload 로그와 destructive cleanup을 금지한다. |
| 6 | 문서·운영·stack | PASS | Issue/Epic/PR handoff, exact head/CI/DoD, stable manual `1.12.1`, 후속 MySQL/benchmark/cancellation 경계를 명시했다. |
| 7 | 검증·회귀·복구 | PASS | H2 targeted regression, PostgreSQL targeted/full module, detekt, diff/terminology audit와 fail-closed rollback 조건이 있다. |

## 계획 조정

- `poolSize < maxConcurrency`의 정상 완료와 의도적 timeout은 서로 다른 test case로
  유지한다. 전자는 pool 대기 후 회수, 후자는 bounded connection timeout의 root cause
  보존을 증명한다.
- SERIALIZABLE 결과는 성공(삽입 미관찰)과 PostgreSQL serialization failure를 모두
  허용하되, 중복 ID·lease 누수·원인 소실은 허용하지 않는다.
- H2에서 PostgreSQL test가 skip되는 것은 expected이며 PostgreSQL green evidence로
  집계하지 않는다.

## 최종 판정

`CLEAR` — P0=0, P1=0, P2=0. 구현을 시작할 수 있다. Docker가 없거나 driver가
결정론적 timeout을 제공하지 않는 경우 계획의 `N/A` 규칙을 적용하고, 그 상태로 PR을
완료하지 않는다.
