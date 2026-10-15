# Issue #910 커버리지 CI 리뷰

## 범위와 기준

[`Issue #910`](https://github.com/bluetape4k/bluetape4k-exposed/issues/910)의 완료 조건과 `develop` 기준선 `5bb90aad40bae7a8f7a4b26a0c7ff4cf3663b042`부터 [`PR #911`](https://github.com/bluetape4k/bluetape4k-exposed/pull/911)에 포함된 변경을 검토했다. 범위는 Kover 커버리지 선택 계약과 0바이트 XML 회귀 테스트다. 정확한 검토 대상 HEAD는 Type E workflow receipt의 인라인 리뷰 기록에 결합한다. 이 문서는 변경을 작성한 주 세션의 자체 검토이며 독립 검토자의 판정을 대신하지 않는다.

## 결과

확인된 코드 결함은 없다. [`ci.yml`](../../.github/workflows/ci.yml)의 `coverage-enabled`는 Kover XML을 생성하는 테스트 경로와 `workflow_dispatch`를 반영한다. 선택된 생성 작업이 없으면 커버리지 집계를 건너뛰고 `CI Status`에 `N/A`를 남기며, 변경 감지 실패는 기존 실패 판정에 전달한다. [커버리지 선택 검증기](../../scripts/ci/validate_ci_matrix_contract.py)와 [CI matrix 계약 테스트](../../scripts/ci/validate_ci_matrix_contract_test.py)는 이 분기를 검사한다. [집계기 회귀 테스트](../../.github/scripts/test_aggregate_kover_coverage.py)는 생성 작업이 선택된 경우 보고서가 누락되거나 0바이트이거나 손상되면 집계가 실패하는 계약을 확인한다. 다중 변경 파일에서 제외 패턴을 평가하는 테스트는 [dorny/paths-filter 공식 README](https://github.com/dorny/paths-filter/blob/master/README.md)의 `some-with-excludes` 정의에 근거한다.

7-Tier 분류:

| 영역 | 판정 | 근거 |
|---|---|---|
| 보안 | N/A | 권한, 비밀, 입력 신뢰 경계 변경 없음 |
| 정확성·도메인 | PASS | 설정 전용 경로와 보고서 생성 경로를 분리하고 두 경로를 계약 테스트로 확인 |
| 복원력·수명주기 | N/A | 제품 실행 및 자원 수명주기 변경 없음 |
| 공개 API·ABI | N/A | JVM 공개 선언, Maven artifact 좌표, 의존성 변경 없음 |
| 테스트·호출자 | PASS | 0바이트 XML 거부 회귀와 CI matrix 계약 테스트 추가 |
| 성능 | N/A | 제품 실행 경로 변경 없음 |
| 문서·CI·배포 | PASS / PENDING | CI 조건과 요약 계약은 정적 검증 통과. 설정 전용 변경을 대상으로 한 호스티드 CI는 아직 확인되지 않음 |

## 검증과 남은 증거

- `.github/scripts/test_aggregate_kover_coverage.py`: 5개 테스트 통과
- `scripts/ci/validate_ci_matrix_contract_test.py`: 24개 테스트 통과
- `scripts/ci/validate_required_jobs_test.py`: 6개 테스트 통과
- CI matrix validator, `actionlint`, Ruff 검사·포맷 검사, `git diff --check`: 통과
- PR #911의 기존 실행 [38687095032](https://github.com/bluetape4k/bluetape4k-exposed/actions/runs/38687095032)은 HEAD `43eeb86638238ec574360ecacc335156391b9a15`에서 실행됐다. 이 실행은 이후 검토 HEAD의 증거로 재사용하지 않는다.
- Issue #910이 요구하는 설정 전용 경로의 호스티드 CI 증거는 미확인이다. 로컬 진리표는 이 경로의 기대 조건만 확인한다.
- 요청 모델은 `gpt-6-luna max`다. 실제 인라인 검토자의 모델 정보는 확인하지 못했다.

Inline verdict: PASS; P0=0, P1=0.

### 독립 코드 검토

- 최초 독립 `code-reviewer`는 head `e81c2cdb037b53aa49a79c87816ac4e839075c89`의 workflow, coverage 집계기, 회귀 테스트를 검토했다. 최종 독립 검토는 head `8667239591dbf6186d1b2da022b8ff03f0a883c4`에서 코드와 CI 계약을 다시 확인했다.
- 두 검토에서 코드 결함은 확인되지 않았고 P0/P1은 0건이다. 최종 검토가 지적한 이 문서의 오래된 검토 상태 표현을 이 수정에서 바로잡는다.
- 설정 전용 변경의 hosted exact-head CI 증거는 여전히 없다. 이 검증 공백이 해소되기 전에는 merge-ready로 판정하지 않는다.
- 독립 검토자의 실제 모델 정보는 실행 결과에서 확인할 수 없다. 요청 모델은 `gpt-6-luna max`다.
