# 이슈 311 Spring Modulith 완료 처리 경계

## 배경

이슈 #311에서는 Exposed 기반 Spring Modulith 이벤트 발행 저장소의 중복 완료 처리와
반복 재제출 동작을 강화했다.

## 결정

- Spring Modulith 팩토리 호출에는 Kotlin 패키지 함수를 사용한다.
  `targetEventPublicationOf(...)`와 `publicationTargetIdentifierOf(...)`.
- 재시도 경계 테스트는 결정적으로 유지한다. 이 이슈는 스레드 경합 스트레스가 아니라
  중복 재시도 호출을 다루므로 bluetape4k-junit5 동시성 테스터는 적합하지 않다.
- 모듈 사용자에게 노출되는 멱등성 동작을 두 README 언어 파일에 모두 문서화한다.

## 결과

- UPDATE 모드 식별자 완료 처리는 이제 최초 완료 일자를 보존한다.
- DELETE 및 ARCHIVE 중복 완료 처리 경로를 테스트로 고정했다.
- 반복 재제출 시 첫 재제출 이후에는 시도 횟수와 타임스탬프가 변하지 않는다.

## 검증

- `./gradlew :bluetape4k-exposed-spring-modulith:test`에서 테스트 43개가 통과했다.
- `git diff --check`가 통과했다.

## 향후 준수 사항

bluetape4k Kotlin 테스트를 수정할 때는 중위 표기법의 `shouldBeEqualTo`를 사용하고,
불리언에는 `shouldBeTrue()` / `shouldBeFalse()`를 사용한다. 패키지 함수로 Kotlin API를
표현할 수 있다면 Java 스타일 정적 팩토리 호출을 피한다. 또한 중복 재시도 테스트가
스트레스 기반이 아니라 결정적이라면 동시성 도우미를 사용하지 않은 근거를 PR DoD에
기록한다.
