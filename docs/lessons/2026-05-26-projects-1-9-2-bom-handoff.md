# Projects 1.9.2 BOM Handoff

## 배경

`bluetape4k-projects` 1.9.2가 release되었고 Maven Central에서
`bluetape4k-bom:1.9.2`를 볼 수 있습니다.

## 결정

matching projects snapshot 대신 이 release-prep branch에는 stable
`bluetape4k-bom` 1.9.2 line을 사용합니다.

## 결과

version catalog는 repository 자체 release line은 바꾸지 않고 stable 1.9.2 release에서
`io.github.bluetape4k:bluetape4k-bom`을 resolve합니다.

## 검증

- `bluetape4k-bom:1.9.2`의 Maven Central HTTP 200
- `./gradlew help --refresh-dependencies --no-daemon --no-configuration-cache --no-build-cache`
