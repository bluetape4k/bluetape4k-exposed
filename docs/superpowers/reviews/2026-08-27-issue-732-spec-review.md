# Issue #732 설계 명세 통합 리뷰

## 리뷰 범위

- 대상: `docs/superpowers/specs/2026-08-27-issue-732-caffeine-coordinator-design.md`
- 기준: `origin/develop@c5e9d499d9c1baeb6f92a531345d184c16febc27`
- workflow: Type A `bluetape-full-feature`
- 독립 관점: API/ABI, 보안·관측성, 성능·동시성, 안정성·호환성,
  사용자·호출자, 운영·검증
- 판정 규칙: P0/P1은 구현 진입을 차단하고, P2는 실행 계획과 검증 산출물에
  추적한다.

## 최종 관점별 판정

| 관점 | P0 | P1 | P2 | 판정 | 증거 |
|---|---:|---:|---:|---|---|
| API/ABI | 0 | 0 | 0 | READY | `spec732-final5-api`, `/tmp/spec732-final5-api-result.json` |
| 보안·관측성 | 0 | 0 | 0 | READY | `spec732-final5-security`, `/tmp/spec732-final5-security-result.json` |
| 성능·동시성 | 0 | 0 | 0 | READY | `spec732-final5-performance`, `/tmp/spec732-final5-performance-result.json` |
| 안정성·호환성 | 0 | 0 | 2 | READY | `spec732-final5-stability`, `/tmp/spec732-final5-stability-result.json` |
| 사용자·호출자 | 0 | 0 | 0 | READY | `spec732-final5-user`, `/tmp/spec732-final5-user-result.json` |
| 운영·검증 | 0 | 0 | 2 | READY | `spec732-final5-ops`, `/tmp/spec732-final5-ops-result.json` |
| **합계** | **0** | **0** | **4** | **READY** | run `20260827T071058Z-6fa1ef92` |

모든 P0/P1이 0이므로 구현 계획으로 진행할 수 있다. publication lease와
commit lock의 경합·deadline·cleanup 순서는 최종 보완 후 6관점에서 재확인했다.

## 통합 결정

1. accepted queue handoff는 adapter-owned `PublicationLease` 획득과
   `settleEnqueue(true)`를 terminal gate와 같은 짧은 원자 경계에서 선형화한다.
   lease는 실제 `cache.put` 성공·예외·취소/거부까지 유지하며 close readiness에
   포함한다.
2. close owner는 짧은 commit lock에서 terminal gate를 설치한 뒤 lock을 풀고,
   동일 absolute deadline으로 lease drain을 수행한다. drain 중 lock을 잡지
   않으며, 완료 후 lock을 재획득해 invalidate를 선형화하고 cleanup 이후에만
   completion/follower signal을 공개한다.
3. coordinator는 logical admission/state와 fresh owner identity/CAS만 소유하고
   Channel, scope, DB, cache key/entity, raw Throwable를 소유하지 않는다.
   `internal` coordinator와 friend-path는 public API/clean consumer에서 차단한다.
4. DB matrix P2는 실행 계획에 schema `1`, `required`/`applicable`, `status`,
   `reason`, timestamp, adapter/database 행 필드를 고정하고
   `build/verification/write-behind-db-matrix.json` parser/aggregator가
   fail-closed하도록 추적한다. friend-path/ABI invocation과 baseline/fixture
   경로도 계획의 T3/T5/T7에 고정한다.
5. bounded admission pool, retry batch cap(최초 포함 8회), stable log/metric
   schema와 기존 report/serial/metric ABI를 유지한다. non-cooperative 외부
   backend와 interrupt 불능 driver는 caller contract로 명시한다.

## 게이트 결과

- `git diff --check`: 최종 명세 보완에서 PASS.
- 독립 6관점 final5 리뷰: P0 0, P1 0, P2 4.
- 구현 계획: `docs/superpowers/plans/2026-08-27-issue-732-caffeine-coordinator-plan.md`
  에 P2의 schema/required row, friend-path/ABI invocation, blocked-put 회귀와
  순차 DB matrix를 반영했다.
- 구현/PR/merge/release는 이 리뷰에서 수행하지 않았다.

## 결론

`READY`: 승인된 설계 명세는 구현 단계로 이동할 수 있다. P2는 실행 계획과
verification 산출물에서 추적하며, 구현 전 최종 리뷰에서 P1이 다시 발견되면
해당 계약을 먼저 보완한다.
