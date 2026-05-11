# WIP - bluetape4k-exposed

Snapshot: 2026-05-11 KST
Scope: open GitHub issues assigned to `debop`, created on or after 2026-01-01. Open count: 2 issues.

## Recently Completed

- `#8` FastjsonSerializer facade parity — PR #21 merged.
- `#6` AuditableR2dbcRepository — PR #22 merged.
- R2DBC MySQL parallel read timeout mitigation — PR #34 merged.
- Governance/doc maintenance merged today: PR #35 Nightly smoke/full lanes, PR #36 lessons guidance, PR #37 Kover policy, PR #38 Dependabot baseline, PR #40 unassigned Dependabot updates.

## Current Direction

The Spring Boot 3 removal / Spring Boot 4 versionless rename lane is closed, and the two immediate R2DBC/serializer correctness gaps are now merged. The remaining active work is post-R2DBC feature expansion.

Do not keep a merge-wait lane open for PR #21/#22; both are now part of develop history.

## Priority Queue

| Priority | Issue | Difficulty | Notes |
|---|---|---:|---|
| P1 | [#4](https://github.com/bluetape4k/bluetape4k-exposed/issues/4) exposed-bucket4j | L | Useful distributed rate limiting feature; can start after confirming the merged R2DBC baseline remains stable. |
| P1 | [#5](https://github.com/bluetape4k/bluetape4k-exposed/issues/5) Spring Modulith Exposed | L | Should wait until Boot 4-only direction and event publication boundaries are stable. |

## Dependency Map

```text
projects #280/#263 policy and removal/rename (closed)
  -> dependencies #8 first official Spring Boot aliases (closed)
  -> exposed #3 Spring Boot 3 removal + spring-boot4 -> spring-boot rename (closed)

exposed #7 QueryLookupStrategy ✅
exposed #8 serializer parity ✅ (PR #21)
exposed #6 AuditableR2dbcRepository ✅ (PR #22)
  -> exposed #4 bucket4j
  -> exposed #5 Spring Modulith integration
```

## WIP Limits

| Lane | Limit | Current next |
|---|---:|---|
| New integrations | 1 | Choose `#4` or `#5`; keep each as a separate design/implementation PR. |
| Correctness gaps | 0 | `#6/#7/#8` are closed/merged; reopen only with new evidence. |
| Build/CI maintenance | 1 | Handle only concrete failures from Nightly/CI. |
