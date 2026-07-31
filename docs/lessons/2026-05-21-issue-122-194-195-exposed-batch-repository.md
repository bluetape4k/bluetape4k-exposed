# Issues 122, 194, 195 - Batch Executor Properties 및 Repository Edge Case

## 배경

Issues #122, #194, #195는 exposed repository의 작지만 독립적인 surface인 Spring Batch
auto-configuration, R2DBC repository KDoc, `saveAll` edge coverage를 건드리므로 하나의
branch에서 함께 처리했습니다.

## 결정

- `bluetape4k.batch.executor.*` 아래에 `ExposedBatchProperties`를 추가하고
  `@EnableConfigurationProperties`로 연결했습니다.
- 기존 behavior를 보존하면서 virtual thread, concurrency, shutdown timeout, enablement를
  configurable하게 만들기 위해 `batchPartitionTaskExecutor`를 `SimpleAsyncTaskExecutor`로
  유지했습니다.
- runtime code 변경 없이 `R2dbcRepository.kt` KDoc을 English로 전환했습니다.
- JDBC, R2DBC, auditable JDBC repository에 H2 중심의 empty 및 single-entity `saveAll`
  coverage를 추가했습니다.

## 결과

auto-configured executor는 user bean에 back off하고 configuration으로 disable할 수 있으며
configured concurrency와 task termination timeout을 적용합니다. `R2dbcRepository.kt`에는
Hangul text가 남아 있지 않습니다. `saveAll(emptyList())`와 single-row insert는 기존 bulk
insert test 옆에서 검증됩니다.

## 검증

- `git diff --check`
- `rg -n "[가-힣]" exposed/exposed-r2dbc/src/main/kotlin/io/bluetape4k/exposed/r2dbc/repository/R2dbcRepository.kt`
- `EXPOSED_TEST_DB=H2 ./gradlew :bluetape4k-exposed-spring-boot-batch:test :bluetape4k-exposed-jdbc:test :bluetape4k-exposed-r2dbc:test --tests '*ExposedBatchAutoConfigurationTest' --tests '*ActorJdbcRepositoryTest' --tests '*AuditableJdbcRepositoryEdgeCaseTest' --tests '*ActorR2dbcRepositoryTest' --console=plain --no-daemon`
  - 결과: 37 tests 실행, 2 skipped, build successful.

## 향후 메모

Spring Boot 4에서 `ApplicationContextRunner` bean backoff를 name으로 test할 때는 작은
`@TestConfiguration`과 named `@Bean`을 우선합니다. 사용 가능한 `withBean` overload는
Kotlin에서 named bean registration을 지원하지 않을 수 있습니다.
