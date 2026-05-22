# Issue 200 Jdbc Record Serialization

## Context

Issue #200 reported that `AuditableEdgeCaseRecord` did not implement
`java.io.Serializable` and lacked an explicit `serialVersionUID`.

## Decision

Fix the reported record and the sibling `exposed-jdbc` repository test records
in the same package. The project rule applies to all data classes, and leaving
adjacent records without stable serialization contracts would recreate the same
daily review finding.

## Outcome

All `exposed-jdbc` repository test record data classes now implement
`Serializable` and define `serialVersionUID = 1L`. A focused regression test
checks the Java serialization contract with `ObjectStreamClass`.

## Verification

- `./gradlew :bluetape4k-exposed-jdbc:test --tests 'io.bluetape4k.exposed.jdbc.repository.JdbcRepositoryRecordSerializationTest' --no-daemon --no-configuration-cache --no-build-cache`
- `./gradlew :bluetape4k-exposed-jdbc:test --no-daemon --no-configuration-cache --no-build-cache`

## Future Guard

When a daily review flags one data class serialization miss, inspect sibling
test fixtures in the same package before opening a PR. Prefer a small
`ObjectStreamClass` regression test when the fix is contract-only.
