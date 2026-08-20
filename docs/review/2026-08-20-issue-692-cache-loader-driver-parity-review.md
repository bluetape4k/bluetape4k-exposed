# Issue #692 cache loader custom ID·driver parity 7-Tier review

## 검토 대상과 기준

- 대상: Issue [#692](https://github.com/bluetape4k/bluetape4k-exposed/issues/692),
  Epic [#659](https://github.com/bluetape4k/bluetape4k-exposed/issues/659) stacked slot 7
- branch: `test/cache-loader-driver-parity`
- base/head: `develop` / `736f07d5be05f17ff2e057586e40d27d59cd0c20`
- 범위: 세 cache loader의 test-local transformed custom `IdTable`, page mutation·caller가
  주입한 scope cancellation·R2DBC
  scope cancellation·R2DBC retry/timeout 증거, EN/KO README·KDoc 정합성, Type A lesson
- 제외: production algorithm/API/ABI, catalog/BOM/workflow, benchmark/chart,
  `docs/manual/**`의 안정 `1.12.1`, MySQL(#698), network fault 별도 후속 범위

## 7-Tier 판정

| Tier | 판정 | 근거 |
| --- | --- | --- |
| 1. 요구사항·범위 | CLEAR | non-`Comparable` transformed ID, 세 adapter, PostgreSQL targeted evidence와 비목표 경계가 Issue/설계/계획에 일치한다. |
| 2. 구조·API/ABI | CLEAR | production signature·constructor·cache model·ABI 변경 없음; JDBC Redisson 단일 transaction materialization을 KDoc과 설계에 반영했다. |
| 3. Kotlin·coroutine | CLEAR/WATCH | R2DBC rendezvous back-pressure, `CancellationException` 재전파, top-level `maxAttempts=1`, ambient caller-owned retry를 검증한다. cancellation은 caller가 주입한 `Job` scope에 한정하며 기본 shared `SupervisorJob`의 자동 전파를 과장하지 않는다. |
| 4. Exposed·DB 계약 | CLEAR/WATCH | H2 full과 PostgreSQL custom selector에서 `LIMIT`/`OFFSET`/ASC/cardinality/page mutation을 통과했다. queryTimeout statement 값·단위·cleanup은 #699가 소유하며 MySQL은 #698이다. |
| 5. 테스트·driver 증거 | CLEAR/WATCH | H2 세 module full과 PostgreSQL 세 targeted selector가 모두 fresh PASS다. PostgreSQL full module/nightly는 local 범위를 넘어 merge gate로 `Blocked: 1`이다. |
| 6. 문서·EN/KO·lesson | CLEAR | README/KDoc EN/KO 의미 parity, terminology audit finding 0, Type A lesson과 설계·계획·review artifact를 보존했다. |
| 7. delivery·안전 | WATCH | 변경은 worktree에만 있고 PR/CI/merge를 아직 실행하지 않았다. exact-head PR/hosted CI/merge는 별도 승인·게이트다. |

## Findings

### P0

없음.

### P1

없음. 이전 검토의 stale KDoc, H2 XML 부재, caller cancellation scope, terminology
audit, Type A lesson 지적은 현재 소스 상태에서 정리했다.

### P2 / 잔여 범위

1. PostgreSQL full module/nightly CI와 hosted required checks는 이 local slot에서
   대체하지 않는다. PR handoff 후 `Blocked: 1`로 유지하고 CI evidence가 생길 때
   해소한다.
2. JDBC/R2DBC low-level connection close event를 driver instrumentation으로 직접
   관찰하는 검증은 이번 test-only fixture의 acceptance가 아니다. JDBC Lettuce는
   cancellation 전파·partial 결과 미반환·후속 재조회를 확인하지만, R2DBC는 caller가
   주입한 scope 취소와 다음 page 미실행까지만 직접 확인한다. R2DBC channel cause·
   재조회 복구·직접 close provenance가 필요하면 별도 driver lifecycle issue로
   분리한다.
3. JDBC Lettuce README의 기존 `../../infra/lettuce` cross-repository reference는
   현재 repository tree에서 대상을 확인할 수 없다. 링크를 추측해 바꾸지 않고,
   upstream module ownership이 확인될 때까지 문서 링크 후속으로 남긴다.

## 검증 증거

| 범위 | tests | failures | errors | skipped | 결과 |
| --- | ---: | ---: | ---: | ---: | --- |
| JDBC Lettuce H2 full | 892 | 0 | 0 | 73 | `BUILD SUCCESSFUL` |
| JDBC Redisson H2 full | 630 | 0 | 0 | 1 | `BUILD SUCCESSFUL` |
| R2DBC Redisson H2 full | 220 | 0 | 0 | 2 | `BUILD SUCCESSFUL` |
| JDBC Lettuce PostgreSQL custom selector | 6 | 0 | 0 | 0 | `BUILD SUCCESSFUL` |
| JDBC Redisson PostgreSQL custom selector | 4 | 0 | 0 | 0 | `BUILD SUCCESSFUL` |
| R2DBC Redisson PostgreSQL custom selector + 60초 timeout | 12 | 0 | 0 | 1 | `BUILD SUCCESSFUL` |

추가 검증:

- affected module detekt 3개: 성공, finding 없음
- `git diff --check`: 성공
- EN/KO README contract parity: 의미·이미지 수 일치; 기존 cross-repository reference 2건은 N/A
- `$bluetape-writer` terminology audit: 7개 입력, finding 0
- Type A lesson: [`docs/lessons/2026-08-20-issue-692-cache-loader-driver-parity.md`](../lessons/2026-08-20-issue-692-cache-loader-driver-parity.md)

## 결론과 handoff

현재 구현·문서·로컬 증거의 7-Tier 상태는 **P0=0, P1=0, P2=3, WATCH**다. 구현
worktree의 DoD는 충족했지만, PR 생성 전에는 exact head와 변경 경로를 다시 읽고,
PR body 마지막 heading을 정확히 `## DoD Status`로 작성해야 한다. PostgreSQL full
module/nightly CI가 성공하기 전에는 merge-ready로 선언하지 않는다.
