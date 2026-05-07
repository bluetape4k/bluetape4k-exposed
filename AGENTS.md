# AGENTS.md - bluetape4k-exposed

JetBrains Exposed ORM extensions for Kotlin: JDBC/R2DBC repositories, cache
integrations, JSON serialization, encryption columns, and Spring Boot 3/4
auto-configuration.

- Group: `io.bluetape4k.exposed`
- Publishing: Maven Central through NMCP

## Layout

```text
exposed/
  exposed-core/
  exposed-dao/
  exposed-jdbc/
  exposed-r2dbc/
  exposed-jdbc-tests/
  exposed-r2dbc-tests/
  exposed-cache/
  exposed-jdbc-caffeine/
  exposed-jdbc-lettuce/
  exposed-jdbc-redisson/
  exposed-r2dbc-caffeine/
  exposed-r2dbc-lettuce/
  exposed-r2dbc-redisson/
  exposed-jackson2/
  exposed-jackson3/
  exposed-fastjson2/
  exposed-tink/
  exposed-measured/
  exposed-mysql8/
  exposed-postgresql/
  exposed-bigquery/
  exposed-clickhouse/
  exposed-trino/
  exposed-duckdb/
  exposed-timefold-solver-persistence/
utils/
spring-boot3/
spring-boot4/
buildSrc/
```

## Module Naming

`settings.gradle.kts` maps directories to published-style Gradle names. Examples:

| Directory | Gradle module |
|---|---|
| `exposed/exposed-core` | `:bluetape4k-exposed-core` |
| `exposed/exposed-jdbc` | `:bluetape4k-exposed-jdbc` |
| `exposed/exposed-r2dbc` | `:bluetape4k-exposed-r2dbc` |
| `spring-boot3/exposed-jdbc` | `:bluetape4k-spring-boot3-exposed-jdbc` |
| `spring-boot4/exposed-r2dbc` | `:bluetape4k-spring-boot4-exposed-r2dbc` |
| `utils/batch` | `:bluetape4k-utils-batch` |

## Commands

```bash
./gradlew clean build
./gradlew build -x test -x koverVerify --parallel
./gradlew :bluetape4k-exposed-core:build
./gradlew :bluetape4k-exposed-jdbc:test
./gradlew :bluetape4k-exposed-r2dbc:test
./gradlew :bluetape4k-exposed-jdbc-lettuce:test
./gradlew test --tests "io.bluetape4k.exposed.jdbc.ExposedJdbcRepositoryTest"
./gradlew :bluetape4k-spring-boot3-exposed-jdbc:test
./gradlew detekt
./gradlew publishAggregationToCentralSnapshots
./gradlew publishAggregationToCentralPortal
```

## Design Contracts

- JDBC repository code runs inside Exposed `transaction {}`.
- R2DBC repository code uses `suspendTransaction {}`.
- Cache-backed repositories use decorator-style wrappers around repository
  delegates.
- JSON/encryption column helpers should match existing module-specific DSL
  styles.
- Spring Boot modules expose enable annotations and conditional auto-config.

## Test Environment

| Variable | Values | Purpose |
|---|---|---|
| `EXPOSED_TEST_DB` | `H2`, `POSTGRESQL`, `MYSQL_V8` | Select test DB |
| `TESTCONTAINERS_RYUK_DISABLED` | `true` | Disable Ryuk in CI |
| `DOCKER_HOST` | Docker socket | CI Docker host |

## Publishing

- Snapshot: `./gradlew publishAggregationToCentralSnapshots`
- Release: clear `snapshotVersion`, then run
  `./gradlew publishAggregationToCentralPortal`.

## CI

- CI is optimized for fast modules without Docker.
- Nightly covers PostgreSQL, MySQL, Redis, and broader Testcontainers paths.
- Snapshot publishing follows successful nightly or manual dispatch.
