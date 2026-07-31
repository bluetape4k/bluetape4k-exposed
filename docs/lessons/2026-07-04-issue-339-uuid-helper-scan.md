# 이슈 339 UUID 도우미 검사

## 변경 내용

프로덕션 코드와 테스트의 직접적인 `UUID.randomUUID()` 호출을 bluetape4k 생태계 도우미로 교체했다.

- UUID 값 식별자는 이제 `Uuid.V7.nextId()`를 사용한다.
- 문자열 전용 소유자 ID, 데이터베이스 이름, 스레드 이름, 캐시 접미사는 이제 `Base58.randomString(8)`을 사용한다.
- exposed 벤치마크에서는 UUID 생성 자체가 측정 대상 비교에 포함되므로 `UUID.randomUUID()`를 유지한다.

## 반복 적용할 사항

- 수정 전에 난수 값을 대상 타입에 따라 분류한다.
  - 대상이 `UUID`이면 `Uuid.V7.nextId()`를 사용한다.
  - 대상이 고유 문자열, 이름, 접미사이면 `Base58.randomString(8)`을 사용한다.
- 수정 후 최종 grep을 실행하고 의도적인 예외를 문서화한다.
- 캐시 저장소에서는 Testcontainers 기반 모듈을 하나의 직렬 Gradle 호출로 실행한다.

## 근거

- 직접 호출 검사 결과 벤치마크 예외만 남았다.
- 수정한 모듈의 `compileTestKotlin`이 통과했다.
- 대상 비캐시 테스트에서 355개가 통과했고 7개가 보류되었다.
- Redis 기반 Redisson 모듈을 포함한 캐시 모듈 직렬 테스트가 통과했다.
