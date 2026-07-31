# Issue #77 ArtifactId 마이그레이션

## 요약

`bluetape4k-exposed`는 동일한 groupId와 repository identity를 유지하지만, 게시되는 artifactIds와 Gradle project paths를 단축합니다.

GroupId는 유지됩니다:

```text
io.github.bluetape4k.exposed
```

## Coordinate 매핑

| 기존 artifactId | 새 artifactId |
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

내부 demo/example Gradle paths도 변경됩니다:

| 기존 Gradle path | 새 Gradle path |
|---|---|
| `:bluetape4k-spring-boot-exposed-jdbc-demo` | `:exposed-spring-boot-jdbc-demo` |
| `:bluetape4k-spring-boot-exposed-r2dbc-demo` | `:exposed-spring-boot-r2dbc-demo` |
| `:bluetape4k-examples-exposed-clickhouse-oltp-olap` | `:examples-exposed-clickhouse-oltp-olap` |

## 변경되지 않는 항목

- 저장소 slug: `bluetape4k-exposed`
- 루트 Gradle 프로젝트 이름: `bluetape4k-exposed`
- GitHub URL: `https://github.com/bluetape4k/bluetape4k-exposed`
- `bluetape4k-exposed.git`로 끝나는 SCM URLs
- `io.bluetape4k.exposed` 하위의 Package names
- Maven groupId: `io.github.bluetape4k.exposed`
- `io.github.bluetape4k:bluetape4k-coroutines`와 같은 Cross-ecosystem bluetape4k dependencies

## 롤아웃 순서

1. PR CI와 PR branch Nightly(full)이 통과한 후에만 exposed rename PR을 병합합니다.
2. develop Nightly(full)을 실행합니다.
3. exposed snapshot을 게시합니다.
4. 대표적인 변경된 coordinates가 Central Snapshots에서 resolve되는지 확인합니다.
5. `bluetape4k-dependencies`를 업데이트하고 해당 snapshot을 먼저 게시합니다.
6. dependencies snapshot을 사용할 수 있게 된 후에만 consumer/example repositories를 업데이트합니다.

Consumer repositories는 게시되지 않은 exposed coordinates를 직접 대상으로 업데이트해서는 안 됩니다. 먼저 dependencies snapshot을 사용해야 합니다.

## `bluetape4k-dependencies` 동기화 요구 사항

`bluetape4k-dependencies`는 이 repository의 Gradle project graph에서 managed catalog aliases와 BOM constraints를 생성합니다. dependencies snapshot을 게시하기 전에 새 explicit mapping shape에 맞게 해당 sync script를 업데이트해야 합니다:

- `includeMappedModule("path", "project-name")` entries를 parse
- `includeModules("exposed", withBaseDir = false)`를 `bluetape4k-*`가 아니라 publishing directory names를 직접 사용하는 것으로 처리
- consumer source compatibility를 의도적으로 유지하는 경우에만 기존 alias keys를 보존; 그렇지 않으면 consuming build scripts를 함께 업데이트
- 생성된 aliases와 constraints에 `exposed-*`, `exposed-batch`, `exposed-spring-boot-*`, `exposed-spring-modulith`가 포함되는지 확인

이는 exposed snapshot publication 이후의 첫 번째 downstream blocker입니다.
