# 이슈 337 단언문 스타일 리뷰

## 범위

- 브랜치: `test/issue-337-assertion-style`
- 스택 기준: `chore/issue-312-314-code-pattern-audit`
- 이슈: #337
- 리뷰한 diff: 저장소 전체의 테스트 단언문 스타일 표준화.

## 7단계 리뷰

1. 정확성: PASS
   - `Map<String, Any?>` Boolean 매처 캐스트를 수정한 뒤 Kotlin 컴파일 게이트가 통과했다.
   - R2DBC suspend 예외 헬퍼는 더 이상 자체 단언 실패를 포착하지 않는다.
2. API 및 호환성: PASS
   - 프로덕션 API는 변경되지 않았다.
   - 테스트 헬퍼 함수 이름과 호출부는 소스 호환성을 유지한다.
3. Kotlin 및 bluetape4k 스타일: PASS
   - 이슈 범위의 `kotlin.test` 단언문과 실패 처리를 `bluetape4k-assertions`로 교체했다.
   - 적용할 수 있는 곳에는 중위 표기법 `shouldBeEqualTo`, `shouldHaveSize`,
     `shouldBeTrue`, `shouldBeFalse`와 null 매처를 사용했다.
4. 테스트 전략: PASS
   - 저장소 전체 테스트 컴파일을 실행했다.
   - 변경한 helper/readable/r2dbc/batch 경로를 다루는 대상 모듈이 통과했다.
5. 동시성 및 코루틴 안전성: PASS
   - 프로덕션 코루틴 제어 흐름은 변경하지 않았다.
   - R2DBC suspend 헬퍼는 여전히 발생한 예외만 단언 입력으로 취급한다.
6. 유지보수성: PASS
   - 로컬 래퍼가 단언 헬퍼 방언의 실패 메시지를 보존한다.
   - 기계적인 변경 과정에서 새로운 추상화나 의존성을 추가하지 않았다.
7. 회귀 및 운영 위험: PASS
   - 위험 범위는 테스트 코드와 테스트 헬퍼 동작으로 제한된다.
   - CI, Gradle, 의존성, 공개 문서 영역은 변경하지 않았다.

## 검토 결과

- P0/P1: 없음.
- 잔여 위험: 변경하지 않은 일부 테스트에는 명시적인 #337 금지 패턴 범위 밖의
  `shouldBeEqualTo null` 또는 `shouldBeEqualTo emptyList()` 패턴이 여전히 남아 있다.
  필요하다면 별도의 스타일 정리 작업에서 처리할 수 있다.

## 근거

- 금지 패턴 검색: `kotlin.test.assert*`, `kotlin.test.fail`, `.shouldBeEqualTo(...)`,
  `.size shouldBeEqualTo`, Boolean 동등성 매처 패턴과 일치하는 항목 0개.
- `git diff --check`: 통과.
- `./gradlew compileTestKotlin --no-configuration-cache`: 통과.
- `./gradlew :bluetape4k-exposed-core:test :bluetape4k-exposed-fastjson2:test :bluetape4k-exposed-jackson2:test :bluetape4k-exposed-r2dbc:test :bluetape4k-exposed-batch:test --no-configuration-cache`: 통과, 355개 통과, 7개 보류.
- `./gradlew :bluetape4k-exposed-jdbc-tests:test :bluetape4k-exposed-r2dbc-tests:test :exposed-spring-boot-r2dbc-demo:test --no-configuration-cache`: 통과, 170 + 149 + 25개 통과.
- `./gradlew :bluetape4k-exposed-r2dbc-tests:test --no-configuration-cache`: 통과, suspend 헬퍼 정리 후 149개 통과.
- `./gradlew test --no-configuration-cache`: 통과, `BUILD SUCCESSFUL in 13m 6s`, 실행 가능한 작업 234개.
