# 리뷰 - R2DBC Lettuce 캐시 전용 무효화 (2026-06-23)

이슈: #286 `fix(r2dbc-lettuce): make invalidate cache-only`

## 범위

- `exposed/r2dbc-lettuce/src/main/kotlin/io/bluetape4k/exposed/r2dbc/lettuce/repository/AbstractR2dbcLettuceRepository.kt`
- `exposed/r2dbc-lettuce/src/main/kotlin/io/bluetape4k/exposed/r2dbc/lettuce/repository/R2dbcLettuceRepository.kt`
- R2DBC Lettuce read-through 및 write-through 시나리오 테스트
- 이미 캐시 전용으로 구현된 동작과 맞지 않는 오래된 JDBC Lettuce 저장소 KDoc

## 검토 결과

- P0: 없음
- P1: 없음
- P2/P3: 차단 이슈 없음

## 리뷰 근거

- Code-reviewer 검토: APPROVE. P0/P1 이슈는 없었다. `R2dbcLettuceRepositoryExtrasTest`가 스모크 테스트 수준이라는 지적에 따라 리뷰 후 어설션을 강화했다.
- Architect 검토: WATCH. P0/P1 차단 이슈는 없었다. 캐시 전용 구현인데도 DB 삭제를 설명하던 오래된 JDBC Lettuce KDoc을 지적했으며, 리뷰 후 KDoc을 수정했다.
- 코드 그래프 영향 흐름: 변경된 7개 파일에 대해 보고된 흐름은 0개였다.

## 검증

- RED: DB 행이 유지되어야 한다는 시나리오로 변경한 뒤 기존 프로덕션 코드에서 `R2dbcLettuceWriteThroughCacheTest`가 실패했다.
- GREEN: `./gradlew :bluetape4k-exposed-r2dbc-lettuce:test --tests "io.bluetape4k.exposed.r2dbc.lettuce.repository.R2dbcLettuceWriteThroughCacheTest"` 명령으로 테스트 48개가 통과했다.
- `./gradlew :bluetape4k-exposed-r2dbc-lettuce:test --tests "io.bluetape4k.exposed.r2dbc.lettuce.repository.R2dbcLettuceReadThroughCacheTest"` 명령으로 테스트 24개가 통과했다.
- `./gradlew :bluetape4k-exposed-r2dbc-lettuce:test` 명령으로 테스트 130개가 통과했다.
- `./gradlew :bluetape4k-exposed-r2dbc-lettuce:test :bluetape4k-exposed-jdbc-lettuce:compileKotlin :bluetape4k-exposed-jdbc-lettuce:compileTestKotlin` 명령이 통과했다.
- `./gradlew detekt` 명령이 `:detekt NO-SOURCE` 상태로 통과했다.
- `git diff --check` 명령이 통과했다.

## 판정

APPROVE. 이제 구현은 `invalidate`와 `invalidateAll`에서 캐시 축출을 사용하므로 DB 행을 유지하며 공유 캐시 저장소 계약과 일치한다. P0/P1은 0이다.
