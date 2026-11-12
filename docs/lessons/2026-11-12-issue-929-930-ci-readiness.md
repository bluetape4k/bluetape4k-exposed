# #929·#930 CI 회귀와 ClickHouse readiness 관측성 교훈

## 배경과 결정

7-Tier 리뷰에서 두 개의 P2 경계를 확인했다. `VerifyGuavaDependencySubmissionMetadataTest`는
detached Dependency Submission graph를 검증하지만 hosted CI의 명시적 `buildSrc` 회귀 단계에
연결되어 있지 않았다. ClickHouse job은 제품 테스트 전체를 최대 8회 재시도해 컨테이너
startup/readiness 초기 실패를 최종 green 결과에 합쳤다.

두 경계를 각각 좁은 회귀 검사로 분리했다.

- Guava TestKit을 required hosted `buildSrc` 단계에 추가하고 JUnit XML과 테스트명을 확인한다.
- detached graph fixture의 parent 목록을 `build.gradle.kts` production allowlist와 비교해 목록
  추가·삭제·중복을 fail-closed로 검출한다.
- ClickHouse는 제품 suite보다 readiness probe를 먼저 실행하고, probe 시도별 로그를
  `build/reports/clickhouse-readiness/`에 보존한다.
- readiness가 성공한 뒤 제품 suite는 한 번 실행해 제품 실패와 컨테이너 startup 실패를 분리한다.

## 결과와 검증

- #929: `VerifyGuavaDependencySubmissionMetadataTest`가 `BUILD SUCCESSFUL`로 통과했고,
  JUnit XML에 테스트 case가 생성됐다.
- #930: `ClickHouseReadinessTest` 1개와 전체 `bluetape4k-exposed-clickhouse` 257개
  (1 skipped)가 통과했다.
- `actionlint .github/workflows/ci.yml .github/workflows/nightly-tests.yml` 통과
- `:bluetape4k-exposed-clickhouse:checkKotlinAbi checkProductionAbi` 통과
- `git diff --check develop...HEAD` 통과
- hosted exact-head CI run `43138584191`에서 `VerifyGuavaDependencySubmissionMetadataTest` JUnit
  artifact가 1/1 pass로 생성됐다.
- hosted `clickhouse-readiness-diagnostics` artifact에 attempt 1 timeout과 attempt 2
  `readiness=passed`가 함께 보존됐고, readiness 통과 후 제품 suite가 성공했다.
- 이 증거를 확인한 뒤에도 merge 전까지 #929·#930은 열린 상태로 유지한다.

## 놓친 점과 교정

리뷰 당시 green 재시도 결과만으로는 ClickHouse readiness 실패의 원인과 제품 테스트 결과를
구분할 수 없었다. 또한 로컬 TestKit 통과만으로 required CI가 회귀를 감지한다고 볼 수 없었다.
이번 변경은 실행 범위와 artifact를 분리해 이 두 가지를 직접 관찰하도록 했다.

## 향후 보호 기준

1. detached dependency graph를 추가하거나 production replacement allowlist를 변경할 때는
   fixture와 allowlist 일치 검사를 함께 유지한다.
2. Testcontainers job은 infrastructure readiness retry와 제품 테스트 retry를 분리하고,
   모든 readiness 시도의 로그와 시도 번호를 artifact로 보존한다.
3. hosted exact-head CI가 새 회귀 단계를 실행하고 artifact를 업로드했는지 확인하기 전에는
   #929·#930을 완료로 닫지 않는다.
