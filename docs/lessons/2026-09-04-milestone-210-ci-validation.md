# Milestone 2.1.0 CI 검증 lesson

## 재발 방지 규칙

- **전역 카탈로그 변경은 전체 매트릭스를 실행한다.** `settings.gradle.kts`,
  `gradle.properties`, `gradle/**`, 루트 빌드 파일, `buildSrc/**`, CI
  workflow/script 변경이 모듈별 path filter를 우회하면 일부 검증이 조용히
  생략된다. `all-modules` 출력과 각 모듈 조건을 함께 계약 테스트하고,
  `ci-status`와 write-behind 게이트에도 같은 신호를 전달한다.
- **벤치마크는 양성 경로를 별도로 고정한다.** 벤치마크 디렉터리 변경이 일반
  build 성공만으로 통과하지 않도록 `test`, `benchmarkClasses`, `detekt`를
  실행하는 job과 조건을 계약 테스트로 확인한다.
- **Kover 집계는 fail-closed로 유지한다.** 빈 디렉터리, 빈 파일, malformed
  XML, instruction counter 부재/음수/중복은 성공으로 취급하지 않는다.
  다운로드 artifact에는 `if-no-files-found: error`를 사용하고, aggregator는
  유효한 보고서와 양의 총 instruction 수를 요구한다.

## TDD 및 검증 근거

- 기존 path-filtered CI가 전역 변경에서 모듈 job을 생략할 수 있다는 검토가
  #789의 실패 가정이었다. synthetic fixture가 누락된 `all-modules` 전파를
  먼저 재현한 뒤 계약 검증을 추가했다.
- 기존 Kover aggregator가 `_No coverage reports found._`를 출력하고 exit 0을
  반환하는 동작이 #796의 RED 관찰이었다. 빈/오염 보고서 unittest를 먼저
  고정한 뒤 예외 기반 집계를 적용했다.
- 최초 workflow runtime mutation gate에서는 Git SHA를 receipt checksum으로
  잘못 전달했고, lane scope에는 절대 경로를 넣었다. 두 명령 모두 receipt를
  변경하지 않고 실패했으며, 이후 `--expected-head`에는 최신 receipt checksum,
  lane 입력에는 repo-relative scope만 사용하도록 수정했다.

## 다음 작업 전 확인

1. `python3 scripts/ci/validate_ci_matrix_contract_test.py`와 validator를
   항상 실행한다.
2. `python3 .github/scripts/test_aggregate_kover_coverage.py`로 fail-closed
   경계를 확인한다.
3. 전역 변경 PR에서는 exact-head hosted CI에서 모든 module/benchmark 및
   coverage artifact가 실제로 실행·집계됐는지 확인한다.
