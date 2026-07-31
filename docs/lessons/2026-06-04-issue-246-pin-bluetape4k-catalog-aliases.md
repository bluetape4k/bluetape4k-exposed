# 2026-06-04 Issue 246 Pin Bluetape4k Catalog Alias

## 배경

Gradle action caching을 disabled로 둔 뒤 Nightly smoke는 더 이상 `exposed-measured`에서
실패하지 않았지만 H2와 Spring Boot job은 여전히 `exposed-jdbc-tests` bluetape4k
dependency를 `group:artifact:.`로 resolve했습니다.

## 결정

task graph가 dependency-management timing에만 의존하지 않게 repo-local
`libs.bluetape4k.*` alias에 `version.ref = "bluetape4k-bom"`을 유지합니다.

## 결과

local catalog는 기존 `bluetape4k-bom` version key를 보존하면서 bluetape4k artifact의
release-train catalog style을 반영합니다.

## 검증

- 예정: clean/fresh Gradle home H2 Nightly task graph.
- 예정: clean/fresh Gradle home Spring Boot Nightly task graph.
- 예정: `git diff --check` 및 catalog alias audit.

## 향후 규칙

repo-local version catalog가 bluetape4k alias를 유지할 때 dependency-management만 있는
unversioned alias에 의존하지 말고 bluetape4k BOM ref로 version을 지정합니다.
