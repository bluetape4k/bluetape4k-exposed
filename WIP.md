# WIP - bluetape4k-exposed

Snapshot: 2026-05-18 KST
Scope: open GitHub issues assigned to `debop`, created on or after 2026-01-01.

## Recently Completed

### CTE Query DSL (2026-05-18)

- [#157](https://github.com/bluetape4k/bluetape4k-exposed/issues/157) added `CteTable` and JDBC/R2DBC
  `withCte` / `withCtes` SELECT helpers for PostgreSQL/MySQL `WITH` and `WITH RECURSIVE` queries.
- The implementation keeps CTE body predicates and final SELECT predicates on the same Exposed `QueryBuilder`
  path, preserving prepared-parameter binding order.
- Targeted CTE tests passed for JDBC and R2DBC against H2, PostgreSQL, and MySQL 8.

### 1.8.0 Release (2026-05-16) — All 18 PRs merged and CI green

**Critical bug fixes (PRs #95–#108):**

| Issue | PR | Description |
|-------|----|-------------|
| #79 | #95 | `writeBehindQueue.trySend()` overflow now throws `IllegalStateException` |
| #80 | #96 | Redisson `invalidateAll` unsafe cast replaced with per-element `fastRemove` |
| #81 | #97 | `executeDelete` uses atomic `table.deleteWhere {}` DSL |
| #82 | #98 | `coerceIdValue` bare cast guarded with `isInstance` check |
| #83 | #99 | ClickHouse close exception attached via `addSuppressed` |
| #84 | #100 | `markResubmitted` counter uses `completionAttempts + 1` SQL expression |
| #85 | #102 | R2DBC `toSqlArg` silent fallback replaced with warn-then-fallback |
| #87 | #102 + #114 | Broad `catch (Exception)` in ID column extraction narrowed to `IllegalArgumentException` |
| #86 | #103 | `restoreFrom` checkpoint cast guarded with `ClassCastException` handler |
| #88 | #104 | `insertArchive` TOCTOU race replaced with INSERT + duplicate-key handler |
| #89 | #105 | `convertValue` wraps errors with column name and raw value context |
| #90 | #106 | `executePageQuery` double `find()` replaced with `entityClass.find { op }.count()` |

**Documentation and maintenance:**

| PR | Description |
|----|-------------|
| #107 | CHANGELOG `[Unreleased]` promoted to `[1.8.0] - 2026-05-16` |
| #108 | Korean KDoc converted to English for all changed public APIs (#79–#90) |
| #110 | WIP refresh for 1.8.0 pre-release gate |
| #111 | README.md and README.ko.md structural alignment |
| #112 | JetBrains Exposed upgraded from 1.2.0 to 1.3.0 |
| #113 | `completionAttempts` column changed to non-nullable `integer().default(0)` (#101) |
| #115 | TrinoDatabase KDoc converted to English; `connect(DataSource)` added to CHANGELOG (#25) |

**Trino Phase 2 (PRs merged before 1.8.0 gate):**
- `TrinoDatabase.connect(dataSource)` — HikariCP integration via `TrinoConnectionWrapper` (#27)
- `trinoBatchInsert` — bounded connector-safe batch writes (#29)
- `pagedQueryFlow` — page-by-page large result streaming (#28)

**Nightly CI confirmation:** Run 25962597252 — SUCCESS (2026-05-16)

## Current Direction

**1.8.0 is released; develop is open for 1.8.1-SNAPSHOT.**

All pre-release blocker issues (#79–#94, #101) are resolved and released in `1.8.0`.
The next patch snapshot line is `1.8.1-SNAPSHOT`, with #120 as the first priority.

Post-release work resumes with the CockroachDB epic and Spring Boot R2DBC raw SQL.

## 1.8.1 Bug Queue

Bugs identified by post-release code review (2026-05-16). All assigned to milestone `1.8.1`.

| Priority | Issue | Module | Description |
|----------|-------|--------|-------------|
| P0 | [#161](https://github.com/bluetape4k/bluetape4k-exposed/issues/161) | exposed-r2dbc-caffeine | R2DBC write-behind `finally` block calls suspend `flushBatch()` without `NonCancellable` — silent data loss on cancellation |
| P0 | [#120](https://github.com/bluetape4k/bluetape4k-exposed/issues/120) | jdbc-caffeine / r2dbc-caffeine | `get()` / `getAll()` non-atomic cache-miss → stale-read overwrite under concurrency |
| P1 | [#163](https://github.com/bluetape4k/bluetape4k-exposed/issues/163) | exposed-r2dbc-caffeine | `close()` cancels coroutine scope before write-behind flush completes — data loss (fix after #161) |
| P1 | [#162](https://github.com/bluetape4k/bluetape4k-exposed/issues/162) | exposed-jdbc-caffeine | `findAll()` cache-warming uses bare `runCatching{}` — all exceptions silently dropped |
| P1 | [#117](https://github.com/bluetape4k/bluetape4k-exposed/issues/117) | batch | `findOrCreateJobExecution`: `firstOrNull()!!` NPE after concurrent INSERT failure |
| P1 | [#118](https://github.com/bluetape4k/bluetape4k-exposed/issues/118) | jdbc-caffeine / r2dbc-caffeine | `close()` uses `runCatching{}` → swallows `CancellationException` |
| P2 | [#119](https://github.com/bluetape4k/bluetape4k-exposed/issues/119) | batch | `ExposedJdbcBatchReader` / `ExposedR2dbcBatchReader`: `close()` does not reset key-cursor state |

## 1.9.0 Feature Queue

Features identified by post-release review (2026-05-16). All assigned to milestone `1.9.0`.

| Priority | Issue | Module | Description |
|----------|-------|--------|-------------|
| P1 | [#121](https://github.com/bluetape4k/bluetape4k-exposed/issues/121) | exposed-jdbc / exposed-r2dbc | Add `saveAll(entities)` to `JdbcRepository` and `R2dbcRepository` interfaces |
| P1 | [#30](https://github.com/bluetape4k/bluetape4k-exposed/issues/30) | exposed-cockroachdb | CockroachDB module scaffold and Testcontainers smoke test |
| P2 | [#122](https://github.com/bluetape4k/bluetape4k-exposed/issues/122) | spring-boot/batch-exposed | Spring Batch executor auto-configuration properties |
| P2 | [#126](https://github.com/bluetape4k/bluetape4k-exposed/issues/126) | jdbc-redisson / r2dbc-redisson | Add `upsertAll(Map<ID, E>)` batch API to Redisson-backed repositories |
| P2 | [#123](https://github.com/bluetape4k/bluetape4k-exposed/issues/123) | jdbc-caffeine / r2dbc-caffeine | Add `validateConsistency()` cache health check |
| P2 | [#124](https://github.com/bluetape4k/bluetape4k-exposed/issues/124) | batch | Integration tests for concurrent `findOrCreateJobExecution` race condition |
| P2 | [#31](https://github.com/bluetape4k/bluetape4k-exposed/issues/31) | exposed-cockroachdb | CockroachDB dialect/DDL differences (depends on `#30`) |
| P2 | [#32](https://github.com/bluetape4k/bluetape4k-exposed/issues/32) | exposed-cockroachdb | CockroachDB serializable retry guide and tests (depends on `#30`/`#31`) |
| P3 | [#4](https://github.com/bluetape4k/bluetape4k-exposed/issues/4) | new module | exposed-bucket4j distributed rate limiting |
| P3 | [#5](https://github.com/bluetape4k/bluetape4k-exposed/issues/5) | new module | Spring Modulith Exposed (hold until Boot 4 event boundaries stable) |
| P3 | [#24](https://github.com/bluetape4k/bluetape4k-exposed/issues/24) | epic | CockroachDB epic — planning container; do not implement directly |

## Dependency Map

```text
1.8.0 release
  -> Tag 1.8.0
  -> ./gradlew publishAggregationToCentralPortal

1.8.1 patch
  -> #161 R2DBC write-behind finally NonCancellable (P0) — fix first
  -> #163 R2DBC write-behind close() scope race (P1) — depends on #161
  -> #162 AbstractJdbcCaffeineRepository.findAll() runCatching{} swallows errors (P1)
  -> #120 Caffeine cache stale-read race (P0)
  -> #117 batch NPE on concurrent INSERT
  -> #118 CancellationException swallowed in close()
  -> #119 BatchReader close() state not reset

1.9.0 minor
  -> #121 saveAll() interface API
  -> #30 CockroachDB scaffold
      -> #31 dialect/DDL
      -> #32 serializable retry
  -> #122 Batch executor properties
  -> #126 Redisson upsertAll
  -> #123 Caffeine validateConsistency
  -> #124 batch concurrent test coverage
  -> #4/#5 (hold)
```

## WIP Limits

| Lane | Limit | Current next |
|------|------:|--------------|
| Release gate | 1 | Closed for 1.8.0; publish 1.8.1 snapshots from `develop`. |
| 1.8.1 patch | 1 | Fix #120 (Caffeine stale-read, P0) first. |
| Database connector | 1 | Resume after 1.8.0 ships; start with `#30`. |
| Lower-priority integrations | 0 | Hold `#4`/`#5` until connector queue is stable. |
| Build/CI maintenance | 1 | Handle only concrete failures from Nightly/CI. |
