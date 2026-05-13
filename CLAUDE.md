# CLAUDE.md - bluetape4k-exposed

JetBrains Exposed ORM extensions for Kotlin. This repository provides JDBC and
R2DBC repository patterns, cache integration, JSON/encrypted columns, dialect
extensions, and Spring Boot 4 auto-configuration.

- **Group**: `io.bluetape4k.exposed`
- **Publishing**: Maven Central through NMCP

## Repository Layout

```text
exposed/
  exposed-core/                         # Column types and common DSL helpers
  exposed-dao/                          # DAO entity extensions and lifecycle hooks
  exposed-jdbc/                         # JDBC repository pattern and transaction DSL
  exposed-r2dbc/                        # R2DBC coroutine repositories and suspend transactions
  exposed-jdbc-tests/                   # JDBC integration test fixtures
  exposed-r2dbc-tests/                  # R2DBC integration test fixtures
  exposed-cache/                        # Repository cache abstraction
  exposed-jdbc-caffeine/                # JDBC + Caffeine cache
  exposed-jdbc-lettuce/                 # JDBC + Lettuce Redis cache
  exposed-jdbc-redisson/                # JDBC + Redisson Redis cache
  exposed-r2dbc-caffeine/               # R2DBC + Caffeine cache
  exposed-r2dbc-lettuce/                # R2DBC + Lettuce Redis cache
  exposed-r2dbc-redisson/               # R2DBC + Redisson Redis cache
  exposed-jackson2/                     # Jackson 2.x columns
  exposed-jackson3/                     # Jackson 3.x columns
  exposed-fastjson2/                    # Fastjson2 columns
  exposed-tink/                         # Google Tink encrypted columns
  exposed-measured/                     # Micrometer metrics
  exposed-mysql8/                       # MySQL 8 dialect helpers
  exposed-postgresql/                   # PostgreSQL dialect helpers
  exposed-bigquery/                     # BigQuery support
  exposed-clickhouse/                   # ClickHouse support
  exposed-trino/                        # Trino support
  exposed-duckdb/                       # DuckDB support
  exposed-timefold-solver-persistence/  # Timefold Solver persistence
utils/
  batch/                                # Batch utilities
spring-boot/
  exposed-jdbc/                         # Spring Boot 4 JDBC auto-configuration
  exposed-jdbc-demo/                    # JDBC demo app
  exposed-r2dbc/                        # Spring Boot 4 R2DBC auto-configuration
  exposed-r2dbc-demo/                   # R2DBC demo app
  batch-exposed/                        # Spring Batch + Exposed integration
```

## Module Naming

`settings.gradle.kts` maps directories to published-style Gradle module names:

| Directory | Gradle module |
|---|---|
| `exposed/exposed-core` | `:bluetape4k-exposed-core` |
| `exposed/exposed-jdbc` | `:bluetape4k-exposed-jdbc` |
| `exposed/exposed-r2dbc` | `:bluetape4k-exposed-r2dbc` |
| `spring-boot/exposed-jdbc` | `:bluetape4k-spring-boot-exposed-jdbc` |
| `spring-boot/exposed-r2dbc` | `:bluetape4k-spring-boot-exposed-r2dbc` |
| `utils/batch` | `:bluetape4k-batch` |

## Build Commands

```bash
./gradlew clean build
./gradlew build -x test --parallel
./gradlew :bluetape4k-exposed-core:build
./gradlew :bluetape4k-exposed-jdbc:test
./gradlew :bluetape4k-exposed-r2dbc:test
./gradlew :bluetape4k-exposed-jdbc-lettuce:test
./gradlew test --tests "io.bluetape4k.exposed.jdbc.ExposedJdbcRepositoryTest"
./gradlew :bluetape4k-spring-boot-exposed-jdbc:test
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

## Documentation Rules

- Keep `README.md` and `README.ko.md` structurally aligned.
- Store shared README images under `docs/assets/` and reference them with the
  same relative path from both locales.
- Keep this file and other agent-facing guidance in English.
