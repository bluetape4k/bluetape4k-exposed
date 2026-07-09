# Issue 317 Outstanding Republication

## Context

Spring Modulith can resubmit incomplete publications during application startup. The Exposed-backed repository needed proof that stored incomplete rows survive context shutdown and are replayed through the real listener registry on restart.

## Decision

Use `context.publishEvent(...)` to create the stored publications instead of manually building rows with a guessed listener id. Then restart the application context with `spring.modulith.events.republish-outstanding-events-on-restart=true` and assert only the incomplete event is delivered.

## Outcome

The test now covers H2, PostgreSQL, and MySQL 8 through `TestDB.enabledDialects()` and all Spring Modulith completion modes. README documentation was updated in English and Korean with the restart property and listener idempotency cautions.

## Future Guidance

- Keep restart republication tests on the real Spring event path so listener-id drift is caught.
- Treat startup resubmission as asynchronous event delivery and use bounded polling for state assertions.
- Add a new diagram only when restart behavior introduces a new topology or state transition that the existing lifecycle diagram does not already explain.

## Verification

- `repo-test-summary -- ./gradlew :bluetape4k-exposed-spring-modulith:test --no-configuration-cache --no-build-cache --no-parallel --rerun-tasks --console=plain`
- `SUCCESS: Executed 54 tests in 15.2s`
- `BUILD SUCCESSFUL in 19s`
- `git diff --check`
