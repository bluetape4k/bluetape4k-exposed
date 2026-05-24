# Snapshot Validation Line

## Context

After the previous release, snapshot validation needed the repository reopened
on the next development line while consuming the matching upstream bluetape4k
snapshot.

## Decision

Set `baseVersion=1.9.2`, keep `snapshotVersion=` empty, and consume
`bluetape4k-bom:1.9.2-SNAPSHOT`.

## Outcome

The repository can publish `1.9.2-SNAPSHOT` through `publish-snapshot.yml`
without checking a snapshot suffix into `gradle.properties`.

## Verification

Pending in the snapshot validation train.
