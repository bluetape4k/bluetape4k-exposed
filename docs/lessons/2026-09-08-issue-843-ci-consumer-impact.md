# test fixture 변경은 역방향 소비자 CI를 함께 깨운다

## 배경

이슈 #843에서 공통 모듈 경로만 검사하는 path filter가 Gradle의 역방향
`testImplementation` 관계를 알지 못해 소비자 테스트를 생략할 수 있음을 확인했다.
실제 `build.gradle.kts` 참조를 읽어 다음 fixture 소비자 job을 대조했다.

- JDBC fixture: `test-core`, `test-serialization`, `test-tink`, `test-jdbc-h2`,
  `test-spring-boot`, `test-spring-modulith`, `test-ktor-exposed`,
  `test-ktor-driver-timeout`, `test-lettuce`, `test-redisson`, `test-measured`,
  `test-postgresql-module`, `test-mysql8-module`, `test-cache`,
  `test-jdbc-caffeine`, `test-benchmark`, `test-timefold`,
  `test-spring-boot-batch`, `test-utils-batch`
- R2DBC fixture: `test-r2dbc-h2`, `test-spring-boot`, `test-ktor-exposed`,
  `test-ktor-driver-timeout`, `test-lettuce`, `test-redisson`, `test-cache`,
  `test-r2dbc-caffeine`, `test-utils-batch`

참조한 실제 소비 모듈은 JDBC의 `exposed/core`, `exposed/dao`,
`exposed/{jdbc,jdbc-caffeine,jdbc-lettuce,jdbc-redisson,jackson2,jackson3,fastjson2,tink,measured,postgresql,mysql8,timefold-solver-persistence,cache}`,
`spring-boot/{jdbc,spring-modulith,batch-exposed}`, `ktor/{jdbc,exposed}`,
`utils/batch/{core,jdbc}` 및 `benchmark/exposed-benchmark`이다. R2DBC는
`exposed/{r2dbc,r2dbc-caffeine,r2dbc-lettuce,r2dbc-redisson,cache}`,
`spring-boot/r2dbc`, `ktor/{r2dbc,exposed}`, `utils/batch/{core,r2dbc}`와
`utils/batch`에서 참조했다.

기존 `jdbc` filter에는 `exposed/tenant-jdbc/**`가 함께 있어 fixture 변경과
tenant JDBC 변경을 구분할 수도 없었다. 이는 작은 경로 필터의 비용 최적화가
실제 소비자 검증을 보장하지 못한다는 놓친 점이었다.

## 결정

- `exposed/jdbc/**`와 `exposed/jdbc-tests/**`를 `jdbc-test-fixture`로,
  `exposed/r2dbc/**`와 `exposed/r2dbc-tests/**`를 `r2dbc-test-fixture`로
  명시한다.
- 실제 fixture를 `testImplementation` 또는 `testFixturesImplementation`으로
  사용하는 소비자 output에만 해당 filter를 OR로 연결한다. tenant JDBC 변경은
  기존 `jdbc` 경로를 유지하되 fixture 전용 소비자를 불필요하게 확장하지 않는다.
- `ci-status`는 changes output이 활성화한 모든 소비자 job이 `success`인지
  검사한다. `missing`, `skipped`, `cancelled`, 실패는 통과로 취급하지 않는다.
  문서 전용 PR의 기존 예외와 `all-modules` 경로는 유지한다.

## 결과

fixture 변경의 downstream 소비자 job이 path filter에서 누락되지 않도록
workflow output과 계약 검증기를 보강했다. positive fixture 경로와 tenant
negative 경로를 Python 테스트로 고정해, JDBC와 R2DBC fixture의 범위를
독립적으로 확인할 수 있다.

## 검증

- RED: fixture positive 테스트를 먼저 추가한 뒤 현재 YAML에서 JDBC 소비자
  output 18개(19개 job) 중
  `core`, `serialization`, `tink`, `measured`, `postgresql-module`,
  `mysql8-module`, `cache`, `utils-batch` 등이, R2DBC 소비자 output 8개(9개
  job) 중 `cache`와 `utils-batch`가 활성화되지 않아 두 테스트가 실패했다.
- GREEN: `python3.14 -m unittest discover -s scripts/ci -p '*_test.py' -v`로
  28개 테스트가 통과했다. `python3.14 scripts/ci/validate_ci_matrix_contract.py`는
  `CI global-change matrix contract is aligned`를 출력했다.
- `ruff check scripts/ci`, `ruff format --check scripts/ci`,
  `actionlint .github/workflows/ci.yml`, `git diff --check`가 모두 통과했다.
- Gradle, 실제 데이터베이스, 유료 외부 서비스 호출, workflow dispatch는
  실행하지 않았다.

## 향후 지침

새 모듈이 `*-jdbc-tests` 또는 `*-r2dbc-tests`를 test configuration에 추가하면
먼저 역방향 소비자 목록을 다시 읽고 fixture filter, output 연결, positive path
테스트를 함께 갱신한다. isolated module 경로와 문서 전용 예외를 보존하고,
`ci-status`의 `needs`와 결과 검사를 같은 변경에서 확인한다. 수동 Full Nightly
실행은 path-filter 계약의 대체 검증으로 사용하지 않는다.
