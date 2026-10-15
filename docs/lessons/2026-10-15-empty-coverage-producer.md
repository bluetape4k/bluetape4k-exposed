# Kover 보고서를 만드는 테스트 작업이 없는 변경의 coverage 처리

## 배경

[Exposed #910](https://github.com/bluetape4k/bluetape4k-exposed/issues/910)은 설정 전용 변경에서 `Coverage Report`가 실패한 문제를 다룬다. [실패한 CI 실행](https://github.com/bluetape4k/bluetape4k-exposed/actions/runs/38678808567)은 `.github/dependabot.yml`만 바뀌어 모듈 테스트 작업을 하나도 선택하지 않았다. PR 변경은 문서 전용이 아니어서 `coverage-report` 작업이 실행됐지만, `coverage-artifacts`에는 Kover XML이 없었다.

`aggregate-kover-coverage.py`는 `no Kover XML reports found`로 실패했고, 아티팩트 업로드도 입력 파일이 없다고 보고했다. 이는 제품 테스트 실패가 아니라, Kover 보고서를 만드는 테스트 작업이 없는 경로와 작업이 선택됐지만 보고서가 누락된 경로를 워크플로가 구분하지 못한 문제다. `coverage-report`의 기존 조건은 모듈 테스트 선택 여부 대신 문서 전용 변경인지만 확인했다.

## 결정

`coverage-enabled` 출력은 Kover 보고서를 만드는 테스트 작업의 경로 필터에서 계산한다. 전역 모듈 변경, Kover 보고서를 올리는 모듈 테스트, JDBC/R2DBC fixture 변경은 coverage 집계를 선택한다. `workflow_dispatch`에서는 기존 테스트 작업 조건이 모든 모듈 테스트를 실행하므로 집계도 선택한다. `benchmark`만 선택된 경로는 Kover 보고서를 만들지 않아 제외한다.

Kover 보고서 생성 작업이 선택되면 `coverage-report` 작업을 계속 실행하고, 보고서가 없거나 손상됐을 때 집계가 실패하는 동작을 유지한다. 보고서 생성 작업이 없으면 집계를 건너뛰고, `CI Status`의 `Step Summary`에 coverage를 `N/A`로 기록한다. 빈 artifact 디렉터리, instruction counter가 없는 XML, 손상 XML은 계속 실패한다.

## 검증과 남은 경계

CI matrix 계약 테스트는 `coverage-enabled`의 Boolean truth table로 설정 전용, benchmark 전용, docs 전용, docs와 모듈 파일이 함께 바뀐 경우, 모듈 변경, `workflow_dispatch`를 확인한다. `some-with-excludes` filter는 변경 파일마다 positive pattern 일치와 같은 파일의 negative pattern 제외를 적용한 뒤, 조건을 만족하는 파일이 하나라도 있으면 filter를 선택한다. 따라서 docs 파일의 제외 패턴이 함께 변경된 모듈 파일을 가리지 않도록 테스트 오라클도 파일별로 평가한다. 이 동작은 [dorny/paths-filter README](https://github.com/dorny/paths-filter#usage)에 설명되어 있다. 계약 검증기는 모든 생성 작업 filter가 `coverage-enabled`에 연결되고 각 producer가 수동 실행에서도 선택되는지, 생성 작업이 없는 경로가 `N/A`를 기록하는지 검사한다. `.github/scripts/test_aggregate_kover_coverage.py`는 생성 작업이 선택됐는데 보고서가 없거나 유효하지 않으면 집계가 계속 실패하는 계약을 고정한다.

실패 원인은 기존 PR #909의 head `327783528c1cc8f10bd127541678327b8b9b28fa`에서 수집한 CI 기록으로 확인했다. 이 수정 브랜치에서는 로컬 계약 테스트와 워크플로 정적 검증을 수행하며, 수정 브랜치의 원격 CI는 아직 실행되지 않았다. 병합 후 기준 checkout 동기화와 GNO 색인은 별도 마무리 단계다.

## 다음 변경에서 확인할 점

새 테스트 작업이나 coverage 아티팩트 경로를 추가할 때 `COVERAGE_TRIGGER_FILTERS`와 해당 작업의 경로 필터 및 아티팩트 업로드를 함께 갱신한다. 보고서 생성 작업이 선택되는 경로와 생성 작업이 0개인 설정 경로를 계약 테스트하고, 선택된 작업의 보고서 누락·손상 검사는 계속 실패하도록 유지한다.
