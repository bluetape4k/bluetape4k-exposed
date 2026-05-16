# README.md / README.ko.md Structural Alignment

**Date**: 2026-05-16
**Issue**: #94
**Type**: Maintenance (docs)

## Summary

Aligned README.md and README.ko.md structural differences found during 1.8.0 pre-release review.

## Changes

1. Updated outdated `JetBrains Exposed 0.60+` requirement to `1.2+` in both files
   (the project has used Exposed 1.x since the standalone repo was created)
2. Added missing `deleteById` method to README.ko.md JDBC example to match README.md

## Future Guidance

- When adding code examples to README.md, always add the equivalent to README.ko.md in the same PR.
- Check `JetBrains Exposed` version in Requirements section when Exposed is upgraded.
