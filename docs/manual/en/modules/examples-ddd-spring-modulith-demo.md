---
manualId: "examples-ddd-spring-modulith-demo"
id: "examples-ddd-spring-modulith-demo"
title: "DDD Spring Modulith Demo"
locale: "en"
kind: "example"
gradlePath: ":examples-ddd-spring-modulith-demo"
sourceDir: "examples/ddd-spring-modulith-demo"
releaseRef: "1.11.0"
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

## Sources {#sources}

- [`DddSpringModulithDemoApplication`](../../../../examples/ddd-spring-modulith-demo/src/main/kotlin/io/bluetape4k/exposed/examples/modulith/DddSpringModulithDemoApplication.kt)
- [`OrderAcceptedEvent`](../../../../examples/ddd-spring-modulith-demo/src/main/kotlin/io/bluetape4k/exposed/examples/modulith/orders/events/OrderAcceptedEvent.kt)
- [`ShippingReservationRepository`](../../../../examples/ddd-spring-modulith-demo/src/main/kotlin/io/bluetape4k/exposed/examples/modulith/shipping/internal/ShippingReservationRepository.kt)
