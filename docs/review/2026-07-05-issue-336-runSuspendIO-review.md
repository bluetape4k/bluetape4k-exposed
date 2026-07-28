# Issue #336 리뷰 — IO-bound suspend test는 runSuspendIO 사용

## 범위

- R2DBC, JDBC cache, Redis-backed cache, dialect extension, BigQuery query, Spring Boot R2DBC, R2DBC demo test module의 IO-bound suspend test를 `kotlinx.coroutines.test.runTest`에서 `io.bluetape4k.junit5.coroutines.runSuspendIO`로 옮겼습니다.
- DB/cache test에 `runTest`를 안내하던 suspend-test KDoc 예제를 갱신했습니다.
- DB/cache/Testcontainers IO가 없는 순수 coroutine/virtual-time/unit check에만 `runTest`를 남겼습니다.

## `runTest` 유지 근거

- `exposed/core/.../UserContextTest.kt`: dispatcher hop 사이의 coroutine context propagation을 검증하며 DB/cache IO가 없습니다.
- `exposed/bigquery/.../BigQueryQueryContinuationUnitTest.kt`: mocked BigQuery job/page continuation과 downstream cancellation unit test입니다.
- `exposed/r2dbc-redisson/.../ExposedEntityRedissonCodecTest.kt`: codec round-trip unit test이며 Redisson server/repository IO가 없습니다.
- `utils/batch/.../BatchStepRunner*Test.kt`: 명시적 coroutine-test timeout을 사용하는 batch retry/checkpoint/timeout/cancellation 동작 검증입니다. Exposed DB/cache IO migration 범위가 아닙니다.

## 검증

- `./gradlew --no-parallel :exposed-spring-boot-r2dbc-demo:compileTestKotlin :bluetape4k-exposed-clickhouse:compileTestKotlin :bluetape4k-exposed-r2dbc-lettuce:compileTestKotlin :bluetape4k-exposed-cache:compileTestFixturesKotlin :bluetape4k-exposed-trino:compileTestKotlin :bluetape4k-exposed-r2dbc:compileTestKotlin :bluetape4k-exposed-bigquery:compileTestKotlin :bluetape4k-exposed-jdbc-caffeine:compileTestKotlin :bluetape4k-exposed-r2dbc-tests:compileTestKotlin :bluetape4k-exposed-jdbc-lettuce:compileTestKotlin :bluetape4k-exposed-jdbc-redisson:compileTestKotlin :bluetape4k-exposed-r2dbc-redisson:compileTestKotlin :bluetape4k-exposed-duckdb:compileTestKotlin :bluetape4k-exposed-r2dbc-caffeine:compileTestKotlin :bluetape4k-exposed-spring-boot-r2dbc:compileTestKotlin` — BUILD SUCCESSFUL in 24s.
- `./gradlew --no-parallel :exposed-spring-boot-r2dbc-demo:test :bluetape4k-exposed-clickhouse:test :bluetape4k-exposed-r2dbc-lettuce:test :bluetape4k-exposed-trino:test :bluetape4k-exposed-r2dbc:test :bluetape4k-exposed-bigquery:test :bluetape4k-exposed-jdbc-caffeine:test :bluetape4k-exposed-r2dbc-tests:test :bluetape4k-exposed-jdbc-lettuce:test :bluetape4k-exposed-jdbc-redisson:test :bluetape4k-exposed-r2dbc-redisson:test :bluetape4k-exposed-duckdb:test :bluetape4k-exposed-r2dbc-caffeine:test :bluetape4k-exposed-spring-boot-r2dbc:test` — BUILD SUCCESSFUL in 8m 53s.

## 리뷰 결과

- 수정된 Exposed DB/cache IO test에는 `runTest`가 남아 있지 않습니다.
- 남아 있는 `runTest`는 위에 문서화했으며 의도적으로 유지했습니다.
