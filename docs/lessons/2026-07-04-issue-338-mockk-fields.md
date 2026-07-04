# Issue 338 MockK Fixture Cleanup

## What Changed

Repeated MockK collaborator setup in representative tests was moved to class-level fixture fields and reset in `@BeforeEach` with `clearMocks(...)`.

## What To Repeat

- Promote stable collaborators such as API clients, service handles, `ResultRow`, and `Expression` mocks to class fields.
- Reset class-level mocks with `clearMocks(...)` before each test, then apply scenario-specific `every { ... }` stubs inside the test.
- Keep scenario data, payloads, and capture slots method-local because they encode the individual test case.
- If Gradle reports `Shutdown in progress` after tests have completed, rerun the same targeted command before treating it as a code failure.

## Evidence

- Baseline targeted tests passed before edits.
- Touched-file MockK scan leaves `mockk` calls only in class-level fixture declarations.
- Targeted tests passed after a retry with `BUILD SUCCESSFUL in 36s`.
