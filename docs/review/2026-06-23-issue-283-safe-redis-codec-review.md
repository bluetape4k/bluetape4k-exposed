# 이슈 283 Redis 코덱 안전성 리뷰

날짜: 2026-06-23
범위: `exposed/jdbc-lettuce`, `exposed/r2dbc-lettuce`, `exposed/jdbc-redisson`, `exposed/r2dbc-redisson`
이슈: #283

## 판정

P0 지적 사항: 0
P1 지적 사항: 0

저장소 기본값은 더 이상 저장소 엔티티 값에 Fory 계열 바이너리 Redis 코덱을 암묵적으로 상속하지 않는다. Lettuce 저장소는 명시적인 값 코덱을 요구하며, Redisson 저장소는 호출자가 `trustedBinaryCache = true`로 신뢰할 수 있는 Redis 데이터를 명시적으로 선택하지 않는 한 알려진 바이너리 코덱을 거부한다.

## 리뷰 참고 사항

- Lettuce 저장소 생성자는 기반 Redis 유틸리티에서 LZ4/Fory를 선택하는 상속된 `LettuceLoadedMap` / `LettuceSuspendedLoadedMap` 기본값 대신 호출자가 제공한 `RedisCodec<String, E>`를 요구한다.
- JDBC 및 R2DBC Lettuce 모듈은 로컬 `Exposed*Lettuce*LoadedMap` 구현을 사용한다. 이를 통해 읽기 경유, 쓰기 경유, 지연 쓰기, 삭제, 무효화, 전체 삭제 및 종료 동작을 유지하면서 명시적인 엔티티 코덱으로 저장소 값을 인코딩할 수 있다.
- `ExposedLettuceCodecs.jackson3(valueType)` 및 `ExposedR2dbcLettuceCodecs.jackson3(valueType)`는 저장소 데이터의 구조적 기본 경로를 제공한다.
- 공개 `jackson3` 코덱 헬퍼가 런타임에 `JacksonSerializer`를 요구하므로 Lettuce 모듈은 `bluetape4k-jackson3`를 API 의존성으로 선언한다.
- Redisson 캐시 구성은 계속 `RedissonCacheConfig`에서 가져오지만, 저장소 생성자는 `trustedBinaryCache = true`가 명시되지 않으면 안전하지 않은 Fory, Kryo 및 JDK 계열 코덱을 차단한다.
- Redisson JSON 헬퍼 코덱은 보안 이외의 오류 경로에서 전역 Fory 코덱으로 대체될 수 있으므로 기본 완화책으로 채택하지 않았다. 따라서 저장소 계층은 모든 JSON 헬퍼가 안전한 기본값인 것처럼 간주하는 대신 명시적인 신뢰 선택 방식을 사용한다.
- README 파일은 새로운 명시적 Lettuce 코덱 인자와 Redisson 신뢰 바이너리 선택 방식을 영문과 한글로 설명한다.

## 검증

- `./gradlew :bluetape4k-exposed-jdbc-lettuce:testClasses :bluetape4k-exposed-r2dbc-lettuce:testClasses :bluetape4k-exposed-jdbc-redisson:testClasses :bluetape4k-exposed-r2dbc-redisson:testClasses --continue`
  - 결과: 성공.
- `./gradlew :bluetape4k-exposed-jdbc-lettuce:test :bluetape4k-exposed-r2dbc-lettuce:test :bluetape4k-exposed-jdbc-redisson:test :bluetape4k-exposed-r2dbc-redisson:test --continue`
  - 결과: 성공.
- `./gradlew :bluetape4k-exposed-jdbc-lettuce:cleanTest :bluetape4k-exposed-r2dbc-lettuce:cleanTest :bluetape4k-exposed-jdbc-lettuce:test :bluetape4k-exposed-r2dbc-lettuce:test --continue --rerun-tasks`
  - 결과: Jackson3 런타임 의존성을 선언한 후 성공. JDBC Lettuce 테스트 790개와 R2DBC Lettuce 테스트 130개 통과.
- 코덱 안전성 테스트 XML
  - 결과: 성공, 테스트 6개, 실패 0개, 오류 0개, 건너뜀 0개.
- `git diff --check`
  - 결과: 성공.

## 잔여 위험

- 전체 Redis/Testcontainers 테스트 스위트의 범위는 대상 생성자 안전성 검사보다 넓다. 이번 변경은 기존의 신뢰된 테스트 픽스처가 명시적 선택을 통해 바이너리 테스트 코덱을 계속 사용하도록 의도했으므로, 전체 테스트 스위트는 일반 CI/Nightly 경로에 유지해야 한다.
