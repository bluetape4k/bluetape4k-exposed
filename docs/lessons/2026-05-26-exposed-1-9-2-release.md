# Exposed 1.9.2 Release

## Context

The 1.9.2 stable release needed README coordinate cleanup before tagging and
then failed once because tag-triggered release used the stale repository catalog
variable `catalog/2026-05-23-00`.

## Decision

Publish the existing `1.9.2` tag through manual `release.yml` dispatch with
`catalogRef=catalog/2026-05-26-00`. Reopen `develop` on `baseVersion=1.9.3`,
but keep `bluetape4k-bom=1.9.2` because `1.9.3-SNAPSHOT` is not yet published.

## Outcome

Release workflow run `26441507142` succeeded and Maven Central returned HTTP
200 for both `bluetape4k-exposed-bom` and `bluetape4k-exposed-core` 1.9.2 POMs.

## Verification

- `./gradlew help --refresh-dependencies --no-daemon --no-configuration-cache --no-build-cache`
- Publish Snapshot run `26440950061`
- Nightly full run `26440951731`
- Publish Release run `26441507142`
- Maven Central HTTP 200 for 1.9.2 BOM and core POMs

## Future Notes

Do not rely on tag-push release defaults when a release needs a newer dependency
catalog than the repository variable. Dispatch `release.yml` manually with the
explicit `catalogRef`, or update the repository variable before tagging.
