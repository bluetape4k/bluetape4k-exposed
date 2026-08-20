# Issue #697 JDBC 병렬 key enumeration 취소 lifecycle 7-Tier review

## 검토 대상과 기준

- 대상: [Issue #697](https://github.com/bluetape4k/bluetape4k-exposed/issues/697),
  [Epic #659](https://github.com/bluetape4k/bluetape4k-exposed/issues/659) stacked slot 9
- 선행 slot: #698, PR #705가 `develop`에 merge된 뒤 시작
- base: `develop` `5b2f0a1a4f4580d2f293d42b622080ad9e3bd8c2`
- branch/worktree: `fix/issue-697-cancellation-lifecycle` /
  `.worktrees/fix-issue-697-cancellation-lifecycle`
- 범위: JDBC `parallelJdbcKeyEnumeration`의 child transaction·connection cleanup join,
  H2 synthetic cancellation, PostgreSQL/MySQL Hikari lease 증거, KDoc·설계·계획·lesson
- 제외: R2DBC, #690 benchmark/chart, dependency/catalog/workflow, API/ABI 확장,
  안정 `docs/manual/**`(현재 `1.12.1`)

## 7-Tier 판정

| Tier | 판정 | 근거 |
| --- | --- | --- |
| 1. 요구사항·범위 | CLEAR | 실패·caller interrupt 뒤 child transaction 종료를 기다리고 caller executor를 닫지 않는 #697 acceptance를 구현했다. #690과 manual은 제외했다. |
| 2. 구조·API/ABI | CLEAR | private `JdbcEnumerationChild`와 latch만 추가했으며 public signature, `JdbcParallelKeyEnumerationOptions`, `VirtualFuture`, sequential loader는 변경하지 않았다. 자동 ABI task는 저장소에 구성되어 있지 않아 N/A로 기록한다. |
| 3. Kotlin·동시성 | CLEAR | `NEW → RUNNING → COMPLETED/CANCELLED` CAS 경계가 선행 취소의 latch 미완료 race를 막고, cleanup join 중 받은 interrupt는 모든 child 완료 후 복원한다. `finally`에서 permit과 completion을 transaction 바깥에 둔다. |
| 4. Exposed·JDBC driver | CLEAR | Exposed `transaction(db, isolation, readOnly)`가 반환한 뒤 permit/latch를 완료한다. PostgreSQL와 MySQL Hikari fixture에서 실패 sibling의 `tracker.active == 0`을 확인했다. |
| 5. 테스트·증거 | CLEAR/WATCH | H2 targeted/full, 공유 JDBC module, PostgreSQL, MySQL 및 detekt가 모두 fresh PASS다. hosted exact-head CI와 nightly 전체 행렬은 PR delivery gate로 남는다. |
| 6. 문서·KDoc·lesson | CLEAR | KDoc에 cleanup join과 interrupt-ignoring child의 지연 가능성을 명시하고, 설계·계획·review·lesson 수치와 범위를 일치시켰다. terminology audit를 최종 실행한다. |
| 7. delivery·안전 | WATCH | 변경은 아직 commit/PR/hosted CI 단계 전이다. exact-head 확인과 fresh merge approval 없이 merge하지 않는다. |

## Findings

### P0

없음.

### P1

없음. pre-start cancellation race, interrupt-ignoring sibling의 조기 반환, permit 조기
재사용, 실제 Hikari lease 잔존 문제를 lifecycle latch와 두 단계 취소·join으로 해소했다.

### P2 / 잔여 범위

1. hosted exact-head CI와 nightly 전체 driver 행렬은 이 local worktree에서 대체하지
   않는다. PR 생성 뒤 required check와 skipped/N/A 범위를 재판정해야 한다.
2. 외부 JDBC driver가 interrupt를 무기한 무시하면 API가 실제 child 종료까지 기다릴 수
   있다. 임의 timeout으로 live connection을 남기는 대신 이 지연을 명시적 계약으로
   유지했으며, driver별 강제 abort는 별도 이슈 범위다.
3. 저장소에 production ABI 검증 task가 구성되어 있지 않다. 변경 diff에서 public
   signature를 확인했지만 binary compatibility 실행 PASS로 과장하지 않는다.

## 검증 증거

| 범위 | tests | failures | errors | skipped | 결과 |
| --- | ---: | ---: | ---: | ---: | --- |
| JDBC H2 targeted lifecycle | 10 | 0 | 0 | 0 | `BUILD SUCCESSFUL` |
| JDBC H2 full module | 211 | 0 | 0 | 23 | `BUILD SUCCESSFUL` |
| 공유 JDBC test module | 72 | 0 | 0 | 5 | `BUILD SUCCESSFUL` |
| PostgreSQL Testcontainers targeted | 7 | 0 | 0 | 0 | `BUILD SUCCESSFUL` |
| MySQL Testcontainers targeted | 11 | 0 | 0 | 0 | `BUILD SUCCESSFUL` |

추가 검증:

- `./gradlew :bluetape4k-exposed-jdbc:detekt ...`: 성공, finding 없음.
- `git diff --check`: 성공.
- H2 RED는 새 두 lifecycle 테스트를 추가한 뒤 `10 tests, 2 failures`로 의도한
  active-child 조기 반환 결함을 재현했다. production fix 후 `10/0/0`으로 전환했다.
- 첫 PostgreSQL 실행에서 fixture의 `selectAll()`이 `try/finally` 밖에 있어 latch가
  보장되지 않는 test-only 결함을 발견했고, DB read를 `try/finally` 안으로 옮긴 뒤
  PostgreSQL/MySQL 모두 재실행 PASS했다. 이는 production failure가 아니다.

## 결론과 handoff

현재 local 구현·문서·테스트의 7-Tier 상태는 **P0=0, P1=0, P2=3, WATCH**다. PR 생성
전 exact diff·AGENTS·issue metadata를 다시 읽고 Korean PR body의 마지막 H2를
`## DoD Status`로 고정한다. PR 이후 required checks와 independent review가 모두
fresh PASS가 되기 전에는 merge-ready를 선언하지 않는다.

## Writer DoD (SPW-01~05)

- [x] SPW-01 — #697/#659, 선행 #698 merge, source/test/backend fixture와 release/manual
  경계를 고정했다.
- [x] SPW-02 — 취소 요청과 실제 transaction/connection completion을 분리하는 decision,
  acceptance, N/A/P2 경계를 기록했다.
- [x] SPW-03 — 한국어 technical register와 `Future.cancel(true)`, `CountDownLatch`,
  `PENDING`, `N/A`, API token을 보존했다.
- [x] SPW-04 — production control flow, H2 RED/GREEN, PostgreSQL/MySQL lease XML과
  detekt 결과를 대조했다.
- [x] SPW-05 — Markdown read-back과 수치 ledger를 완료했으며 terminology audit 결과를
  아래에 기록한다.

### Terminology audit

최종 변경 파일 8개를 대상으로 `$bluetape-writer` audit를 실행한다. finding이 남으면
commit 전에 해당 문맥만 수정한다.

실행 결과: `8 file(s)`, `series=clinic-appointment`, `findings=0`.
