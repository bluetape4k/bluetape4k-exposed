# Issue 317 Outstanding Republication Review

## Scope

- Issue: #317 `feat(spring-modulith): verify outstanding event republication with Exposed store`
- Files reviewed:
  - `spring-boot/spring-modulith/src/test/kotlin/io/bluetape4k/spring/modulith/exposed/ExposedEventPublicationRepositoryTest.kt`
  - `spring-boot/spring-modulith/README.md`
  - `spring-boot/spring-modulith/README.ko.md`

## Tier 4 - Implementation Review

- Result: PASS
- P0/P1 findings: 0
- Evidence:
  - The test creates real Spring Modulith publications through `context.publishEvent(...)`, so the stored listener id is assigned by Spring Modulith instead of a hand-written fixture.
  - The restart path recreates the Spring application context with `spring.modulith.events.republish-outstanding-events-on-restart=true`.
  - The assertion proves the incomplete event is replayed exactly once and the manually completed publication is not replayed.
  - The verification runs across `TestDB.enabledDialects()` and `CompletionMode.entries`.

## Tier 5 - Test Review

- Result: PASS
- P0/P1 findings: 0
- Evidence:
  - `repo-test-summary -- ./gradlew :bluetape4k-exposed-spring-modulith:test --no-configuration-cache --no-build-cache --no-parallel --rerun-tasks --console=plain`
  - Result: `SUCCESS: Executed 54 tests in 15.2s`
  - Result: `BUILD SUCCESSFUL in 19s`
- Concurrency helper gate:
  - No stress, contention, or thread-safety behavior is introduced.
  - The bounded polling helper only waits for Spring Modulith's asynchronous startup resubmission listener to finish; `MultithreadingTester`, `SuspendedJobTester`, and `StructuredTaskScopeTester` do not fit this event-delivery wait.

## Tier 7 - Documentation And Diagram Review

- Result: PASS
- P0/P1 findings: 0
- Evidence:
  - `README.md` and `README.ko.md` now name `spring.modulith.events.republish-outstanding-events-on-restart`.
  - Both README files explain completed-publication skip behavior, idempotent listener requirements, stable listener ids, duplicate side-effect guards, completion-mode behavior after replay, and unloadable event caution.
  - Diagram assessment: no new diagram asset was added. The existing lifecycle sequence diagram already covers publication creation, completion modes, and retry/resubmission state tracking. Issue #317 adds an operator-facing startup property and idempotency cautions rather than a new component topology or new state machine, so a text subsection is the clearer and lower-maintenance artifact.

## Hygiene

- `git diff --check`: PASS
- IDE diagnostics: Kotlin/IntelliJ diagnostics backend was not available in this CLI session; Gradle `compileTestKotlin` and module tests passed.
