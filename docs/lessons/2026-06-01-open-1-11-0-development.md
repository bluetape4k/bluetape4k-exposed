# 2026-06-01 Open 1.11.0 Development

## 배경

`bluetape4k-exposed` `1.10.0`은 publication되었고
`bluetape4k-dependencies` `1.2.0`에 포함되었습니다.

## 결정

committed `baseVersion`을 `1.11.0`으로 옮기고 release workflow가 snapshot qualifier를
명시적으로 inject할 수 있게 `snapshotVersion=`은 비웁니다. `bluetape4k-bom`과
compatibility `bluetape4kVersion` property를 `1.11.0-SNAPSHOT`과 정렬합니다.

## 결과

repository는 다음 minor development line을 시작할 준비가 되었습니다.

## 검증

- `gradle.properties`는 `baseVersion=1.11.0`을 사용합니다.
- `snapshotVersion=`은 비어 있습니다.
- `./gradlew help --no-daemon --console=plain`이 update된 catalog를 resolve합니다.
