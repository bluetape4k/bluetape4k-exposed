# Issue 281 Spring Modulith Unloadable Events Review

Date: 2026-06-23
Scope: `spring-boot/spring-modulith`
Issue: #281

## Verdict

P0 findings: 0
P1 findings: 0

Stored Spring Modulith publications with unloadable event classes are no longer filtered out during repository queries.
They remain visible to incomplete, failed, and status lookup paths, and they fail explicitly only when the caller tries
to deserialize the event payload.

## Review Notes

- Publication row materialization now maps every matching row instead of dropping rows whose event class cannot load.
- `UnloadableEventPublicationException` carries the publication id, listener id, and event type so operators can locate
  and repair the stored row.
- Event deserialization is deferred behind the existing `TargetEventPublication.event` access path, preserving query
  visibility while still failing loudly at the point where a concrete event object is required.
- The regression inserts unknown `EVENT_TYPE` rows directly and verifies both incomplete and failed publication queries
  surface those rows.
- English and Korean READMEs document the operator choices: restore the classpath, migrate stored rows, or explicitly
  delete/resubmit after correction.

## Validation

- `./gradlew :bluetape4k-exposed-spring-modulith:testClasses --rerun-tasks`
  - Result: success.
- `./gradlew :bluetape4k-exposed-spring-modulith:test --continue --rerun-tasks`
  - Result: success.
- `git diff --check`
  - Result: success.

## Residual Risk

- Operators still need an explicit migration or cleanup path for renamed event classes. This change makes those rows
  discoverable and diagnostic, but it does not infer a safe payload migration automatically.
