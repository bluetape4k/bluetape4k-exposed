# Issue #250: exposed snapshot retry backoff

## Context

After projects Nightly passed on the latest develop commit, exposed Nightly full
failed in `Build & Detekt` while resolving upstream `1.11.0-SNAPSHOT` metadata
from Central snapshots.

## Decision

Use the same bounded retry posture across exposed CI and Nightly Gradle gates:
five attempts with a 30 second delay. Keep configuration cache disabled for
these CI commands so failed dependency resolution does not introduce cache
serialization noise.

## Outcome

The workflow retry window now matches the transient Central 403 pattern seen
across downstream bluetape4k repositories.

## Verification

Run `git diff --check` and `actionlint` after editing the workflow files, then
rerun exposed Nightly full before continuing to downstream repositories.

## Future guidance

When Nightly fails on Central snapshot metadata after an upstream publish, verify
the upstream repo first, then harden downstream workflow retry windows before
rerunning the dependency chain.
