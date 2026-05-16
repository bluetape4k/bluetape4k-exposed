# WIP - bluetape4k-exposed

Snapshot: 2026-05-16 KST
Scope: open GitHub issues assigned to `debop`, created on or after 2026-01-01.

## Recently Completed

- **1.8.0 pre-release blocker fixes** (2026-05-16) — 12 PRs merged or in CI:
  - #79 → PR #95: `writeBehindQueue.trySend()` overflow now throws `IllegalStateException`
  - #80 → PR #96: Redisson `invalidateAll` unsafe cast replaced with per-element `fastRemove`
  - #81 → PR #97: `executeDelete` now uses atomic `table.deleteWhere {}` DSL
  - #82 → PR #98: `coerceIdValue` bare cast guarded with `isInstance` check
  - #83 → PR #99: ClickHouse close exception attached via `addSuppressed`
  - #84 → PR #100: `markResubmitted` counter now uses `Coalesce + 1` SQL expression
  - #85 + #87 → PR #102: R2DBC silent fallback and broad catch narrowed
  - #86 → PR #103: `restoreFrom` checkpoint cast guarded with `ClassCastException` handler
  - #88 → PR #104: `insertArchive` TOCTOU race replaced with INSERT + duplicate-key handler
  - #89 → PR #105: `convertValue` wraps conversion errors with column context
  - #90 → PR #106: `executePageQuery` double `find()` replaced with DSL count
  - #91 → PR #107: CHANGELOG `[Unreleased]` promoted to `[1.8.0] - 2026-05-16`
  - #92 → PR #108: Korean KDoc converted to English for all changed public APIs
- Spring Boot 3 removal and Spring Boot 4 versionless rename are closed.
- Query lookup, serializer parity, and `AuditableR2dbcRepository` work are merged into `develop`.
- Trino `trinoBatchInsert` and `pagedQueryFlow` merged.

## Current Direction

**1.8.0 Maven Central release gate** is the immediate priority.

Remaining pre-release blockers (#93 WIP refresh, #94 README alignment) are in progress.
After all 16 PRs are merged and CI is green, the release can be tagged and published.

Post-release backlog resumes the database connector and Spring Boot R2DBC tracks below.

## Pre-release Gate Status (1.8.0)

| Issue | PR | Status |
|-------|----|--------|
| #79 | #95 | CI pending |
| #80 | #96 | CI pending |
| #81 | #97 | CI pending |
| #82 | #98 | CI pending |
| #83 | #99 | CI pending |
| #84 | #100 | CI pending |
| #85 + #87 | #102 | CI pending |
| #86 | #103 | CI pending |
| #88 | #104 | CI pending |
| #89 | #105 | CI pending |
| #90 | #106 | CI pending |
| #91 | #107 | CI pending |
| #92 | #108 | CI pending |
| #93 | this PR | in progress |
| #94 | — | next |

## Follow-up Issues Registered

| Issue | Description |
|-------|-------------|
| #101 | `completionAttempts` column should be `integer().default(0)` (non-nullable) |
| #109 | Upgrade JetBrains Exposed to 1.3.0 |

## Post-release Priority Queue

| Priority | Issue | Difficulty | Notes |
|---|---|---:|---|
| P1 | [#26](https://github.com/bluetape4k/bluetape4k-exposed/issues/26) Spring Boot R2DBC raw SQL support | M | Directly improves repository query ergonomics; keep JDBC/R2DBC semantics explicit. |
| P1 | [#27](https://github.com/bluetape4k/bluetape4k-exposed/issues/27) Trino DataSource connection support | M | First executable slice for Trino Phase 2. |
| P1 | [#30](https://github.com/bluetape4k/bluetape4k-exposed/issues/30) CockroachDB module scaffold and smoke test | M | Establishes the module and Testcontainers baseline before dialect details. |
| P1 | [#101](https://github.com/bluetape4k/bluetape4k-exposed/issues/101) `completionAttempts` non-nullable default | S | Schema fix; affects `exposed-spring-modulith` module. |
| P2 | [#109](https://github.com/bluetape4k/bluetape4k-exposed/issues/109) Upgrade JetBrains Exposed to 1.3.0 | M | Check for deprecation removals and dialect-level API changes. |
| P2 | [#28](https://github.com/bluetape4k/bluetape4k-exposed/issues/28) Trino streaming/paged query API | M | Depends on the connection shape from `#27`. |
| P2 | [#29](https://github.com/bluetape4k/bluetape4k-exposed/issues/29) Trino batch insert/write optimization | M | Depends on Phase 2 connection and query behavior. |
| P2 | [#31](https://github.com/bluetape4k/bluetape4k-exposed/issues/31) CockroachDB dialect/DDL differences | M | Depends on `#30`; must verify PostgreSQL compatibility boundaries. |
| P2 | [#32](https://github.com/bluetape4k/bluetape4k-exposed/issues/32) CockroachDB serializable retry guide and tests | M | Depends on `#30/#31`; should document retry contracts clearly. |
| P3 | [#4](https://github.com/bluetape4k/bluetape4k-exposed/issues/4) exposed-bucket4j | L | Useful distributed rate limiting feature; start after connector epics are scheduled. |
| P3 | [#5](https://github.com/bluetape4k/bluetape4k-exposed/issues/5) Spring Modulith Exposed | L | Wait until Boot 4 event publication boundaries are stable. |
| P3 | [#24](https://github.com/bluetape4k/bluetape4k-exposed/issues/24) CockroachDB epic | L | Planning container for `#30/#31/#32`; do not implement directly. |
| P3 | [#25](https://github.com/bluetape4k/bluetape4k-exposed/issues/25) Trino Phase 2 epic | L | Planning container for `#27/#28/#29`; do not implement directly. |

## Dependency Map

```text
1.8.0 release gate
  -> #93 WIP refresh (this PR)
  -> #94 README alignment (next)
  -> Merge all PRs #95-#109
  -> Tag and publish to Maven Central

#25 Trino Phase 2 epic (post-release)
  -> #27 DataSource connection support
      -> #28 streaming/paged query API
      -> #29 batch insert/write optimization

#24 CockroachDB epic (post-release)
  -> #30 module scaffold + smoke test
      -> #31 dialect/DDL differences
      -> #32 serializable retry guide + regression tests

#26 Spring Boot R2DBC raw SQL (post-release, independent)

#101 completionAttempts schema fix (post-release, small)
#109 Exposed 1.3.0 upgrade (post-release)
```

## WIP Limits

| Lane | Limit | Current next |
|---|---:|---|
| 1.8.0 release gate | 1 | Complete #94 README alignment, then merge all PRs. |
| Database connector | 1 | Resume after 1.8.0 ships; choose `#27` or `#30`. |
| Spring Boot repository | 1 | `#26` can proceed independently after release. |
| Lower-priority integrations | 0 | Hold `#4/#5` until connector queue is stable. |
| Build/CI maintenance | 1 | Handle only concrete failures from Nightly/CI. |
