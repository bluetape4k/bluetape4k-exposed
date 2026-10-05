# Issue #890 `buildSrc` configuration-cache CI 검증

## 배경

[#890](https://github.com/bluetape4k/bluetape4k-exposed/issues/890)은 strict configuration-cache 저장과 재사용을 검증하는 `CheckProductionAbiConfigurationCacheTest`를 PR CI에 연결하도록 요청했다. CI 로그와 보존 artifact에서 실제 testcase 실행을 확인할 수 있어야 했다.

## 결정

기존 필수 `build` job에 테스트 클래스 실행을 추가하고, 생성된 JUnit XML에 실제 testcase 이름이 있는지 확인한다. 테스트 결과 XML은 실패 여부와 관계없이 artifact로 업로드한다. job 이름은 유지해 기존 필수 check context를 보존한다.

## 결과

CI 실행 단계를 추가했고, JUnit testcase 이름을 확인한 뒤 결과 XML을 artifact로 보존하도록 했다. PR CI artifact는 아직 생성되지 않았다.

## 교정한 workflow helper 사용 오류

`bluetape-flow.py`의 `--expected-head`에 Git commit SHA를 전달해 `expected receipt head must be lowercase SHA-256` 오류가 발생했다. 이 값은 Git HEAD가 아니라 직전 workflow receipt의 64자리 SHA-256이다. receipt를 다시 확인한 뒤 올바른 checksum으로 승인 단계를 완료했다.

`lane-complete` 증거 summary를 256자보다 길게 작성해 helper가 입력을 거부했다. receipt는 변경되지 않았으며, 리뷰 근거를 줄여 다시 제출했다.

## 검증

`./gradlew -p buildSrc test --tests CheckProductionAbiConfigurationCacheTest --no-configuration-cache --no-daemon` 실행에서 testcase 1개가 실패, 오류, 건너뜀 없이 통과했다. `buildSrc/build/test-results/test/TEST-CheckProductionAbiConfigurationCacheTest.xml`에 testcase가 기록됐고, `actionlint .github/workflows/ci.yml`도 통과했다. 원격 PR CI는 실행하지 않았다.

## 향후 기준

1. configuration-cache 회귀를 CI에서 다룰 때는 회귀 테스트 클래스를 명시적으로 실행하고, JUnit XML testcase까지 확인해 실행 사실을 남긴다. XML artifact는 실패 시에도 업로드한다.
2. workflow helper의 `--expected-head`에는 각 상태 변경 직전 최신 receipt checksum을 사용한다. Git SHA와 receipt SHA-256을 혼용하지 않는다.
3. receipt helper 입력은 해당 단계의 길이 제한에 맞춰 간결하게 작성한다. 긴 근거는 허용된 evidence artifact로 관리하고 summary에는 판정과 핵심 범위만 남긴다.
