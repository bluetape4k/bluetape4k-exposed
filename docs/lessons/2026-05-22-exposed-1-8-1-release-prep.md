# Exposed 1.8.1 Release Prep

## 배경

ecosystem이 `bluetape4k-bom` 1.9.0 release line으로 이동하기 전에
`bluetape4k-exposed` 1.8.1을 publication해야 합니다.

## 결정

`snapshotVersion=`으로 1.8.1 release tag를 준비하고 이 release는
`io.github.bluetape4k:bluetape4k-bom:1.8.0`에 유지합니다. 이후 1.9.0 release line은
`bluetape4k-bom:1.9.0`으로 옮깁니다.

## 결과

release metadata는 이제 release workflow gate와 일치합니다.

- `baseVersion=1.8.1`
- `snapshotVersion=`
- `bluetape4kVersion=1.8.0`
- `gradle/libs.versions.toml` `bluetape4k-bom = "1.8.0"`

## 검증

- `actionlint .github/workflows/release.yml .github/workflows/publish-snapshot.yml .github/workflows/nightly-tests.yml .github/workflows/ci.yml`
- `./gradlew generatePomFileForBluetapeExposedPublication --no-daemon --no-configuration-cache --no-build-cache`
- 31개 publication POM을 생성해 `SNAPSHOT`을 scan했습니다.
- 생성 publication POM이 artifact version `1.8.1` 및 `bluetape4k-bom:1.8.0`을 사용하는지
  확인했습니다.
- `./gradlew build -x test -x koverVerify publishToMavenLocal --parallel --no-daemon --no-configuration-cache --no-build-cache`
- 사용하지 않는 `opentelemetry-bom-alpha` snapshot catalog reference를 현재 Maven Central
  release `1.62.0-alpha`로 pin해 제거했습니다.

알려진 후속 작업: tag `1.8.1` push 후 GitHub release workflow를 실행해야 합니다.

## 향후 지침

1.8.1 release tag가 `bluetape4k-bom:1.8.1-SNAPSHOT` 또는 `1.9.0`을 사용하지 않게
합니다. immutable 1.8.1 release를 publication한 뒤 1.9.0 development branch를 시작합니다.
