# exposed-measured README correction

## Context

The root README described `exposed-measured` as a Micrometer metrics integration, while the module actually provides Exposed custom column types for `bluetape4k-measured` units.

## Decision

Correct the root README and localized README to describe measured-unit column mappings. Replace the module README's Micrometer timer flow with a column conversion flow.

## Outcome

The documentation now matches the module behavior: `Measure<T>`, `Temperature`, and `TemperatureDelta` values are converted to base-unit `DOUBLE` values and restored on read.

## Verification

Searched README files outside `.worktrees` for `exposed-measured` combined with `Micrometer`, `metrics`, or `메트릭`; no remaining hits.

## Future Guidance

When documenting `exposed-measured`, treat "measured" as physical/unit measurement, not observability metrics.
