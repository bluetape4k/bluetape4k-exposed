# 1.9.2 Release Prep

## 배경

1.9.2 stable release preflight는 target milestone이 닫히고 `baseVersion=1.9.2`임을
확인했지만 `CHANGELOG.md`에는 dated 1.9.2 section이 없었습니다.

## 결정

publication 전에 간결한 1.9.2 changelog section을 추가합니다. release-line BOM/catalog
alignment, Exposed Gradle plugin adoption, milestone의 두 README documentation issue를
다룹니다.

## 결과

release-prep branch에는 changelog metadata와 이 lesson만 있습니다. runtime code, build
logic, generated artifact는 바꾸지 않습니다.

## 검증

- `git diff --check`
- 이 prep PR이 merge된 뒤 release preflight를 계속합니다.

## 향후 메모

milestone과 version file이 준비되어 보이더라도 stable release dispatch 전에
`CHANGELOG.md`를 확인합니다.

## 2026-05-26 README Coordinate Preflight

stable-release preflight는 user-facing README dependency snippet이 여전히 이전
`*-SNAPSHOT` coordinate를 가리킴도 발견했습니다. GitHub release artifact가 stale snapshot
dependency를 안내하지 않도록 tagging 전 multilingual README example을 stable release
version으로 업데이트합니다.
