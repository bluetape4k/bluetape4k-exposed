# Module Folder and README Asset Rename

Date: 2026-06-22
Repo: `bluetape4k-exposed`

## Context

The repository kept published-style names in physical folders, for example
`exposed/exposed-core` and `spring-boot/exposed-jdbc`. After the artifact naming
contract stabilized, those prefixes made local paths noisier than the Gradle
project names they mapped to.

README diagram and chart asset names had the same duplicate pattern:
`exposed-exposed-*`, and the BOM asset used
`exposed-bluetape4k-exposed-bom-*`.

## Decision

Keep Gradle project names and Maven artifact names unchanged, but simplify
physical paths and README-facing asset filenames:

- `exposed/exposed-core` -> `exposed/core`
- `exposed/bluetape4k-exposed-bom` -> `exposed/bom`
- `spring-boot/exposed-jdbc` -> `spring-boot/jdbc`
- `spring-boot/exposed-spring-modulith` -> `spring-boot/spring-modulith`
- `docs/images/readme-diagrams/exposed-exposed-core-diagram-01.png` ->
  `docs/images/readme-diagrams/exposed-core-diagram-01.png`
- `docs/images/readme-diagrams/exposed-bluetape4k-exposed-bom-diagram-01.png` ->
  `docs/images/readme-diagrams/exposed-bom-diagram-01.png`

`spring-boot/batch-exposed` was left unchanged because it does not use the
`exposed-` prefix pattern and its name communicates Spring Batch integration.

## Validation

Use path-level validation for this kind of rename:

- `./gradlew -q projects --no-configuration-cache --no-daemon` to prove project
  names still map to the new directories.
- `./gradlew build -x test --parallel --no-configuration-cache --no-daemon` to
  prove compile-only build wiring still works.
- `actionlint .github/workflows/ci.yml .github/workflows/migration-smoke.yml`
  after workflow path-filter edits.
- `xmllint --noout` over renamed SVG assets because only names and references
  changed, not SVG geometry.
- README image-reference existence checks after replacing asset paths.
- `git diff --check` before commit.

## Follow-up Guard

Future module additions under `exposed/` should use short physical folder names
and map to published-style Gradle project names through `settings.gradle.kts`.
README-facing generated asset names should avoid repeating the directory or
artifact prefix.
