# Issue 337 단언문 스타일 검토

## 범위

- 이슈: #337 `test: normalize repo-wide assertions to bluetape4k-assertions style`
- 현재 브랜치의 기준에는 앞서 작성한 단언문 정리 교훈과 광범위한 수정이 이미 포함되어 있다.
- 현재 상태를 다시 검색한 결과 `kotlin.test.Test` import는 5개만 남아 있었고, 금지된 단언문 패턴은 이미 0개였다.

## 변경 사항

다음 파일에서 `kotlin.test.Test`를 `org.junit.jupiter.api.Test`로 교체했다.

- `exposed/fastjson2/src/test/kotlin/io/bluetape4k/exposed/core/fastjson2/ReadableExtensionsTest.kt`
- `exposed/jackson2/src/test/kotlin/io/bluetape4k/exposed/core/jackson/ReadableExtensionsTest.kt`
- `exposed/jackson3/src/test/kotlin/io/bluetape4k/exposed/core/jackson3/ReadableExtensionsTest.kt`
- `exposed/r2dbc/src/test/kotlin/io/bluetape4k/exposed/r2dbc/QueryExtensionsTest.kt`
- `exposed/r2dbc-redisson/src/test/kotlin/io/bluetape4k/exposed/r2dbc/redisson/map/R2dbcExposedEntityMapLoaderTest.kt`

## 패턴 검색

| 패턴 | 결과 |
| --- | --- |
| `import kotlin.test` | 0 |
| `.shouldBeEqualTo(` | 0 |
| `.size shouldBeEqualTo` | 0 |
| `shouldBeEqualTo true/false` | 0 |
| `.shouldBeEqualTo(true/false)` | 0 |

## 간소화한 7단계 검토

| 단계 | 결과 | 근거 |
| --- | --- | --- |
| 1 정확성 | PASS | 테스트 애너테이션 제공자만 변경했고 테스트 본문은 변경하지 않았다. |
| 2 단언문 스타일 | PASS | 남아 있던 `kotlin.test` import를 제거했고 금지된 단언문 검색 결과는 0개다. |
| 3 범위 | PASS | 영향받는 파일은 5개뿐이며 프로덕션 코드는 변경하지 않았다. |
| 4 유지보수성 | PASS | 수정한 모든 테스트에서 JUnit Jupiter 애너테이션을 일관되게 사용한다. |
| 5 호환성 | PASS | 저장소 전반에서 이미 JUnit Jupiter를 사용하고 있다. |
| 6 테스트 증거 | PASS | 영향받는 모듈의 테스트가 통과했다. |
| 7 문서화 | PASS | 기존 교훈 문서를 유지했으며, 이 검토에서 현재 상태의 최종 마무리를 기록한다. |

## 검증

- `./gradlew --no-parallel :bluetape4k-exposed-fastjson2:test :bluetape4k-exposed-jackson2:test :bluetape4k-exposed-jackson3:test :bluetape4k-exposed-r2dbc:test :bluetape4k-exposed-r2dbc-redisson:test` — 1m 37s 만에 BUILD SUCCESSFUL.

## 판정

P0/P1: 0. `git diff --check`와 `gno update` 후 PR을 생성할 수 있다.
