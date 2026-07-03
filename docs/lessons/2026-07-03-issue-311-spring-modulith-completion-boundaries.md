# Issue 311 Spring Modulith Completion Boundaries

## Context

Issue #311 hardened duplicate completion and repeated resubmission behavior for
the Exposed-backed Spring Modulith event publication repository.

## Decision

- Use Kotlin package functions for Spring Modulith factory calls:
  `targetEventPublicationOf(...)` and `publicationTargetIdentifierOf(...)`.
- Keep retry-boundary tests deterministic. This issue covers duplicate retry
  calls, not thread-race stress, so bluetape4k-junit5 concurrency testers are
  not the right fit here.
- Document module-facing idempotency behavior in both README locale files.

## Outcome

- UPDATE-mode identifier completion now preserves the first completion date.
- DELETE and ARCHIVE duplicate completion paths are locked by tests.
- Repeated resubmission keeps attempts and timestamp stable after the first
  resubmission.

## Verification

- `./gradlew :bluetape4k-exposed-spring-modulith:test` passed 43 tests.
- `git diff --check` passed.

## Future Guard

When touching bluetape4k Kotlin tests, use infix `shouldBeEqualTo`, use
`shouldBeTrue()` / `shouldBeFalse()` for booleans, avoid Java-style static
factory calls when a package function can express the Kotlin API, and record the
concurrency-helper rationale in PR DoD when duplicate retry tests are
deterministic rather than stress-based.
