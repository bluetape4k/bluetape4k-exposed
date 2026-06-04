# 2026-06-04 Issue 246 Pin Bluetape4k Catalog Aliases

## Context

After disabling Gradle action caching, Nightly smoke no longer failed in `exposed-measured`, but H2 and Spring Boot jobs still resolved `exposed-jdbc-tests` bluetape4k dependencies as `group:artifact:.`.

## Decision

Keep repo-local `libs.bluetape4k.*` aliases versioned with `version.ref = "bluetape4k-bom"` so task graphs are not dependent on dependency-management timing alone.

## Outcome

The local catalog now mirrors the release-train catalog style for bluetape4k artifacts while preserving the existing `bluetape4k-bom` version key.

## Verification

- Planned: clean/fresh Gradle home H2 Nightly task graph.
- Planned: clean/fresh Gradle home Spring Boot Nightly task graph.
- Planned: `git diff --check` and catalog alias audit.

## Future Rule

When a repo-local version catalog keeps bluetape4k aliases, version them through the bluetape4k BOM ref instead of relying on unversioned aliases plus dependency-management only.
