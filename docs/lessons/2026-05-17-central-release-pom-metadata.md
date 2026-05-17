# Central Release POM Metadata

## Context

The 1.8.0 Central Portal release failed validation because generated Maven POMs
omitted dependency version metadata for dependencies managed by imported BOMs.

## Decision

Keep Spring dependency-management POM customization enabled for release POMs so
the generated POM includes dependency management entries.

## Outcome

Generated publication POMs now include `dependencyManagement` with
`io.github.bluetape4k:bluetape4k-bom:1.8.0` and no `SNAPSHOT` references.

## Verification

- `./gradlew generatePomFileForBluetapeExposedPublication --no-daemon --no-configuration-cache --no-build-cache`
- Searched generated `pom-default.xml` files for `SNAPSHOT`.

## Future Guidance

Before tagging a Central release, generate Maven POMs locally and verify managed
dependencies are represented either with explicit versions or valid POM
dependency management.
