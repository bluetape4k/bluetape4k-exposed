# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- Removed `spring-boot3/*` modules and renamed `spring-boot4/*` to versionless `spring-boot/*`.
- Standardized Spring Boot Gradle catalog aliases to `spring.boot.dependencies`, `spring.cloud.dependencies`, and `libs.plugins.spring.boot`.
- Updated CI, BOM documentation, and module README files for the Spring Boot 4-only contract.

## [1.8.0] - 2026-05-07

### Added

- Initial release of bluetape4k-exposed as a standalone repository
- `exposed-core`: Core Column types, extension functions for JetBrains Exposed DSL
- `exposed-dao`: DAO Entity extensions and lifecycle hooks
- `exposed-jdbc`: JDBC-based Repository pattern with type-safe transaction DSL
- `exposed-r2dbc`: R2DBC coroutine-native Repository with `suspendTransaction` DSL
- `exposed-jdbc-tests` / `exposed-r2dbc-tests`: Shared integration test fixtures
- `exposed-cache`: Cache abstraction interfaces for Repository pattern
- `exposed-jdbc-caffeine`: JDBC Repository backed by Caffeine local cache
- `exposed-jdbc-lettuce`: JDBC Repository backed by Lettuce Redis distributed cache
- `exposed-jdbc-redisson`: JDBC Repository backed by Redisson Redis distributed cache
- `exposed-r2dbc-caffeine`: R2DBC Repository backed by Caffeine local cache
- `exposed-r2dbc-lettuce`: R2DBC Repository backed by Lettuce Redis distributed cache
- `exposed-r2dbc-redisson`: R2DBC Repository backed by Redisson Redis distributed cache
- `exposed-jackson2`: Jackson 2.x JSON column serialization
- `exposed-jackson3`: Jackson 3.x JSON column serialization
- `exposed-fastjson2`: Fastjson2 JSON column serialization
- `exposed-tink`: Google Tink AES-GCM encrypted column support
- `exposed-measured`: Micrometer metrics integration for query instrumentation
- `exposed-postgresql`: PostgreSQL dialect-specific column types and extensions
- `exposed-mysql8`: MySQL 8 dialect-specific column types and extensions
- `exposed-bigquery`: BigQuery connector support (requires external SaaS account)
- `exposed-clickhouse`: ClickHouse connector support (requires external SaaS account)
- `exposed-trino`: Trino connector support (requires external SaaS account)
- `exposed-duckdb`: DuckDB embedded analytics database support
- `exposed-timefold-solver-persistence`: Timefold Solver persistence integration
- `spring-boot3/exposed-jdbc`: Spring Boot 3.x JDBC auto-configuration
- `spring-boot3/exposed-r2dbc`: Spring Boot 3.x R2DBC auto-configuration
- `spring-boot3/batch-exposed`: Spring Batch + Exposed integration for Boot 3.x
- `spring-boot4/exposed-jdbc`: Spring Boot 4.x JDBC auto-configuration
- `spring-boot4/exposed-r2dbc`: Spring Boot 4.x R2DBC auto-configuration
- `spring-boot4/batch-exposed`: Spring Batch + Exposed integration for Boot 4.x
- GitHub Actions CI workflow (H2-only fast tests on PR/push)
- GitHub Actions Nightly workflow (full matrix: H2, PostgreSQL, MySQL, Redis)
- NMCP aggregation publishing to Maven Central (Snapshot + Release)
