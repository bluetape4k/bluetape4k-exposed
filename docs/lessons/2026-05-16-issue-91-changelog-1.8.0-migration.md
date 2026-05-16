# CHANGELOG [Unreleased] → [1.8.0] Migration

**Date**: 2026-05-16
**Issue**: #91
**Type**: Maintenance (docs)

## Summary

Migrated the `[Unreleased]` CHANGELOG section to a versioned `[1.8.0] - 2026-05-16` release entry,
incorporating all accumulated changes since the initial standalone repository creation, plus
12 pre-release bug-fix entries (#79–#90) from PR #95–#106.

## Actions Taken

1. Removed `[Unreleased]` content (Added / Changed / Fixed subsections)
2. Created `[1.8.0] - 2026-05-16` section merging:
   - Original `[1.8.0] - 2026-05-07` Added items (initial repo setup)
   - Post-creation Added/Changed items from the prior `[Unreleased]` block
   - All 12 bug-fix entries from #79–#90
3. Left fresh empty `[Unreleased]` header at top for future unreleased work

## Future Guidance

- After merging all 16 pre-release PRs, this CHANGELOG entry represents the final 1.8.0 Maven Central release.
- Keep `[Unreleased]` empty until the next cycle of changes begins.
- When a PR adds a new notable feature, update `[Unreleased]` immediately in the same PR.
