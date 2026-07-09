# DDD Spring Modulith Demo

[English](README.md) | 한국어

이 예제는 bluetape4k Exposed DDD aggregate 계약, Spring Modulith application-module boundary, Exposed 기반 Spring Modulith publication repository를 JDBC 애플리케이션에서 함께 사용하는 방법을 보여줍니다.

![DDD Spring Modulith Exposed demo architecture](../../docs/images/readme-diagrams/examples-ddd-spring-modulith-demo-architecture.png)

## 개요

샘플은 두 application module로 구성됩니다.

- `orders`: 주문 command를 수락하고 Exposed JDBC로 `Order` aggregate를 저장한 뒤 `OrderAcceptedEvent`를 기록합니다.
- `orders :: events`: 다른 module에 공개되는 named interface입니다. event payload는 `aggregateId`, `eventId`, `occurredAt`만 담습니다.
- `shipping`: `@ApplicationModuleListener(id = "shipping.reserve-order")`로 event를 받아 order id당 하나의 shipping reservation만 만듭니다.

`shipping`은 `orders :: events`에만 의존할 수 있습니다. 테스트에는 `orders.internal` 직접 의존이 Spring Modulith verification에서 거부되는 negative fixture도 포함되어 있습니다.

## Transaction 및 Publication Boundary

`OrderApplicationService.accept(...)`는 command transaction 안에서 aggregate domain event를 snapshot하고, aggregate를 저장한 뒤 Spring `ApplicationEventPublisher`로 event를 발행합니다. Spring Modulith는 같은 JDBC transaction manager를 통해 `ExposedEventPublicationRepository`에 publication row를 기록합니다.

Aggregate event buffer는 transaction callback이 성공적으로 끝난 뒤에만 비웁니다. Publication handoff 또는 command transaction이 실패하면 in-memory event buffer는 command boundary에 남고, database row는 함께 rollback됩니다.

## 지원 범위

- Spring Boot 기반 JDBC-only Exposed repository.
- `bluetape4k-exposed-spring-modulith`를 통한 Spring Modulith publication row 저장.
- Stable listener id와 idempotent consumer.
- `spring.modulith.events.republish-outstanding-events-on-restart=true`를 통한 incomplete publication restart replay.
- secret, natural key, customer id, full aggregate snapshot을 피한 최소 event payload.

## 지원하지 않는 범위

- R2DBC 또는 `suspend` Spring Modulith publication SPI.
- Exactly-once delivery.
- Spring Modulith publication table을 넘어서는 durable application outbox.
- Benchmark, throughput, latency claim.
- Publication row를 외부 input channel로 취급하는 방식.
- 기존 publication row migration 없이 event DTO package를 rename하는 방식.

## 운영 메모

샘플은 로컬 테스트를 위해 `bluetape4k.spring.modulith.exposed.initialize-schema=true`를 사용합니다. 운영 애플리케이션은 Flyway 또는 Liquibase로 publication table을 관리하고, write access를 애플리케이션으로 제한해야 합니다. Publication table은 애플리케이션 내부 상태입니다.

전용 `EventSerializer`는 `OrderAcceptedEvent`만 허용하고 deterministic JSON을 생성하며 default typing 또는 polymorphic type metadata를 사용하지 않습니다. 저장 payload를 확인 가능하게 유지하면서 안전하지 않은 역직렬화 패턴을 피합니다.

Micrometer가 활성화되면 Exposed Modulith store meter는 `bluetape4k.exposed.modulith.publications`입니다. 주요 state는 `incomplete`, `completed`, `failed`, `unloadable`입니다.

Event DTO package를 rename하면 기존 row가 unloadable 상태가 될 수 있습니다. 이런 row는 운영 repair data로 다루세요. Event class를 복구하거나, `EVENT_TYPE` 및 `SERIALIZED_EVENT`를 migration하거나, delivery 상태를 검증한 뒤 운영자가 통제하는 경로로 row를 삭제 또는 재전송해야 합니다.

이 샘플의 실제 table은 `DDD_MODULITH_ORDERS`, `EVENT_PUBLICATION`, `DDD_MODULITH_SHIPPING_RESERVATIONS`입니다.

## 직접 호출에서 Migration

Aggregate 저장 후 다른 module의 repository를 직접 호출하고 있다면, 공유해야 할 사실을 named interface package 아래 stable event DTO로 옮기세요. Consumer module은 그 named interface에만 의존하고, stable `@ApplicationModuleListener` id로 event를 수신하며, projection 또는 reservation write를 order id 같은 business key로 idempotent하게 만들어야 합니다.

## 테스트 실행

확인할 source package는 다음과 같습니다.

- `io.bluetape4k.exposed.examples.modulith.orders`
- `io.bluetape4k.exposed.examples.modulith.orders.events`
- `io.bluetape4k.exposed.examples.modulith.shipping`
- `io.bluetape4k.exposed.examples.modulithinvalid`

```bash
./gradlew :examples-ddd-spring-modulith-demo:test --no-configuration-cache --no-daemon --console=plain
```

Happy-path 테스트는 주문 하나를 수락한 뒤 order row 하나, completed publication row 하나, shipping reservation 하나를 기대합니다. Restart 테스트는 같은 H2 database를 incomplete publication row 상태로 다시 열고, replay가 row를 완료하면서 reservation을 중복 생성하지 않는지 확인합니다.
