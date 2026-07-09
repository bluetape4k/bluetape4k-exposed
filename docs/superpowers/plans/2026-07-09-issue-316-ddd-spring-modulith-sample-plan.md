# Issue #316 DDD Spring Modulith Sample Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a public DDD + Spring Modulith + Exposed example that demonstrates module boundaries, aggregate persistence, durable Modulith publication rows, idempotent listener handling, bilingual docs, and CI/Nightly registration.

**Architecture:** Add `examples/ddd-spring-modulith-demo` as `:examples-ddd-spring-modulith-demo`. The valid app has `orders` and `shipping` modules; `shipping` may depend only on `orders :: events`. The command path snapshots aggregate events inside the transaction, records the Spring Modulith publication handoff in the same transaction, and clears aggregate events only after the transaction returns successfully.

**Tech Stack:** Kotlin, Spring Boot, Spring Modulith, JetBrains Exposed JDBC, H2, `:bluetape4k-exposed-core`, `:bluetape4k-exposed-spring-boot-jdbc`, `:bluetape4k-exposed-spring-modulith`, JUnit 5, bluetape4k assertions, Awaitility, CairoSVG.

---

## Files

Create:

- `examples/ddd-spring-modulith-demo/build.gradle.kts`
- `examples/ddd-spring-modulith-demo/README.md`
- `examples/ddd-spring-modulith-demo/README.ko.md`
- `examples/ddd-spring-modulith-demo/src/main/kotlin/io/bluetape4k/exposed/examples/modulith/DddSpringModulithDemoApplication.kt`
- `examples/ddd-spring-modulith-demo/src/main/kotlin/io/bluetape4k/exposed/examples/modulith/orders/ModuleMetadata.kt`
- `examples/ddd-spring-modulith-demo/src/main/kotlin/io/bluetape4k/exposed/examples/modulith/orders/OrderDomain.kt`
- `examples/ddd-spring-modulith-demo/src/main/kotlin/io/bluetape4k/exposed/examples/modulith/orders/OrderApplicationService.kt`
- `examples/ddd-spring-modulith-demo/src/main/kotlin/io/bluetape4k/exposed/examples/modulith/orders/events/ModuleMetadata.kt`
- `examples/ddd-spring-modulith-demo/src/main/kotlin/io/bluetape4k/exposed/examples/modulith/orders/events/OrderAcceptedEvent.kt`
- `examples/ddd-spring-modulith-demo/src/main/kotlin/io/bluetape4k/exposed/examples/modulith/orders/internal/OrderRepository.kt`
- `examples/ddd-spring-modulith-demo/src/main/kotlin/io/bluetape4k/exposed/examples/modulith/shipping/ModuleMetadata.kt`
- `examples/ddd-spring-modulith-demo/src/main/kotlin/io/bluetape4k/exposed/examples/modulith/shipping/ShippingReservationHandler.kt`
- `examples/ddd-spring-modulith-demo/src/main/kotlin/io/bluetape4k/exposed/examples/modulith/shipping/internal/ShippingReservationRepository.kt`
- `examples/ddd-spring-modulith-demo/src/test/kotlin/io/bluetape4k/exposed/examples/modulith/DddSpringModulithDemoApplicationTest.kt`
- `examples/ddd-spring-modulith-demo/src/main/kotlin/io/bluetape4k/exposed/examples/modulithinvalid/InvalidBoundaryApplication.kt`
- `examples/ddd-spring-modulith-demo/src/main/kotlin/io/bluetape4k/exposed/examples/modulithinvalid/orders/**`
- `examples/ddd-spring-modulith-demo/src/main/kotlin/io/bluetape4k/exposed/examples/modulithinvalid/shipping/**`
- `examples/ddd-spring-modulith-demo/src/test/resources/junit-platform.properties`
- `examples/ddd-spring-modulith-demo/src/test/resources/logback-test.xml`
- `docs/images/readme-diagrams/examples-ddd-spring-modulith-demo-architecture.svg`
- `docs/images/readme-diagrams/examples-ddd-spring-modulith-demo-architecture.png`

Modify:

- `README.md`
- `README.ko.md`
- `.github/workflows/ci.yml`
- `.github/workflows/nightly-tests.yml`

## Task 1: Scaffold Module And RED Tests

complexity: medium

Apply `bluetape4k-code-patterns`, `ecc-kotlin-exposed`, `ecc-springboot-kotlin`, and `ecc-kotlin-testing`.

- [ ] Create `build.gradle.kts` with these dependencies:

```kotlin
plugins {
    kotlin("plugin.spring")
    application
}

application {
    mainClass.set("io.bluetape4k.exposed.examples.modulith.DddSpringModulithDemoApplicationKt")
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(platform(libs.spring.boot.dependencies))
    implementation(platform(libs.spring.modulith.bom))

    implementation(project(":bluetape4k-exposed-core"))
    implementation(project(":bluetape4k-exposed-spring-boot-jdbc"))
    implementation(project(":bluetape4k-exposed-spring-modulith"))

    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.exposed.spring7.transaction)
    implementation(libs.hikaricp)
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation(libs.spring.modulith.events.jackson)

    runtimeOnly(libs.h2.v2)

    testImplementation(libs.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.assertions)
    testImplementation(libs.awaitility.kotlin)
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
}
```

- [ ] Add test resources based on existing example resources:
  - `junit.jupiter.execution.parallel.enabled=false`
  - `junit.jupiter.testinstance.lifecycle.default=per_class`
  - Loggers for `io.bluetape4k.exposed.examples.modulith` and Exposed.
- [ ] Create the minimum invalid-boundary test fixture skeleton before writing tests:
  - `io.bluetape4k.exposed.examples.modulithinvalid.InvalidBoundaryApplication`
  - valid-enough `orders` and `shipping` module metadata under the invalid root
  - one invalid `shipping` type that imports an `orders.internal` type
  - keep the fixture outside `io.bluetape4k.exposed.examples.modulith`
  - this fixture must compile from the first RED run because Gradle compiles all test sources before applying `--tests` filters.
  - place it in `src/main/kotlin` under the sibling `modulithinvalid` package if Spring Modulith cannot scan the test-output package directly; the valid application scan still starts at `io.bluetape4k.exposed.examples.modulith`.
- [ ] Write `DddSpringModulithDemoApplicationTest` first with these test names:
  - `application modules allow shipping to depend only on order events`
  - `boundary verifier rejects shipping dependency on order internals`
  - `application context exposes Exposed backed publication repository`
  - `accepting an order persists reservation through Modulith publication`
  - `publication row stores only opaque event data`
  - `duplicate order accepted events keep shipping reservation idempotent`
  - `restart republishes incomplete order event without duplicate reservation`
  - `failed command transaction leaves no order reservation or publication row`
  - `failed handoff keeps aggregate domain events recorded`
- [ ] Expand the tests with exact state assertions:
  - happy path: orders `=1`, shipping reservations `=1`, completed publication rows `=1`, incomplete/failed rows `=0`.
  - duplicate delivery: shipping reservations stay `=1`.
  - restart republication: shipping reservations stay `=1`, publication completes.
  - rollback: order rows `=0`, shipping rows `=0`, publication rows `=0`.
  - add `count()` helpers to repositories/tables where needed.
- [ ] Make restart republication executable:
  - Use a 2-context test with the same H2 database name and the same static publication/order/shipping table names.
  - First context accepts an order through the real DDD `publishEvent` path, creates one shipping reservation, then deterministically reverts the matching publication row to incomplete by clearing its completion date through the publication table helper.
  - Close the first context.
  - Second context starts with `spring.modulith.events.republish-outstanding-events-on-restart=true`.
  - Use bounded polling, then assert publication completed and shipping reservation count remains `1`.
- [ ] Make transaction and handoff failure executable:
  - Rollback test: use the real `ApplicationEventPublisher` plus a test-only `failAfterPublish` transaction probe inside the `TransactionTemplate` path. Assert rollback leaves orders `=0`, shipping reservations `=0`, publication rows `=0`.
  - Handoff-failure test: use a throwing `ApplicationEventPublisher`/handoff bean only to prove aggregate `domainEvents()` remains non-empty when handoff fails before acceptance.
- [ ] Use Awaitility or equivalent bounded polling with `atMost(5 seconds)` for listener completion and publication-state observation.
  - Do not use unbounded sleeps.
  - Do not use raw `Thread.sleep`.
  - Do not assert listener effects with immediate reads after `publishEvent`.
- [ ] For `publication row stores only opaque event data`, accept input containing distinct `orderKey`, `customerId`, and secret-like sentinel strings, query raw publication `SERIALIZED_EVENT`, and assert:
  - none of the input identifiers or sentinel strings appear.
  - payload is limited to `aggregateId`, `eventId`, and `occurredAt`.
  - payload contains no `@class`, `@type`, or polymorphic type marker.
- [ ] Use bluetape4k assertions only:
  - `assertFailsWith<Violations> { ... }`
  - `actual shouldBeEqualTo expected`
  - `collection shouldHaveSize n`
  - `value.shouldNotBeNull()`
- [ ] Run RED:

```bash
./gradlew :examples-ddd-spring-modulith-demo:test --no-configuration-cache --no-daemon --console=plain
```

Expected: fails because production symbols are missing or not implemented.

## Task 2: Implement Valid Modulith Application

complexity: medium

- [ ] Add `DddSpringModulithDemoApplication.kt` as a Spring Boot application root with auto-configuration enabled and Exposed Modulith properties.
- [ ] Add `orders/ModuleMetadata.kt`:

```kotlin
package io.bluetape4k.exposed.examples.modulith.orders

import org.springframework.modulith.PackageInfo

@PackageInfo
class ModuleMetadata
```

- [ ] Add `orders/events/ModuleMetadata.kt` with `@NamedInterface("events")`.
- [ ] Add `shipping/ModuleMetadata.kt` with:

```kotlin
@ApplicationModule(allowedDependencies = ["orders :: events"])
@PackageInfo
class ModuleMetadata
```

- [ ] Implement `OrderId`, `AcceptOrderCommand`, `Order`, and `OrderStatus` in `orders/OrderDomain.kt`.
  - Data/value classes implement `Serializable` and define `serialVersionUID`.
  - Use `requireNotBlank` for command input validation.
  - `Order` extends `AbstractAggregateRoot<OrderId>`.
  - `Order.accept(...)` records `OrderAcceptedEvent`.
- [ ] Implement `OrderAcceptedEvent` in `orders.events` with only `aggregateId`, `eventId`, and `occurredAt`.
- [ ] Implement `orders.internal.OrderRepository` with an Exposed table and repository methods:
  - `createSchema()`
  - `deleteAll()`
  - `save(order: Order)`
  - `findByOrderId(orderId: OrderId)`
- [ ] Implement `OrderApplicationService`:
  - Transaction boundary uses `TransactionTemplate`.
  - Snapshot events with `domainEvents()`.
  - Persist aggregate.
  - Publish each event through `ApplicationEventPublisher`.
  - Service shape: snapshot, persist, and publish inside the `TransactionTemplate` callback; return only after the callback completes successfully; clear the aggregate event buffer outside the transaction result path.
  - Leave events recorded when transaction or handoff fails.

## Task 3: Implement Shipping Listener And Publication Wiring

complexity: medium

- [ ] Implement `shipping.internal.ShippingReservationRepository` with an Exposed table and idempotent insert keyed by order id.
  - `order_id` is the primary or unique key.
  - Use `insertIgnore`/upsert when available, or treat SQLState `23xxx` duplicate-key failure as successful idempotent handling.
  - Test direct duplicate handling by invoking the handler/repository twice with the same `OrderAcceptedEvent` and asserting row count `1`.
- [ ] Implement `ShippingReservationHandler`:
  - `@ApplicationModuleListener(id = "shipping.reserve-order")`
  - Handles `OrderAcceptedEvent`.
  - Inserts at most one reservation per order id.
- [ ] Implement application configuration beans:
  - `DataSource`.
  - `springTransactionManager`.
  - Narrow `EventSerializer` for `OrderAcceptedEvent`.
  - schema initializer for order and shipping tables.
- [ ] The `OrderAcceptedEvent` serializer must:
  - accept only `OrderAcceptedEvent::class.java` for deserialization.
  - emit deterministic JSON without type metadata.
  - reject unknown event types/classes.
  - explicitly avoid `activateDefaultTyping`, default typing, and unsafe polymorphic configuration.
- [ ] Use static safe table names in the example and unique H2 database names for normal test isolation.
- [ ] Test database isolation is mandatory:
  - Normal tests use unique H2 database names with static safe table names.
  - Restart republication test reuses the same H2 database name/table names and includes `DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE`.
  - Test helpers own table cleanup/drop for orders, shipping reservations, publication, and archive tables.
- [ ] Bounded async observation is mandatory in implementation/tests:
  - use `await.atMost(Duration.ofSeconds(5)).untilAsserted { ... }` or an equivalent latch helper.
  - wait for both reservation insert and publication completion.
  - no immediate read assumptions after `publishEvent`.
- [ ] Lifecycle cleanup:
  - close Spring contexts with `.use {}` or try/finally.
  - reset listener probes/latches in `@BeforeEach` or `@AfterEach`.
  - close Hikari `DataSource` through Spring context shutdown.
  - table state cleanup belongs to test helper methods.
- [ ] Verify GREEN for valid-app event/publication subset only; full module GREEN waits until the invalid fixture exists:

```bash
./gradlew :examples-ddd-spring-modulith-demo:test --tests '*accepting an order persists reservation through Modulith publication' --no-configuration-cache --no-daemon --console=plain --rerun-tasks
```

## Task 4: Invalid Boundary Fixture Completion

complexity: low

- [ ] Complete or adjust the Task 1 invalid fixture only as needed for the boundary verifier assertion.
- [ ] Keep the invalid root outside `io.bluetape4k.exposed.examples.modulith` so positive verification cannot scan it.
- [ ] Verify the negative test fails with `Violations` containing `orders` and `internal`.
- [ ] Run the full example test suite after the invalid fixture exists:

```bash
./gradlew :examples-ddd-spring-modulith-demo:test --no-configuration-cache --no-daemon --console=plain --rerun-tasks
```

## Task 5: README And Diagram

complexity: medium

Apply `bluetape4k-diagram`.

- [ ] Add `README.md` and `README.ko.md` with language switches.
- [ ] Include sections:
  - overview
  - architecture
  - supported / not supported
  - transaction and publication boundary
  - migration from direct service/repository calls
  - operational diagnostics
  - running tests
- [ ] In supported / not supported, explicitly state:
  - JDBC-only example.
  - No R2DBC or suspend API implementation.
  - No exactly-once guarantee.
  - No durable outbox beyond Spring Modulith publication rows.
  - Stable listener ids and idempotent consumers are required.
  - Unloadable event DTO/package rename risk belongs to application repair.
  - No direct cross-module repository access.
  - No benchmark, stress, throughput, or latency claims in this PR; verification is bounded functional integration plus exact row-count/state assertions.
- [ ] In security/operation guidance, explicitly state:
  - Publication table is app-owned internal state.
  - Write access to publication rows must be restricted.
  - Rows are not an external input channel.
  - Unloadable rows are operator repair data.
  - Event serializers must avoid unsafe polymorphic/default typing.
  - `initialize-schema=true` is H2/sample/local only; production DDL belongs to Flyway, Liquibase, or equivalent migrations.
- [ ] In migration guidance, show the before/after path:
  - before: direct call from shipping to order repository/service internals.
  - after: keep repositories internal, export only `orders.events`, move side effects to `@ApplicationModuleListener(id = "shipping.reserve-order")`, verify with `ApplicationModules.verify()`.
- [ ] Add a copy-paste running path:
  - command: `./gradlew :examples-ddd-spring-modulith-demo:test`
  - source packages to inspect: `orders`, `orders.events`, `shipping`, `orders.internal`, `shipping.internal`
  - expected result: one order, one exported event, one publication row transition, one shipping reservation.
- [ ] Add root README rows:
  - `README.md`: `examples-ddd-spring-modulith-demo`
  - `README.ko.md`: Korean equivalent
  - verification command: `./gradlew :examples-ddd-spring-modulith-demo:test`
- [ ] Add `spring-boot/spring-modulith/README.md` and `README.ko.md` See Also links to the new example.
- [ ] Create `docs/images/readme-diagrams/examples-ddd-spring-modulith-demo-architecture.svg`.
  - Show modules, named interface, tables, publication store, and numbered transaction/publication/listener/completion flow.
- [ ] Embed the rendered PNG in both new README files and verify relative paths.
- [ ] Full-size PNG inspection must confirm:
  - no label overflow.
  - no connector/card collisions.
  - lane/title spacing is readable.
  - diagram names actual modules, named interface, tables, publication store, and retry/incomplete path.
- [ ] Validate diagram:

```bash
xmllint --noout docs/images/readme-diagrams/examples-ddd-spring-modulith-demo-architecture.svg
/Users/debop/.local/bin/cairosvg docs/images/readme-diagrams/examples-ddd-spring-modulith-demo-architecture.svg -o docs/images/readme-diagrams/examples-ddd-spring-modulith-demo-architecture.png -s 2
```

- [ ] Inspect the generated PNG at full size.

## Task 6: CI And Nightly Registration

complexity: low

- [ ] Modify `.github/workflows/ci.yml`:
  - add `:examples-ddd-spring-modulith-demo:test`
  - add `:examples-ddd-spring-modulith-demo:koverXmlReport`
- [ ] Modify `.github/workflows/nightly-tests.yml` with the same task additions.
- [ ] Do not touch unrelated workflow jobs.
- [ ] Verify:

```bash
actionlint .github/workflows/ci.yml .github/workflows/nightly-tests.yml
rg -n ":examples-ddd-spring-modulith-demo:(test|koverXmlReport)" .github/workflows/ci.yml .github/workflows/nightly-tests.yml
```

## Task 7: Full Verification

complexity: medium

- [ ] Run:

```bash
./gradlew :examples-ddd-spring-modulith-demo:test --no-configuration-cache --no-daemon --console=plain --rerun-tasks
./gradlew :examples-ddd-spring-modulith-demo:build --no-configuration-cache --no-daemon --console=plain --rerun-tasks --warning-mode all
./gradlew projects --no-configuration-cache --no-daemon --console=plain
git diff --check
```

- [ ] Confirm `./gradlew projects` lists `:examples-ddd-spring-modulith-demo`.
- [ ] Confirm public docs grep-match actual source names:

```bash
rg -n "shipping.reserve-order|OrderAcceptedEvent|examples-ddd-spring-modulith-demo|EventPublicationRepository" README.md README.ko.md examples/ddd-spring-modulith-demo
rg -n "initialize-schema|Flyway|Liquibase|app-owned|restricted write|unsafe polymorphic|default typing|minimal|non-sensitive|customerId|orderKey|shipping.reserve-order|bluetape4k.exposed.modulith.publications" README.md README.ko.md examples/ddd-spring-modulith-demo/README.md examples/ddd-spring-modulith-demo/README.ko.md
```

- [ ] Verify required documentation terms per new README file, not only with a broad OR grep:
  - `examples/ddd-spring-modulith-demo/README.md`
  - `examples/ddd-spring-modulith-demo/README.ko.md`
  - Required concepts: `initialize-schema`, `Flyway` or `Liquibase`, app-owned/internal publication table, restricted write access, unsafe polymorphic/default typing, minimal non-sensitive payload, `customerId` and `orderKey` exclusion, `shipping.reserve-order`, `bluetape4k.exposed.modulith.publications`.
- [ ] Verification note: do not add benchmark, stress, throughput, or latency claims; verify exact row counts/state transitions and bounded functional integration only.

## Task 8: Review, Lessons, Commit, PR

complexity: medium

- [ ] Run Step 5 verifier against the spec and this plan.
- [ ] Run Step 6-R code review with six perspective lanes plus current-session integration.
- [ ] Add `docs/lessons/2026-07-09-issue-316-ddd-spring-modulith-sample.md`.
- [ ] Confirm release readiness scope:
  - no Maven publication changes.
  - no BOM/catalog changes.
  - no publish/release workflow changes.
  - the new project remains a non-published example module.
- [ ] Commit with Lore trailers after verification passes.
- [ ] Push branch and create PR:
  - body includes `Fixes #316`
  - before PR creation, read live issue metadata with `gh issue view 316 --json assignees,labels,milestone`
  - mirror the live issue assignee, milestone, and labels onto the PR
  - PR body ends with `## DoD Status`
- [ ] Verify live PR metadata and body:

```bash
gh pr view <number> --json body,assignees,labels,milestone,statusCheckRollup,mergeStateStatus,reviewDecision
gh issue view 316 --json number,state,assignees,labels,milestone,closedByPullRequestsReferences
```

- [ ] PR CI gate:
  - wait for CI Status success or record exact blocker.
  - verify `gh pr view <number> --json statusCheckRollup,mergeStateStatus,reviewDecision`.
  - dispatch Nightly full for the branch unless explicitly downgraded with rationale:

```bash
gh workflow run nightly-tests.yml --ref feat/issue-316-ddd-modulith-sample -f scope=full
gh run view <run-id> --json status,conclusion,jobs,url
```

  - record CI check evidence, Nightly dispatch URL/run URL, and any downgrade rationale in the PR `## DoD Status`.

## Rollback / Re-run Points

- If RED test does not fail for missing implementation, fix the test before production code.
- If Modulith verification scans the invalid fixture in the valid app, move the invalid package root farther away and rerun only boundary tests.
- If workflow YAML validation fails, stop and fix YAML before running Gradle CI-equivalent checks.
