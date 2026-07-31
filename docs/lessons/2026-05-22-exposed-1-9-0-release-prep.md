# Exposed 1.9.0 Release Prep

## 배경

`bluetape4k-projects` 1.9.0이 release되어 Maven Central에서 보이므로 Exposed 1.9.0
release line은 1.8.x core BOM에서
`io.github.bluetape4k:bluetape4k-bom:1.9.0`으로 이동할 수 있었습니다.

## 결정

`baseVersion=1.9.0`, `snapshotVersion=`, Gradle property 및 version catalog를 모두
`bluetape4k-bom:1.9.0`에 pin한 상태로 release tag를 준비합니다.

## 결과

생성 publication metadata는 immutable `io.github.bluetape4k.exposed` 1.9.0 artifact를
publication하고 immutable `io.github.bluetape4k:bluetape4k-bom:1.9.0` dependency BOM을
import합니다.

## 검증

- `curl -fsSL https://repo.maven.apache.org/maven2/io/github/bluetape4k/bluetape4k-bom/1.9.0/bluetape4k-bom-1.9.0.pom`
- `actionlint .github/workflows/release.yml .github/workflows/publish-snapshot.yml .github/workflows/nightly-tests.yml .github/workflows/ci.yml`
- `./gradlew properties --no-configuration-cache --no-daemon --quiet`
- `./gradlew generatePomFileForBluetapeExposedPublication --no-daemon --no-configuration-cache --no-build-cache`
- `SNAPSHOT|examples|demo|benchmark`용 생성 POM scan
- `io.github.bluetape4k:bluetape4k-bom:1.9.0`용 생성 POM scan
- `./gradlew build -x test -x koverVerify publishToMavenLocal --parallel --no-daemon --no-configuration-cache --no-build-cache`
- `./gradlew :bluetape4k-exposed-jdbc:test --no-daemon --no-configuration-cache --no-build-cache`

## 향후 guard

`snapshotVersion`이 비어 있지 않거나 생성 POM이 snapshot upstream BOM을 import할 때
release tag를 만들지 않습니다.
