# Issue #69 Trino Options Serializable 교훈

## 맥락

Issue #28과 Issue #29에서 `TrinoPagedQueryOptions`, `TrinoBatchInsertOptions`를 추가했지만, 새 public data class에 bluetape4k Serializable 계약이 빠졌다.

## 교훈

- bluetape4k 모듈의 새 public data class는 `java.io.Serializable`을 구현하고 명시적 `serialVersionUID`를 정의해야 한다.
- primitive property만 가진 작은 option/config class라도, 리뷰 체크리스트에는 JVM 직렬화 호환성을 포함해야 한다.
- GNO 조회는 이 저장소에 적용할 강한 최신 규칙을 찾지 못했다. 이때는 issue 본문과 기존 source pattern을 기준으로 삼고, 이후 검색을 위해 규칙을 이 문서에 남긴다.

## 검증

- `TrinoPagedQueryOptions`, `TrinoBatchInsertOptions`에 `Serializable`과 안정적인 `serialVersionUID = 1L`를 추가했다.
- `Serializable` 구현 여부와 `ObjectStreamClass`의 serialVersionUID 값을 모두 확인하는 회귀 테스트를 추가했다.
- `./gradlew :exposed-trino:test --tests "io.bluetape4k.exposed.trino.TrinoExtensionsTest" --no-configuration-cache --console=plain`이 통과했다.
- `./gradlew :exposed-trino:test --no-configuration-cache --console=plain`은 67개 테스트를 통과했다.
- `git diff --check`가 통과했다.
- `./gradlew detekt --no-configuration-cache --console=plain`은 `:detekt NO-SOURCE`와 함께 통과했다.
- Claude advisor 리뷰에서 P0/P1 차단 항목이 없었다.

## 후속 지침

- public Kotlin `data class`를 추가할 때는 PR을 열기 전에 주변 `serialVersionUID` pattern을 검색한다.
- `is Serializable`만 확인하지 말고 `ObjectStreamClass.lookup(...).serialVersionUID`를 검증하는 테스트를 우선한다. 이렇게 해야 명시적 UID가 빠져 생기는 드리프트를 잡을 수 있다.
