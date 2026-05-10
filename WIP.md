# WIP - bluetape4k-exposed

Snapshot: 2026-05-10 KST
Scope: open GitHub issues assigned to `debop`, created on or after 2026-01-01. Open count: 3 issues.

## Current Direction

The Spring Boot 3 removal / Spring Boot 4 versionless rename lane is closed. The remaining active work is now post-R2DBC-gap feature expansion.

Two recently completed correctness gaps are already represented by PRs/issues:
`#7` is closed, `#8` has PR #21 open even though the issue is already closed, and `#6` has PR #22 open.

## Priority Queue

| Priority | Issue                                                                                      | Difficulty | Notes                                                                                   |
|----------|--------------------------------------------------------------------------------------------|-----------:|-----------------------------------------------------------------------------------------|
| P0       | [#8](https://github.com/bluetape4k/bluetape4k-exposed/issues/8) FastjsonSerializer missing |          S | PR #21 is open and CI passed; issue is already closed, so merge PR to align code state. |
| P0       | [#6](https://github.com/bluetape4k/bluetape4k-exposed/issues/6) AuditableR2dbcRepository   |          M | PR #22 is open and CI passed; wait for merge/close before treating as complete.         |
| P2       | [#4](https://github.com/bluetape4k/bluetape4k-exposed/issues/4) exposed-bucket4j           |          L | Useful distributed rate limiting feature; do after R2DBC gaps.                          |
| P2       | [#5](https://github.com/bluetape4k/bluetape4k-exposed/issues/5) Spring Modulith Exposed    |          L | Should wait until Boot 4-only direction and event publication boundaries are stable.    |

## Dependency Map

```text
projects #280/#263 policy and removal/rename (closed)
  -> dependencies #8 first official Spring Boot aliases (closed)
  -> exposed #3 Spring Boot 3 removal + spring-boot4 -> spring-boot rename (closed)

exposed #8 serializer parity (PR #21 open; issue closed)
exposed #7 QueryLookupStrategy (closed)
exposed #6 AuditableR2dbcRepository (PR #22 open)
  -> exposed #4 bucket4j
  -> exposed #5 Spring Modulith integration
```

## WIP Limits

| Lane             |                 Limit | Current next                           |
|------------------|----------------------:|----------------------------------------|
| Merge wait       |                     2 | `#8` via PR #21, `#6` via PR #22.      |
| Correctness gaps |                     0 | `#7` closed; `#8/#6` wait on PR merge. |
| New integrations | 1 after PR #22 merges | `#4/#5`.                               |
