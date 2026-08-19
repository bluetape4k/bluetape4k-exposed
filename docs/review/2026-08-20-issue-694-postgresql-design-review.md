# Issue #694 PostgreSQL 첫 slot 설계 7-Tier review

검토 대상은 `ff85c999` 기반 `test/issue-694-postgresql` worktree의
`docs/superpowers/specs/2026-08-19-issue-694-postgresql-driver-conformance-design.md`이다.
이번 단계에서는 production source와 테스트 구현을 변경하지 않았으며, H2 baseline
`JdbcParallelKeyEnumerationTest` 8/8, failure/error 0을 기준 evidence로 사용했다.

## Tier 판정

| Tier | 관점 | 판정 | 근거 |
| --- | --- | --- | --- |
| 1 | 요구사항·데이터 계약 | PASS | #694 전체와 PostgreSQL 첫 slot, 후속 MySQL/benchmark/cancellation N/A를 분리했다. sparse/ordered/no-duplicate와 weak-consistency를 서로 다른 계약으로 적었다. |
| 2 | 동시성·수명주기 | PASS | Hikari lease 관찰은 test-only `DataSource` decorator로 한정하고, helper의 `maxConcurrency`, child transaction, caller executor 소유권을 각각 검증하도록 설계했다. |
| 3 | API·ABI·경계 | PASS | production Kotlin/API/ABI, catalog/BOM, loader signature를 변경하지 않는다. pool fixture만 `Database.connect(dataSource)`를 사용해 `TestDB.db` DriverManager 경계와 섞지 않는다. |
| 4 | 성능·자원 | PASS | 이 slot은 benchmark 수치를 만들지 않고 pool lease 상한과 failure cause만 증명한다. H2/실제 driver 성능 일반화와 chart는 cross-driver slot으로 명시적으로 제외했다. |
| 5 | 안정성·보안 | PASS | 기존 container/driver 설정과 bounded timeout을 재사용하고, 무한 재시도·raw payload/credential 로그·destructive cleanup을 설계에 포함하지 않았다. serialization failure는 성공으로 변환하지 않는다. |
| 6 | 문서·운영 | PASS | Issue #694/#659에 첫 slot과 후속 경계를 한국어로 기록했고, nightly PostgreSQL job을 재사용한다. 안정 manual `docs/manual/**` `1.12.1`은 변경하지 않는다. |
| 7 | 검증·유지보수 | PASS | parity, pool 조합, isolation/mutation, cleanup, N/A evidence의 수용 기준과 exact failure 기록 규칙을 정의했다. 문서 `git diff --check`와 Korean terminology audit가 통과했다. |

## 잔여 위험

- **P2 — pool 부족 의미의 환경 의존성:** `poolSize < maxConcurrency`에서 Hikari가
  대기 후 정상 완료할지 timeout으로 실패할지는 timeout 설정과 driver 상태에 좌우된다.
  성공/실패를 고정된 성능 결론으로 만들지 않고 exact command와 원인을 기록한다.
- **P2 — isolation mutation timing:** barrier를 사용해 writer 시점을 고정해도
  PostgreSQL serialization failure와 statement 관찰 결과는 transaction scheduling에
  영향을 받는다. 하나의 읽기 기준 또는 repeatable-read 계약으로 승격하지 않는다.
- 실제 PostgreSQL query cancellation 및 sibling interrupt가 driver close까지
  회수되는지는 이 slot의 failure cleanup assertion으로 대체하지 않고 후속 lifecycle
  slot에서 재현한다.

## 리뷰 결론

`CLEAR` — P0=0, P1=0, P2=2. 설계 범위와 구현 경계가 분리되어 있어 implementation
plan 검토 단계로 이동할 수 있다. P2는 acceptance를 차단하지 않으며 plan에서
`N/A`/재현 조건을 구체화해야 한다.
