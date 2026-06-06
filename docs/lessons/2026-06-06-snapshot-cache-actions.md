# Snapshot Cache Actions

## Context

CI and Nightly forced dependency refreshes while the repository relies on mutable
bluetape4k SNAPSHOT artifacts from Central snapshots.

## Decision

Remove `--refresh-dependencies`, remove Nightly `cache-disabled: true`, and change
the root changing-module cache TTL from zero seconds to one day.

## Outcome

CI and Nightly keep their existing test task structure, but regular dependency
resolution can use Gradle cache metadata instead of forcing Central snapshot
metadata requests on every job.

## Verification

- `actionlint .github/workflows/*.yml`
- `rg -n -- '--refresh-dependencies|cache-disabled: true' .github/workflows` -> no matches
- `./gradlew help --no-daemon`
- `git diff --check`

## Future Guidance

Use explicit dependency refresh only in dedicated post-publish freshness checks.
Ordinary CI, Nightly, and Examples workflows should rely on cached changing-module
metadata plus targeted warm-up when a test-only SNAPSHOT dependency needs it.
