# Issue 338 MockK Fixture 리뷰

## 범위

- Issue: #338 `test: move repeated MockK setup to fields reset with clearMocks`
- Branch: `test/issue-338-mockk-fields`
- Review type: Type B 6-R lite, Tier 4 code correctness + Tier 5 test/evidence

## 근거

- 수정 전 baseline targeted test: `BUILD SUCCESSFUL`.
- `git diff --check`: clean.
- 수정 파일 MockK scan: 남은 `mockk` 호출은 수정 파일의 class-level fixture declaration입니다.
- 수정 후 targeted test: 첫 실행은 test 통과 보고 뒤 Gradle shutdown race에 걸렸고, 재실행은 `BUILD SUCCESSFUL in 36s`로 통과했습니다.

## 발견 사항

| Severity | Finding | Evidence | Status |
|---|---|---|---|
| P0 | 없음 | diff, compile/test 실행, 수정 파일 MockK scan 검토 | PASS |
| P1 | 없음 | stable collaborator는 class-level field이고 `@BeforeEach`에서 `clearMocks(...)`로 reset됩니다 | PASS |
| P2 | scenario data와 capture slot은 method-local로 유지 | slot과 per-test payload는 재사용 collaborator가 아니라 scenario-specific 값입니다 | 허용된 예외 |

## 판정

P0/P1 = 0. 최종 검증 뒤 PR 생성 가능한 상태입니다.
