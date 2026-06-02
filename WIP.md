# WIP - bluetape4k-exposed

Snapshot: 2026-06-02 KST
Scope: post-1.10.0 release train version alignment.
Open count: 6 issues.

## Current Direction

The `1.10.0` stable line has been published and consumed by
`bluetape4k-dependencies` `1.2.0`. Development now moves to `1.11.0` with
`snapshotVersion=` kept empty for workflow-injected snapshot publication.

## Active Queue

| Priority | Issue | Milestone | Notes |
|---|---|---|---|
| P1 | [#225](https://github.com/bluetape4k/bluetape4k-exposed/issues/225) feat: add Spring Boot Actuator health indicators for Exposed cache consistency | 1.10.0 | Implemented in this branch for JDBC and R2DBC Caffeine repositories. |
| P1 | [#226](https://github.com/bluetape4k/bluetape4k-exposed/issues/226) test: add migration generation smoke coverage for Exposed Gradle plugin demo modules | 1.10.0 | Implemented in this branch through a weekly/PR workflow. |
| P1 | [#228](https://github.com/bluetape4k/bluetape4k-exposed/issues/228) feat: add BigQuery query job options and dry-run validation | 1.10.0 | Implemented in this branch with credential-free dry-run coverage. |
| P1 | [#229](https://github.com/bluetape4k/bluetape4k-exposed/issues/229) feat: expose Trino JDBC performance options and pushdown smoke coverage | 1.10.0 | Implemented in this branch for typed JDBC options and EXPLAIN guidance. |
| P1 | [#230](https://github.com/bluetape4k/bluetape4k-exposed/issues/230) feat: add database-specific scenario examples for exposed modules | 1.10.0 | Implemented in this branch with a BigQuery dry-run example plus the existing ClickHouse example matrix. |
| P1 | [#231](https://github.com/bluetape4k/bluetape4k-exposed/issues/231) [epic] 1.10.0 database stability, analytics, and examples | 1.10.0 | Close after all child issues are closed. |

## Open PRs

None before this branch.

## Refresh Notes

- Verified with `gh` on 2026-06-02 KST.
- Keep `bluetape4k-*` issue and resolving PR milestones aligned.
