# bt4k Version Catalog Consumption

## Context

`bluetape4k-exposed` duplicated shared dependency versions in its local Gradle
catalog while `bluetape4k-dependencies` already publishes the ecosystem catalog.

## Decision

Import `io.github.bluetape4k:bluetape4k-version-catalog` as `bt4k` and resolve
shared leaf dependency constraints through `bt4kVersion(alias)`. Keep local
aliases for module names and plugin/BOM train versions that are still resolved
locally.

## Outcome

The local catalog no longer pins the selected shared leaf dependency aliases;
dependency management reads those versions from `bt4k`. This keeps dependency
coordinates local while centralizing the governed version values.

## Verification

- `git diff --check`
- `./gradlew help --no-daemon --no-configuration-cache`
- `./gradlew compileKotlin --no-daemon --no-configuration-cache`

## Future Guidance

Prefer `bt4k` for new shared dependency versions. Avoid adding a local version
unless the dependency is repository-specific or the central catalog cannot yet
serve the required plugin/BOM use case.
