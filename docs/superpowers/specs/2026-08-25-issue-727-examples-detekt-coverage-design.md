# 이슈 #727 examples 정적검사 범위 확대 설계

## 문제

root `build.gradle.kts`가 `/examples/`의 모든 `Detekt` 입력을 `exclude("**")`로 제거한다. 현재 예제별 `detekt` task는 성공하지만 실제 분석 없이 `NO-SOURCE`로 끝나므로, nightly의 `Build & Detekt`와 XML artifact가 examples 품질을 증명하지 못한다.

## 목표

1. examples의 Kotlin production/test source가 실제 Detekt 분석 대상이 되도록 한다.
2. 생성 소스만 공통 제외하고 fixture·benchmark 예외는 파일/규칙 단위의 좁은 근거로 남긴다.
3. root `detekt`와 전용 `exampleDetekt`가 examples 실행·보고서 누락을 fail-closed로 검증하도록 한다.
4. nightly CI가 example lint 실행 범위와 비어 있지 않은 XML 보고서를 명시적으로 확인하도록 한다.
5. 활성화 후 발견되는 Kotlin pattern·assertion·logging·ID helper 잔여는 기존 bluetape4k helper로 정리하고, production `println`·`System.out`·`System.err`는 logger 경계로 교체한다.

## 경계와 보존 계약

- production API, persistence schema, runtime feature behavior는 바꾸지 않는다.
- `build.gradle.kts`, `.github/workflows/nightly-tests.yml`, examples의 필요한 Kotlin test/fixture와 좁은 Detekt baseline/allowlist만 변경한다.
- `bluetape4k-assertions`가 있는 test는 `shouldBe*` 계열을 사용한다. raw JUnit assertion은 Detekt가 검출할 수 있도록 rule/scan evidence를 고정한다.
- UUID 생성은 이미 존재하는 ecosystem helper를 재사용하고 새 wrapper/version pin은 추가하지 않는다.
- 운영 출력은 `KLogging`/기존 logger를 사용하며 `println`, `System.out`, `System.err`를 새로 남기지 않는다.
- README 동작 설명을 바꾸지 않으면 English/Korean parity는 source/docs 변경 없음의 N/A 근거로 기록한다.

## 선택한 구조

기존 모든 subproject `detekt` task를 유지하고, examples 경로에 대한 blanket exclusion을 제거한다. root에는 examples task를 묶는 `exampleDetekt` verification task를 추가해 다음을 검증한다.

- examples subproject 목록이 비어 있지 않다.
- 각 task가 생성한 non-empty XML report가 존재한다.
- examples-only invocation도 root aggregate와 같은 generated-source 예외와 report 계약을 따른다.

nightly의 static-analysis step은 `detekt exampleDetekt`를 실행하고 reports 아래 examples XML 목록을 출력·검증한다. 기존 root aggregate의 전체 report guard는 유지한다.

## 위험과 완화

- 기존 예제의 잠복 findings로 lint가 처음 실패할 수 있다. 먼저 RED 결과를 파일/line과 severity로 기록하고, 작은 helper/assertion/logging 교체를 한 번에 수행한다.
- Testcontainers/통합 fixture는 정적검사 대상에서 임의로 제외하지 않는다. 불가피한 generated/fixture 예외만 명시적 baseline 또는 path rule로 좁힌다.
- CI가 오래 걸릴 수 있으므로 local에서는 examples `detekt`를 순차 실행하고, nightly는 report guard와 artifact upload를 유지한다.

## 검증 계약

- baseline: 현재 examples tasks가 모두 `NO-SOURCE`임을 기록한다.
- RED: exclusion 제거 후 findings 또는 non-empty report 생성 여부를 확인한다.
- GREEN: examples `detekt`/`exampleDetekt`, root `detekt`, targeted examples compile/test, workflow YAML/actionlint, `git diff --check`를 검증한다.
- 7-Tier T1~T7와 Kotlin checklist에서 P0/P1=0이어야 하며, Testcontainers 경로는 실제 실행 여부에 따라 PASS 또는 concrete N/A로 남긴다.
