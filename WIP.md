# WIP - bluetape4k-exposed

Snapshot: 2026-05-09 KST
Scope: open GitHub issues assigned to `debop`, created on or after 2026-01-01.
Open count: 6 issues.

## Current Direction

This repo is still carrying Spring Boot 3 modules while the broader
bluetape4k plan has moved to Spring Boot 4-only for 2.0+. Follow
`bluetape4k-projects #280/#263`: remove the Exposed Boot 3 group and rename the
Boot 4 group to versionless `spring-boot` before the public
`bluetape4k-dependencies` contract is released.

After that, close small correctness gaps before adding new integrations.

## Priority Queue

| Priority | Issue | Difficulty | Notes |
|---|---|---:|---|
| P0 | [#3](https://github.com/bluetape4k/bluetape4k-exposed/issues/3) remove Boot 3 and rename Boot 4 modules | L | Follows `bluetape4k-projects #263`; removes Boot 3 and renames `spring-boot4` to versionless `spring-boot`. |
| P1 | [#8](https://github.com/bluetape4k/bluetape4k-exposed/issues/8) FastjsonSerializer missing | S | Small serializer parity bug; good first closure. |
| P1 | [#7](https://github.com/bluetape4k/bluetape4k-exposed/issues/7) Spring Data R2DBC QueryLookupStrategy | L | User-facing repository gap; PartTree queries are unsupported. |
| P1 | [#6](https://github.com/bluetape4k/bluetape4k-exposed/issues/6) AuditableR2dbcRepository | M | Completes JDBC/R2DBC auditable repository parity. |
| P2 | [#4](https://github.com/bluetape4k/bluetape4k-exposed/issues/4) exposed-bucket4j | L | Useful distributed rate limiting feature; do after R2DBC gaps. |
| P2 | [#5](https://github.com/bluetape4k/bluetape4k-exposed/issues/5) Spring Modulith Exposed | L | Should wait until Boot 4-only direction and event publication boundaries are stable. |

## Dependency Map

```text
projects #280/#263 policy and removal/rename
  -> dependencies #8 first official Spring Boot aliases
  -> exposed #3 Spring Boot 3 removal + spring-boot4 -> spring-boot rename

exposed #8 serializer parity
exposed #7 QueryLookupStrategy
exposed #6 AuditableR2dbcRepository
  -> exposed #4 bucket4j
  -> exposed #5 Spring Modulith integration
```

## WIP Limits

| Lane | Limit | Current next |
|---|---:|---|
| Breaking cleanup | 1 | `#3`, only after policy is clear. |
| Correctness gaps | 2 | `#8`, then `#7/#6`. |
| New integrations | 0 until gaps close | `#4/#5` wait. |
