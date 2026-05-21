# Issues 122, 194, 195 - Batch Executor Properties and Repository Edge Cases

## Context

Issues #122, #194, and #195 were handled together in one branch because they
touch independent but small surfaces in the exposed repository: Spring Batch
auto-configuration, R2DBC repository KDoc, and `saveAll` edge coverage.

## Decision

- Added `ExposedBatchProperties` under `bluetape4k.batch.executor.*` and wired
  it through `@EnableConfigurationProperties`.
- Kept `batchPartitionTaskExecutor` as a `SimpleAsyncTaskExecutor` so existing
  behavior remains intact while making virtual threads, concurrency, shutdown
  timeout, and enablement configurable.
- Converted `R2dbcRepository.kt` KDoc to English without changing runtime code.
- Added H2-focused empty and single-entity `saveAll` coverage for JDBC, R2DBC,
  and auditable JDBC repositories.

## Outcome

The auto-configured executor now backs off for user beans, can be disabled with
configuration, and applies configured concurrency and task termination timeout.
`R2dbcRepository.kt` has no remaining Hangul text. `saveAll(emptyList())` and
single-row inserts are now covered next to the existing bulk insert tests.

## Verification

- `git diff --check`
- `rg -n "[가-힣]" exposed/exposed-r2dbc/src/main/kotlin/io/bluetape4k/exposed/r2dbc/repository/R2dbcRepository.kt`
- `EXPOSED_TEST_DB=H2 ./gradlew :bluetape4k-exposed-spring-boot-batch:test :bluetape4k-exposed-jdbc:test :bluetape4k-exposed-r2dbc:test --tests '*ExposedBatchAutoConfigurationTest' --tests '*ActorJdbcRepositoryTest' --tests '*AuditableJdbcRepositoryEdgeCaseTest' --tests '*ActorR2dbcRepositoryTest' --console=plain --no-daemon`
  - Result: 37 tests executed, 2 skipped, build successful.

## Future Notes

When testing `ApplicationContextRunner` bean backoff by name on Spring Boot 4,
prefer a small `@TestConfiguration` with a named `@Bean`; the available
`withBean` overloads may not support named bean registration from Kotlin.
