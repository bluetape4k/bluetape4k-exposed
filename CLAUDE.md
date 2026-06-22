# CLAUDE.md - bluetape4k-exposed

JetBrains Exposed ORM extensions for Kotlin. This repository provides JDBC and
R2DBC repository patterns, cache integration, JSON/encrypted columns, dialect
extensions, and Spring Boot 4 auto-configuration.

- **Group**: `io.github.bluetape4k.exposed`
- **Publishing**: Maven Central through NMCP

## Repository Layout

```text
exposed/
  core/                                # Column types and common DSL helpers
  dao/                                 # DAO entity extensions and lifecycle hooks
  jdbc/                                # JDBC repository pattern and transaction DSL
  r2dbc/                               # R2DBC coroutine repositories and suspend transactions
  jdbc-tests/                          # JDBC integration test fixtures
  r2dbc-tests/                         # R2DBC integration test fixtures
  cache/                               # Repository cache abstraction
  jdbc-caffeine/                       # JDBC + Caffeine cache
  jdbc-lettuce/                        # JDBC + Lettuce Redis cache
  jdbc-redisson/                       # JDBC + Redisson Redis cache
  r2dbc-caffeine/                      # R2DBC + Caffeine cache
  r2dbc-lettuce/                       # R2DBC + Lettuce Redis cache
  r2dbc-redisson/                      # R2DBC + Redisson Redis cache
  jackson2/                            # Jackson 2.x columns
  jackson3/                            # Jackson 3.x columns
  fastjson2/                           # Fastjson2 columns
  tink/                                # Google Tink encrypted columns
  measured/                            # Micrometer metrics
  mysql8/                              # MySQL 8 dialect helpers
  postgresql/                          # PostgreSQL dialect helpers
  bigquery/                            # BigQuery support
  clickhouse/                          # ClickHouse support
  trino/                               # Trino support
  starrocks/                           # StarRocks support
  cockroachdb/                         # CockroachDB support
  duckdb/                              # DuckDB support
  timefold-solver-persistence/         # Timefold Solver persistence
  bom/                                 # BOM for all publishable modules
utils/
  batch/                                # Batch utilities → bluetape4k-exposed-batch
spring-boot/
  jdbc/                                 # Spring Boot 4 JDBC auto-configuration → bluetape4k-exposed-spring-boot-jdbc
  jdbc-demo/                            # JDBC demo app (not published)
  r2dbc/                                # Spring Boot 4 R2DBC auto-configuration → bluetape4k-exposed-spring-boot-r2dbc
  r2dbc-demo/                           # R2DBC demo app (not published)
  batch-exposed/                        # Spring Batch + Exposed integration → bluetape4k-exposed-spring-boot-batch
  spring-modulith/                      # Spring Modulith integration → bluetape4k-exposed-spring-modulith
```

## Module Naming

`settings.gradle.kts` maps directories to published-style Gradle module names:

| Directory | Gradle module |
|---|---|
| `exposed/core` | `:bluetape4k-exposed-core` |
| `exposed/jdbc` | `:bluetape4k-exposed-jdbc` |
| `exposed/r2dbc` | `:bluetape4k-exposed-r2dbc` |
| `spring-boot/jdbc` | `:bluetape4k-exposed-spring-boot-jdbc` |
| `spring-boot/r2dbc` | `:bluetape4k-exposed-spring-boot-r2dbc` |
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
