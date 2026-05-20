# bluetape4k BOM snapshot alignment

## Context

`bluetape4k-dependencies` moved the managed `bluetape4k-bom` alias from `1.8.0`
to `1.8.1-SNAPSHOT` after the shared dependency catalog upgrade.

## Decision

Align this repository's local catalog with the central BOM snapshot before
rerunning downstream sync verification.

## Outcome

The catalog now resolves bluetape4k modules from the same `1.8.1-SNAPSHOT`
family as the central dependency constraints.

## Verification

- `scripts/sync-shared-versions.py --workspace .. --check --summary` from
  `bluetape4k-dependencies` should no longer report this repository after the
  branch is merged.

## Future note

When the central BOM points at a new bluetape4k snapshot, downstream repositories
that keep a local `bluetape4k-bom` alias need their own sync PR after the central
change lands.
