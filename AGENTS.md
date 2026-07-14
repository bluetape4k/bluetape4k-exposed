# AGENTS.md - bluetape4k-exposed

This repository inherits the workspace guidance from `../AGENTS.md`.
Read and follow the workspace root guide first. This file only adds
repo-specific layout, commands, domain rules, and local exceptions.


JetBrains Exposed ORM extensions for Kotlin: JDBC/R2DBC repositories, cache
integrations, JSON serialization, encryption columns, and Spring Boot 4
auto-configuration.

- Group: `io.github.bluetape4k.exposed`
- Publishing: Maven Central through NMCP

## Layout

```text
exposed/
  core/
  dao/
  jdbc/
  r2dbc/
  jdbc-tests/
  r2dbc-tests/
  cache/
  jdbc-caffeine/
  jdbc-lettuce/
  jdbc-redisson/
  r2dbc-caffeine/
  r2dbc-lettuce/
  r2dbc-redisson/
  jackson2/
  jackson3/
  fastjson2/
  tink/
  measured/
  mysql8/
  postgresql/
  bigquery/
  clickhouse/
  trino/
  starrocks/
  cockroachdb/
  duckdb/
  druid/
  timefold-solver-persistence/
  bom/
utils/
  batch/
examples/
  jdbc-demo/
  r2dbc-demo/
  ktor-exposed-demo/
benchmark/
  exposed-benchmark/
docs/
  manual/
spring-boot/
  jdbc/
  r2dbc/
  batch-exposed/
  spring-modulith/
ktor/
  exposed/
buildSrc/
```

## Module Naming

`settings.gradle.kts` maps directories to published-style Gradle names. Examples:

| Directory | Gradle module |
|---|---|
| `exposed/core` | `:bluetape4k-exposed-core` |
| `exposed/jdbc` | `:bluetape4k-exposed-jdbc` |
| `exposed/r2dbc` | `:bluetape4k-exposed-r2dbc` |
| `spring-boot/jdbc` | `:bluetape4k-exposed-spring-boot-jdbc` |
| `spring-boot/r2dbc` | `:bluetape4k-exposed-spring-boot-r2dbc` |
| `ktor/exposed` | `:bluetape4k-exposed-ktor` |
| `examples/jdbc-demo` | `:exposed-spring-boot-jdbc-demo` |
| `examples/r2dbc-demo` | `:exposed-spring-boot-r2dbc-demo` |
| `utils/batch` | `:bluetape4k-exposed-batch` |

## Commands

```bash
./gradlew clean build
./gradlew build -x test --parallel
./gradlew :bluetape4k-exposed-core:build
./gradlew :bluetape4k-exposed-jdbc:test
./gradlew :bluetape4k-exposed-r2dbc:test
./gradlew :bluetape4k-exposed-ktor:test
./gradlew :bluetape4k-exposed-jdbc-lettuce:test
./gradlew test --tests "io.bluetape4k.exposed.jdbc.ExposedJdbcRepositoryTest"
./gradlew :bluetape4k-exposed-spring-boot-jdbc:test
./gradlew exportManualModuleInventory
ruby scripts/manual/validate_manuals.rb build/manual/module-inventory-1.11.0.json docs/manual/manifest.yaml
ruby scripts/manual/validate_release_manuals.rb 1.11.0 <release-commit>
./gradlew detekt
./gradlew publishAggregationToCentralSnapshots
./gradlew publishAggregationToCentralPortal
```

## Design Contracts

- JDBC repository code runs inside Exposed `transaction {}`.
- R2DBC repository code uses `suspendTransaction {}`.
- Cache-backed repositories use decorator-style wrappers around repository
  delegates.
- DDD contracts in this repository must stay Spring-neutral and JaVers-neutral.
  `AggregateRoot`, `DomainEvent`, and event-publishing helpers may support
  repository lifecycle integration, but they must not depend on JaVers audit
  types or encode JaVers commit semantics.
- This repository owns Exposed repository execution, cache behavior,
  transaction boundaries, and Spring Boot/Ktor adapters. Audit history and
  JaVers snapshot persistence belong in `bluetape4k-javers`.
- JSON/encryption column helpers should match existing module-specific DSL
  styles.
- Ktor integration is explicit opt-in only. Applications own `Database`,
  `R2dbcDatabase`, dispatchers, pools, content negotiation, status page
  composition, metrics registries, and resource shutdown.
- Spring Boot modules expose enable annotations and conditional auto-config.
- `docs/manual/` is the source of truth for user-facing module behavior. Keep
  English and Korean landings in parity, pin stable manuals to an exact release
  ref and commit, and link only files that exist in that release tree.
- Consumers declare the `bluetape4k-dependencies` BOM version; manual and README
  examples omit individual bluetape4k library versions.
- Manual diagrams use the approved dark family and keep SVG/PNG pairs. Run the
  `bluetape-diagram` checklist, CairoSVG render, connector audits, and full-size
  PNG inspection after the final SVG change.

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

## Repo-Specific Guards

- For module or artifact moves, scan workflows for both old and new names, then
  update generated catalog/check scripts with the same branch.
- Run PostgreSQL, MySQL, Redis, and other Exposed Testcontainers-backed
  verification sequentially.
