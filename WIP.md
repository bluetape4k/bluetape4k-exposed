# WIP - bluetape4k-exposed

Snapshot: 2026-05-13 KST
Scope: open GitHub issues assigned to `debop`, created on or after 2026-01-01.
Open count: 12 issues.

## Recently Completed

- Spring Boot 3 removal and Spring Boot 4 versionless rename are closed.
- Query lookup, serializer parity, and `AuditableR2dbcRepository` work are
  merged into `develop`.
- R2DBC MySQL timeout mitigation, Nightly lane split, lessons guidance, Kover
  policy, and Dependabot governance are merged.
- PR #63 refreshed the WIP queue after the Trino and CockroachDB epics were
  opened.

## Current Direction

The active backlog is now post-R2DBC expansion. Keep database connector work
separate from Spring Boot repository query work so testcontainers and dialect
behavior stay reviewable.

- Trino Phase 2 is tracked by epic `#25` and implementation issues `#27/#28/#29`.
- CockroachDB support is tracked by epic `#24` and implementation issues
  `#30/#31/#32`.
- Spring Boot R2DBC raw SQL support is tracked by `#26`.
- Earlier integration ideas `#4/#5` remain useful but should not displace the
  open database-connector epics.

## Priority Queue

| Priority | Issue | Difficulty | Notes |
|---|---|---:|---|
| P1 | [#26](https://github.com/bluetape4k/bluetape4k-exposed/issues/26) Spring Boot R2DBC raw SQL support | M | Directly improves repository query ergonomics; keep JDBC/R2DBC semantics explicit. |
| P1 | [#27](https://github.com/bluetape4k/bluetape4k-exposed/issues/27) Trino DataSource connection support | M | First executable slice for Trino Phase 2. |
| P1 | [#30](https://github.com/bluetape4k/bluetape4k-exposed/issues/30) CockroachDB module scaffold and smoke test | M | Establishes the module and Testcontainers baseline before dialect details. |
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
#25 Trino Phase 2 epic
  -> #27 DataSource connection support
      -> #28 streaming/paged query API
      -> #29 batch insert/write optimization

#24 CockroachDB epic
  -> #30 module scaffold + smoke test
      -> #31 dialect/DDL differences
      -> #32 serializable retry guide + regression tests

#26 Spring Boot R2DBC raw SQL
  -> independent Spring Boot repository feature
```

## WIP Limits

| Lane | Limit | Current next |
|---|---:|---|
| Database connector | 1 | Choose `#27` or `#30`; avoid parallel connector epics. |
| Spring Boot repository | 1 | `#26` can proceed independently. |
| Lower-priority integrations | 0 | Hold `#4/#5` until connector queue is stable. |
| Build/CI maintenance | 1 | Handle only concrete failures from Nightly/CI. |
