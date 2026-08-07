---
manualId: "examples-ddd-spring-modulith-demo"
id: "examples-ddd-spring-modulith-demo"
title: "DDD Spring Modulith Demo"
locale: "en"
kind: "example"
gradlePath: ":examples-ddd-spring-modulith-demo"
sourceDir: "examples/ddd-spring-modulith-demo"
releaseRef: "1.12.1"
artifact: null
---

# DDD Spring Modulith Demo

This runnable Spring Boot example keeps order acceptance, event publication, and shipping reservation in named application modules. It demonstrates a DDD-style boundary using Exposed persistence without making the shared contracts depend on Spring or JaVers types.

## What to inspect {#what-to-inspect}

The `orders` module accepts an order and publishes `OrderAcceptedEvent` through its named events interface. The `shipping` module consumes that public event with a stable listener and persists a reservation. `modulithinvalid` deliberately contains an invalid dependency so Spring Modulith verification has a negative case to detect.

The concrete tables are `DDD_MODULITH_ORDERS`, `EVENT_PUBLICATION`, and `DDD_MODULITH_SHIPPING_RESERVATIONS`.

## Run the verification {#run}

```bash
./gradlew :examples-ddd-spring-modulith-demo:test --no-configuration-cache --no-daemon --console=plain
```

The tests verify module structure as well as the order-to-shipping flow. Read the invalid package alongside the valid module interfaces to see which dependency direction the verifier protects.

## Boundary rules {#boundary-rules}

- Publish stable event DTOs from an explicitly named interface package.
- Consume events through `@ApplicationModuleListener` with a stable listener id.
- Make replay-sensitive writes idempotent by a business key such as `orderId`; shipping checks whether a reservation already exists.
- Keep aggregate and event contracts Spring-neutral. Spring Modulith supplies lifecycle integration in this example, not the domain type system.
- Keep JDBC persistence inside the owning transaction boundary; do not let another module reach into an internal repository.

## Learning path {#learning-path}

Start with `orders`, follow `OrderAcceptedEvent` into `shipping`, then inspect `ShippingReservationRepository` and its schema initializer. Finally run the structure tests and study `modulithinvalid` as the intentionally rejected arrangement.

## Problem {#problem}

The example shows how to keep domain events and module boundaries explicit while persistence and application orchestration evolve independently.

## When to use it {#when-to-use}

Use it as a small Spring Modulith verification fixture or as a reference for an order-to-shipping event flow. It is not a production deployment template.

## Coordinates {#coordinates}

This is an example application and does not publish a library artifact. Run its Gradle test task from the repository root.

## Core concepts {#concepts}

Named interfaces expose stable events; internal repositories and invalid module dependencies remain private to their owning module.

## Quick start {#quick-start}

Run `./gradlew :examples-ddd-spring-modulith-demo:test --no-configuration-cache --no-daemon --console=plain` and inspect the structure and flow test reports.

## API by task {#api-by-task}

- Publish `OrderAcceptedEvent` through the named events interface.
- Consume it with `@ApplicationModuleListener`.
- Persist the shipping reservation through its internal repository.

## Recommended patterns {#patterns}

Keep event DTOs stable, use business-key idempotency for replay-sensitive writes, and keep aggregate contracts independent of framework types.

## Integrations {#integrations}

Spring Modulith supplies module verification and event lifecycle integration; Exposed owns JDBC persistence inside the application transaction boundary.

## Configuration {#configuration}

Configure the test database and Spring application context through the example's Gradle and test-resource settings. Keep production credentials outside this fixture.

## Failure modes {#failures}

The structure test must reject `modulithinvalid`. Duplicate event delivery and persistence conflicts should be handled by the business key and transaction boundary.

## Operations {#operations}

Observe event publication, listener completion, reservation writes, and verification failures separately. Record correlation identifiers when adapting the flow to a service.

## Testing {#testing}

Run the Gradle test task and read both module-structure failures and order-to-shipping flow assertions.

## Workshops and learning path {#workshops}

Read `orders`, follow `OrderAcceptedEvent` into `shipping`, then compare the valid modules with `modulithinvalid`.

## Limitations {#limitations}

The example does not define a production messaging topology, retry policy, deployment model, or cross-service consistency guarantee.

## Sources {#sources}

- [`DddSpringModulithDemoApplication`](../../../../examples/ddd-spring-modulith-demo/src/main/kotlin/io/bluetape4k/exposed/examples/modulith/DddSpringModulithDemoApplication.kt)
- [`OrderAcceptedEvent`](../../../../examples/ddd-spring-modulith-demo/src/main/kotlin/io/bluetape4k/exposed/examples/modulith/orders/events/OrderAcceptedEvent.kt)
- [`ShippingReservationRepository`](../../../../examples/ddd-spring-modulith-demo/src/main/kotlin/io/bluetape4k/exposed/examples/modulith/shipping/internal/ShippingReservationRepository.kt)
