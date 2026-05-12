# WIP - bluetape4k-exposed

Snapshot: 2026-05-12 KST
Scope: open GitHub issues assigned to `debop`, created on or after 2026-01-01.
Open count: 11 issues.

## Refresh Notes

Verified with GitHub connector on 2026-05-12 KST. `gh` CLI was not used because the local token is invalid.

Open PRs to watch:

- PR #58 `build(deps): bump com.github.luben:zstd-jni from 1.5.7-7 to 1.5.7-8`.
- PR #59 `build(deps): bump org.elasticmq:elasticmq-rest-sqs_2.13 from 1.6.12 to 1.7.1`.

Recently completed and no longer part of the active implementation queue:

- `#8` FastjsonSerializer facade parity, PR #21 merged.
- `#6` AuditableR2dbcRepository, PR #22 merged.
- R2DBC MySQL parallel read timeout mitigation, PR #34 merged.
- Governance/doc maintenance merged through PR #35, #36, #37, #38, and #40.

## Current Direction

The Spring Boot 3 removal and versionless Spring Boot lane is closed. The active queue shifted from immediate R2DBC/serializer gaps to three feature lanes:

1. CockroachDB JDBC dialect/Testcontainers support.
2. Trino Phase 2 connection, streaming, and batch write improvements.
3. R2DBC declared `@Query` parity.

Keep `#4` and `#5` visible, but do not start them until the newer database-specific lanes have a clear owner or are deliberately deferred.

## Priority Queue

| Priority | Issue | Difficulty | Notes |
|---|---|---:|---|
| P1 | [#24](https://github.com/bluetape4k/bluetape4k-exposed/issues/24) CockroachDB epic | L | New DB module umbrella; split work is already filed in #30/#31/#32. |
| P1 | [#30](https://github.com/bluetape4k/bluetape4k-exposed/issues/30) CockroachDB scaffolding and smoke test | M | First concrete CockroachDB step. |
| P1 | [#31](https://github.com/bluetape4k/bluetape4k-exposed/issues/31) CockroachDB dialect/DDL compatibility | M | Follow scaffolding; document PostgreSQL compatibility boundaries. |
| P1 | [#32](https://github.com/bluetape4k/bluetape4k-exposed/issues/32) CockroachDB retry guidance | M | Transaction correctness and cancellation boundary work. |
| P1 | [#25](https://github.com/bluetape4k/bluetape4k-exposed/issues/25) Trino Phase 2 epic | L | Umbrella for #27/#28/#29. |
| P1 | [#27](https://github.com/bluetape4k/bluetape4k-exposed/issues/27) Trino DataSource connection | M | Smallest Trino Phase 2 entry point. |
| P2 | [#28](https://github.com/bluetape4k/bluetape4k-exposed/issues/28) Trino streaming/paged query API | L | Needs lifecycle/cancellation design before implementation. |
| P2 | [#29](https://github.com/bluetape4k/bluetape4k-exposed/issues/29) Trino batch write optimization | L | Requires connector capability review. |
| P2 | [#26](https://github.com/bluetape4k/bluetape4k-exposed/issues/26) R2DBC `@Query` raw SQL | L | R2DBC/JDBC parity item; keep isolated from Trino/CockroachDB. |
| P3 | [#4](https://github.com/bluetape4k/bluetape4k-exposed/issues/4) exposed-bucket4j | L | Useful distributed rate limiting feature after DB lanes settle. |
| P3 | [#5](https://github.com/bluetape4k/bluetape4k-exposed/issues/5) Spring Modulith Exposed | L | Wait until event publication boundaries and Boot 4-only docs are stable. |

## Dependency Map

```text
projects #280/#263 policy and removal/rename (closed)
  -> dependencies #8 first official Spring Boot aliases (closed)
  -> exposed #3 Spring Boot 3 removal + spring-boot4 -> spring-boot rename (closed)

exposed #7 QueryLookupStrategy (closed)
exposed #8 serializer parity (closed by PR #21)
exposed #6 AuditableR2dbcRepository (closed by PR #22)
  -> #26 R2DBC @Query parity
  -> #4 bucket4j
  -> #5 Spring Modulith integration

#24 CockroachDB epic
  -> #30 scaffolding and smoke test
  -> #31 PostgreSQL compatibility and DDL differences
  -> #32 serializable transaction retry guidance

#25 Trino Phase 2 epic
  -> #27 DataSource connection
  -> #28 streaming/paged query API
  -> #29 batch insert/write path
```

## WIP Limits

| Lane | Limit | Current next |
|---|---:|---|
| CockroachDB | 1 | Start with `#30`; keep #31/#32 as follow-ups. |
| Trino Phase 2 | 1 | Start with `#27`; design #28 before code. |
| R2DBC parity | 1 | `#26` only when not competing with CockroachDB/Trino. |
| New integrations | 0 | Defer `#4/#5` until the active DB lanes are triaged. |
| Dependency PRs | 2 | PR #58 and #59. |
