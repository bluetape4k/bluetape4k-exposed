# Issue #77 ArtifactId Migration

## Summary

`bluetape4k-exposed` keeps the same groupId and repository identity, but shortens
its published artifactIds and Gradle project paths.

GroupId stays:

```text
io.github.bluetape4k.exposed
```

## Coordinate Mapping

| Old artifactId | New artifactId |
|---|---|
| `bluetape4k-exposed-bom` | `exposed-bom` |
| `bluetape4k-exposed-core` | `exposed-core` |
| `bluetape4k-exposed-dao` | `exposed-dao` |
| `bluetape4k-exposed-jdbc` | `exposed-jdbc` |
| `bluetape4k-exposed-r2dbc` | `exposed-r2dbc` |
| `bluetape4k-exposed-jdbc-tests` | `exposed-jdbc-tests` |
| `bluetape4k-exposed-r2dbc-tests` | `exposed-r2dbc-tests` |
| `bluetape4k-exposed-cache` | `exposed-cache` |
| `bluetape4k-exposed-jdbc-caffeine` | `exposed-jdbc-caffeine` |
| `bluetape4k-exposed-jdbc-lettuce` | `exposed-jdbc-lettuce` |
| `bluetape4k-exposed-jdbc-redisson` | `exposed-jdbc-redisson` |
| `bluetape4k-exposed-r2dbc-caffeine` | `exposed-r2dbc-caffeine` |
| `bluetape4k-exposed-r2dbc-lettuce` | `exposed-r2dbc-lettuce` |
| `bluetape4k-exposed-r2dbc-redisson` | `exposed-r2dbc-redisson` |
| `bluetape4k-exposed-jackson2` | `exposed-jackson2` |
| `bluetape4k-exposed-jackson3` | `exposed-jackson3` |
| `bluetape4k-exposed-fastjson2` | `exposed-fastjson2` |
| `bluetape4k-exposed-tink` | `exposed-tink` |
| `bluetape4k-exposed-measured` | `exposed-measured` |
| `bluetape4k-exposed-postgresql` | `exposed-postgresql` |
| `bluetape4k-exposed-mysql8` | `exposed-mysql8` |
| `bluetape4k-exposed-bigquery` | `exposed-bigquery` |
| `bluetape4k-exposed-clickhouse` | `exposed-clickhouse` |
| `bluetape4k-exposed-trino` | `exposed-trino` |
| `bluetape4k-exposed-duckdb` | `exposed-duckdb` |
| `bluetape4k-exposed-timefold-solver-persistence` | `exposed-timefold-solver-persistence` |
| `bluetape4k-spring-boot-exposed-jdbc` | `exposed-spring-boot-jdbc` |
| `bluetape4k-spring-boot-exposed-r2dbc` | `exposed-spring-boot-r2dbc` |
| `bluetape4k-spring-boot-batch-exposed` | `exposed-spring-boot-batch` |
| `bluetape4k-spring-boot-exposed-spring-modulith` | `exposed-spring-modulith` |
| `bluetape4k-batch` | `exposed-batch` |

Internal demo/example Gradle paths also change:

| Old Gradle path | New Gradle path |
|---|---|
| `:bluetape4k-spring-boot-exposed-jdbc-demo` | `:exposed-spring-boot-jdbc-demo` |
| `:bluetape4k-spring-boot-exposed-r2dbc-demo` | `:exposed-spring-boot-r2dbc-demo` |
| `:bluetape4k-examples-exposed-clickhouse-oltp-olap` | `:examples-exposed-clickhouse-oltp-olap` |

## What Does Not Change

- Repository slug: `bluetape4k-exposed`
- Root Gradle project name: `bluetape4k-exposed`
- GitHub URL: `https://github.com/bluetape4k/bluetape4k-exposed`
- SCM URLs ending in `bluetape4k-exposed.git`
- Package names under `io.bluetape4k.exposed`
- Maven groupId: `io.github.bluetape4k.exposed`
- Cross-ecosystem bluetape4k dependencies such as
  `io.github.bluetape4k:bluetape4k-coroutines`

## Rollout Order

1. Merge the exposed rename PR only after PR CI and PR branch Nightly(full) pass.
2. Run develop Nightly(full).
3. Publish the exposed snapshot.
4. Verify representative renamed coordinates resolve from Central Snapshots.
5. Update `bluetape4k-dependencies` and publish its snapshot first.
6. Update consumer/example repositories only after the dependencies snapshot is
   available.

Consumer repositories should not be updated directly against unpublished exposed
coordinates. They should consume the dependencies snapshot first.
