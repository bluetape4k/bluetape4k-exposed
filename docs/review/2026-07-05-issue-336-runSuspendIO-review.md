# Issue #336 Review — IO-bound suspend tests use runSuspendIO

## Scope

- Migrated IO-bound suspend tests from `kotlinx.coroutines.test.runTest` to `io.bluetape4k.junit5.coroutines.runSuspendIO` across R2DBC, JDBC cache, Redis-backed cache, dialect extension, BigQuery query, Spring Boot R2DBC, and R2DBC demo test modules.
- Updated suspend-test KDoc examples that pointed DB/cache tests at `runTest`.
- Kept `runTest` only where tests are pure coroutine/virtual-time/unit checks without DB/cache/Testcontainers IO.

## Retained `runTest` rationale

- `exposed/core/.../UserContextTest.kt`: validates coroutine context propagation across dispatcher hop, no DB/cache IO.
- `exposed/bigquery/.../BigQueryQueryContinuationUnitTest.kt`: mocked BigQuery job/page continuation and downstream cancellation unit tests.
- `exposed/r2dbc-redisson/.../ExposedEntityRedissonCodecTest.kt`: codec round-trip unit test, no Redisson server/repository IO.
- `utils/batch/.../BatchStepRunner*Test.kt`: batch retry/checkpoint/timeout/cancellation behavior with explicit coroutine-test timeouts; not part of Exposed DB/cache IO migration.

## Verification

- `./gradlew --no-parallel :exposed-spring-boot-r2dbc-demo:compileTestKotlin :bluetape4k-exposed-clickhouse:compileTestKotlin :bluetape4k-exposed-r2dbc-lettuce:compileTestKotlin :bluetape4k-exposed-cache:compileTestFixturesKotlin :bluetape4k-exposed-trino:compileTestKotlin :bluetape4k-exposed-r2dbc:compileTestKotlin :bluetape4k-exposed-bigquery:compileTestKotlin :bluetape4k-exposed-jdbc-caffeine:compileTestKotlin :bluetape4k-exposed-r2dbc-tests:compileTestKotlin :bluetape4k-exposed-jdbc-lettuce:compileTestKotlin :bluetape4k-exposed-jdbc-redisson:compileTestKotlin :bluetape4k-exposed-r2dbc-redisson:compileTestKotlin :bluetape4k-exposed-duckdb:compileTestKotlin :bluetape4k-exposed-r2dbc-caffeine:compileTestKotlin :bluetape4k-exposed-spring-boot-r2dbc:compileTestKotlin` — BUILD SUCCESSFUL in 24s.
- `./gradlew --no-parallel :exposed-spring-boot-r2dbc-demo:test :bluetape4k-exposed-clickhouse:test :bluetape4k-exposed-r2dbc-lettuce:test :bluetape4k-exposed-trino:test :bluetape4k-exposed-r2dbc:test :bluetape4k-exposed-bigquery:test :bluetape4k-exposed-jdbc-caffeine:test :bluetape4k-exposed-r2dbc-tests:test :bluetape4k-exposed-jdbc-lettuce:test :bluetape4k-exposed-jdbc-redisson:test :bluetape4k-exposed-r2dbc-redisson:test :bluetape4k-exposed-duckdb:test :bluetape4k-exposed-r2dbc-caffeine:test :bluetape4k-exposed-spring-boot-r2dbc:test` — BUILD SUCCESSFUL in 8m 53s.

## Review result

- No remaining `runTest` in touched Exposed DB/cache IO tests.
- Residual `runTest` occurrences are documented above and intentionally retained.
