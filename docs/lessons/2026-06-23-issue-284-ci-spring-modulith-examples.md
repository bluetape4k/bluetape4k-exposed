# 이슈 284 CI Spring Modulith 및 예제 교훈

Date: 2026-06-23
Issue: #284

## 교훈

문서화된 example과 demo 모듈에는 명시적인 CI 소유권이 필요합니다. compile-only coverage는 예제가 여전히 실행됨을 증명하지 못하며, 특히 문서화된 예제가 Testcontainers에 의존하면 주요 모듈 matrix에서 조용히 이탈할 수 있습니다.

## 지침

- 모듈 또는 예제에 자체 CI lane을 부여할 때는 path-filter output, 전용 test job, coverage artifact, 최종 status `needs` 항목을 함께 추가합니다.
- 새 CI lane은 filter에 workflow/build-file 경로를 포함해야 lane을 추가한 PR이 GitHub Actions에서 이를 증명할 수 있습니다.
- example job은 example source tree와 예제가 실행하는 모듈/build file 양쪽에서 trigger되어야 합니다. 그렇지 않으면 모듈 변경이 문서화된 사용 경로를 우회할 수 있습니다.
- 예제가 지원되는 사용 경로로 소개된다면 example/demo job을 PR CI와 Nightly workflow 모두에서 보이게 유지합니다.
- Docker-backed example 테스트는 순차 실행하고, 로컬 Docker를 사용할 수 없을 때는 local compile/testClasses 근거와 전체 Testcontainers 근거를 분리합니다.
- Docker-heavy Nightly example 테스트는 smoke-safe와 full suite로 명시적으로 나누지 않는 한 full-scope guard 아래에 둡니다.
- push 전에 `actionlint`, 구조적 YAML 검사, escaped-quote fixed-string 검색으로 workflow 변경을 검증합니다.

## 후속 조치

이후 workflow가 example 모듈을 더 분리한다면, 누락된 coverage를 정확한 example group까지 추적할 수 있도록 artifact 이름을 충분히 세분화합니다.
