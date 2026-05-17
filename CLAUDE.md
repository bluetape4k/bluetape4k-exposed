# CLAUDE.md - bluetape4k-exposed

JetBrains Exposed ORM extensions for Kotlin. This repository provides JDBC and
R2DBC repository patterns, cache integration, JSON/encrypted columns, dialect
extensions, and Spring Boot 4 auto-configuration.

- **Group**: `io.github.bluetape4k.exposed`
- **Publishing**: Maven Central through NMCP

## Repository Layout

```text
exposed/
  bluetape4k-exposed-core/                         # Column types and common DSL helpers
  bluetape4k-exposed-dao/                          # DAO entity extensions and lifecycle hooks
  bluetape4k-exposed-jdbc/                         # JDBC repository pattern and transaction DSL
  bluetape4k-exposed-r2dbc/                        # R2DBC coroutine repositories and suspend transactions
  bluetape4k-exposed-jdbc-tests/                   # JDBC integration test fixtures
  bluetape4k-exposed-r2dbc-tests/                  # R2DBC integration test fixtures
  bluetape4k-exposed-cache/                        # Repository cache abstraction
  bluetape4k-exposed-jdbc-caffeine/                # JDBC + Caffeine cache
  bluetape4k-exposed-jdbc-lettuce/                 # JDBC + Lettuce Redis cache
  bluetape4k-exposed-jdbc-redisson/                # JDBC + Redisson Redis cache
  bluetape4k-exposed-r2dbc-caffeine/               # R2DBC + Caffeine cache
  bluetape4k-exposed-r2dbc-lettuce/                # R2DBC + Lettuce Redis cache
  bluetape4k-exposed-r2dbc-redisson/               # R2DBC + Redisson Redis cache
  bluetape4k-exposed-jackson2/                     # Jackson 2.x columns
  bluetape4k-exposed-jackson3/                     # Jackson 3.x columns
  bluetape4k-exposed-fastjson2/                    # Fastjson2 columns
  bluetape4k-exposed-tink/                         # Google Tink encrypted columns
  bluetape4k-exposed-measured/                     # Micrometer metrics
  bluetape4k-exposed-mysql8/                       # MySQL 8 dialect helpers
  bluetape4k-exposed-postgresql/                   # PostgreSQL dialect helpers
  bluetape4k-exposed-bigquery/                     # BigQuery support
  bluetape4k-exposed-clickhouse/                   # ClickHouse support
  bluetape4k-exposed-trino/                        # Trino support
  bluetape4k-exposed-duckdb/                       # DuckDB support
  bluetape4k-exposed-timefold-solver-persistence/  # Timefold Solver persistence
  bluetape4k-exposed-bom/                          # BOM for all publishable modules
utils/
  batch/                                # Batch utilities → bluetape4k-exposed-batch
spring-boot/
  exposed-jdbc/                         # Spring Boot 4 JDBC auto-configuration → bluetape4k-exposed-spring-boot-jdbc
  exposed-jdbc-demo/                    # JDBC demo app (not published)
  exposed-r2dbc/                        # Spring Boot 4 R2DBC auto-configuration → bluetape4k-exposed-spring-boot-r2dbc
  exposed-r2dbc-demo/                   # R2DBC demo app (not published)
  batch-exposed/                        # Spring Batch + Exposed integration → bluetape4k-exposed-spring-boot-batch
  exposed-spring-modulith/              # Spring Modulith integration → bluetape4k-exposed-spring-modulith
```

## Module Naming

`settings.gradle.kts` maps directories to published-style Gradle module names:

| Directory | Gradle module |
|---|---|
| `exposed/exposed-core` | `:bluetape4k-exposed-core` |
| `exposed/exposed-jdbc` | `:bluetape4k-exposed-jdbc` |
| `exposed/exposed-r2dbc` | `:bluetape4k-exposed-r2dbc` |
| `spring-boot/exposed-jdbc` | `:bluetape4k-exposed-spring-boot-jdbc` |
| `spring-boot/exposed-r2dbc` | `:bluetape4k-exposed-spring-boot-r2dbc` |
| `utils/batch` | `:bluetape4k-exposed-batch` |

## Build Commands

```bash
./gradlew clean build
./gradlew build -x test --parallel
./gradlew :bluetape4k-exposed-core:build
./gradlew :bluetape4k-exposed-jdbc:test
./gradlew :bluetape4k-exposed-r2dbc:test
./gradlew :bluetape4k-exposed-jdbc-lettuce:test
./gradlew test --tests "io.bluetape4k.exposed.jdbc.ExposedJdbcRepositoryTest"
./gradlew :bluetape4k-exposed-spring-boot-jdbc:test
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
