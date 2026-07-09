# Issue #316 - DDD Spring Modulith Exposed Sample Design

## Context

Issue [#316](https://github.com/bluetape4k/bluetape4k-exposed/issues/316)
asks for a public example that combines:

- DDD aggregate persistence with bluetape4k Exposed repositories.
- Spring Modulith application-module boundaries.
- Domain/application event publication with `@ApplicationModuleListener`.
- Durable publication state through `:bluetape4k-exposed-spring-modulith`.
- English and Korean README documentation when a new public example is added.

Current repository evidence:

- `settings.gradle.kts` auto-registers directories under `examples/`, so
  `examples/ddd-spring-modulith-demo` becomes
  `:examples-ddd-spring-modulith-demo`.
- `.github/workflows/ci.yml` and `.github/workflows/nightly-tests.yml` run
  example tests through explicit Gradle task lists, so a new example must be
  added to both `test-examples` jobs and their Kover report commands.
- `exposed/core/src/main/kotlin/io/bluetape4k/exposed/core/ddd/` already
  provides Spring-neutral `AggregateRoot`, `AbstractAggregateRoot`, and
  `DomainEvent` contracts.
- `spring-boot/spring-modulith` already provides the Exposed-backed Spring
  Modulith event-publication repository and tests a real
  `@ApplicationModuleListener` path.
- The related `exposed-workshop` issue #145 and PR #157 used the same
  `orders :: events` named-interface pattern with positive and negative
  `ApplicationModules.verify()` tests.
- Context7 Spring Modulith documentation confirms
  `ApplicationModules.of(Application::class.java).verify()`,
  `@ApplicationModule(allowedDependencies = "order :: *")`,
  Kotlin `@PackageInfo` metadata classes, `@NamedInterface`, and
  `@ApplicationModuleListener` as current patterns.

## Approved Direction

Create `examples/ddd-spring-modulith-demo`.

The user approved the next-issue plan after the #370 merge. #326 remains
deferred because it was previously questioned as a possible exclusion and is a
broader Ktor/R2DBC/cache expansion. #316 is the narrower continuation of the
recent Spring Modulith and DDD work.

## Architecture

The example models an order-to-shipping handoff with two Spring Modulith
application modules:

- `orders`: accepts an order command, creates an aggregate, persists it with
  Exposed, and records an `OrderAcceptedEvent`.
- `shipping`: listens to `OrderAcceptedEvent` via
  `@ApplicationModuleListener(id = "shipping.reserve-order")` and persists an
  idempotent shipping reservation with its own Exposed table.

The only exported dependency surface from `orders` is `orders.events`, marked
with `@NamedInterface("events")`. The `shipping` module declares
`@ApplicationModule(allowedDependencies = ["orders :: events"])` through a
Kotlin `ModuleMetadata` class annotated with `@PackageInfo`.

The aggregate uses `AbstractAggregateRoot<OrderId>` and emits
`DomainEvent<OrderId>` payloads. The command service persists the aggregate in a
Spring transaction, snapshots the recorded domain events, publishes that
snapshot through Spring's `ApplicationEventPublisher`, and clears the aggregate
buffer only after the transaction has successfully accepted the Spring Modulith
publication handoff. The `publishEvent(...)` call is the handoff to Spring
Modulith publication recording, not a generic durable outbox.

The Exposed Modulith publication repository owns durable listener publication
state. The listener must use a stable listener id and deduplicate reservations
by order id so restart republication or duplicate delivery does not create a
second reservation. The sample config enables schema initialization through
`bluetape4k.spring.modulith.exposed.initialize-schema=true` and uses a
test-specific publication table name so tests stay isolated. That flag is for
H2 sample/test execution only; production deployments should keep schema
auto-initialization disabled and manage DDL with Flyway, Liquibase, or an
equivalent migration process.

## Design Alternatives

### Option A - New focused example under `examples/ddd-spring-modulith-demo`

Selected.

Pros:

- Fits issue #316 exactly and keeps the example discoverable beside existing
  public examples.
- Reuses current repo modules instead of copying workshop code.
- Gives CI and Nightly a clear single Gradle project to run.
- Keeps Ktor/R2DBC/cache expansion out of this PR.

Cons:

- Adds a new example module and workflow registration surface.
- Requires bilingual README and diagram validation.

### Option B - Extend `spring-boot/spring-modulith` tests only

Rejected.

Pros:

- Smaller diff and faster local verification.

Cons:

- Does not create the public DDD sample requested by the issue.
- Keeps module-boundary guidance hidden in library tests instead of user-facing
  documentation.
- Does not exercise example registration and README conventions.

### Option C - Reuse or copy the `exposed-workshop` #145 module

Rejected as a direct copy, partially borrowed as prior art.

Pros:

- Proven `orders :: events` boundary shape and negative verifier fixture.

Cons:

- Workshop code uses workshop package names and dependencies.
- This repository should demonstrate bluetape4k-exposed DDD contracts and
  `:bluetape4k-exposed-spring-modulith`, not only plain Exposed plus Modulith.

## Package And File Shape

Main package:

```text
io.bluetape4k.exposed.examples.modulith
```

Expected files:

- `examples/ddd-spring-modulith-demo/build.gradle.kts`
- `examples/ddd-spring-modulith-demo/README.md`
- `examples/ddd-spring-modulith-demo/README.ko.md`
- `examples/ddd-spring-modulith-demo/src/main/kotlin/.../DddSpringModulithDemoApplication.kt`
- `examples/ddd-spring-modulith-demo/src/main/kotlin/.../orders/**`
- `examples/ddd-spring-modulith-demo/src/main/kotlin/.../orders/events/**`
- `examples/ddd-spring-modulith-demo/src/main/kotlin/.../orders/internal/**`
- `examples/ddd-spring-modulith-demo/src/main/kotlin/.../shipping/**`
- `examples/ddd-spring-modulith-demo/src/main/kotlin/.../shipping/internal/**`
- `examples/ddd-spring-modulith-demo/src/test/kotlin/.../DddSpringModulithDemoApplicationTest.kt`
- `examples/ddd-spring-modulith-demo/src/test/kotlin/io/bluetape4k/exposed/examples/modulithinvalid/**`
- `examples/ddd-spring-modulith-demo/src/test/resources/junit-platform.properties`
- `examples/ddd-spring-modulith-demo/src/test/resources/logback-test.xml`
- `docs/images/readme-diagrams/examples-ddd-spring-modulith-demo-architecture.svg`
- `docs/images/readme-diagrams/examples-ddd-spring-modulith-demo-architecture.png`

## Domain Model

The domain stays intentionally small:

- `OrderId`: opaque value object.
- `OrderStatus`: `ACCEPTED`.
- `Order`: aggregate root.
- `AcceptOrderCommand`: input command.
- `OrderAcceptedEvent`: exported event in `orders.events`; fields are
  `aggregateId: OrderId`, `eventId: String`, and `occurredAt: Instant`.
- `ShippingReservation`: consumer-side projection/reservation keyed by order id.

Rules:

- `orderKey` and `customerId` must be non-blank command input only.
- Event payloads carry only opaque sample identifiers and minimal facts.
  `OrderAcceptedEvent` must not persist customer-facing identifiers, natural
  order keys, addresses, emails, tokens, credentials, secrets, or full aggregate
  snapshots in the publication table.
- Exposed tables and repositories are internal to their owning module.
- `shipping` must not import `orders.internal` in the valid application.
- The shipping listener must be idempotent: repeated `OrderAcceptedEvent`
  delivery for the same order id leaves exactly one reservation.
- A test-only invalid fixture must import `orders.internal` from `shipping` and
  prove that `ApplicationModules.verify()` rejects the dependency.

All data classes must implement `Serializable` and define `serialVersionUID`.
New public classes and functions need English KDoc where they are part of the
example's reader-facing API.

## Persistence And Transaction Boundary

The sample uses H2 in PostgreSQL compatibility mode for local-first tests and
docs. No external credentials or Testcontainers are required for this example.

The command service runs inside a Spring transaction:

1. Create or accept an order aggregate.
2. Persist the aggregate with an Exposed JDBC repository.
3. Snapshot aggregate domain events once with `domainEvents()`.
4. Publish the snapshot with Spring `ApplicationEventPublisher` so Spring
   Modulith records the listener publication in the same command transaction.
5. After the transaction returns successfully, clear the aggregate event buffer.
   If publishing, handoff recording, or the surrounding transaction fails, the
   aggregate event buffer remains intact for caller-owned retry/discard handling.

The intended boundary is explicit: order persistence and Spring Modulith
publication-row creation are part of the command transaction. Shipping listener
side effects run after the command transaction has accepted the event and must
not be required for the command commit. If the command transaction rolls back
after event snapshot but before commit, the test must prove that no order,
shipping reservation, or publication row remains.

Spring Modulith records publication rows through the
`:bluetape4k-exposed-spring-modulith` repository and invokes the shipping
listener. The integration test verifies both the shipping reservation and the
publication repository state against the Exposed-backed store. The happy path
must have exactly one exported event and exactly one listener, and tests should
assert exactly one publication state transition. The expected DB overhead is one
order insert, one publication insert/update pair owned by Spring Modulith, and
one shipping insert. Listener tests must use bounded waiting around asynchronous
publication completion, target the existing 5 second local test window, and use
thread-safe, per-context state instead of unbounded sleeps or immediate reads.

Tests must isolate all mutable database state. Acceptable approaches are a
unique H2 database per Spring context or randomized table names for orders,
shipping reservations, and publication rows. Test resources must disable JUnit
parallel execution for this module. Configured table names must be static
code/test-owned identifiers matching `[A-Z][A-Z0-9_]*`; they must not be derived
from request parameters, user input, or untrusted environment values.

The negative Modulith fixture must use a distinct package root outside the
valid application base package, for example
`io.bluetape4k.exposed.examples.modulithinvalid`. Positive and negative
verification must call separate `ApplicationModules.of(...)` entrypoints so the
invalid fixture cannot contaminate the valid application scan.

The publication table is trusted, app-owned internal state, not an external
input channel. README guidance must state that database write access to the
publication table is restricted to the application/migration owner and that
event serializers must avoid unsafe polymorphic/default typing. The example
uses one stable DTO event class under `orders.events`.

## Build And Runtime Wiring

`examples/ddd-spring-modulith-demo/build.gradle.kts` must include:

- Spring Boot dependency platform and Spring Modulith BOM.
- `application` and Kotlin Spring plugin.
- `implementation(project(":bluetape4k-exposed-core"))`.
- `implementation(project(":bluetape4k-exposed-spring-boot-jdbc"))`.
- `implementation(project(":bluetape4k-exposed-spring-modulith"))`.
- Exposed JDBC, Java time, Spring transaction, HikariCP, Spring Boot starter
  JDBC, Spring Modulith starter/core/events, and H2 runtime.
- Test dependencies for Spring Boot test, Spring Modulith test/core,
  `bluetape4k-junit5`, `bluetape4k-assertions`, and Awaitility when bounded
  listener polling needs it.

The Spring context must expose:

- `DataSource`.
- `springTransactionManager` compatible with the Exposed Modulith
  auto-configuration.
- `EventSerializer` for `OrderAcceptedEvent`.
- `EventPublicationRepository` from `:bluetape4k-exposed-spring-modulith`.

## Operational Resources And Runbook

The example owns these local resources:

| Resource | Owner | Local initialization | Cleanup / rollback |
| --- | --- | --- | --- |
| H2 `DataSource` | Sample Spring context | Spring Boot test/application properties | Close Spring context; use unique database names per context |
| Orders table | `orders.internal` repository | Sample schema initializer | Drop/delete in test cleanup; rollback leaves no rows |
| Shipping reservations table | `shipping.internal` repository | Sample schema initializer | Drop/delete in test cleanup; idempotent order key prevents duplicates |
| Modulith publication/archive tables | `:bluetape4k-exposed-spring-modulith` | `initialize-schema=true` for H2 sample/test only | Query `incomplete`, `completed`, `failed`, and `unloadable` rows; production uses migrations |

Operational documentation must include:

- `bluetape4k.spring.modulith.exposed.initialize-schema=true` is sample/local
  only. Production should keep it disabled and manage DDL through migrations.
- Stable listener id: `shipping.reserve-order`.
- Publication table/status query guidance for local diagnosis.
- Micrometer meter name `bluetape4k.exposed.modulith.publications` and states
  `incomplete`, `completed`, `failed`, and `unloadable`.
- Failure triage:
  - listener not invoked: check listener id, module scan, and publication row.
  - publication incomplete/failed: inspect publication completion date and
    listener exception path.
  - unloadable event type: treat the row as app-owned repair data, verify class
    name/package migration, and do not deserialize untrusted external rows.

## Documentation And Diagram

The new public example gets both `README.md` and `README.ko.md` with language
switches. The docs explain:

- When to use this pattern.
- Which boundary belongs to DDD, Spring Modulith, Exposed, and the Exposed
  Modulith store.
- How to run the example tests.
- Why `orders.events` is the only exported named interface.
- Why event payloads should remain minimal and non-sensitive.
- Supported/not supported boundaries: JDBC-only, no R2DBC or suspend API, no
  exactly-once guarantee, no durable outbox beyond Spring Modulith publication
  rows, stable listener ids, idempotent consumers, unloadable event DTO/package
  rename risk, and no direct cross-module repository access.
- Migration from direct service/repository calls: keep repositories internal,
  export only `orders.events`, move shipping side effects into an idempotent
  `@ApplicationModuleListener(id = "...")`, and verify boundaries with
  `ApplicationModules.verify()`.

A README architecture diagram is required because the example is about
cross-module ownership, not only API syntax. The diagram should show:

- `orders` module ownership.
- exported `orders.events` named interface.
- `shipping` module allowed dependency.
- Exposed order and shipping tables.
- Exposed-backed Spring Modulith publication table.
- A numbered flow for order transaction, publication row creation, listener
  invocation, completion state, and retryable/incomplete state. A second
  sequence diagram is allowed if the architecture diagram becomes too crowded.

The diagram must follow `bluetape4k-diagram` rules, render SVG to PNG with
CairoSVG, and pass XML/render/visual inspection evidence.

## Workflow Registration

`settings.gradle.kts` auto-registers the new example, but the following
surfaces still need explicit verification or edits:

- `./gradlew projects` must list `:examples-ddd-spring-modulith-demo`.
- `.github/workflows/ci.yml` `test-examples` task list and Kover report list
  must include the new project.
- `.github/workflows/nightly-tests.yml` `test-examples` task list and Kover
  report list must include the new project.
- Root `README.md` and `README.ko.md` contain example discovery tables and must
  add the new DDD/Spring Modulith example row with the real Gradle verification
  command.
- No Maven publication or BOM/catalog constraint is expected for an example
  module.

## Risks And Mitigations

| Risk | Mitigation |
| --- | --- |
| Module verification passes because the invalid fixture is not imported | Use a separate invalid application root and assert `ApplicationModules.of(...).verify()` throws `Violations` containing `orders` and `internal`. |
| Event publication test observes listener state but not durable publication state | Query `EventPublicationRepository` or the configured Exposed publication table in the integration test. |
| Publication rows leak customer data | Keep `OrderAcceptedEvent` to opaque `OrderId`, `eventId`, and `occurredAt`; assert serialized rows do not contain `customerId`, natural order key, or secret-like payloads. |
| Publication table is treated as untrusted input | Document it as app-owned internal state with restricted write access and stable DTO serialization only. |
| Test schema creation is mistaken for production guidance | README must mark `initialize-schema=true` as H2/sample-only and recommend migration-managed production DDL. |
| Restart republication creates duplicate shipping rows | Use stable `@ApplicationModuleListener(id = "shipping.reserve-order")`, make reservations unique by order id, and test duplicate delivery/republication behavior. |
| Rollback leaves a publication row without an order | Add a rollback-path test that fails after event snapshot/publish attempt and verifies empty order, shipping, and publication state. |
| Example accidentally teaches direct cross-context repository access | Keep repositories in `internal` packages and document `orders.events` as the only allowed surface. |
| CI misses the new module | Add explicit CI and Nightly Gradle tasks and verify with `./gradlew projects`. |
| Diagram drifts from source | Build diagram from this spec and source package names, then render and inspect PNG before PR. |

## Acceptance Criteria

- `:examples-ddd-spring-modulith-demo` exists and is registered by Gradle.
- The application context exposes the Exposed-backed
  `EventPublicationRepository`.
- The valid application passes `ApplicationModules.verify()`.
- The invalid test fixture fails verification for a dependency on
  `orders.internal`.
- The order acceptance flow persists an order, publishes an
  `OrderAcceptedEvent`, writes a shipping reservation, and uses the
  Exposed-backed Modulith publication store.
- The happy path uses one event, one listener, and one publication row/state
  transition; benchmark or stress testing is intentionally out of scope for this
  educational example.
- Serialized publication rows contain only opaque event data and do not contain
  customer-facing identifiers, natural order keys, addresses, emails, tokens,
  credentials, or full aggregate snapshots.
- Duplicate or restart-republished `OrderAcceptedEvent` delivery leaves exactly
  one shipping reservation and a completed publication state.
- A rollback-path test leaves no order, shipping reservation, or publication row
  when the command transaction fails before commit.
- A handoff failure or rollback-path test proves aggregate events are not
  cleared before the transactionally recorded Modulith handoff is accepted.
- README.md and README.ko.md explain the pattern and link the rendered diagram.
- CI and Nightly example jobs include the new module.
- Targeted test/build, workflow syntax, diagram validation, and diff hygiene
  evidence are captured before PR.

## Verification Plan

- RED test:
  `./gradlew :examples-ddd-spring-modulith-demo:test --no-configuration-cache --no-daemon --console=plain`
- Named tests:
  - `application modules allow shipping to depend only on order events`
  - `boundary verifier rejects shipping dependency on order internals`
  - `accepting an order persists reservation through Modulith publication`
  - `publication row stores only opaque event data`
  - `duplicate order accepted events keep shipping reservation idempotent`
  - `restart republishes incomplete order event without duplicate reservation`
  - `failed command transaction leaves no order reservation or publication row`
  - `failed handoff keeps aggregate domain events recorded`
- Targeted verification:
  `./gradlew :examples-ddd-spring-modulith-demo:test --no-configuration-cache --no-daemon --console=plain --rerun-tasks`
  `./gradlew :examples-ddd-spring-modulith-demo:build --no-configuration-cache --no-daemon --console=plain --rerun-tasks --warning-mode all`
- Registration:
  `./gradlew projects --no-configuration-cache --no-daemon --console=plain`
- Workflow syntax:
  `actionlint .github/workflows/ci.yml .github/workflows/nightly-tests.yml`
- Workflow registration grep:
  `rg -n ":examples-ddd-spring-modulith-demo:(test|koverXmlReport)" .github/workflows/ci.yml .github/workflows/nightly-tests.yml`
- Diagram:
  `xmllint --noout docs/images/readme-diagrams/examples-ddd-spring-modulith-demo-architecture.svg`
  `/Users/debop/.local/bin/cairosvg docs/images/readme-diagrams/examples-ddd-spring-modulith-demo-architecture.svg -o docs/images/readme-diagrams/examples-ddd-spring-modulith-demo-architecture.png -s 2`
- Diff hygiene:
  `git diff --check`
