# Issue #910 커버리지 CI 리뷰

## 범위와 기준

[`Issue #910`](https://github.com/bluetape4k/bluetape4k-exposed/issues/910)의 완료 조건과 `develop` 기준선 `5bb90aad40bae7a8f7a4b26a0c7ff4cf3663b042`부터 [`PR #911`](https://github.com/bluetape4k/bluetape4k-exposed/pull/911)에 포함된 변경을 검토했다. 독립 7-Tier 검토는 head `49c4af989ab9306dd6ac1f5af4a957fba0abe3d1`의 워크플로와 계약 검증을 확인했다. 여기 기록한 이전 head의 CI 결과는 각각 해당 SHA에만 적용한다.

## 검토 결과

독립 검토에서 MEDIUM 두 건을 찾았다.

1. `.github/workflows/ci.yml`은 `CHANGES_RESULT=success`일 때 `COVERAGE_REQUIRED`가 정확히 `true`가 아닌 모든 값을 `N/A`로 처리했다. 빈 값과 예상 밖 값도 통과할 수 있는 fail-open 판정이었다. [coverage applicability helper](../../scripts/ci/report_coverage_applicability.py)를 추가해 `true`와 `false`만 허용하고, 그 밖의 값은 실패하도록 수정했다. 변경 감지 자체가 성공하지 않으면 `N/A`를 쓰지 않으며 기존 `CI Status` 결과 검사가 해당 job 실패를 처리한다.
2. PR #911 본문이 실제 head보다 오래된 SHA와 CI 실행을 현재 증거처럼 표시했다. 새 exact-head 검증을 마친 뒤 live PR 본문을 갱신해야 한다.

첫 번째 수정은 회귀 테스트와 워크플로 계약 검사에 반영했다. 수정 후 병합 판정은 현재 PR head의 CI와 독립 검토 결과를 기준으로 한다.

## 7-Tier 분류

| 영역 | 판정 | 근거 |
|---|---|---|
| 보안 | N/A | 권한, 비밀, 입력 신뢰 경계 변경 없음 |
| 정확성·도메인 | 수정됨 | coverage applicability의 알 수 없는 값이 N/A로 통과하던 경로를 닫고 `true`/`false` 계약을 테스트 |
| 복원력·수명주기 | N/A | 제품 실행 및 자원 수명주기 변경 없음 |
| 공개 API·ABI | N/A | Kotlin 공개 선언, JVM ABI, Maven 좌표, 의존성 변경 없음 |
| 테스트·호출자 | PASS | 빈 값, 알 수 없는 값, 대소문자 오류는 실패하고 명시적 `false`만 N/A가 되는 회귀 테스트 추가 |
| 성능 | N/A | 제품 실행 경로 변경 없음 |
| 문서·CI·배포 | 검증 대기 | 워크플로 수정과 구조 검증은 통과했으며, 수정 head의 hosted CI와 독립 리뷰는 PR #911에서 확인 |

## 검증 증거와 경계

- `scripts/ci/validate_ci_matrix_contract_test.py`: 25개 테스트 통과
- `scripts/ci/validate_required_jobs_test.py`: 6개 테스트 통과
- CI matrix validator, Python 컴파일, `actionlint`, `git diff --check`: 통과
- 기존 exact-head 실행 [38731702494](https://github.com/bluetape4k/bluetape4k-exposed/actions/runs/38731702494)은 head `49c4af989ab9306dd6ac1f5af4a957fba0abe3d1`의 수정 전 코드다. 이 run은 이 문서에 기록한 fail-open 수정의 hosted CI 증거가 아니다.
- 기존 full-matrix 실행 [38696808398](https://github.com/bluetape4k/bluetape4k-exposed/actions/runs/38696808398)은 head `34440fd4e4a5bcd2b7433ec8a356b5cfdf0b4455`에서 40개 job 중 39개 성공, 1개 skip, 0개 실패였다. 29개 모듈 테스트, `Coverage Report`, `CI Status`가 성공했고 `Docs-only validation`은 skip이었다. 수정 전 코드의 결과다.
- 설정 전용 임시 PR #912의 [hosted CI run 38705050851](https://github.com/bluetape4k/bluetape4k-exposed/actions/runs/38705050851)은 head `3b57a2db3bd20733c3deb2432ad00c0881fe6d3a`에서 40개 job 중 8개 성공, 32개 skip, 0개 실패였다. 29개 모듈 테스트와 `Coverage Report`는 skip됐고 `CI Status`가 `N/A`를 기록했다. 이는 설정 전용 경로의 producer 0개 동작만 증명한다. PR #912는 닫혔고 병합되지 않았다.
- 사용자가 요청한 모델은 `gpt-6-luna max`다. 이전 독립 `code-reviewer` lane이 보고한 실제 실행 모델은 `gpt-5.6-luna max`로 요청과 다르다. 해당 검토를 요청 모델로 실행한 것으로 기록하지 않는다. 수정 후 독립 검토의 모델은 결과가 확인되면 별도로 기록한다.

현재 변경에서 Kotlin 제품 코드, public API/ABI, dependency, 성능 경로는 바뀌지 않았다. 설정 전용 CI에서 skip된 테스트는 통과한 테스트로 집계하지 않는다. 최신 병합 준비 증거와 CG-15 상태는 PR #911의 live 본문과 체크를 기준으로 갱신한다.
