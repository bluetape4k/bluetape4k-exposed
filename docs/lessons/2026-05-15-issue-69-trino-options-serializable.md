# Issue #69 Trino Options Serializable Lessons

## Context

Issue #28 and Issue #29 introduced `TrinoPagedQueryOptions` and
`TrinoBatchInsertOptions`, but the new public data classes missed the
bluetape4k Serializable contract.

## Lessons

- New public data classes in bluetape4k modules should implement
  `java.io.Serializable` and define an explicit `serialVersionUID`.
- Review checklists for small option/config classes must include JVM
  serialization compatibility, even when the class only contains primitive
  properties.
- qmd lookup did not surface a strong current rule for this repository. Treat
  the issue body and existing source patterns as the source of truth, then
  capture the rule here for future searches.

## Verification

- Added `Serializable` and stable `serialVersionUID = 1L` to
  `TrinoPagedQueryOptions` and `TrinoBatchInsertOptions`.
- Added a regression test that checks both `Serializable` implementation and
  `ObjectStreamClass` serialVersionUID values.
- `./gradlew :exposed-trino:test --tests "io.bluetape4k.exposed.trino.TrinoExtensionsTest" --no-configuration-cache --console=plain`
  passed.
- `./gradlew :exposed-trino:test --no-configuration-cache --console=plain`
  passed with 67 tests.
- `git diff --check` passed.
- `./gradlew detekt --no-configuration-cache --console=plain` passed with
  `:detekt NO-SOURCE`.
- Claude advisor review found no P0/P1 blockers.

## Follow-up Guidance

- When adding a public Kotlin `data class`, search for nearby
  `serialVersionUID` patterns before opening the PR.
- Prefer tests that verify `ObjectStreamClass.lookup(...).serialVersionUID`
  instead of only checking `is Serializable`; this catches missing explicit UID
  drift.
