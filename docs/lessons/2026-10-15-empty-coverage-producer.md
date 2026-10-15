# Kover 보고서를 만드는 테스트 작업이 없는 변경의 coverage 처리

## 배경

[Exposed #910](https://github.com/bluetape4k/bluetape4k-exposed/issues/910)은 설정 전용 변경에서 `Coverage Report`가 실패한 문제를 다룬다. [실패한 CI 실행](https://github.com/bluetape4k/bluetape4k-exposed/actions/runs/38678808567)은 `.github/dependabot.yml`만 바뀌어 모듈 테스트 작업을 하나도 선택하지 않았다. PR 변경은 문서 전용이 아니어서 `coverage-report` 작업이 실행됐지만, `coverage-artifacts`에는 Kover XML이 없었다.

`aggregate-kover-coverage.py`는 `no Kover XML reports found`로 실패했고, 아티팩트 업로드도 입력 파일이 없다고 보고했다. 이는 제품 테스트 실패가 아니라, Kover 보고서를 만드는 테스트 작업이 없는 경로와 작업이 선택됐지만 보고서가 누락된 경로를 워크플로가 구분하지 못한 문제다. `coverage-report`의 기존 조건은 모듈 테스트 선택 여부 대신 문서 전용 변경인지만 확인했다.

## 결정

`coverage-enabled` 출력은 Kover 보고서를 만드는 테스트 작업의 경로 필터에서 계산한다. 전역 모듈 변경, Kover 보고서를 올리는 모듈 테스트, JDBC/R2DBC fixture 변경은 coverage 집계를 선택한다. `workflow_dispatch`에서는 기존 테스트 작업 조건이 모든 모듈 테스트를 실행하므로 집계도 선택한다. `benchmark`만 선택된 경로는 Kover 보고서를 만들지 않아 제외한다.

Kover 보고서 생성 작업이 선택되면 `coverage-report` 작업을 계속 실행하고, 보고서가 없거나 손상됐을 때 집계가 실패하는 동작을 유지한다. 보고서 생성 작업이 없으면 집계를 건너뛰고, `CI Status`의 `Step Summary`에 coverage를 `N/A`로 기록한다. 빈 artifact 디렉터리, instruction counter가 없는 XML, 손상 XML은 계속 실패한다.

## 검증과 남은 경계

CI matrix 계약 테스트는 `coverage-enabled`의 Boolean truth table로 설정 전용, benchmark 전용, docs 전용, docs와 모듈 파일이 함께 바뀐 경우, 모듈 변경, `workflow_dispatch`를 확인한다. `some-with-excludes` filter는 변경 파일마다 positive pattern 일치와 같은 파일의 negative pattern 제외를 적용한 뒤, 조건을 만족하는 파일이 하나라도 있으면 filter를 선택한다. 따라서 docs 파일의 제외 패턴이 함께 변경된 모듈 파일을 가리지 않도록 테스트 오라클도 파일별로 평가한다. 이 동작은 [dorny/paths-filter README](https://github.com/dorny/paths-filter#usage)에 설명되어 있다. 계약 검증기는 모든 생성 작업 filter가 `coverage-enabled`에 연결되고 각 producer가 수동 실행에서도 선택되는지, 생성 작업이 없는 경로가 `N/A`를 기록하는지 검사한다. `.github/scripts/test_aggregate_kover_coverage.py`는 생성 작업이 선택됐는데 보고서가 없거나 유효하지 않으면 집계가 계속 실패하는 계약을 고정한다.

실패 원인은 기존 PR #909의 head `327783528c1cc8f10bd127541678327b8b9b28fa`에서 수집한 CI 기록으로 확인했다. 수정 PR #911의 head `34440fd4e4a5bcd2b7433ec8a356b5cfdf0b4455`에서 실행한 [호스티드 CI run](https://github.com/bluetape4k/bluetape4k-exposed/actions/runs/38696808398)은 40개 job 중 39개가 성공하고 `Docs-only validation` 1개가 건너뛰었다. 29개 모듈 테스트와 `Coverage Report`, `CI Status`는 성공했다.

설정 전용 변경 경로는 임시 PR #912의 head `3b57a2db3bd20733c3deb2432ad00c0881fe6d3a`에서 별도로 확인했다. [호스티드 CI run](https://github.com/bluetape4k/bluetape4k-exposed/actions/runs/38705050851)은 40개 job 중 8개가 성공하고 32개가 건너뛰었으며 실패는 없었다. 29개 `Test /` job은 모두 건너뛰었으므로 테스트 성공으로 세지 않는다. `Coverage Report`도 건너뛰었고, `CI Status`는 `Kover coverage: N/A — coverage report를 생성할 테스트 job이 선택되지 않았습니다.`를 기록하고 성공했다. 임시 PR은 병합하지 않고 닫았다.

독립 코드 검토자는 PR #911의 head `34440fd4e4a5bcd2b7433ec8a356b5cfdf0b4455`에서 코드 P0/P1 결함을 찾지 못했으며, review와 lesson의 CI 상태가 오래됐다는 P2를 보고했다. 이 문서와 review 기록에서 그 상태를 바로잡았다. 사용자가 요청한 모델은 `gpt-6-luna max`이며, 이전 독립 `code-reviewer` lane이 보고한 실제 실행 모델은 `gpt-5.6-luna max`다. 두 값을 구분해 기록하고 해당 검토를 요청 모델로 실행한 것으로 간주하지 않는다. 위 실행들은 각각 기록된 SHA에 대한 증거다. 머지 판정에는 최신 PR head의 CI와 리뷰 상태를 확인한다.

## 다음 변경에서 확인할 점

새 테스트 작업이나 coverage 아티팩트 경로를 추가할 때 `COVERAGE_TRIGGER_FILTERS`와 해당 작업의 경로 필터 및 아티팩트 업로드를 함께 갱신한다. 보고서 생성 작업이 선택되는 경로와 생성 작업이 0개인 설정 경로를 계약 테스트하고, 선택된 작업의 보고서 누락·손상 검사는 계속 실패하도록 유지한다.
