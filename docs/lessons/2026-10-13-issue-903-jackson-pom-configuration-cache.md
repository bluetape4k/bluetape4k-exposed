# #903 Jackson POM 검증의 configuration-cache 경계

## 배경과 원인

`verifyJacksonSecurityPublicationMetadata`는 Jackson 2/3의 POM과 Gradle module metadata를 생성한 뒤 게시 BOM 버전을 검사한다. 저장소는 configuration cache를 기본 활성화한다. 기본 명령은 `GenerateMavenPom` 두 task의 `withXml` 처리에 필요한 `Project`, `Configuration`, `DependencyHandler` 참조와 검증 task의 Gradle script·`Project` 참조 때문에 strict configuration-cache 저장에 실패했다. 기준 실행에서는 `withXml()` 처리 중 `detachedConfiguration` delegate가 null인 오류도 재현됐다.

같은 명령에 `--no-configuration-cache`를 붙이면 성공하지만, 이것만으로 기본 경로가 지원된다고 볼 수 없다. [#804 해시 계약](2026-09-06-issue-804-crypt-contracts.md)은 POM 검사에 cache를 끄는 우회를 기록했다. 이번에는 그 우회를 Jackson publication 검증 task graph에 한정했다.

## 결정

Jackson 2/3 `GenerateMavenPom` task와 검증 task에 `notCompatibleWithConfigurationCache`를 지정한다. Gradle은 이 task graph의 configuration-cache entry를 버리고 실행을 계속한다. 따라서 기본 명령은 성공해도 cache 저장이나 재사용을 뜻하지 않는다. 다른 task graph의 기본 cache 동작은 그대로 둔다.

## 검증

- 기준 head에서 기본 명령은 `--configuration-cache-problems=fail`과 함께 실패했고, configuration-cache 문제는 Jackson POM 생성 task 두 개와 검증 task에 한정됐다.
- 수정 후 `./gradlew verifyJacksonSecurityPublicationMetadata --configuration-cache --configuration-cache-problems=fail --no-daemon --max-workers=2`를 두 번 실행했다. 두 실행 모두 성공했고 `Configuration cache entry discarded with 18 problems.`를 출력했다. 두 번째 실행은 cache를 재사용하지 않았다.
- TestKit 계약은 Jackson 2 module metadata와 Maven POM의 BOM 버전을 각각 잘못된 값으로 바꿔 검증 task가 거부하는지 확인한다.
- 전체 `buildSrc:test`는 18건 통과, 실패·오류·제외 0건이다. `actionlint .github/workflows/ci.yml`과 `git diff --check`도 통과했다.

## 향후 기준

1. third-party publication hook이 cache-safe해지면 task-scoped opt-out을 제거하고 cold/reuse TestKit 계약으로 바꾼다.
2. 비호환 task를 포함한 명령은 실제 offending task와 상위 검증 task에만 표시하고, issue 참조와 이유를 남긴다.
3. CI는 이 검증 task를 기본 configuration-cache 옵션으로 호출한다. cache entry 폐기를 예상 결과로 기록하고, 이를 cache 재사용 성공으로 보고하지 않는다.
4. 다른 publication 검증을 이유로 `--no-configuration-cache`를 글로벌 설정이나 무관한 task에 확대하지 않는다.

## 참고

- [Gradle configuration-cache task opt-out](https://docs.gradle.org/current/userguide/configuration_cache_debugging.html)
- [Gradle configuration-cache adoption](https://docs.gradle.org/current/userguide/configuration_cache_adoption.html)
- [Issue #903](https://github.com/bluetape4k/bluetape4k-exposed/issues/903)
