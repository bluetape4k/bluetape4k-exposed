# Issue #690 설계·계획 7-Tier review

## 검토 범위

- 설계: `docs/superpowers/specs/2026-08-19-issue-690-virtual-thread-key-enumeration-design.md`
- 계획: `docs/superpowers/plans/2026-08-19-issue-690-virtual-thread-key-enumeration-plan.md`
- 기준 source: `develop` `b334586863695fa7f020149477099f269d77a971`
- 관점: performance, stability, security, operator/Ops, developer/API, user/caller
- 통합 방식: 각 관점의 독립 질문을 현재 source/설계/계획에 대조하고, 동일 원인의
  finding은 하나로 합쳤다.

## 관점별 결과

| 관점 | 결과 | 근거 |
| --- | --- | --- |
| Performance | PASS | range별 `List`와 최종 merge의 O(N) 비용, `maxConcurrency`와 pool 책임, sequential streaming 대안을 설계에 명시했다. benchmark가 row/range/pool 값을 고정한다. |
| Stability | PASS | 독립 transaction, bounded submission, `cancel(true)`·종료 대기, interrupt flag 복원, weak consistency와 driver 협력 취소 한계를 계획에 포함했다. internal reader hook으로 sibling failure를 결정론적으로 재현한다. |
| Security | PASS | range predicate는 Exposed DSL parameter binding을 사용하고 raw SQL 문자열을 만들지 않는다. executor/database는 caller-owned이며 payload/ID 로그를 추가하지 않는다. |
| Operator/Ops | PASS | custom executor close 책임, pool 상한, benchmark 재현 명령, failure/interrupt 원인 보존, manual `1.12.1` 불변 경계를 기록했다. |
| Developer/API | PASS | 기존 `ID: Any`, constructor, sequential method를 보존하는 additive surface이며 공용 helper와 loader 책임이 분리된다. internal test hook은 public ABI에 노출하지 않는다. |
| User/caller | PASS | `[lowerInclusive, upperExclusive)`와 open outer bound, overlap/reverse 거부, ordered merge, materialization·snapshot 비보장과 adapter 제외 범위를 KDoc/README에 반영하도록 계획했다. |

## 통합 finding

| Priority | Area | Finding | Required edit | Status |
| --- | --- | --- | --- | --- |
| P2 | Tests | 실제 JDBC query failure를 순수 H2 fixture만으로 결정론적으로 만들기 어렵다. | `exposed/jdbc` test source set에만 internal range-reader overload를 두어 실제 transaction/future lifecycle 안에서 한 range reader를 실패시킨다. | FIXED in design/plan |
| P2 | Docs | benchmark README 경로가 계획에서 짧은 이름으로만 적혀 있었다. | `benchmark/exposed-benchmark/README.md`와 `README.ko.md`를 정확한 파일로 고정한다. | FIXED in plan |
| P2 | API | custom comparator와 range list ordering은 caller 입력을 신뢰하면 silent duplicate가 가능하다. | comparator validation과 adjacent non-overlap `require`, `distinct()` 금지, overlap/reverse test를 고정한다. | FIXED in design/plan |

P0/P1 finding은 없다. P2는 모두 구현 전 계획에 반영했으며, 새 public API나 preview
dependency를 추가하는 별도 scope는 없다.

## 계획 traceability

| Acceptance | Plan task |
| --- | --- |
| range/order/snapshot | Task 1, 2, 6 |
| bounded execution/pool | Task 1, 2, 7 |
| sequential parity/benchmark | Task 3, 4, 5, 7 |
| failure/cancellation | Task 1, 2, 7 |
| default/API/docs parity | Task 3, 4, 6, 7 |

## Writer DoD (SPW-01~05)

- [x] SPW-01 — 대상 문서, issue/epic, 기준 SHA와 review 범위를 고정했다.
- [x] SPW-02 — 관점별 근거, P2 repair, 수용 기준 traceability와 상태를 기록했다.
- [x] SPW-03 — 한국어 technical register와 range/keyset/snapshot/transaction 용어를
  일관되게 사용했다.
- [x] SPW-04 — 현재 loader와 Virtual Thread helper를 설계·계획과 대조했다.
- [x] SPW-05 — Markdown read-back과 `git diff --check`로 표/링크/code token을 확인했다.

## 최종 판정

`CLEAR` — 최신 설계·계획 기준 P0=0, P1=0. 다음 단계는 설계/계획 문서 commit 후
Task 1 RED 테스트이며, 구현 GREEN 전에는 benchmark/PR을 주장하지 않는다.

