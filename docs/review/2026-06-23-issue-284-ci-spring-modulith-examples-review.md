# 이슈 284 CI Spring Modulith 및 예제 리뷰

날짜: 2026-06-23
범위: `.github/workflows/ci.yml`, `.github/workflows/nightly-tests.yml`
이슈: #284

## 판정

P0 지적 사항: 0
P1 지적 사항: 0

워크플로 차이는 spring-modulith 모듈과 문서화된 예제 및 데모 테스트 스위트에 정식 CI 및 Nightly 검증 범위를 추가한다. 새 작업은 커버리지 집계와 최종 상태 게이트에 연결되며, 아티팩트에는 전용 이름을 사용하므로 업로드 누락이 기존 커버리지 요약 흐름에 계속 드러난다.

## 리뷰 참고 사항

- CI 경로 필터는 이제 `spring-modulith` 및 `examples` 출력을 노출하며, 이 출력은 `spring-boot/spring-modulith/**`와 `examples/**`를 대상으로 하는 작업을 실행한다.
- `spring-modulith` CI 필터에는 기반 JDBC/core 모듈과 워크플로/빌드 파일이 포함되어, 이 PR과 향후 의존성 경로 변경이 실제로 새 검증 경로를 실행하도록 한다.
- `examples` CI 필터에도 기반 예제 의존성과 워크플로/빌드 파일이 포함된다. 해당 경로는 `exposed/bigquery/**`, `exposed/clickhouse/**`, `spring-boot/jdbc/**`, `spring-boot/r2dbc/**`, 워크플로 YAML, 루트 Gradle 스크립트, `gradle/**` 및 `buildSrc/**`이다.
- `test-spring-modulith`는 `:bluetape4k-exposed-spring-modulith:test`를 실행하고 `test-results-spring-modulith`와 `coverage-spring-modulith`를 업로드한다.
- `test-examples`는 BigQuery 드라이런 예제, ClickHouse OLTP/OLAP 예제 및 두 Spring Boot 데모 테스트를 실행하며, Docker 기반 예제에는 Testcontainers 환경 변수를 적용한다.
- Nightly `test-examples`는 기존의 Docker 부하가 큰 전체 범위 가드를 따르므로 일일 스모크 실행에 ClickHouse Testcontainers 예제 부하가 추가되지 않는다.
- CI 및 Nightly의 `coverage-report`와 최종 상태 작업은 `needs`에 새 작업 이름을 포함한다. 따라서 추가된 검증 경로를 확인하지 않고는 커버리지/상태 처리가 완료될 수 없다.
- GitHub Actions 표현식 인용은 일반적인 `${{ needs.changes.outputs['spring-modulith'] == 'true' }}` 형식을 사용한다. 이스케이프된 따옴표 시퀀스는 발견되지 않았다.

## 검증

- `actionlint .github/workflows/ci.yml .github/workflows/nightly-tests.yml`
  - 결과: 성공.
- 두 워크플로 파일에 대한 Ruby YAML 구조 검사
  - 결과: 성공. `test-spring-modulith` 및 `test-examples` 작업과 커버리지/상태 `needs` 항목을 확인했다.
- `git diff --check`
  - 결과: 성공.
- `./gradlew :bluetape4k-exposed-spring-modulith:test :examples-exposed-bigquery-dry-run:test :exposed-spring-boot-jdbc-demo:test :exposed-spring-boot-r2dbc-demo:test --no-build-cache --console=plain --no-configuration-cache --no-daemon`
  - 결과: 성공.
  - 근거: spring-modulith 테스트 12개, JDBC 데모 테스트 26개, R2DBC 데모 테스트 25개 및 BigQuery 드라이런 예제 통과.
- `./gradlew :examples-exposed-clickhouse-oltp-olap:testClasses --no-build-cache --console=plain --no-configuration-cache --no-daemon`
  - 결과: 성공.

## 잔여 위험

- 현재 머신이 Testcontainers에 유효한 Docker 환경을 제공하지 않아 `:examples-exposed-clickhouse-oltp-olap:test`는 로컬에서 완료되지 못했다. 워크플로 작업은 의도적으로 Docker를 사용하도록 구성되어 있으므로 GitHub Actions CI/Nightly 러너에서 검증해야 한다.
