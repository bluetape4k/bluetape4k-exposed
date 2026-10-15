# Kover 보고서를 만드는 테스트 작업이 없는 변경의 coverage 처리

## 배경

[Exposed #910](https://github.com/bluetape4k/bluetape4k-exposed/issues/910)은 설정 전용 변경에서 `Coverage Report`가 실패한 문제를 다룬다. [실패한 CI 실행](https://github.com/bluetape4k/bluetape4k-exposed/actions/runs/38678808567)은 `.github/dependabot.yml`만 바뀌어 모듈 테스트 작업을 하나도 선택하지 않았다. `aggregate-kover-coverage.py`는 `no Kover XML reports found`로 실패했고, 아티팩트 업로드도 입력 파일이 없다고 보고했다.

이는 제품 테스트 실패가 아니라 Kover 보고서를 만드는 작업이 없는 경로와 작업이 선택됐지만 보고서가 누락된 경로를 워크플로가 구분하지 못한 문제다. 전자는 coverage 집계를 건너뛰어야 하고, 후자는 계속 실패해야 한다.

## 결정

`coverage-enabled` 출력은 Kover XML을 생성하는 테스트 작업의 경로 필터에서 계산한다. 전역 모듈 변경, Kover 보고서를 올리는 모듈 테스트, JDBC/R2DBC fixture 변경은 coverage 집계를 선택한다. `workflow_dispatch`에서는 테스트 작업 조건이 모든 모듈 테스트를 실행하므로 집계도 선택한다. `benchmark`만 선택된 경로는 Kover 보고서를 만들지 않아 제외한다.

Kover 보고서 생성 작업이 선택되면 `coverage-report`를 계속 실행하고, 보고서가 없거나 손상됐을 때 집계가 실패하는 동작을 유지한다. 생성 작업이 선택되지 않은 경우에는 coverage applicability가 명시적으로 `false`일 때만 `CI Status`의 `Step Summary`에 `N/A`를 기록한다. `true`는 coverage가 적용됨을 뜻하고 요약을 추가하지 않는다. 빈 값이나 예상 밖 값은 CI를 실패시킨다. 변경 감지 job이 실패하면 `N/A`를 기록하지 않고 기존 최종 상태 검사가 실패를 전파한다.

이 fail-closed 규칙은 [runtime helper](../../scripts/ci/report_coverage_applicability.py)에서 처리하고 [CI matrix 계약 테스트](../../scripts/ci/validate_ci_matrix_contract_test.py)에서 검증한다. 집계 작업 선택 조건은 [워크플로 계약 검증기](../../scripts/ci/validate_ci_matrix_contract.py)로 확인한다. 0바이트 XML, 누락 XML, 손상 XML 검사는 그대로 실패해야 한다.

## 검증과 남은 경계

- coverage 선택 테스트는 설정 전용, benchmark 전용, docs 전용, docs와 모듈 파일의 동시 변경, 모듈 변경, `workflow_dispatch`를 확인한다. `some-with-excludes`는 파일마다 positive pattern과 negative pattern을 평가하므로, docs 파일의 제외 패턴이 함께 바뀐 모듈 파일을 가리지 않도록 테스트 oracle도 파일별로 적용한다.
- applicability 회귀 테스트는 성공한 변경 감지에서 `true`와 `false`를 구분하고, 빈 값·예상 밖 값·대소문자 오류를 거부한다. 변경 감지 실패 시에는 `N/A`를 만들지 않는다.
- 수정 후 로컬 검증: `scripts/ci/validate_ci_matrix_contract_test.py` 27개, `scripts/ci/validate_required_jobs_test.py` 6개, `.github/scripts/test_aggregate_kover_coverage.py` 5개 통과. CI matrix validator, Python 컴파일, Ruff, `actionlint`, `git diff --check`도 통과했다. 수정 head의 hosted CI와 독립 코드 검토는 PR #911의 live checks에서 확인한다.
- 이전 full-matrix 실행 [38696808398](https://github.com/bluetape4k/bluetape4k-exposed/actions/runs/38696808398)은 head `34440fd4e4a5bcd2b7433ec8a356b5cfdf0b4455`의 수정 전 코드다. 40개 job 중 39개 성공, 1개 skip, 실패 0개였고 29개 모듈 테스트, `Coverage Report`, `CI Status`가 성공했다.
- 설정 전용 경로는 임시 PR #912의 [run 38705050851](https://github.com/bluetape4k/bluetape4k-exposed/actions/runs/38705050851)에서 확인했다. head `3b57a2db3bd20733c3deb2432ad00c0881fe6d3a`에 대해 40개 job 중 8개 성공, 32개 skip, 실패 0개였고, 29개 모듈 테스트와 `Coverage Report`는 실행되지 않았다. `CI Status`가 `N/A`를 기록해 설정 전용 경로만 증명했다. PR #912는 닫혔고 병합되지 않았다.
- 사용자는 `gpt-6-luna max`를 요청했다. head `49c4af9`와 `39546079`의 독립 reviewer lane은 실제 실행 모델을 각각 `gpt-5.6-luna max`로 보고했다. 이를 요청 모델 실행과 동일하게 취급하지 않는다.

## 다음 변경에서 확인할 점

새 테스트 작업이나 coverage 아티팩트 경로를 추가할 때 `COVERAGE_TRIGGER_FILTERS`, 작업별 경로 필터, artifact upload를 함께 갱신한다. producer가 선택되는 경로와 0개인 설정 경로를 각각 계약 테스트하고, applicability 출력이 비어 있거나 예상 밖이면 실패하는 규칙을 유지한다. producer가 선택된 경로의 보고서 누락·손상은 계속 실패해야 한다.
