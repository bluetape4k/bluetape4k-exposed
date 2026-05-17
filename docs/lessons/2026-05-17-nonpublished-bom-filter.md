# Non-published module BOM filter

## Context

Example and demo modules are useful for validation but should not become
consumer BOM or Central Portal artifacts.

## Decision

Use one normalized non-published module filter for examples, `*-examples`,
`*-demo`, `benchmark/`, and `*-benchmark` across BOM constraints, NMCP
aggregation, and publication/signing setup.

## Outcome

The Exposed BOM and publishing aggregation now include only library modules.

## Verification

- `./gradlew clean generatePomFileForBluetapeExposedPublication --no-daemon --no-configuration-cache --no-build-cache`
- Generated BOM POM scan found no `examples`, `demo`, or `benchmark` entries.
