# Testcontainers Serial Verification

## 배경

Issue #118과 #119는 별도 worktree에서 검증했습니다. code 변경은 올바르지만 여러
worktree에서 Testcontainers-backed Gradle test를 동시에 실행하면 PostgreSQL/MySQL startup
noise가 발생하고 orphan `org.testcontainers=true` Docker network가 남았습니다.

## 결정

local bluetape4k work에서는 Ryuk을 disabled로 두고 reusable Testcontainers를 활성화하지만
Testcontainers-backed Gradle command를 module, worktree, delegated agent, 별도 Gradle JVM
사이에서 parallel로 실행하지 않습니다.

`build.gradle.kts`의 Gradle `BuildService` test mutex는 하나의 Gradle invocation 안에서만
`Test` task를 serialize합니다. 다른 worktree에서 실행한 별도 `./gradlew` process는
coordinate하지 않습니다.

## 결과

labeled Testcontainers residue만 제거하고 test를 sequential로 다시 실행한 뒤 다음을
확인했습니다.

- `:bluetape4k-exposed-batch:cleanTest :bluetape4k-exposed-batch:test --no-build-cache`
  는 332 tests, 1 skipped로 통과했습니다.
- `:bluetape4k-exposed-jdbc-caffeine:cleanTest :bluetape4k-exposed-jdbc-caffeine:test --no-build-cache`
  는 309 tests, 22 skipped로 통과했습니다.
- final Docker check에서 `org.testcontainers=true` container와 network가 없었습니다.

## 향후 guard

Testcontainers verification에는 combined Gradle command 하나 또는 명시적인 sequential
module command를 사용합니다. run이 중단되거나 실수로 concurrent였으면
`docker ps -a --filter label=org.testcontainers=true`와
`docker network ls --filter label=org.testcontainers=true`를 확인하고 rerun 전에 labeled
residue만 정리합니다.
