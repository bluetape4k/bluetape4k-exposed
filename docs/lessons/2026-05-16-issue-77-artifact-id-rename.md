# Lessons Learned - Issue #77 ArtifactId Rename

## L1: Keep Repository Identity Separate From Maven Coordinates

The artifact rename must not rewrite repository identity strings. Keep these
stable unless the repository itself is renamed:

- `rootProject.name = "bluetape4k-exposed"`
- GitHub and SCM URLs ending in `bluetape4k-exposed`
- README/WIP/SECURITY references to the repository slug
- Kotlin package names under `io.bluetape4k.exposed`

Maven coordinates change only under group `io.github.bluetape4k.exposed`.

## L2: Make Non-Directory Naming Explicit

Directory-derived project naming is safe for `exposed/*`, but Spring Boot,
utility, demo, and example paths need explicit mapping. Use dedicated
`includeMappedModule` entries for:

- `utils/batch` -> `:exposed-batch`
- `spring-boot/exposed-jdbc` -> `:exposed-spring-boot-jdbc`
- `spring-boot/exposed-r2dbc` -> `:exposed-spring-boot-r2dbc`
- `spring-boot/batch-exposed` -> `:exposed-spring-boot-batch`
- `spring-boot/exposed-spring-modulith` -> `:exposed-spring-modulith`

`exposed-spring-modulith` is intentionally not
`exposed-spring-boot-modulith`.

## L3: Verify ArtifactIds Through Generated POMs

Project path rewrites are not enough. Generate representative publication POMs
with `--no-configuration-cache` and grep the top-level `<artifactId>` values and
the BOM constraints. The BOM must publish as `exposed-bom` with `pom` packaging,
and its constraints must include renamed exposed modules while excluding demos
and examples.

## L4: CI/Nightly Paths Are Part Of The Contract

Workflow task paths must be renamed with the Gradle graph. Always run:

- `actionlint .github/workflows/ci.yml .github/workflows/nightly.yml .github/workflows/publish-snapshot.yml`
- `rg -n 'bluetape4k-' .github/workflows/{ci,nightly}.yml`
- `rg -n -F "\\'" .github/workflows`

The second and third checks should return no hits.

## L5: Downstream Order Matters

Do not update consumer repositories immediately after the exposed PR is merged.
The safe rollout is:

1. Merge exposed rename PR after PR CI and PR branch Nightly(full) pass.
2. Run exposed `develop` Nightly(full).
3. Publish exposed snapshot.
4. Verify representative renamed coordinates resolve from Central Snapshots.
5. Update and publish `bluetape4k-dependencies` snapshot first.
6. Update consumer/example repositories against the dependencies snapshot.

Consumer repositories depend on the dependencies catalog/BOM, so the
dependencies snapshot is the first downstream blocker.

## L6: Downstream Generators Must Understand New Settings Shape

`bluetape4k-dependencies` does not only bump versions. It reads managed
repositories and generates catalog aliases plus BOM constraints. After this
rename, its sync script must understand `includeMappedModule(...)` and the
`includeModules("exposed", withBaseDir = false)` convention, or it can recreate
old `bluetape4k-exposed-*` aliases from stale assumptions. Treat this as part of
the dependencies PR, not as a consumer repo cleanup.

## Verification Snapshot

Local verification for the implementation branch covered project graph, old
path absence, generated publication POMs, workflow syntax, and targeted tests
across H2, PostgreSQL, and MySQL 8 paths. Remote PR CI, PR branch Nightly(full),
develop Nightly(full), snapshot publish, and downstream repository PRs remain
post-PR gates.
