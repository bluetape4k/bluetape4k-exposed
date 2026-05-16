# WIP - bluetape4k-exposed

Snapshot: 2026-05-16 KST
Scope: open GitHub issues assigned to `debop`, created on or after 2026-01-01.

## Recently Completed

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

**1.8.0 release is ready for tagging and Maven Central publishing.**

All pre-release blocker issues (#79–#94, #101) are resolved and merged into `develop`.
Nightly CI is green. The next step is to tag `1.8.0` and run the publish pipeline.

Post-release work resumes with the CockroachDB epic and Spring Boot R2DBC raw SQL.

## Post-release Priority Queue

| Priority | Issue | Difficulty | Notes |
|----------|-------|:----------:|-------|
| P1 | [#30](https://github.com/bluetape4k/bluetape4k-exposed/issues/30) CockroachDB module scaffold and smoke test | M | Establishes module and Testcontainers baseline before dialect details. |
| P1 | [#26](https://github.com/bluetape4k/bluetape4k-exposed/issues/26) Spring Boot R2DBC raw SQL support | M | Improves repository query ergonomics; independent of connector epics. |
| P2 | [#31](https://github.com/bluetape4k/bluetape4k-exposed/issues/31) CockroachDB dialect/DDL differences | M | Depends on `#30`; verify PostgreSQL compatibility boundaries. |
| P2 | [#32](https://github.com/bluetape4k/bluetape4k-exposed/issues/32) CockroachDB serializable retry guide and tests | M | Depends on `#30`/`#31`; document retry contracts clearly. |
| P3 | [#4](https://github.com/bluetape4k/bluetape4k-exposed/issues/4) exposed-bucket4j | L | Useful distributed rate limiting; start after connector epics are scheduled. |
| P3 | [#5](https://github.com/bluetape4k/bluetape4k-exposed/issues/5) Spring Modulith Exposed | L | Wait until Boot 4 event publication boundaries are stable. |
| P3 | [#24](https://github.com/bluetape4k/bluetape4k-exposed/issues/24) CockroachDB epic | L | Planning container for `#30`/`#31`/`#32`; do not implement directly. |

## Dependency Map

```text
1.8.0 release
  -> Tag 1.8.0
  -> ./gradlew publishAggregationToCentralPortal

#24 CockroachDB epic (post-release)
  -> #30 module scaffold + smoke test
      -> #31 dialect/DDL differences
      -> #32 serializable retry guide + regression tests

#26 Spring Boot R2DBC raw SQL (post-release, independent)

#4/#5 lower-priority integrations (hold until connector queue is stable)
```

## WIP Limits

| Lane | Limit | Current next |
|------|------:|--------------|
| Release gate | 1 | Tag 1.8.0 and publish to Maven Central. |
| Database connector | 1 | Resume after 1.8.0 ships; start with `#30`. |
| Spring Boot repository | 1 | `#26` can proceed independently after release. |
| Lower-priority integrations | 0 | Hold `#4`/`#5` until connector queue is stable. |
| Build/CI maintenance | 1 | Handle only concrete failures from Nightly/CI. |
