# 이슈 #351 r2dbc-lettuce 커버리지 리뷰

## 범위

- 이슈: <https://github.com/bluetape4k/bluetape4k-exposed/issues/351>
- 모듈: `:bluetape4k-exposed-r2dbc-lettuce`
- 변경된 테스트 범위:
  - `exposed/r2dbc-lettuce/src/test/kotlin/io/bluetape4k/exposed/r2dbc/lettuce/map/ExposedR2dbcLettuceSuspendedLoadedMapTest.kt`

## 리뷰 결과

- P0/P1 지적 사항: 0
- Tier 4 정확성: PASS
- Tier 5 테스트 적정성: PASS
- Tier 7 증거 무결성: PASS

## 증거

- 기준 Kover XML 명령어 커버리지: `73.71%` (`covered=2868`, `missed=1023`, `total=3891`).
- 기준 상태에서 가장 큰 공백: `ExposedR2dbcLettuceSuspendedLoadedMap.kt`, 명령어 커버리지 `59.7%` (`missed=726`, `covered=1077`).
- 직접적인 맵 계약에 초점을 맞춘 Redis 기반 테스트를 추가했다.
  - `get`과 `getAll`을 통한 캐시 미스 로딩.
  - 패턴 무효화와 전체 비우기.
  - 즉시 반영 방식의 Redis 쓰기와 제거.
  - `suspendClose()`와 블로킹 `close()`를 통한 지연 쓰기 드레인.
  - 종료 중 지연 쓰기 실패 처리.
- 집중 검증 명령:
  - `./gradlew --no-parallel :bluetape4k-exposed-r2dbc-lettuce:compileTestKotlin :bluetape4k-exposed-r2dbc-lettuce:test --tests 'io.bluetape4k.exposed.r2dbc.lettuce.map.ExposedR2dbcLettuceSuspendedLoadedMapTest'`
  - 결과: `5 passing`, `BUILD SUCCESSFUL`.
- 전체 모듈 명령:
  - `./gradlew --no-parallel :bluetape4k-exposed-r2dbc-lettuce:test :bluetape4k-exposed-r2dbc-lettuce:koverXmlReport :bluetape4k-exposed-r2dbc-lettuce:koverLog`
  - 결과: `138 passing`, `4 pending`, `BUILD SUCCESSFUL`.
- 최종 Kover:
  - 라인 커버리지: `85.9116%`.
  - XML 명령어 커버리지: `88.33%` (`covered=3437`, `missed=454`, `total=3891`).
  - `ExposedR2dbcLettuceSuspendedLoadedMap.kt`: 명령어 커버리지 `91.3%` (`missed=157`, `covered=1646`).

## 참고

- 프로덕션 동작은 변경되지 않았다.
- Testcontainers 기반 검증은 `--no-parallel`로 실행했다.
- 새 테스트는 기존 모듈 픽스처, `runSuspendIO`, `bluetape4k-assertions`를 재사용한다.
