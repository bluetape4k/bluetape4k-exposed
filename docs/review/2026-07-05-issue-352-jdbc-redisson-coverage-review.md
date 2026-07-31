# 이슈 #352 jdbc-redisson 커버리지 리뷰

## 범위

- 이슈: <https://github.com/bluetape4k/bluetape4k-exposed/issues/352>
- 모듈: `:bluetape4k-exposed-jdbc-redisson`
- 변경된 테스트 범위:
  - `exposed/jdbc-redisson/src/test/kotlin/io/bluetape4k/exposed/redisson/repository/JdbcRedissonRepositoryDefaultMethodTest.kt`

## 리뷰 결과

- P0/P1 지적 사항: 0
- Tier 4 정확성: PASS
- Tier 5 테스트 적정성: PASS
- Tier 7 증거 무결성: PASS

## 증거

- 기준 Kover XML 명령어 커버리지: `80.49%` (`covered=3874`, `missed=939`, `total=4813`).
- 이슈의 기준 목표: 저장소 모듈 평균인 `80.81%`를 초과하도록 높인다.
- `JdbcRedissonRepository` 기본 메서드 계약에 초점을 맞춘 단위 테스트를 추가했다.
  - `containsKey`와 `get`을 통한 읽기 위임.
  - `put`, `putAll`, `upsertAll`, `invalidate`, `invalidateAll`, `clear`를 통한 쓰기 위임.
  - 빈 벌크 연산의 조기 반환.
  - 양수 `batchSize` 및 스캔 `count` 검증.
  - 빈 키 경로와 비어 있지 않은 키 경로의 패턴 무효화.
- 집중 검증 명령:
  - `./gradlew --no-parallel :bluetape4k-exposed-jdbc-redisson:compileTestKotlin :bluetape4k-exposed-jdbc-redisson:test --tests 'io.bluetape4k.exposed.redisson.repository.JdbcRedissonRepositoryDefaultMethodTest'`
  - 결과: `7 passing`, `BUILD SUCCESSFUL`.
- 전체 모듈 명령:
  - `./gradlew --no-parallel :bluetape4k-exposed-jdbc-redisson:test :bluetape4k-exposed-jdbc-redisson:koverXmlReport :bluetape4k-exposed-jdbc-redisson:koverLog`
  - 결과: `446 passing`, `BUILD SUCCESSFUL`.
- 최종 Kover:
  - 라인 커버리지: `82.6687%`.
  - XML 명령어 커버리지: `82.61%` (`covered=3976`, `missed=837`, `total=4813`).

## 참고

- 프로덕션 동작은 변경되지 않았다.
- Testcontainers 기반 검증은 `--no-parallel`로 실행했다.
- Redis 시나리오 실행 시간을 추가하지 않고, 목 기반 기본 메서드 계약 테스트로 커버리지를 높였다.
