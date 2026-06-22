# Issue #77 ArtifactId Contract Rename Design

## Summary

Rename the `bluetape4k-exposed` repository's published artifactId and Gradle
project-path surface from long `bluetape4k-*` coordinates to shorter exposed
domain coordinates before the first public release.

The groupId remains unchanged:

```text
io.github.bluetape4k.exposed
```

The new artifact surface removes the repeated `bluetape4k-` prefix from
artifactId values and makes the artifactId match the repo domain.

## Scope

This spec covers the first PR in the sequence: the `bluetape4k-exposed`
repository rename.

In scope:

- Gradle project path rename in `settings.gradle.kts`
- Maven publication artifactId changes through project names
- internal `project(":...")` dependency updates
- BOM constraints and BOM POM name updates
- root and module README coordinate updates
- `AGENTS.md` / `CLAUDE.md` command and module mapping updates
- `AGENTS.md` / `CLAUDE.md` groupId correction to
  `io.github.bluetape4k.exposed`
- CI and Nightly workflow task-path updates
- generated POM verification for renamed coordinates
- `docs/superpowers/migrations/2026-05-16-issue-77-artifact-id-migration.md`
  as the current old -> new coordinate migration note

Out of scope for this PR:

- Updating `bluetape4k-dependencies`
- Updating consumer/example repositories
- Running remote Nightly(full) and snapshot publish
- Merging the PR

Those happen only after this PR passes CI and Nightly(full), is merged, and the
new exposed snapshot is published.

## Decisions

| Current artifactId | New artifactId |
|---|---|
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
| `bluetape4k-exposed-bom` | `exposed-bom` |
| `bluetape4k-spring-boot-exposed-jdbc-demo` | `exposed-spring-boot-jdbc-demo` |
| `bluetape4k-spring-boot-exposed-r2dbc-demo` | `exposed-spring-boot-r2dbc-demo` |
| `bluetape4k-examples-exposed-clickhouse-oltp-olap` | `examples-exposed-clickhouse-oltp-olap` |

Demo and example modules are internal Gradle projects. They should also use the
short repo-local project path convention so workflow task paths stay coherent:

- `bluetape4k-spring-boot-exposed-jdbc-demo` -> `exposed-spring-boot-jdbc-demo`
- `bluetape4k-spring-boot-exposed-r2dbc-demo` -> `exposed-spring-boot-r2dbc-demo`
- `bluetape4k-examples-exposed-clickhouse-oltp-olap` -> `examples-exposed-clickhouse-oltp-olap`

`exposed-spring-modulith` is an intentional naming exception. The artifact is a
Spring Modulith adapter backed by Exposed JDBC and should read as the concise
domain integration name, not as `exposed-spring-boot-modulith`. Its dependency
on `exposed-spring-boot-jdbc` remains explicit in Gradle metadata.

## Current Evidence

`settings.gradle.kts` currently derives names from `val projectName = "bluetape4k"`:

- `includeModules("exposed", withBaseDir = false)` produces `bluetape4k-exposed-*`
- `includeModules("utils", withBaseDir = false)` produces `bluetape4k-batch`
- `includeModules("spring-boot", withBaseDir = true)` produces `bluetape4k-spring-boot-*`
- `includeModules("examples", withBaseDir = true)` produces `bluetape4k-examples-*`

`build.gradle.kts` and `exposed/exposed-bom/build.gradle.kts` special-case
`bluetape4k-exposed-bom`, so the BOM rename must update those guards.

`ci.yml` and `nightly.yml` hardcode old Gradle project paths. This makes
workflow updates part of the same atomic PR.

`publish-snapshot.yml` runs on `workflow_run` for `Nightly` on `develop`.
Therefore PR-branch Nightly(full) does not publish snapshots. Snapshot publish
can only be proven after merge to `develop` or by a manual dispatch against
`develop`.

`repo1.maven.org` metadata spot checks returned `404` for representative old and
new coordinates. This supports treating the rename as a pre-release contract
cleanup.

## Architecture Options

### Option A: Override only Maven artifactId

Keep Gradle project paths unchanged and set publication `artifactId` manually.

Rejected.

The old names would remain in every Gradle task, CI job, Nightly job, Kover
aggregation, and developer command. That preserves most of the cognitive cost
and creates drift between Gradle project paths and Maven coordinates.

### Option B: Rename Gradle project paths and Maven coordinates together

Change `settings.gradle.kts` naming rules so Gradle project paths and artifactId
values converge on the same short names.

Accepted.

This is a larger local change, but it keeps project paths, generated POMs, BOM
constraints, workflow task paths, and user-facing dependency snippets aligned.

### Option C: Move directories too

Rename directories such as `exposed/exposed-core` to `exposed/core`.

Rejected.

Directory names already carry useful context and are not public Maven
coordinates. Moving directories would multiply file churn without improving the
published contract.

## Design

### Gradle project naming

Replace the current global `bluetape4k` prefix in `settings.gradle.kts` with
explicit include rules:

- `exposed/*` -> directory name (`exposed-core`, `exposed-jdbc`, ...)
- `utils/batch` -> `exposed-batch`
- `spring-boot/exposed-jdbc` -> `exposed-spring-boot-jdbc`
- `spring-boot/exposed-r2dbc` -> `exposed-spring-boot-r2dbc`
- `spring-boot/batch-exposed` -> `exposed-spring-boot-batch`
- `spring-boot/exposed-spring-modulith` -> `exposed-spring-modulith`
- spring boot demo modules follow the same short pattern with `-demo`
- `examples/*` -> `examples-*`

Manual mapping for `spring-boot` and `utils` is preferred over a clever generic
rule because only a few modules exist and their new names are contract-bearing.

The intended shape is explicit enough that adding a module requires choosing its
public project path:

```kotlin
includeModules("exposed", withBaseDir = false)
includeModules("examples", withBaseDir = true)

includeMappedModule("utils/batch", "exposed-batch")

includeMappedModule("spring-boot/exposed-jdbc", "exposed-spring-boot-jdbc")
includeMappedModule("spring-boot/exposed-r2dbc", "exposed-spring-boot-r2dbc")
includeMappedModule("spring-boot/batch-exposed", "exposed-spring-boot-batch")
includeMappedModule("spring-boot/exposed-spring-modulith", "exposed-spring-modulith")
includeMappedModule("examples/jdbc-demo", "exposed-spring-boot-jdbc-demo")
includeMappedModule("examples/r2dbc-demo", "exposed-spring-boot-r2dbc-demo")
```

### Maven publication

The regular `MavenPublication` can continue to rely on `project.name`, because
the project path rename now encodes the desired artifactId.

Keep the publication name `BluetapeExposed` to avoid unnecessary task-name churn
in this PR. Verification commands continue to use
`generatePomFileForBluetapeExposedPublication`.

The BOM module must update:

- project path: `:exposed-bom`
- POM name: `exposed-bom`
- constraints guard: exclude `exposed-bom`

The root build must also update every BOM-name special case:

- `build.gradle.kts` subproject skip guard
- `build.gradle.kts` Kover aggregation exclusion
- `exposed/exposed-bom/build.gradle.kts` constraints guard and POM name

The root publish aggregation continues to include all non-example and non-demo
projects.

### Internal dependencies

Every internal `project(":bluetape4k-...")` reference becomes the matching short
project path.

Examples:

- `project(":bluetape4k-exposed-core")` -> `project(":exposed-core")`
- `project(":bluetape4k-spring-boot-exposed-jdbc")` -> `project(":exposed-spring-boot-jdbc")`
- `project(":bluetape4k-batch")` -> `project(":exposed-batch")`

### Workflow updates

Update `ci.yml` and `nightly.yml` task paths:

- `:bluetape4k-exposed-core:test` -> `:exposed-core:test`
- `:bluetape4k-spring-boot-exposed-jdbc:test` -> `:exposed-spring-boot-jdbc:test`
- `:bluetape4k-spring-boot-batch-exposed:test` -> `:exposed-spring-boot-batch:test`
- `:bluetape4k-batch:test` -> `:exposed-batch:test`

Path filters do not need semantic changes because directory layout is not
moving.

Run `actionlint` before PR push. Also verify workflows no longer reference old
project paths or accidental shell quoting artifacts:

- `rg -n 'bluetape4k-' .github/workflows/{ci,nightly}.yml` returns no hits
- `rg -n -F "\\'" .github/workflows` returns no hits

### Documentation

Update active docs:

- root `README.md` and `README.ko.md`
- module README pairs under `exposed/`, `spring-boot/`, and `utils/`
- `exposed-bom` README pair
- `AGENTS.md` and `CLAUDE.md`
- current research/spec docs that describe the current contract

Historical lessons, changelog entries, and old design documents may keep old
names if they describe past state. Add a current migration note instead of
rewriting history.

The migration note path is:

```text
docs/superpowers/migrations/2026-05-16-issue-77-artifact-id-migration.md
```

It must include the old -> new coordinate table and the downstream rollout gate
order so `bluetape4k-dependencies` and consumer repository PRs can copy the same
contract.

### Downstream sequence

This PR only prepares and publishes the new exposed surface. The org-wide rollout
continues after merge:

1. exposed merge to `develop`
2. exposed `develop` Nightly(full)
3. exposed snapshot publish
4. verify renamed coordinates resolve from Central Snapshots
5. `bluetape4k-dependencies` update and snapshot publish
6. consumer/example repo updates against the dependencies snapshot

The `bluetape4k-dependencies` PR is blocked until the exposed snapshot
resolution check succeeds. Consumer repository PRs are blocked until the
dependencies snapshot is published, because those repositories consume the
dependencies catalog/BOM first.

## Verification

Local verification before PR:

- `./gradlew -q projects`
- old path absence:
  - `./gradlew -q projects | rg 'bluetape4k-exposed-|bluetape4k-spring-boot-|bluetape4k-examples-|bluetape4k-batch'` must return no project path hits
- generated POM tasks for representative modules:
  - run with `--no-configuration-cache` because empirical verification showed
    the current publication task graph is not configuration-cache compatible
  - `:exposed-core:generatePomFileForBluetapeExposedPublication`
  - `:exposed-jdbc:generatePomFileForBluetapeExposedPublication`
  - `:exposed-r2dbc:generatePomFileForBluetapeExposedPublication`
  - `:exposed-bom:generatePomFileForBluetapeExposedPublication`
  - `:exposed-batch:generatePomFileForBluetapeExposedPublication`
  - `:exposed-spring-boot-jdbc:generatePomFileForBluetapeExposedPublication`
  - `:exposed-spring-modulith:generatePomFileForBluetapeExposedPublication`
- generated POM artifactId grep for:
  - `<artifactId>exposed-core</artifactId>`
  - `<artifactId>exposed-jdbc</artifactId>`
  - `<artifactId>exposed-r2dbc</artifactId>`
  - `<artifactId>exposed-bom</artifactId>`
  - `<artifactId>exposed-batch</artifactId>`
  - `<artifactId>exposed-spring-boot-jdbc</artifactId>`
  - `<artifactId>exposed-spring-modulith</artifactId>`
- targeted tests:
  - `./gradlew :exposed-core:test :exposed-dao:test --no-daemon`
  - `./gradlew :exposed-jdbc:test :exposed-jdbc-tests:test --no-daemon`
  - `./gradlew :exposed-r2dbc:test :exposed-r2dbc-tests:test --no-daemon`
  - `./gradlew :exposed-cache:test :exposed-jdbc-caffeine:test :exposed-r2dbc-caffeine:test --no-daemon`
  - `./gradlew :exposed-spring-boot-jdbc:test :exposed-spring-boot-r2dbc:test :exposed-spring-modulith:test --no-daemon`
  - `./gradlew :exposed-spring-boot-batch:test :exposed-batch:test --no-daemon`
- workflow validation:
  - `actionlint .github/workflows/ci.yml .github/workflows/nightly.yml .github/workflows/publish-snapshot.yml`
  - `rg -n 'bluetape4k-' .github/workflows/{ci,nightly}.yml` returns no hits
  - `rg -n -F "\\'" .github/workflows` returns no hits

Remote verification before downstream PRs:

- PR CI succeeds
- PR branch Nightly(full) succeeds
- after merge, `develop` Nightly(full) succeeds
- snapshot publish succeeds on `develop`
- manually dispatch `publish-snapshot.yml` on `develop` if the automatic
  workflow-run trigger is not observed after the first successful develop
  Nightly(full)
- verify representative renamed snapshot coordinates resolve before opening the
  `bluetape4k-dependencies` PR:
  - `io.github.bluetape4k.exposed:exposed-bom`
  - `io.github.bluetape4k.exposed:exposed-core`
  - `io.github.bluetape4k.exposed:exposed-jdbc`
  - `io.github.bluetape4k.exposed:exposed-r2dbc`
  - `io.github.bluetape4k.exposed:exposed-batch`
  - `io.github.bluetape4k.exposed:exposed-spring-boot-jdbc`
  - `io.github.bluetape4k.exposed:exposed-spring-modulith`

Remote verification after PR:

- PR CI success
- PR branch Nightly(full) success
- after merge: `develop` Nightly(full) success
- after merge: exposed snapshot publish success

## Risks

| Risk | Mitigation |
|---|---|
| Gradle project path rename breaks internal dependencies | replace all internal `project(":bluetape4k-*")` refs and run representative compile/tests |
| Workflow task path miss causes CI/Nightly failure | update `ci.yml` and `nightly.yml`, run `actionlint`, and grep workflow task paths |
| Changed-module CI misses renamed surface | Nightly(full) is a merge gate |
| BOM constraints accidentally publish old names | generated BOM POM grep and BOM build verification |
| Demo/example modules accidentally enter publish aggregation | keep publish filter excluding examples and `-demo`; verify publishable projects if needed |
| Downstream repo updates race ahead of dependencies snapshot | block consumer PRs until dependencies snapshot publish succeeds |
| Historical docs are rewritten incorrectly | update active docs only; preserve historical records unless they are current installation guidance |

## Open Follow-Ups

- `bluetape4k-dependencies` needs its own PR after exposed snapshot publish.
- Consumer repos need separate PRs after dependencies snapshot publish.
- Any remote Nightly(full) or snapshot publish failures must block downstream PRs.
