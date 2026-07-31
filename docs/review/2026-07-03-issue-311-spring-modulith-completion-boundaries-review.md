# 이슈 311 Spring Modulith 완료 경계 리뷰

## 범위

- 이슈: #311
- 모듈: `:bluetape4k-exposed-spring-modulith`
- 변경 유형: 멱등성 수정 한 건과 Kotlin 팩토리 헬퍼를 포함한 집중적인 테스트 강화

## bluetape4k-code-patterns 근거

| 단계 | 상태 | 근거 |
|---|---|---|
| Kotlin 팩토리 스타일 | PASS | `targetEventPublicationOf(...)`와 `publicationTargetIdentifierOf(...)`를 추가했다. 이제 테스트에서 Spring Modulith Java 정적 팩토리를 직접 호출하지 않는다. |
| 검증 헬퍼 | PASS | `publicationTargetIdentifierOf(...)`는 위임하기 전에 `requireNotBlank("value")`로 호출자 입력을 검증한다. |
| 단언문 스타일 | PASS | Boolean 검사는 `shouldBeTrue()` / `shouldBeFalse()`를 사용하고, 비교 검사는 중위 표기법 `shouldBeEqualTo`를 사용한다. |
| 고유 UUID 스타일 | PASS | 테스트 UUID 값은 기존의 임의 UUID 생성 방식 대신 `nextJavaUuid()`를 통해 `Uuid.V7.nextId()`를 사용한다. |
| 동시성 헬퍼 게이트 | PASS | 임시 동시성 반복문을 추가하지 않았다. 중복 재시도 호출은 스레드 안전성이나 경합 스트레스 테스트가 아니라 결정적인 멱등성 경계이므로 `MultithreadingTester`, `StructuredTaskScopeTester`, `SuspendedJobTester`는 이 이슈 범위에 맞지 않는다. |
| README 로케일 세트 | PASS | 완료 멱등성 동작과 Kotlin 패키지 함수 사용법을 `README.md`와 `README.ko.md`에 반영했다. |

## 7단계 리뷰

1. 정확성: PASS
   - 식별자 완료 처리는 이미 완료된 UPDATE 행을 무시하므로 최초 완료 시각이 유지된다.
   - DELETE와 ARCHIVE의 중복 완료 호출은 무동작 멱등 결과로 검증한다.
2. API 호환성: PASS
   - 공개 API 시그니처는 변경되지 않았다.
3. 영속성 의미: PASS
   - 동일하게 직렬화된 이벤트와 리스너를 공유하는 여러 행에 대해 이벤트/리스너 중복 완료 처리를 검증한다.
   - ARCHIVE 모드는 완료된 행을 아카이브 테이블에 유지하며, 중복 호출로 추가 행이 생성되지 않는다.
4. 테스트 품질: PASS
   - 새로운 매개변수화 테스트는 UPDATE, DELETE, ARCHIVE 모드의 H2, PostgreSQL, MySQL_V8을 검증한다.
   - 단언문은 `bluetape4k-assertions`를 사용하며 JUnit/kotlin.test 단언문은 추가하지 않았다.
5. bluetape4k 패턴: PASS
   - 모킹을 추가하지 않았으므로 `clearMocks(...)` 설정이 필요하지 않다.
   - 테스트 픽스처 데이터 클래스는 `serialVersionUID`와 함께 `Serializable`을 구현한다.
   - Kotlin 호출부는 Java 스타일의 Spring Modulith 정적 팩토리 대신 패키지 함수를 사용한다.
6. 문서 영향: PASS
   - README 로케일 세트에 멱등적인 완료 재시도와 Kotlin 헬퍼 사용법을 문서화했다.
7. 검증: PASS
   - `./gradlew :bluetape4k-exposed-spring-modulith:test`에서 예상한 43개 테스트 중 43개가 통과했다.
   - `git diff --check`

## 잔여 위험

- 이 CLI 세션에서는 IDE 진단을 사용할 수 없었다. 변경한 Kotlin 소스는 Gradle 컴파일과 모듈 테스트로 검증했다.
