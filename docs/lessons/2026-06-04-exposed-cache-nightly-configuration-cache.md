# Lessons Learned — Exposed-cache Nightly Configuration Cache (2026-06-04)

**Related issues**: #240, #242, #244
**Affected module**: `bluetape4k-exposed-cache`

## 배경

snapshot-refresh workflow fix가 PR CI를 통과한 뒤에도 post-merge Nightly smoke의
`Test / exposed-cache (H2)`가 실패했습니다. GitHub runner는 configuration-cache entry를
버리고 `io.github.bluetape4k:bluetape4k-logging:.`처럼 version 없는 dependency를
resolve했습니다.

후속 PR은 CI의 `Build (compile only)` job이 `--refresh-dependencies` 없이 snapshot
artifact를 resolve해 Nightly 전에 stale Central metadata가 PR check를 깨뜨림을 보였습니다.
그 수정 뒤에는 `Test / exposed-core + exposed-dao (H2)`도 실패해
configuration-cache/BOM-empty-version failure가 cache module에 국한되지 않음이
증명되었습니다. `--no-configuration-cache`를 Nightly Gradle command 전체에 적용해도
run `26963387223`은 같은 GitHub runner path에서 실패했고 affected job은
`gradle/actions/setup-gradle@v6`가 test command 전에 cache를 restore한 뒤
`io.github.bluetape4k:bluetape4k-junit5:.` 같은 empty version을 resolve했음을 보였습니다.

## 결정

dependency refresh와 Nightly `--no-configuration-cache`는 유지하되 snapshot BOM metadata
refresh 중 Nightly workflow에서 Gradle cache를 restore하지 않습니다. local macOS run과
clean temporary `GRADLE_USER_HOME`은 통과했으므로 source test failure가 아니라 runner
cache path 문제입니다.

snapshot refresh와 GitHub runner configuration-cache 회피를 CI Gradle invocation에도
반영해 PR check와 Nightly가 같은 dependency-resolution policy를 사용하게 합니다. 모든
Nightly test/Kover Gradle command에 `--no-configuration-cache`를 적용하고 모든
`gradle/actions/setup-gradle@v6` step에 `cache-disabled: true`를 둡니다.

## 결과

Nightly smoke path는 BOM-managed bluetape4k dependency의 refreshed snapshot BOM metadata를
resolve할 때 restored Gradle cache state에 더 이상 의존하지 않습니다.

## 검증

- `./gradlew --refresh-dependencies :bluetape4k-exposed-cache:test --no-daemon`
- `env GRADLE_USER_HOME=/tmp/bt4k-exposed-gradle-home ./gradlew --refresh-dependencies :bluetape4k-exposed-cache:test --no-daemon`
- `actionlint .github/workflows/ci.yml .github/workflows/nightly-tests.yml`
- CI/Nightly Gradle audit: 모든 `./gradlew` call에 `--refresh-dependencies` 포함.
- Nightly Gradle audit: 모든 `./gradlew` run block에 `--no-configuration-cache` 포함.
- Nightly setup-gradle audit: 모든 setup step에 `cache-disabled: true` 포함.

## 향후 규칙

Nightly-only workflow change가 PR CI를 통과해도 post-merge smoke가 실패하면 changed-module
PR CI가 affected module test를 skip했는지 확인합니다. configuration-cache failure가 core
smoke path 전체에서 GitHub runner로 수정·검증될 때까지 exposed Nightly command에는
`--no-configuration-cache`를 유지합니다. post-merge smoke가 snapshot BOM resolution
안정성을 증명할 때까지 Nightly Gradle cache restore를 다시 켜지 않습니다. snapshot
dependency policy를 바꿀 때 `.github/workflows/ci.yml`과 `.github/workflows/nightly-tests.yml`
모두 audit합니다.
