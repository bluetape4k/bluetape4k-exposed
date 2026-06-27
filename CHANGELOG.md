# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.11.0] - 2026-06-27

### Changed

- Opened the `1.11.0` development line after the `1.10.0` stable release.
- Aligned the local `bluetape4k-bom` and direct `bluetape4kVersion`
  references to `1.11.0-SNAPSHOT`.

### Added

- Added Spring Boot Actuator health indicators for JDBC and R2DBC Caffeine cache consistency reports ([#225](https://github.com/bluetape4k/bluetape4k-exposed/issues/225)).
- Added BigQuery query job options and dry-run validation APIs for raw SQL and generated Exposed queries ([#228](https://github.com/bluetape4k/bluetape4k-exposed/issues/228)).
- Added typed Trino JDBC connection options for performance/session properties and documented pushdown verification guidance ([#229](https://github.com/bluetape4k/bluetape4k-exposed/issues/229)).
- Added a credential-free BigQuery dry-run example module and database example matrix ([#230](https://github.com/bluetape4k/bluetape4k-exposed/issues/230)).
- Added the initial `exposed-cockroachdb` module with PostgreSQL-wire JDBC connection helpers and CockroachDB Testcontainers smoke coverage ([#30](https://github.com/bluetape4k/bluetape4k-exposed/issues/30)).
- Documented the `exposed-cockroachdb` DDL compatibility boundary with CockroachDB Testcontainers coverage for primary keys, unique/index DDL, generated IDs, `RETURNING`, metadata, and deferred migration diff semantics ([#31](https://github.com/bluetape4k/bluetape4k-exposed/issues/31)).
- Added CockroachDB-specific serializable transaction retry helpers that retry only SQLSTATE `40001` / `restart transaction` errors, with regression and Testcontainers smoke coverage ([#32](https://github.com/bluetape4k/bluetape4k-exposed/issues/32)).

### Changed

- Prepared the 1.10.0 line to consume `io.github.bluetape4k:bluetape4k-bom:1.10.0`.
- Added a weekly and pull-request migration smoke workflow for the JDBC and R2DBC Spring Boot demo modules ([#226](https://github.com/bluetape4k/bluetape4k-exposed/issues/226)).

## [1.9.2] - 2026-05-26

### Changed

- Prepared the 1.9.2 release line to consume `io.github.bluetape4k:bluetape4k-bom:1.9.2` and the `catalog/2026-05-26-00` shared dependency catalog.
- Adopted the centrally managed JetBrains Exposed Gradle plugin so application and example modules can generate migration scripts from the shared compatibility line ([#213](https://github.com/bluetape4k/bluetape4k-exposed/issues/213)).

### Documentation

- Replaced placeholder Spring Boot JDBC README repository examples with copyable examples ([#208](https://github.com/bluetape4k/bluetape4k-exposed/issues/208)).
- Refreshed root README module relationship diagrams from the current module table and source layout, with matching SVG and PNG assets under `docs/images/readme-diagrams/` ([#209](https://github.com/bluetape4k/bluetape4k-exposed/issues/209)).
- Aligned README dependency snippets with the 1.9.2 stable release coordinates.

## [1.9.0] - 2026-05-22

### Changed

- Prepared the 1.9.0 release line to depend on `io.github.bluetape4k:bluetape4k-bom:1.9.0` and publish immutable `io.github.bluetape4k.exposed` artifacts ([#202](https://github.com/bluetape4k/bluetape4k-exposed/issues/202)).

### Fixed

- Added stable Java serialization contracts to JDBC repository test records, including `AuditableEdgeCaseRecord` ([#200](https://github.com/bluetape4k/bluetape4k-exposed/issues/200)).

## [1.8.1] - 2026-05-22

### Added

- Added `CteTable` plus JDBC/R2DBC `withCte` / `withCtes` SELECT helpers for PostgreSQL/MySQL `WITH` and `WITH RECURSIVE` queries ([#157](https://github.com/bluetape4k/bluetape4k-exposed/issues/157)).
- Added batch `saveAll(entities)` APIs to JDBC and R2DBC repository contracts, plus empty and single-entity edge-case coverage ([#121](https://github.com/bluetape4k/bluetape4k-exposed/issues/121), [#195](https://github.com/bluetape4k/bluetape4k-exposed/issues/195)).
- Added Caffeine repository consistency health checks and explicit Redisson `upsertAll(Map<ID, E>)` cache-warming support ([#123](https://github.com/bluetape4k/bluetape4k-exposed/issues/123), [#126](https://github.com/bluetape4k/bluetape4k-exposed/issues/126)).
- Added Spring Batch auto-configuration properties for virtual-thread executors ([#122](https://github.com/bluetape4k/bluetape4k-exposed/issues/122)).

### Changed

- Prepared the 1.8.1 release line to depend on `io.github.bluetape4k:bluetape4k-bom:1.8.0` instead of the later `1.8.1-SNAPSHOT` BOM.
- Aligned public KDoc and README language for repository and cache APIs touched during the 1.8.1 cycle ([#129](https://github.com/bluetape4k/bluetape4k-exposed/issues/129), [#130](https://github.com/bluetape4k/bluetape4k-exposed/issues/130), [#138](https://github.com/bluetape4k/bluetape4k-exposed/issues/138), [#194](https://github.com/bluetape4k/bluetape4k-exposed/issues/194)).

### Fixed

- Fixed R2DBC batch job execution retry and concurrent insert recovery paths that could throw `NullPointerException` or `NoSuchElementException` ([#117](https://github.com/bluetape4k/bluetape4k-exposed/issues/117), [#124](https://github.com/bluetape4k/bluetape4k-exposed/issues/124), [#165](https://github.com/bluetape4k/bluetape4k-exposed/issues/165)).
- Fixed batch reader close/checkpoint state handling so cursor state is reset safely after close and invalid checkpoint casts fail with context ([#118](https://github.com/bluetape4k/bluetape4k-exposed/issues/118)).
- Fixed Caffeine repository cache miss and cache warming error paths that could cause stale read-through overwrites or swallowed cache failures ([#120](https://github.com/bluetape4k/bluetape4k-exposed/issues/120), [#162](https://github.com/bluetape4k/bluetape4k-exposed/issues/162)).
- Fixed write-behind close/finally behavior so cancellation is preserved and pending batches are flushed before scope shutdown ([#119](https://github.com/bluetape4k/bluetape4k-exposed/issues/119), [#161](https://github.com/bluetape4k/bluetape4k-exposed/issues/161), [#163](https://github.com/bluetape4k/bluetape4k-exposed/issues/163)).
- Added CTE edge-case coverage for multiple CTEs, `UNION`, and invalid field-set usage ([#167](https://github.com/bluetape4k/bluetape4k-exposed/issues/167)).

## [1.8.0] - 2026-05-16

### Added

- Initial release of `bluetape4k-exposed` as a standalone repository.
- `exposed-core`: Core Column types, extension functions for JetBrains Exposed DSL.
- `exposed-dao`: DAO Entity extensions and lifecycle hooks.
- `exposed-jdbc`: JDBC-based Repository pattern with type-safe transaction DSL.
- `exposed-r2dbc`: R2DBC coroutine-native Repository with `suspendTransaction` DSL.
- `exposed-jdbc-tests` / `exposed-r2dbc-tests`: Shared integration test fixtures.
- `exposed-cache`: Cache abstraction interfaces for Repository pattern.
- `exposed-jdbc-caffeine`: JDBC Repository backed by Caffeine local cache.
- `exposed-jdbc-lettuce`: JDBC Repository backed by Lettuce Redis distributed cache.
- `exposed-jdbc-redisson`: JDBC Repository backed by Redisson Redis distributed cache.
- `exposed-r2dbc-caffeine`: R2DBC Repository backed by Caffeine local cache.
- `exposed-r2dbc-lettuce`: R2DBC Repository backed by Lettuce Redis distributed cache.
- `exposed-r2dbc-redisson`: R2DBC Repository backed by Redisson Redis distributed cache.
- `exposed-jackson2`: Jackson 2.x JSON column serialization.
- `exposed-jackson3`: Jackson 3.x JSON column serialization.
- `exposed-fastjson2`: Fastjson2 JSON column serialization.
- `exposed-tink`: Google Tink AES-GCM encrypted column support.
- `exposed-measured`: Micrometer metrics integration for query instrumentation.
- `exposed-postgresql`: PostgreSQL dialect-specific column types and extensions.
- `exposed-mysql8`: MySQL 8 dialect-specific column types and extensions.
- `exposed-bigquery`: BigQuery connector support (requires external SaaS account).
- `exposed-clickhouse`: ClickHouse connector support (requires external SaaS account).
- `exposed-trino`: Trino connector support (requires external SaaS account).
- `exposed-duckdb`: DuckDB embedded analytics database support.
- `exposed-timefold-solver-persistence`: Timefold Solver persistence integration.
- `spring-boot/jdbc`: Spring Boot 4 JDBC auto-configuration.
- `spring-boot/r2dbc`: Spring Boot 4 R2DBC auto-configuration.
- `spring-boot/batch-exposed`: Spring Batch + Exposed integration for Boot 4.
- GitHub Actions CI workflow (H2-only fast tests on PR/push).
- GitHub Actions Nightly workflow (full matrix: H2, PostgreSQL, MySQL, Redis).
- NMCP aggregation publishing to Maven Central (Snapshot + Release).
- `exposed-trino` now provides `TrinoDatabase.connect(dataSource)` for production connection-pool integration (e.g. HikariCP); the overload wraps pool connections in `TrinoConnectionWrapper` to enforce `autoCommit = true` and closes the raw connection on wrapper failure ([#27](https://github.com/bluetape4k/bluetape4k-exposed/issues/27)).
- `exposed-trino` now provides `trinoBatchInsert` for bounded connector-dependent batch writes with generated-key retrieval disabled by default.
- `exposed-trino` now provides `pagedQueryFlow` for page-by-page large result set collection without exposing JDBC `ResultSet` lifetimes outside Exposed transactions.
- Root README hero image plus refreshed purpose, feature, and Mermaid architecture documentation ([PR #64](https://github.com/bluetape4k/bluetape4k-exposed/pull/64)).
- Current WIP queue now tracks the Trino Phase 2 and CockroachDB epics opened after the initial standalone release.
- `exposed-bom` BOM module for Exposed consumers ([PR #15](https://github.com/bluetape4k/bluetape4k-exposed/pull/15)).
- English and Korean README files for the Exposed BOM module ([PR #16](https://github.com/bluetape4k/bluetape4k-exposed/pull/16)).
- Mermaid architecture and sequence diagrams for `exposed-r2dbc-lettuce` documentation ([PR #2](https://github.com/bluetape4k/bluetape4k-exposed/pull/2)).
- `AuditableR2dbcRepository` and `Int`/`Long`/`UUID` convenience interfaces for R2DBC audit update parity.

### Changed

- Upgraded JetBrains Exposed from 1.2.0 to 1.3.0 ([PR #112](https://github.com/bluetape4k/bluetape4k-exposed/pull/112)).
- `ExposedEventPublicationTable.completionAttempts` column changed from nullable `integer().default(0).nullable()` to non-nullable `integer().default(0)`, eliminating the need for `Coalesce` in the `markResubmitted` UPDATE ([PR #113](https://github.com/bluetape4k/bluetape4k-exposed/pull/113), [#101](https://github.com/bluetape4k/bluetape4k-exposed/issues/101)).
- Refreshed WIP queue for 2026-05-12 ([PR #63](https://github.com/bluetape4k/bluetape4k-exposed/pull/63)).
- Build, dependency, and governance maintenance updated NMCP, compatibility guards, and dependency pins ([PR #49](https://github.com/bluetape4k/bluetape4k-exposed/pull/49), [PR #50](https://github.com/bluetape4k/bluetape4k-exposed/pull/50), [PR #53](https://github.com/bluetape4k/bluetape4k-exposed/pull/53), [PR #54](https://github.com/bluetape4k/bluetape4k-exposed/pull/54), [PR #55](https://github.com/bluetape4k/bluetape4k-exposed/pull/55), [PR #56](https://github.com/bluetape4k/bluetape4k-exposed/pull/56), [PR #57](https://github.com/bluetape4k/bluetape4k-exposed/pull/57), [PR #58](https://github.com/bluetape4k/bluetape4k-exposed/pull/58), [PR #59](https://github.com/bluetape4k/bluetape4k-exposed/pull/59), [PR #60](https://github.com/bluetape4k/bluetape4k-exposed/pull/60), [PR #61](https://github.com/bluetape4k/bluetape4k-exposed/pull/61), [PR #62](https://github.com/bluetape4k/bluetape4k-exposed/pull/62)).
- Removed `spring-boot3/*` modules and renamed `spring-boot4/*` to versionless `spring-boot/*`.
- Standardized Spring Boot Gradle catalog aliases to `spring.boot.dependencies`, `spring.cloud.dependencies`, and `libs.plugins.spring.boot`.
- Updated CI, BOM documentation, and module README files for the Spring Boot 4-only contract.
- Reworked CI/Nightly workflows with path filters and new-module test coverage ([PR #11](https://github.com/bluetape4k/bluetape4k-exposed/pull/11)).
- Added `retry=3` to CI and nightly test jobs to reduce transient failure noise ([PR #12](https://github.com/bluetape4k/bluetape4k-exposed/pull/12)).
- Test code migrated from Kluent to `bluetape4k-assertions` ([PR #14](https://github.com/bluetape4k/bluetape4k-exposed/pull/14)).

### Fixed

- **#79** `AbstractJdbcCaffeineRepository` / `AbstractR2dbcCaffeineRepository`: `writeBehindQueue.trySend()` silently dropped entities on channel overflow; now throws `IllegalStateException` ([PR #95](https://github.com/bluetape4k/bluetape4k-exposed/pull/95)).
- **#80** `AbstractJdbcRedissonRepository.invalidateAll()` / `invalidateByPattern()`: unsafe `*ids.toTypedArray<Any>() as Array<ID>` cast replaced with per-element `fastRemove()` to eliminate `ClassCastException` ([PR #96](https://github.com/bluetape4k/bluetape4k-exposed/pull/96)).
- **#81** `PartTreeExposedQuery.executeDelete`: non-atomic SELECT+DELETE replaced with direct `table.deleteWhere { op }` DSL call; return value now reflects actual deleted row count ([PR #97](https://github.com/bluetape4k/bluetape4k-exposed/pull/97)).
- **#82** `DeclaredExposedQuery.coerceIdValue`: bare `rawId as ID` cast guarded with `idType.isInstance(rawId)` check; `IllegalArgumentException` with descriptive message thrown on mismatch ([PR #98](https://github.com/bluetape4k/bluetape4k-exposed/pull/98)).
- **#83** `ClickHouseDatabase.connect()`: close exception after wrapper construction failure now attached via `e.addSuppressed(closeEx)` instead of replacing the original exception ([PR #99](https://github.com/bluetape4k/bluetape4k-exposed/pull/99)).
- **#84** `ExposedEventPublicationRepository.markResubmitted`: non-atomic read-modify-write on `completionAttempts` replaced with single UPDATE using `Coalesce(completionAttempts, 0) + 1` SQL expression ([PR #100](https://github.com/bluetape4k/bluetape4k-exposed/pull/100)).
- **#85** `DeclaredExposedR2dbcQuery.toSqlArg`: `runCatching { resolveColumnType() }.getOrElse { TextColumnType() }` silently swallowed errors; replaced with try/catch that logs a warning before falling back ([PR #102](https://github.com/bluetape4k/bluetape4k-exposed/pull/102)).
- **#86** `ExposedJdbcBatchReader.restoreFrom` / `ExposedR2dbcBatchReader.restoreFrom`: `checkpoint as K` guarded with try/catch `ClassCastException` and rethrows `IllegalArgumentException` with column name and actual type in the message ([PR #103](https://github.com/bluetape4k/bluetape4k-exposed/pull/103)).
- **#87** `DeclaredExposedR2dbcQuery`: broad `catch (_: Exception)` when resolving the ID column by name narrowed to `IllegalArgumentException`; other exceptions rethrow as `IllegalStateException` with method context ([PR #102](https://github.com/bluetape4k/bluetape4k-exposed/pull/102), [PR #114](https://github.com/bluetape4k/bluetape4k-exposed/pull/114)).
- **#88** `ExposedEventPublicationRepository.insertArchive`: TOCTOU existence-check-then-insert race eliminated; duplicate-key `ExposedSQLException` (SQL state `23xxx`) is silently absorbed, all other exceptions rethrow ([PR #104](https://github.com/bluetape4k/bluetape4k-exposed/pull/104)).
- **#89** `BigQueryQueryExecutor.convertValue`: `NumberFormatException` and other conversion errors now wrapped in `IllegalArgumentException` with raw value, column name, and column type context ([PR #105](https://github.com/bluetape4k/bluetape4k-exposed/pull/105)).
- **#90** `PartTreeExposedQuery.executePageQuery`: double `entityClass.find { op }` call for count eliminated; count now uses `table.selectAll().where { op }.count()` directly ([PR #106](https://github.com/bluetape4k/bluetape4k-exposed/pull/106)).
- Added the `DefaultFastjsonSerializer` facade for `exposed-fastjson2` and aligned module defaults with Jackson serializer parity.
- Corrected the initial `utils/batch` Gradle module naming mismatch; current module path is `:bluetape4k-exposed-batch` ([PR #13](https://github.com/bluetape4k/bluetape4k-exposed/pull/13)).
