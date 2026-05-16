# Issue #77 ArtifactId Contract Rename Plan

## Goal

Rename the `bluetape4k-exposed` repository's Gradle project paths and published
artifactIds to the shorter exposed-domain contract before public release, while
keeping directory layout and groupId stable.

Authoritative spec:

```text
docs/superpowers/specs/2026-05-16-issue-77-artifact-id-contract-design.md
```

## Stop Conditions

Stop before PR push if any of these remain true:

- `./gradlew -q projects` still prints old `bluetape4k-*` project paths for this
  repository's modules.
- representative generated POM files do not contain the new artifactIds.
- any `project(":bluetape4k-...")` reference remains in Gradle scripts.
- non-historical Kotlin source still embeds old repository-local artifact names.
- `ci.yml` or `nightly.yml` still references old project paths.
- local targeted tests fail for rename-related compile/configuration reasons.
- `actionlint` fails for changed workflows.

Do not merge the PR from this work item. Remote CI, PR branch Nightly(full),
develop Nightly(full), snapshot publish, `bluetape4k-dependencies` snapshot
publish, and consumer repo PRs remain sequential follow-up gates.

## Implementation Sequence

1. Update `settings.gradle.kts`.
   - Remove the `bluetape4k` prefix from exposed and examples modules.
   - Add explicit mapped includes for `utils/batch` and every `spring-boot/*`
     module, including demo modules.
   - Keep directories unchanged.
   - Keep `rootProject.name = "bluetape4k-exposed"` because it is the
     repository slug, not a published artifactId.

2. Apply mechanical project-path and artifactId replacements.
   - `:bluetape4k-exposed-*` -> `:exposed-*`
   - `:bluetape4k-spring-boot-exposed-jdbc` -> `:exposed-spring-boot-jdbc`
   - `:bluetape4k-spring-boot-exposed-r2dbc` -> `:exposed-spring-boot-r2dbc`
   - `:bluetape4k-spring-boot-batch-exposed` -> `:exposed-spring-boot-batch`
   - `:bluetape4k-spring-boot-exposed-spring-modulith` -> `:exposed-spring-modulith`
   - `:bluetape4k-batch` -> `:exposed-batch`
   - `:bluetape4k-spring-boot-exposed-jdbc-demo` -> `:exposed-spring-boot-jdbc-demo`
   - `:bluetape4k-spring-boot-exposed-r2dbc-demo` -> `:exposed-spring-boot-r2dbc-demo`
   - `:bluetape4k-examples-exposed-clickhouse-oltp-olap` -> `:examples-exposed-clickhouse-oltp-olap`

   Do not rewrite repository identity strings:

   - `https://github.com/bluetape4k/bluetape4k-exposed`
   - `scm:git:git://github.com/bluetape4k/bluetape4k-exposed.git`
   - `scm:git:ssh://github.com/bluetape4k/bluetape4k-exposed.git`
   - `rootProject.name = "bluetape4k-exposed"`
   - repository-context descriptions such as `BOM for bluetape4k-exposed`

3. Update Gradle build logic.
   - Root BOM skip guard: `exposed-bom`
   - Root Kover aggregation exclusion: `exposed-bom`
   - BOM constraints guard and POM name: `exposed-bom`
   - Keep publication name `BluetapeExposed`.

4. Update workflows.
   - Replace old task paths in `.github/workflows/ci.yml`.
   - Replace old task paths in `.github/workflows/nightly.yml`.
   - Inspect `publish-snapshot.yml`; change only if it references project paths.

5. Update active docs.
   - `AGENTS.md` and `CLAUDE.md`: project paths, commands, and actual groupId
     `io.github.bluetape4k.exposed`.
   - Root README pair and module README pairs: dependency snippets, module
     names, task paths, badges where artifact-specific.
   - Add migration note:
     `docs/superpowers/migrations/2026-05-16-issue-77-artifact-id-migration.md`.
   - The migration note must include a "What does not change" section for repo
     URL, repo slug, root project name, and groupId.

6. Run local verification.
   - Project graph and old-path absence checks.
   - Representative generated POM checks.
   - Targeted test batches.
   - Workflow lint and workflow old-reference checks.

7. Write lessons learned before PR.
   - Add `docs/lessons/2026-05-16-issue-77-artifact-id-rename.md`.
   - Capture renaming rules, verification gaps, and downstream sequencing.

8. Commit with Lore trailers, push branch, and open a draft PR assigned to
   `debop`.
   - PR title/body in English.
   - PR body must include local verification evidence and remote follow-up gates.
   - PR body must state the downstream hard gate:
     `bluetape4k-dependencies` snapshot publish happens before consumer repos.

## Verification Commands

Project path checks:

```bash
./gradlew -q projects
./gradlew -q projects | rg 'bluetape4k-exposed-|bluetape4k-spring-boot-|bluetape4k-examples-|bluetape4k-batch'
rg -n 'project\(":bluetape4k-' **/*.gradle.kts settings.gradle.kts
rg -n 'bluetape4k-(exposed|spring-boot-|batch|examples-)' --type kotlin
```

The three residual-reference commands must return no hits. Historical docs under
`docs/superpowers/specs/**` and `docs/lessons/**` may keep old names only when
they explicitly describe past state.

Representative POM generation:

```bash
./gradlew \
  :exposed-core:generatePomFileForBluetapeExposedPublication \
  :exposed-jdbc:generatePomFileForBluetapeExposedPublication \
  :exposed-r2dbc:generatePomFileForBluetapeExposedPublication \
  :exposed-bom:generatePomFileForBluetapeExposedPublication \
  :exposed-batch:generatePomFileForBluetapeExposedPublication \
  :exposed-spring-boot-jdbc:generatePomFileForBluetapeExposedPublication \
  :exposed-spring-modulith:generatePomFileForBluetapeExposedPublication \
  --no-daemon \
  --no-configuration-cache
```

Generated POM artifactId grep:

```bash
rg -n '<artifactId>(exposed-core|exposed-jdbc|exposed-r2dbc|exposed-bom|exposed-batch|exposed-spring-boot-jdbc|exposed-spring-modulith)</artifactId>' \
  **/build/publications/BluetapeExposed/pom-default.xml
rg -n '<packaging>pom</packaging>' exposed/exposed-bom/build/publications/BluetapeExposed/pom-default.xml
```

The artifactId grep must return at least seven hits.

Targeted tests:

```bash
./gradlew :exposed-core:test :exposed-dao:test --no-daemon
./gradlew :exposed-jdbc:test :exposed-jdbc-tests:test --no-daemon
./gradlew :exposed-r2dbc:test :exposed-r2dbc-tests:test --no-daemon
./gradlew :exposed-cache:test :exposed-jdbc-caffeine:test :exposed-r2dbc-caffeine:test --no-daemon
./gradlew :exposed-spring-boot-jdbc:test :exposed-spring-boot-r2dbc:test :exposed-spring-modulith:test --no-daemon
./gradlew :exposed-spring-boot-batch:test :exposed-batch:test --no-daemon
```

Workflow checks:

```bash
actionlint .github/workflows/ci.yml .github/workflows/nightly.yml .github/workflows/publish-snapshot.yml
rg -n 'bluetape4k-' .github/workflows/{ci,nightly}.yml
rg -n -F "\\'" .github/workflows
```

The two `rg` workflow checks must return no hits. The escaped single-quote check
guards against accidental shell quoting artifacts in workflow `run:` blocks.

README/reference sweep:

```bash
rg -n 'bluetape4k-(exposed|spring-boot-|batch|examples-)' README.md README.ko.md AGENTS.md CLAUDE.md exposed spring-boot utils examples
```

Remaining hits must be intentional repository identity, non-renamed bluetape4k
platform dependencies such as `bluetape4k-coroutines`, or historical context
called out in the migration/lessons docs.

## Remote Follow-Up Gates

After PR creation:

1. PR CI succeeds.
2. PR branch Nightly(full) succeeds.
3. Merge only after review and remote checks.
4. Develop Nightly(full) succeeds.
5. Exposed snapshot publish succeeds.
6. Verify representative renamed coordinates resolve from Central Snapshots.
7. Open and publish the `bluetape4k-dependencies` snapshot update.
8. Only then open consumer/example repository PRs.

## Rollback

Before merge, rollback is branch deletion.

After merge but before snapshot publish, rollback is a revert PR restoring the
old project paths and artifactIds.

After snapshot publish, avoid rollback unless the coordinates are unusable.
Instead, fix forward and publish a corrected snapshot, because downstream repos
will be gated on explicit coordinate resolution.
