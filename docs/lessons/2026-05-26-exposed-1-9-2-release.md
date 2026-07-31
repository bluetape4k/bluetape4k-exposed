# Exposed 1.9.2 Release

## 배경

1.9.2 stable release는 tagging 전 README coordinate 정리가 필요했고, tag-triggered
release가 오래된 repository catalog variable `catalog/2026-05-23-00`을 사용해 한 번
실패했습니다.

## 결정

기존 `1.9.2` tag는 `catalogRef=catalog/2026-05-26-00`으로 manual `release.yml`
dispatch를 통해 publication합니다. `develop`은 `baseVersion=1.9.3`으로 다시 열되
`1.9.3-SNAPSHOT`이 아직 publication되지 않았으므로 `bluetape4k-bom=1.9.2`를
유지합니다.

## 결과

release workflow run `26441507142`가 성공했고 Maven Central은
`bluetape4k-exposed-bom` 및 `bluetape4k-exposed-core` 1.9.2 POM에 HTTP 200을
반환했습니다.

## 검증

- `./gradlew help --refresh-dependencies --no-daemon --no-configuration-cache --no-build-cache`
- Publish Snapshot run `26440950061`
- Nightly full run `26440951731`
- Publish Release run `26441507142`
- 1.9.2 BOM 및 core POM의 Maven Central HTTP 200

## 향후 메모

release에 repository variable보다 새 dependency catalog가 필요할 때 tag-push release
default에 의존하지 않습니다. explicit `catalogRef`로 `release.yml`을 manual dispatch하거나
tagging 전에 repository variable을 업데이트합니다.
