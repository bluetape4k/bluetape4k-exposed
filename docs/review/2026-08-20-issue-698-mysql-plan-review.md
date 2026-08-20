# Issue #698 MySQL 8 JDBC conformance 구현 계획 review

## 문서 상태

- 대상: [Issue #698](https://github.com/bluetape4k/bluetape4k-exposed/issues/698)
- 설계: `docs/superpowers/specs/2026-08-20-issue-698-mysql-driver-conformance-design.md`
- 계획: `docs/superpowers/plans/2026-08-20-issue-698-mysql-driver-conformance-plan.md`
- 기준 base: `develop` `ea19b9e0c6d5135d2447c9a95435c85c1127e3b3`
- 검토 상태: **PASS (P0=0, P1=0)**
- 구현 gate: 이 문서의 audit/diff-check PASS와 사용자 승인 전에는 Task 2 RED를 시작하지 않는다.

## 6-perspective 결과

| 관점 | 계획에서 확인한 실행 계약 | 판정 |
| --- | --- | --- |
| performance | pool `1/2/4`, exact acquisition barrier, bounded result | PASS |
| stability | timeout/cancel/await finally, retry/rollback/cleanup oracle | PASS |
| security | container credential/full URL 비노출, localhost fallback 차단 | PASS |
| operator/Ops | startup FAIL/PENDING, scope union, nightly authority/first-attempt WATCH | PASS |
| developer/API | `readOnly=false`, caller-owned executor, explicit `DatabaseConfig`, internal callback 경계 | PASS |
| user/caller | EN/KO evidence parity, pressure-case/N/A 설명, public contract 과장 금지 | PASS |
| main integration | Task 0–8 순서, production/API/ABI/manual 불변, #696→#698 train, Lore/PR gate | PASS |

## 수정 반영 확인

초기 계획 review에서 확인된 P1을 모두 계획 순서와 명령에 반영했다.

1. **Plan gate 순서:** plan review artifact를 먼저 작성한 뒤 spec/plan/review를 포함한
   terminology audit와 `git diff --check`를 실행하고, PASS 전 구현을 금지한다.
2. **Driver provenance와 fixture lifecycle:** `TestDB.MYSQL_V8.connection()`의 기존
   Connector/J 옵션을 container endpoint와 함께 사용하고, Hikari→tracker(fault off)→
   Database→schema/seed→active assert/reset→fault on 순서를 고정했다.
3. **Barrier 실패 안전성:** test-owned executor, bounded acquisition/release latch,
   timeout·예외·취소의 `finally`, helper future cancel/await, 종료 후 active 0 판정을
   명시했다.
4. **Scope와 문서 검증:** committed/staged/unstaged/untracked/base path의 union을
   허용 목록과 대조하며, 미래 lesson은 생성 후 별도 audit한다. `docs/manual/**`와
   workflow는 수정하지 않는다.
5. **Fault oracle:** lease retry는 `maxAttempts=2`와 request count 2, statement fault는
   `maxAttempts=1`과 marker rollback, cleanup fault는 schema-drop/connection-close
   실패의 datasource close와 suppressed 보존을 각각 계약·RED 목록에 포함했다.
6. **External CI ordering:** 최종 audit→Lore commit→push/read-back→PR metadata/read-back
   순서를 고정했다. nightly full dispatch는 별도 권한 hold이며, dispatch 후 feature
   `HEAD_SHA`와 일치하는 run만 선택하고 run ID/headSha/attempt/conclusion과 최초
   `Attempt N failed` 로그를 보존한다.

## 명령과 evidence gate

```bash
node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs \
  docs/superpowers/specs/2026-08-20-issue-698-mysql-driver-conformance-design.md \
  docs/review/2026-08-20-issue-698-mysql-design-review.md \
  docs/superpowers/plans/2026-08-20-issue-698-mysql-driver-conformance-plan.md \
  docs/review/2026-08-20-issue-698-mysql-plan-review.md
git diff --check
```

- 기대 결과: terminology audit `findings=0`, `git diff --check` exit 0
- N/A/PENDING: Docker/Testcontainers 또는 nightly 권한/실행이 없으면 해당 MySQL
  acceptance를 PASS로 대체하지 않는다.
- 실제 MySQL/Connector-J defect: test 삭제나 assertion 완화가 아니라 재현 로그와 연결
  issue를 보존한 `PENDING/BLOCK`으로 보고한다.

## 최종 gate

- [x] P0=0
- [x] P1=0
- [x] SPW-01 — plan이 Issue/source/audience와 외부 근거를 정확히 참조함
- [x] SPW-02 — Task 0–8, ownership, rollback, acceptance와 commands가 완결됨
- [x] SPW-03 — 한국어 technical register와 machine token을 보존함
- [x] SPW-04 — source/fixture/workflow/CI evidence와 N/A 경계를 대조함
- [x] SPW-05 — plan review read-back, audit/diff-check와 RED gate를 고정함
- [x] plan-review artifact가 audit 입력으로 존재
- [x] Integrated plan-review artifact SPW-01 — 6개 관점과 main integration 결과를 통합함
- [x] Integrated plan-review artifact SPW-02 — 초기 P1 수정과 재검토 결과를 기록함
- [x] Integrated plan-review artifact SPW-03 — 문서 언어·machine token·공개 경계를 재확인함
- [x] Integrated plan-review artifact SPW-04 — fresh command/audit/diff-check evidence를 재확인함
- [x] Integrated plan-review artifact SPW-05 — 구현 전 gate와 Task 0–8 순서를 재확인함
- [x] RED 전 plan gate와 Task 0–8 순서가 일치
- [x] cleanup, retry, rollback, pool barrier, scope union에 실행 가능한 oracle 존재
- [x] commit/push/PR/nightly의 side-effect와 권한 경계가 분리됨

계획 단계는 PASS이며 다음 허용 단계는 계획 review artifact를 포함한 terminology/diff
검증을 fresh 실행하는 것이다. 그 결과가 PASS이면 Task 2 RED를 시작한다.
