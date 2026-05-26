# 1.9.2 Release Prep

## Context

The 1.9.2 stable release preflight found the target milestone closed and
`baseVersion=1.9.2`, but `CHANGELOG.md` still lacked a dated 1.9.2 section.

## Decision

Add a concise 1.9.2 changelog section before publishing. Cover the release-line
BOM/catalog alignment, the Exposed Gradle plugin adoption, and the two README
documentation issues in the milestone.

## Outcome

The release-prep branch contains only changelog metadata plus this lesson. It
does not change runtime code, build logic, or generated artifacts.

## Verification

- `git diff --check`
- Release preflight will continue after this prep PR is merged.

## Future Notes

Check `CHANGELOG.md` before stable release dispatch even when the milestone and
version files already look ready.
