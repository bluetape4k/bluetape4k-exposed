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

The failure recurred on the 1.12.0 SNAPSHOT line after shared versions moved to
the central Gradle catalog. Publication-facing dependencies still used local
aliases that intentionally omit versions. Although the authoritative `bt4k`
catalog already exposed versioned aliases for the Exposed and Spring Boot BOMs,
the module build files did not use them. Gradle therefore generated versionless
BOM imports even though the build itself resolved successfully.

Publication-facing BOM declarations now use `bt4k.exposed.bom` and
`bt4k.spring.boot4.dependencies`. A repository validator rejects every
versionless `dependencyManagement` entry and rejects an unversioned regular
dependency unless the same POM supplies explicit dependency management or a
versioned BOM import. CI, SNAPSHOT publication, and stable publication all run
the validator before publishing can continue.

## Verification

- `./gradlew generatePomFileForBluetapeExposedPublication --no-daemon --no-configuration-cache --no-build-cache`
- `ruby scripts/publication/validate_poms.rb`
- Verify generated `pom-default.xml` files contain neither missing dependency
  versions nor unintended `SNAPSHOT` references for the target release class.

## Future Guidance

Do not treat a successful Gradle dependency resolution as publication proof.
Before any Central publication, generate every public POM and require explicit
versions on all dependency-management entries, especially imported BOMs.
Regular dependencies may omit direct versions only when the generated POM
provides valid dependency management. Prefer the authoritative central catalog
alias whenever it already represents a publication-facing platform. Keep the
validator in PR CI as well as both publication workflows so a malformed POM
cannot reach a repository before the gate runs.
